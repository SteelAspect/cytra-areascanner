package dev.steelaspect.areascanner.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.CustomEntry;
import dev.steelaspect.areascanner.config.ScanLists;
import dev.steelaspect.areascanner.scan.Category;
import dev.steelaspect.areascanner.scan.Match;
import dev.steelaspect.areascanner.scan.MatchListener;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the matches with MaLiLib's pipelines (the same ones Litematica's overlays use): translucent sides
 * plus outlines, optionally through walls. Neighbouring matches of the same colour are merged into one
 * shape, so large areas stay cheap to draw.
 */
public final class ScanRenderer implements IRenderer, MatchListener {
    public static final ScanRenderer INSTANCE = new ScanRenderer();

    // Directions: 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x)
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    /** The 12 cube edges as pairs of face directions. */
    private static final double EXPAND = 0.002;
    private static final int[][] EDGES = buildEdges();
    /** Edge end points relative to the block corner (slightly expanded to avoid z-fighting). */
    private static final double[][] EDGE_FROM = new double[12][];
    private static final double[][] EDGE_TO = new double[12][];

    static {
        for (int e = 0; e < 12; e++) {
            double[] p0 = new double[3];
            double[] p1 = new double[3];
            boolean[] fixed = new boolean[3];
            for (int d : EDGES[e]) {
                int axis = d < 2 ? 1 : d < 4 ? 2 : 0;
                double off = (d % 2 == 1) ? 1 + EXPAND : -EXPAND;
                p0[axis] = off;
                p1[axis] = off;
                fixed[axis] = true;
            }
            for (int axis = 0; axis < 3; axis++) {
                if (!fixed[axis]) {
                    p0[axis] = -EXPAND;
                    p1[axis] = 1 + EXPAND;
                }
            }
            EDGE_FROM[e] = p0;
            EDGE_TO[e] = p1;
        }
    }
    /** Time per frame spent rebuilding changed sections; the rest waits for the next frame. */
    private static final long REBUILD_BUDGET_NANOS = 3_000_000L;

    // Render cache: per 16x16x16 section, only the matches with a visible face. Rebuilt per section when a match
    // in it (or touching it) changes, so live updates and big scans never rebuild everything at once.
    private final Long2ObjectOpenHashMap<SectionGeometry> sections = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirtySections = new LongOpenHashSet();
    private boolean cachedMerge = true;
    private boolean loggedError;

    private ScanRenderer() {
    }

    private static int[][] buildEdges() {
        int[][] edges = new int[12][];
        int n = 0;
        for (int a = 0; a < 6; a++) {
            for (int b = a + 1; b < 6; b++) {
                if (a / 2 != b / 2) edges[n++] = new int[]{a, b};
            }
        }
        return edges;
    }

    @Override
    public void onRenderWorldLast(Matrix4f posMatrix, Matrix4f projMatrix) {
        if (!Configs.ENABLED.getBooleanValue() || !ScanManager.isActive() || ScanManager.totalMatches() == 0) return;
        boolean fill = Configs.RENDER_FILL.getBooleanValue();
        boolean outline = Configs.RENDER_OUTLINE.getBooleanValue();
        if (!fill && !outline) return;
        try {
            updateCache();
            render(fill, outline);
        } catch (Exception e) {
            if (!this.loggedError) {
                this.loggedError = true;
                Reference.LOGGER.warn("Area scanner overlay rendering failed", e);
            }
        }
    }

    // ---------------------------------------------------------------- cache

    @Override
    public void onMatchChanged(long pos) {
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        // A change affects the faces/edges of neighbours, which may sit in the next section over.
        int x0 = (x & 15) == 0 ? -1 : 0, x1 = (x & 15) == 15 ? 1 : 0;
        int y0 = (y & 15) == 0 ? -1 : 0, y1 = (y & 15) == 15 ? 1 : 0;
        int z0 = (z & 15) == 0 ? -1 : 0, z1 = (z & 15) == 15 ? 1 : 0;
        for (int dx = x0; dx <= x1; dx++) {
            for (int dy = y0; dy <= y1; dy++) {
                for (int dz = z0; dz <= z1; dz++) {
                    this.dirtySections.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                }
            }
        }
    }

    @Override
    public void onMatchesCleared() {
        this.sections.clear();
        this.dirtySections.clear();
    }

    private void updateCache() {
        boolean merge = Configs.MERGE_FACES.getBooleanValue();
        if (merge != this.cachedMerge) {
            this.cachedMerge = merge;
            this.dirtySections.addAll(ScanManager.sectionKeys());
        }
        if (this.dirtySections.isEmpty()) return;
        long deadline = System.nanoTime() + REBUILD_BUDGET_NANOS;
        Long2ObjectOpenHashMap<Match> matches = ScanManager.matches();
        int n = 0;
        for (LongIterator it = this.dirtySections.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            it.remove();
            LongSet positions = ScanManager.sectionMatches(key);
            if (positions == null || positions.isEmpty()) {
                this.sections.remove(key);
            } else {
                SectionGeometry geometry = this.sections.computeIfAbsent(key, k -> new SectionGeometry(k));
                geometry.rebuild(positions, matches, merge);
                if (geometry.size == 0) this.sections.remove(key);
            }
            if ((++n & 7) == 0 && System.nanoTime() > deadline) break;
        }
    }

    /** Cached render data of one section: matches that have at least one visible face. */
    private static final class SectionGeometry {
        final double centerX, centerY, centerZ;
        long[] positions = new long[16];
        Match[] matches = new Match[16];
        byte[] faceMasks = new byte[16];
        short[] edgeMasks = new short[16];
        int size;

        SectionGeometry(long key) {
            this.centerX = (SectionPos.x(key) << 4) + 8;
            this.centerY = (SectionPos.y(key) << 4) + 8;
            this.centerZ = (SectionPos.z(key) << 4) + 8;
        }

        void rebuild(LongSet sectionPositions, Long2ObjectOpenHashMap<Match> all, boolean merge) {
            int cap = sectionPositions.size();
            if (this.positions.length < cap) {
                this.positions = new long[cap];
                this.matches = new Match[cap];
                this.faceMasks = new byte[cap];
                this.edgeMasks = new short[cap];
            }
            this.size = 0;
            boolean[] same = new boolean[6];
            for (LongIterator it = sectionPositions.iterator(); it.hasNext(); ) {
                long pos = it.nextLong();
                Match match = all.get(pos);
                if (match == null) continue;
                int faces = 0x3F;
                int edges = 0xFFF;
                if (merge) {
                    int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                    faces = 0;
                    for (int d = 0; d < 6; d++) {
                        same[d] = all.get(BlockPos.asLong(x + DX[d], y + DY[d], z + DZ[d])) == match;
                        if (!same[d]) faces |= 1 << d;
                    }
                    if (faces == 0) continue; // fully enclosed by the same colour: nothing to draw
                    edges = 0;
                    for (int e = 0; e < 12; e++) {
                        int a = EDGES[e][0], b = EDGES[e][1];
                        boolean draw;
                        if (!same[a] && !same[b]) {
                            draw = true; // convex edge
                        } else if (same[a] != same[b]) {
                            // One side continues: it's an edge only if the shape turns inward there (concave).
                            draw = all.get(BlockPos.asLong(x + DX[a] + DX[b], y + DY[a] + DY[b], z + DZ[a] + DZ[b])) == match;
                        } else {
                            draw = false;
                        }
                        if (draw) edges |= 1 << e;
                    }
                }
                int i = this.size++;
                this.positions[i] = pos;
                this.matches[i] = match;
                this.faceMasks[i] = (byte) faces;
                this.edgeMasks[i] = (short) edges;
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private void render(boolean fill, boolean outline) throws Exception {
        boolean throughWalls = Configs.RENDER_THROUGH_WALLS.getBooleanValue();
        Vec3 cam = RenderUtils.camPos();
        double range = Configs.RENDER_RANGE.getIntegerValue();
        double rangeSq = range * range;
        int maxRendered = Configs.MAX_RENDERED.getIntegerValue();
        float fillAlpha = (float) Configs.FILL_ALPHA.getDoubleValue();
        float outlineAlpha = (float) Configs.OUTLINE_ALPHA.getDoubleValue();
        float lineWidth = (float) Configs.LINE_WIDTH.getDoubleValue();
        Map<Match, Integer> colors = colorTable();

        // Pick what is in range once (whole sections first), shared by both passes.
        double sectionRange = range + 14.0;
        double sectionRangeSq = sectionRange * sectionRange;
        List<SectionGeometry> visibleSections = new ArrayList<>();
        int[] visibleIndex = new int[Math.min(maxRendered, 1 << 16)];
        int count = 0;
        outer:
        for (SectionGeometry g : this.sections.values()) {
            double sx = g.centerX - cam.x, sy = g.centerY - cam.y, sz = g.centerZ - cam.z;
            if (sx * sx + sy * sy + sz * sz > sectionRangeSq) continue;
            for (int i = 0; i < g.size; i++) {
                long pos = g.positions[i];
                double dx = BlockPos.getX(pos) + 0.5 - cam.x;
                double dy = BlockPos.getY(pos) + 0.5 - cam.y;
                double dz = BlockPos.getZ(pos) + 0.5 - cam.z;
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
                if (count == maxRendered) break outer;
                if (count == visibleIndex.length) visibleIndex = java.util.Arrays.copyOf(visibleIndex, Math.min(maxRendered, count * 2));
                visibleSections.add(g);
                visibleIndex[count++] = i;
            }
        }
        if (count == 0) return;

        if (fill && fillAlpha > 0.0f) {
            try (RenderContext ctx = new RenderContext(() -> Reference.MOD_ID + ":overlay/quads", throughWalls
                    ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL
                    : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_OFFSET_2)) {
                BufferBuilder buffer = ctx.getBuilder();
                for (int k = 0; k < count; k++) {
                    SectionGeometry g = visibleSections.get(k);
                    int i = visibleIndex[k];
                    int rgb = colors.getOrDefault(g.matches[i], 0xFFFFFF);
                    drawFaces(g.positions[i], g.faceMasks[i], cam, rgb, fillAlpha, buffer);
                }
                draw(ctx, buffer);
            }
        }
        if (outline && outlineAlpha > 0.0f) {
            try (RenderContext ctx = new RenderContext(() -> Reference.MOD_ID + ":overlay/lines", throughWalls
                    ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                    : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH)) {
                BufferBuilder buffer = ctx.getBuilder();
                for (int k = 0; k < count; k++) {
                    SectionGeometry g = visibleSections.get(k);
                    int i = visibleIndex[k];
                    int rgb = colors.getOrDefault(g.matches[i], 0xFFFFFF);
                    drawEdges(g.positions[i], g.edgeMasks[i], cam, rgb, outlineAlpha, lineWidth, buffer);
                }
                draw(ctx, buffer);
            }
        }
    }

    /** RGB per match instance (groups from the config, custom blocks from their list entry). */
    private static Map<Match, Integer> colorTable() {
        Map<Match, Integer> map = new IdentityHashMap<>();
        map.put(dev.steelaspect.areascanner.scan.Matcher.UNMOVABLE, Configs.UNMOVABLE_COLOR.getIntegerValue() & 0xFFFFFF);
        map.put(dev.steelaspect.areascanner.scan.Matcher.LIQUID, Configs.LIQUIDS_COLOR.getIntegerValue() & 0xFFFFFF);
        for (CustomEntry entry : ScanLists.custom()) {
            var block = entry.block();
            if (block != null) map.put(dev.steelaspect.areascanner.scan.Matcher.customMatch(block), entry.color);
        }
        return map;
    }

    public static int colorOf(Match match) {
        if (match.category == Category.UNMOVABLE) return Configs.UNMOVABLE_COLOR.getIntegerValue() & 0xFFFFFF;
        if (match.category == Category.LIQUID) return Configs.LIQUIDS_COLOR.getIntegerValue() & 0xFFFFFF;
        if (match.block != null) {
            CustomEntry entry = ScanLists.findCustom(BuiltInRegistries.BLOCK.getKey(match.block).toString());
            if (entry != null) return entry.color;
        }
        return 0xFFFFFF;
    }

    private static void drawFaces(long pos, int mask, Vec3 cam, int rgb, float alpha, BufferBuilder b) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, bl = (rgb & 0xFF) / 255f;
        float x0 = (float) (BlockPos.getX(pos) - cam.x - EXPAND), x1 = (float) (BlockPos.getX(pos) + 1 - cam.x + EXPAND);
        float y0 = (float) (BlockPos.getY(pos) - cam.y - EXPAND), y1 = (float) (BlockPos.getY(pos) + 1 - cam.y + EXPAND);
        float z0 = (float) (BlockPos.getZ(pos) - cam.z - EXPAND), z1 = (float) (BlockPos.getZ(pos) + 1 - cam.z + EXPAND);
        // Same vertex order as MaLiLib's RenderUtils box helpers.
        if ((mask & 1) != 0) { // down
            b.addVertex(x1, y0, z1).setColor(r, g, bl, alpha);
            b.addVertex(x0, y0, z1).setColor(r, g, bl, alpha);
            b.addVertex(x0, y0, z0).setColor(r, g, bl, alpha);
            b.addVertex(x1, y0, z0).setColor(r, g, bl, alpha);
        }
        if ((mask & 2) != 0) { // up
            b.addVertex(x0, y1, z1).setColor(r, g, bl, alpha);
            b.addVertex(x1, y1, z1).setColor(r, g, bl, alpha);
            b.addVertex(x1, y1, z0).setColor(r, g, bl, alpha);
            b.addVertex(x0, y1, z0).setColor(r, g, bl, alpha);
        }
        if ((mask & 4) != 0) { // north
            b.addVertex(x1, y0, z0).setColor(r, g, bl, alpha);
            b.addVertex(x0, y0, z0).setColor(r, g, bl, alpha);
            b.addVertex(x0, y1, z0).setColor(r, g, bl, alpha);
            b.addVertex(x1, y1, z0).setColor(r, g, bl, alpha);
        }
        if ((mask & 8) != 0) { // south
            b.addVertex(x0, y0, z1).setColor(r, g, bl, alpha);
            b.addVertex(x1, y0, z1).setColor(r, g, bl, alpha);
            b.addVertex(x1, y1, z1).setColor(r, g, bl, alpha);
            b.addVertex(x0, y1, z1).setColor(r, g, bl, alpha);
        }
        if ((mask & 16) != 0) { // west
            b.addVertex(x0, y0, z0).setColor(r, g, bl, alpha);
            b.addVertex(x0, y0, z1).setColor(r, g, bl, alpha);
            b.addVertex(x0, y1, z1).setColor(r, g, bl, alpha);
            b.addVertex(x0, y1, z0).setColor(r, g, bl, alpha);
        }
        if ((mask & 32) != 0) { // east
            b.addVertex(x1, y0, z1).setColor(r, g, bl, alpha);
            b.addVertex(x1, y0, z0).setColor(r, g, bl, alpha);
            b.addVertex(x1, y1, z0).setColor(r, g, bl, alpha);
            b.addVertex(x1, y1, z1).setColor(r, g, bl, alpha);
        }
    }

    private static void drawEdges(long pos, int mask, Vec3 cam, int rgb, float alpha, float width, BufferBuilder b) {
        if (mask == 0) return;
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, bl = (rgb & 0xFF) / 255f;
        double bx = BlockPos.getX(pos) - cam.x, by = BlockPos.getY(pos) - cam.y, bz = BlockPos.getZ(pos) - cam.z;
        for (int e = 0; e < 12; e++) {
            if ((mask & (1 << e)) == 0) continue;
            double[] p0 = EDGE_FROM[e];
            double[] p1 = EDGE_TO[e];
            b.addVertex((float) (bx + p0[0]), (float) (by + p0[1]), (float) (bz + p0[2])).setColor(r, g, bl, alpha).setLineWidth(width);
            b.addVertex((float) (bx + p1[0]), (float) (by + p1[1]), (float) (bz + p1[2])).setColor(r, g, bl, alpha).setLineWidth(width);
        }
    }

    private static void draw(RenderContext ctx, BufferBuilder buffer) {
        MeshData mesh = buffer.build();
        if (mesh != null) {
            ctx.draw(mesh, false, true);
            mesh.close();
        }
    }
}
