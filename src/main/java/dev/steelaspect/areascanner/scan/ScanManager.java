package dev.steelaspect.areascanner.scan;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans the boxes of the active Litematica area selection, keeps the matches up to date from client block
 * updates, and rescans chunks when they (re)load.
 *
 * <p>Background mode: the main thread copies the block palettes of the chunk sections a job needs (cheap),
 * worker threads scan the copies, and the results are applied back on the main thread. Positions that change
 * while a job is in flight are re-checked against the live world when its result is applied, and results from
 * an outdated scan (settings changed, chunk reloaded, scan stopped) are dropped by generation number.
 */
public final class ScanManager {
    private static final Long2ObjectOpenHashMap<Match> MATCHES = new Long2ObjectOpenHashMap<>();
    /** Section key -> positions of the matches in that 16x16x16 section. */
    private static final Long2ObjectOpenHashMap<LongSet> MATCHES_BY_SECTION = new Long2ObjectOpenHashMap<>();
    private static final int[] COUNTS = new int[Category.values().length];

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final LongOpenHashSet QUEUED_CHUNKS = new LongOpenHashSet();
    private static final LongOpenHashSet PENDING_CHUNKS = new LongOpenHashSet();
    /** Positions changed by block updates, re-checked on the next tick. Filled from setBlock. */
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();

    // Background scanning
    private static final int MAX_IN_FLIGHT = 64;
    private static final int MAX_SUBMITS_PER_TICK = 48;
    private static final int MAX_APPLIED_PER_TICK = 200_000;
    private static final ConcurrentLinkedQueue<JobResult> RESULTS = new ConcurrentLinkedQueue<>();
    /** Chunk key -> jobs currently being scanned by a worker. */
    private static final Long2ObjectOpenHashMap<List<Job>> IN_FLIGHT = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap CHUNK_GENERATION = new Long2IntOpenHashMap();
    @Nullable
    private static ExecutorService executor;
    private static int inFlightCount;
    private static int generation;

    private static boolean active;
    /** True until the user-started scan finishes, so "Scan done" is announced once. */
    private static boolean initialScan;
    private static List<BoundingBox> boxes = List.of();
    @Nullable
    private static BoundingBox bounds;
    @Nullable
    private static ClientLevel level;
    @Nullable
    private static ResourceKey<Level> dimension;
    private static long totalBlocks;
    private static long doneBlocks;
    private static int version;
    private static MatchListener listener = MatchListener.NONE;

    private ScanManager() {
    }

    public static void setListener(MatchListener l) {
        listener = l;
    }

    // ---------------------------------------------------------------- state

    public static boolean isActive() {
        return active;
    }

    public static boolean isScanning() {
        return active && (!QUEUE.isEmpty() || inFlightCount > 0 || !RESULTS.isEmpty());
    }

    /** Scan progress 0..1 of the queued work. */
    public static double progress() {
        return totalBlocks <= 0 ? 1.0 : Math.min(1.0, (double) doneBlocks / totalBlocks);
    }

    public static int count(Category category) {
        return COUNTS[category.ordinal()];
    }

    public static int totalMatches() {
        return MATCHES.size();
    }

    public static int pendingChunks() {
        return PENDING_CHUNKS.size();
    }

    /** Changes whenever a match is added, removed or changes group. */
    public static int version() {
        return version;
    }

    public static Long2ObjectOpenHashMap<Match> matches() {
        return MATCHES;
    }

    /** Positions of the matches in one section, or null. */
    @Nullable
    public static LongSet sectionMatches(long sectionKey) {
        return MATCHES_BY_SECTION.get(sectionKey);
    }

    public static LongSet sectionKeys() {
        return MATCHES_BY_SECTION.keySet();
    }

    public static List<BoundingBox> boxes() {
        return boxes;
    }

    // ---------------------------------------------------------------- start / stop

    /** Starts a new scan of the active area selection. */
    public static boolean start() {
        Minecraft mc = Minecraft.getInstance();
        if (!Configs.ENABLED.getBooleanValue()) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, Reference.MOD_ID + ".message.disabled");
            return false;
        }
        if (mc.level == null) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.ERROR, Reference.MOD_ID + ".message.no_world");
            return false;
        }
        List<BoundingBox> selected = selectionBoxes();
        if (selected.isEmpty()) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.ERROR, Reference.MOD_ID + ".message.no_selection");
            return false;
        }
        clear();
        Matcher.refresh();
        level = mc.level;
        dimension = mc.level.dimension();
        boxes = selected;
        bounds = union(selected);
        active = true;
        initialScan = true;
        long volume = 0;
        for (BoundingBox box : boxes) {
            volume += (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        }
        enqueueAll();
        InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, Reference.MOD_ID + ".message.scan_started", volume, boxes.size());
        return true;
    }

    public static void stop(boolean message) {
        boolean wasActive = active;
        clear();
        if (message && wasActive) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, Reference.MOD_ID + ".message.stopped");
        }
    }

    private static void clear() {
        active = false;
        initialScan = false;
        clearMatchesAndWork();
        PENDING_CHUNKS.clear();
        synchronized (DIRTY) {
            DIRTY.clear();
        }
        boxes = List.of();
        bounds = null;
        level = null;
        dimension = null;
        ScanActions.resetCycle();
    }

    /** Drops all matches and queued/in-flight work (in-flight results are discarded when they arrive). */
    private static void clearMatchesAndWork() {
        generation++;
        MATCHES.clear();
        MATCHES_BY_SECTION.clear();
        Arrays.fill(COUNTS, 0);
        QUEUE.clear();
        QUEUED_CHUNKS.clear();
        IN_FLIGHT.clear();
        CHUNK_GENERATION.clear();
        RESULTS.clear();
        inFlightCount = 0;
        totalBlocks = 0;
        doneBlocks = 0;
        version++;
        listener.onMatchesCleared();
    }

    /** Settings changed: rescan the same boxes if the match rules changed, otherwise only colours changed. */
    public static void onSettingsChanged() {
        if (!Configs.ENABLED.getBooleanValue() && active) {
            stop(false);
            version++;
            return;
        }
        boolean rulesChanged = Matcher.refresh();
        if (active && rulesChanged) {
            clearMatchesAndWork();
            PENDING_CHUNKS.clear();
            enqueueAll();
        }
        version++;
    }

    private static List<BoundingBox> selectionBoxes() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        List<BoundingBox> list = new ArrayList<>();
        if (selection == null) return list;
        for (Box box : selection.getAllSubRegionBoxes()) {
            BlockPos p1 = box.getPos1();
            BlockPos p2 = box.getPos2();
            if (p1 != null && p2 != null) {
                list.add(BoundingBox.fromCorners(p1, p2));
            }
        }
        return list;
    }

    /** Number of selection boxes, for the GUI status line. */
    public static int selectionBoxCount() {
        return selectionBoxes().size();
    }

    private static BoundingBox union(List<BoundingBox> list) {
        BoundingBox first = list.get(0);
        int minX = first.minX(), minY = first.minY(), minZ = first.minZ();
        int maxX = first.maxX(), maxY = first.maxY(), maxZ = first.maxZ();
        for (BoundingBox b : list) {
            minX = Math.min(minX, b.minX());
            minY = Math.min(minY, b.minY());
            minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX());
            maxY = Math.max(maxY, b.maxY());
            maxZ = Math.max(maxZ, b.maxZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ---------------------------------------------------------------- job queue

    private static void enqueueAll() {
        for (BoundingBox box : boxes) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    enqueue(box, cx, cz);
                }
            }
        }
    }

    /** Queues the part of every box that lies in the given chunk column. */
    private static void enqueueChunk(int cx, int cz) {
        for (BoundingBox box : boxes) {
            if (cx >= box.minX() >> 4 && cx <= box.maxX() >> 4 && cz >= box.minZ() >> 4 && cz <= box.maxZ() >> 4) {
                enqueue(box, cx, cz);
            }
        }
    }

    private static void enqueue(BoundingBox box, int cx, int cz) {
        ClientLevel lvl = level;
        if (lvl == null) return;
        int minY = Math.max(box.minY(), lvl.getMinY());
        int maxY = Math.min(box.maxY(), lvl.getMaxY());
        if (minY > maxY) return;
        Job job = new Job(cx, cz,
                Math.max(box.minX(), cx << 4), minY, Math.max(box.minZ(), cz << 4),
                Math.min(box.maxX(), (cx << 4) + 15), maxY, Math.min(box.maxZ(), (cz << 4) + 15));
        QUEUE.add(job);
        QUEUED_CHUNKS.add(job.chunkKey);
        totalBlocks += job.volume();
    }

    // ---------------------------------------------------------------- ticking

    public static void tick(Minecraft mc) {
        if (!active) return;
        if (!Configs.ENABLED.getBooleanValue()) {
            // switched off from outside the GUI (e.g. Cytra Hub): drop the scan
            stop(false);
            return;
        }
        if (mc.level == null) {
            stop(false);
            return;
        }
        if (mc.level != level) {
            // Dimension change: the scan belongs to the old dimension. Same dimension (e.g. respawn): keep going,
            // the chunks will reload and get rescanned.
            if (mc.level.dimension() != dimension) {
                stop(true);
                return;
            }
            level = mc.level;
        }
        processDirty(mc.level);
        applyResults(mc.level);
        if (Configs.BACKGROUND_SCANNING.getBooleanValue()) {
            submitJobs(mc.level);
        } else {
            processQueueInline(mc.level);
        }
        if (QUEUE.isEmpty() && inFlightCount == 0 && RESULTS.isEmpty() && totalBlocks > 0) {
            finishScan();
        }
    }

    private static void finishScan() {
        if (initialScan) {
            initialScan = false;
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, Reference.MOD_ID + ".message.scan_done",
                    count(Category.UNMOVABLE), count(Category.LIQUID), count(Category.CUSTOM));
        }
        totalBlocks = 0;
        doneBlocks = 0;
    }

    /** ClientLevel.hasChunk always returns true, so ask the chunk cache directly. */
    private static boolean isLoaded(ClientLevel lvl, int cx, int cz) {
        return lvl.getChunkSource().hasChunk(cx, cz);
    }

    /** Takes the next job off the queue; returns the loaded chunk, or null if it was unloaded (now pending). */
    @Nullable
    private static LevelChunk startJob(ClientLevel lvl, Job job) {
        QUEUED_CHUNKS.remove(job.chunkKey);
        if (!isLoaded(lvl, job.cx, job.cz)) {
            PENDING_CHUNKS.add(job.chunkKey);
            doneBlocks += job.volume();
            return null;
        }
        return lvl.getChunk(job.cx, job.cz);
    }

    /** The section of a chunk at a block Y, or null if outside the chunk or empty. */
    @Nullable
    private static LevelChunkSection section(LevelChunk chunk, int y) {
        LevelChunkSection[] sections = chunk.getSections();
        int index = chunk.getSectionIndex(y);
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    /** True if a section can't contain a match: all air, or no palette entry matches. */
    private static boolean skipSection(@Nullable LevelChunkSection section) {
        return section == null || section.hasOnlyAir() || !section.maybeHas(Matcher::couldMatch);
    }

    // ---- background mode

    private static ExecutorService executor() {
        if (executor == null) {
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            AtomicInteger n = new AtomicInteger();
            executor = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "AreaScanner-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
        return executor;
    }

    /** Copies the needed section palettes on the main thread and hands the jobs to the workers. */
    private static void submitJobs(ClientLevel lvl) {
        int submitted = 0;
        while (!QUEUE.isEmpty() && inFlightCount < MAX_IN_FLIGHT && submitted < MAX_SUBMITS_PER_TICK) {
            Job job = QUEUE.pollFirst();
            LevelChunk chunk = startJob(lvl, job);
            if (chunk == null) continue;
            int firstSection = job.minY >> 4;
            int lastSection = job.maxY >> 4;
            @SuppressWarnings("unchecked")
            PalettedContainer<BlockState>[] copies = new PalettedContainer[lastSection - firstSection + 1];
            boolean any = false;
            for (int s = firstSection; s <= lastSection; s++) {
                LevelChunkSection section = section(chunk, s << 4);
                if (!skipSection(section)) {
                    copies[s - firstSection] = section.getStates().copy();
                    any = true;
                }
            }
            if (!any) {
                doneBlocks += job.volume();
                continue;
            }
            job.generation = generation;
            job.chunkGeneration = CHUNK_GENERATION.get(job.chunkKey);
            IN_FLIGHT.computeIfAbsent(job.chunkKey, k -> new ArrayList<>()).add(job);
            inFlightCount++;
            submitted++;
            executor().execute(() -> RESULTS.add(scanCopies(job, copies, firstSection)));
        }
    }

    /** Worker thread: scans the copied sections. Touches no shared state except the immutable match rules. */
    private static JobResult scanCopies(Job job, PalettedContainer<BlockState>[] copies, int firstSection) {
        JobResult result = new JobResult(job);
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = job.minY; y <= job.maxY; y++) {
                PalettedContainer<BlockState> states = copies[(y >> 4) - firstSection];
                if (states == null) {
                    y |= 15; // skip the rest of this section
                    continue;
                }
                int ly = y & 15;
                for (int z = job.minZ; z <= job.maxZ; z++) {
                    for (int x = job.minX; x <= job.maxX; x++) {
                        pos.set(x, y, z);
                        Match match = Matcher.match(states.get(x & 15, ly, z & 15), EmptyBlockGetter.INSTANCE, pos);
                        if (match != null) result.add(pos.asLong(), match);
                    }
                }
            }
        } catch (Throwable t) {
            result.failed = true;
            Reference.LOGGER.warn("Area scanner worker failed for chunk {} {}", job.cx, job.cz, t);
        }
        return result;
    }

    private static void applyResults(ClientLevel lvl) {
        int applied = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        JobResult result;
        while (applied < MAX_APPLIED_PER_TICK && (result = RESULTS.poll()) != null) {
            Job job = result.job;
            if (job.generation != generation) continue; // from a scan that was stopped or restarted
            List<Job> jobs = IN_FLIGHT.get(job.chunkKey);
            if (jobs != null) {
                jobs.remove(job);
                if (jobs.isEmpty()) IN_FLIGHT.remove(job.chunkKey);
            }
            inFlightCount--;
            doneBlocks += job.volume();
            if (job.chunkGeneration != CHUNK_GENERATION.get(job.chunkKey)) continue; // chunk reloaded meanwhile
            if (result.failed) continue; // logged by the worker

            for (int i = 0; i < result.size; i++) {
                if (job.changed != null && job.changed.contains(result.positions[i])) continue;
                set(result.positions[i], result.matches[i]);
            }
            applied += result.size;
            // Blocks that changed while the copy was being scanned: check them against the live world.
            if (job.changed != null && isLoaded(lvl, job.cx, job.cz)) {
                for (LongIterator it = job.changed.iterator(); it.hasNext(); ) {
                    long packed = it.nextLong();
                    pos.set(packed);
                    set(packed, Matcher.match(lvl.getBlockState(pos), lvl, pos));
                }
            }
        }
    }

    // ---- main-thread mode

    private static void processQueueInline(ClientLevel lvl) {
        int budget = Configs.BLOCKS_PER_TICK.getIntegerValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0 && !QUEUE.isEmpty()) {
            Job job = QUEUE.peekFirst();
            if (!job.started) {
                job.started = true;
                LevelChunk chunk = startJob(lvl, job);
                if (chunk == null) {
                    QUEUE.pollFirst();
                    continue;
                }
            } else if (!isLoaded(lvl, job.cx, job.cz)) {
                PENDING_CHUNKS.add(job.chunkKey);
                doneBlocks += job.remaining();
                QUEUE.pollFirst();
                continue;
            }
            budget = job.runInline(lvl, lvl.getChunk(job.cx, job.cz), pos, budget);
            if (job.isDone()) QUEUE.pollFirst();
        }
    }

    private static void processDirty(ClientLevel lvl) {
        long[] positions;
        synchronized (DIRTY) {
            if (DIRTY.isEmpty()) return;
            positions = DIRTY.toLongArray();
            DIRTY.clear();
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long packed : positions) {
            pos.set(packed);
            if (!isLoaded(lvl, pos.getX() >> 4, pos.getZ() >> 4)) continue;
            set(packed, Matcher.match(lvl.getBlockState(pos), lvl, pos));
            // A worker may be scanning an older copy of this block: make it re-check on apply.
            List<Job> jobs = IN_FLIGHT.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            if (jobs != null) {
                for (Job job : jobs) {
                    if (job.contains(pos.getX(), pos.getY(), pos.getZ())) {
                        if (job.changed == null) job.changed = new LongOpenHashSet();
                        job.changed.add(packed);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- live updates

    /** Called for every client-side block change; only queues positions inside the scanned boxes. */
    public static void onBlockChanged(Level changedLevel, BlockPos pos) {
        if (!active || changedLevel != level) return;
        if (!contains(pos.getX(), pos.getY(), pos.getZ())) return;
        synchronized (DIRTY) {
            DIRTY.add(pos.asLong());
        }
    }

    /** A chunk (re)loaded: drop what we knew about it and rescan it. */
    public static void onChunkLoad(ClientLevel loadedLevel, LevelChunk chunk) {
        if (!active || loadedLevel.dimension() != dimension || bounds == null) return;
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        if (!intersectsChunk(cx, cz)) return;
        if (level != loadedLevel) level = loadedLevel;
        long key = ChunkPos.asLong(cx, cz);
        PENDING_CHUNKS.remove(key);
        if (QUEUED_CHUNKS.remove(key)) {
            QUEUE.removeIf(job -> {
                if (job.chunkKey != key) return false;
                totalBlocks -= job.volume();
                return true;
            });
        }
        CHUNK_GENERATION.addTo(key, 1); // results of in-flight jobs for the old chunk data are dropped
        clearChunk(cx, cz);
        enqueueChunk(cx, cz);
    }

    /** A chunk unloaded: keep its matches as last seen, and rescan it when it comes back. */
    public static void onChunkUnload(ClientLevel unloadedLevel, LevelChunk chunk) {
        if (!active || unloadedLevel != level) return;
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        if (intersectsChunk(cx, cz)) {
            PENDING_CHUNKS.add(ChunkPos.asLong(cx, cz));
        }
    }

    public static boolean contains(int x, int y, int z) {
        BoundingBox b = bounds;
        if (b == null || !b.isInside(x, y, z)) return false;
        for (BoundingBox box : boxes) {
            if (box.isInside(x, y, z)) return true;
        }
        return false;
    }

    private static boolean intersectsChunk(int cx, int cz) {
        for (BoundingBox box : boxes) {
            if (cx >= box.minX() >> 4 && cx <= box.maxX() >> 4 && cz >= box.minZ() >> 4 && cz <= box.maxZ() >> 4) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- match storage

    public static long sectionKeyOf(long packedPos) {
        return SectionPos.asLong(BlockPos.getX(packedPos) >> 4, BlockPos.getY(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
    }

    static void set(long packedPos, @Nullable Match match) {
        if (match == null) {
            Match old = MATCHES.remove(packedPos);
            if (old != null) {
                COUNTS[old.category.ordinal()]--;
                long key = sectionKeyOf(packedPos);
                LongSet set = MATCHES_BY_SECTION.get(key);
                if (set != null) {
                    set.remove(packedPos);
                    if (set.isEmpty()) MATCHES_BY_SECTION.remove(key);
                }
                version++;
                listener.onMatchChanged(packedPos);
            }
            return;
        }
        Match old = MATCHES.put(packedPos, match);
        if (old == match) return;
        if (old != null) COUNTS[old.category.ordinal()]--;
        COUNTS[match.category.ordinal()]++;
        if (old == null) {
            MATCHES_BY_SECTION.computeIfAbsent(sectionKeyOf(packedPos), k -> new LongOpenHashSet()).add(packedPos);
        }
        version++;
        listener.onMatchChanged(packedPos);
    }

    /** Removes all matches in a chunk column. */
    private static void clearChunk(int cx, int cz) {
        ClientLevel lvl = level;
        if (lvl == null) return;
        for (int sy = lvl.getMinY() >> 4; sy <= lvl.getMaxY() >> 4; sy++) {
            LongSet set = MATCHES_BY_SECTION.get(SectionPos.asLong(cx, sy, cz));
            if (set == null) continue;
            for (long packed : set.toLongArray()) {
                set(packed, null);
            }
        }
    }

    // ---------------------------------------------------------------- job

    /** The part of one selection box inside one chunk column. */
    private static final class Job {
        final int cx, cz;
        final long chunkKey;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final int layerArea;
        int y;
        boolean started;
        int generation;
        int chunkGeneration;
        /** Positions changed while this job was in flight (main thread only). */
        @Nullable
        LongOpenHashSet changed;

        Job(int cx, int cz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.cx = cx;
            this.cz = cz;
            this.chunkKey = ChunkPos.asLong(cx, cz);
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.layerArea = (maxX - minX + 1) * (maxZ - minZ + 1);
            this.y = minY;
        }

        boolean contains(int x, int y, int z) {
            return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
        }

        long volume() {
            return (long) this.layerArea * (this.maxY - this.minY + 1);
        }

        long remaining() {
            return (long) this.layerArea * (this.maxY - this.y + 1);
        }

        boolean isDone() {
            return this.y > this.maxY;
        }

        /** Main-thread mode: scans whole layers until the budget is used up; returns the budget left. */
        int runInline(ClientLevel lvl, LevelChunk chunk, BlockPos.MutableBlockPos pos, int budget) {
            while (this.y <= this.maxY && budget > 0) {
                LevelChunkSection section = section(chunk, this.y);
                int sectionTop = Math.min(this.maxY, this.y | 15);
                if (skipSection(section)) {
                    doneBlocks += (long) (sectionTop - this.y + 1) * this.layerArea;
                    this.y = sectionTop + 1;
                    budget -= 16;
                    continue;
                }
                int ly = this.y & 15;
                for (int z = this.minZ; z <= this.maxZ; z++) {
                    for (int x = this.minX; x <= this.maxX; x++) {
                        pos.set(x, this.y, z);
                        Match match = Matcher.match(section.getBlockState(x & 15, ly, z & 15), lvl, pos);
                        if (match != null) set(pos.asLong(), match);
                    }
                }
                doneBlocks += this.layerArea;
                budget -= this.layerArea;
                this.y++;
            }
            return budget;
        }
    }

    /** Matches a worker found for one job. */
    private static final class JobResult {
        final Job job;
        long[] positions = new long[64];
        Match[] matches = new Match[64];
        int size;
        boolean failed;

        JobResult(Job job) {
            this.job = job;
        }

        void add(long pos, Match match) {
            if (this.size == this.positions.length) {
                this.positions = Arrays.copyOf(this.positions, this.size * 2);
                this.matches = Arrays.copyOf(this.matches, this.size * 2);
            }
            this.positions[this.size] = pos;
            this.matches[this.size] = match;
            this.size++;
        }
    }
}
