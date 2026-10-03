package dev.steelaspect.areascanner.scan;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans the boxes of the active Litematica area selection over several ticks, keeps the matches up to
 * date from client block updates, and rescans chunks when they (re)load.
 */
public final class ScanManager {
    private static final Long2ObjectOpenHashMap<Match> MATCHES = new Long2ObjectOpenHashMap<>();
    /** Chunk key -> positions of the matches in that chunk column, for clearing a chunk quickly. */
    private static final Long2ObjectOpenHashMap<LongSet> MATCHES_BY_CHUNK = new Long2ObjectOpenHashMap<>();
    private static final int[] COUNTS = new int[Category.values().length];

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final LongOpenHashSet QUEUED_CHUNKS = new LongOpenHashSet();
    private static final LongOpenHashSet PENDING_CHUNKS = new LongOpenHashSet();
    /** Positions changed by block updates, re-checked on the next tick. Filled from setBlock. */
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();

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

    private ScanManager() {
    }

    // ---------------------------------------------------------------- state

    public static boolean isActive() {
        return active;
    }

    public static boolean isScanning() {
        return active && !QUEUE.isEmpty();
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

    public static List<BoundingBox> boxes() {
        return boxes;
    }

    // ---------------------------------------------------------------- start / stop

    /** Starts a new scan of the active area selection. */
    public static boolean start() {
        Minecraft mc = Minecraft.getInstance();
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
        MATCHES.clear();
        MATCHES_BY_CHUNK.clear();
        java.util.Arrays.fill(COUNTS, 0);
        QUEUE.clear();
        QUEUED_CHUNKS.clear();
        PENDING_CHUNKS.clear();
        synchronized (DIRTY) {
            DIRTY.clear();
        }
        boxes = List.of();
        bounds = null;
        level = null;
        dimension = null;
        totalBlocks = 0;
        doneBlocks = 0;
        version++;
        ScanActions.resetCycle();
    }

    /** Settings changed: rescan the same boxes if the match rules changed, otherwise only colours changed. */
    public static void onSettingsChanged() {
        boolean rulesChanged = Matcher.refresh();
        if (active && rulesChanged) {
            MATCHES.clear();
            MATCHES_BY_CHUNK.clear();
            java.util.Arrays.fill(COUNTS, 0);
            QUEUE.clear();
            QUEUED_CHUNKS.clear();
            PENDING_CHUNKS.clear();
            totalBlocks = 0;
            doneBlocks = 0;
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
        processQueue(mc.level);
    }

    private static void processQueue(ClientLevel lvl) {
        if (QUEUE.isEmpty()) return;
        int budget = Configs.BLOCKS_PER_TICK.getIntegerValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0 && !QUEUE.isEmpty()) {
            Job job = QUEUE.peekFirst();
            if (!job.started) {
                job.started = true;
                QUEUED_CHUNKS.remove(job.chunkKey);
            }
            if (!lvl.hasChunk(job.cx, job.cz)) {
                // Not loaded: remember it and scan it when it loads.
                PENDING_CHUNKS.add(job.chunkKey);
                doneBlocks += job.remaining();
                QUEUE.pollFirst();
                continue;
            }
            LevelChunk chunk = lvl.getChunk(job.cx, job.cz);
            budget = job.run(lvl, chunk, pos, budget);
            if (job.isDone()) QUEUE.pollFirst();
        }
        if (QUEUE.isEmpty()) {
            doneBlocks = totalBlocks;
            if (initialScan) {
                initialScan = false;
                InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, Reference.MOD_ID + ".message.scan_done",
                        count(Category.UNMOVABLE), count(Category.LIQUID), count(Category.CUSTOM));
            }
            totalBlocks = 0;
            doneBlocks = 0;
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
            if (!lvl.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            set(packed, Matcher.match(lvl.getBlockState(pos), lvl, pos));
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
        if (QUEUED_CHUNKS.contains(key)) return; // already waiting to be scanned, it will read the new data
        clearChunk(key);
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

    private static long chunkKeyOf(long packedPos) {
        return ChunkPos.asLong(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
    }

    static void set(long packedPos, @Nullable Match match) {
        if (match == null) {
            Match old = MATCHES.remove(packedPos);
            if (old != null) {
                COUNTS[old.category.ordinal()]--;
                long key = chunkKeyOf(packedPos);
                LongSet set = MATCHES_BY_CHUNK.get(key);
                if (set != null) {
                    set.remove(packedPos);
                    if (set.isEmpty()) MATCHES_BY_CHUNK.remove(key);
                }
                version++;
            }
            return;
        }
        Match old = MATCHES.put(packedPos, match);
        if (old == match) return;
        if (old != null) COUNTS[old.category.ordinal()]--;
        COUNTS[match.category.ordinal()]++;
        if (old == null) {
            MATCHES_BY_CHUNK.computeIfAbsent(chunkKeyOf(packedPos), k -> new LongOpenHashSet()).add(packedPos);
        }
        version++;
    }

    private static void clearChunk(long chunkKey) {
        LongSet set = MATCHES_BY_CHUNK.remove(chunkKey);
        if (set == null) return;
        for (LongIterator it = set.iterator(); it.hasNext(); ) {
            Match old = MATCHES.remove(it.nextLong());
            if (old != null) COUNTS[old.category.ordinal()]--;
        }
        version++;
    }

    /** Matches grouped for export; custom matches are keyed by their block. */
    public static List<Long2ObjectMap.Entry<Match>> entries() {
        return new ArrayList<>(MATCHES.long2ObjectEntrySet());
    }

    // ---------------------------------------------------------------- job

    /** The part of one selection box inside one chunk column, scanned one Y layer at a time. */
    private static final class Job {
        final int cx, cz;
        final long chunkKey;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final int layerArea;
        int y;
        boolean started;

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

        long volume() {
            return (long) this.layerArea * (this.maxY - this.minY + 1);
        }

        long remaining() {
            return (long) this.layerArea * (this.maxY - this.y + 1);
        }

        boolean isDone() {
            return this.y > this.maxY;
        }

        /** Scans whole layers until the budget is used up; returns the budget left. */
        int run(ClientLevel lvl, LevelChunk chunk, BlockPos.MutableBlockPos pos, int budget) {
            LevelChunkSection[] sections = chunk.getSections();
            while (this.y <= this.maxY && budget > 0) {
                int sectionIndex = chunk.getSectionIndex(this.y);
                LevelChunkSection section = sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
                int sectionTop = Math.min(this.maxY, this.y | 15);
                if (section == null || section.hasOnlyAir()) {
                    // Nothing can match in an all-air section.
                    int layers = sectionTop - this.y + 1;
                    doneBlocks += (long) layers * this.layerArea;
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
}
