package dev.steelaspect.areascanner.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.CustomEntry;
import dev.steelaspect.areascanner.config.ScanLists;
import dev.steelaspect.areascanner.scan.Category;
import dev.steelaspect.areascanner.scan.Match;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws the matches with MaLiLib's pipelines (the same ones Litematica's overlays use): translucent sides
 * plus outlines, optionally through walls. Neighbouring matches of the same colour are merged into one
 * shape, so large areas stay cheap to draw.
 */
public final class ScanRenderer implements IRenderer {
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
    private static final long REBUILD_INTERVAL_MS = 100;
    private static final long REBUILD_INTERVAL_SCANNING_MS = 500;

    // Render cache, rebuilt when the matches change.
    private long[] positions = new long[0];
    private Match[] cachedMatches = new Match[0];
    private byte[] faceMasks = new byte[0];
    private short[] edgeMasks = new short[0];
    private int size;
    private int cachedVersion = -1;
    private boolean cachedMerge;
    private long lastRebuild;
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
        if (!ScanManager.isActive() || ScanManager.totalMatches() == 0) return;
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

    private void updateCache() {
        boolean merge = Configs.MERGE_FACES.getBooleanValue();
        int version = ScanManager.version();
        if (version == this.cachedVersion && merge == this.cachedMerge) return;
        long now = System.currentTimeMillis();
        // Rebuilding is O(matches); with very large match sets rebuild less often.
        long interval = (ScanManager.isScanning() ? REBUILD_INTERVAL_SCANNING_MS : REBUILD_INTERVAL_MS) + ScanManager.totalMatches() / 2000;
        if (merge == this.cachedMerge && this.cachedVersion != -1 && now - this.lastRebuild < interval) return;
        this.lastRebuild = now;
        this.cachedVersion = version;
        this.cachedMerge = merge;

        Long2ObjectOpenHashMap<Match> matches = ScanManager.matches();
        int n = matches.size();
        if (this.positions.length < n) {
            int cap = Math.max(n, this.positions.length * 3 / 2);
            this.positions = new long[cap];
            this.cachedMatches = new Match[cap];
            this.faceMasks = new byte[cap];
            this.edgeMasks = new short[cap];
        }
        int i = 0;
        boolean[] same = new boolean[6];
        for (ObjectIterator<Long2ObjectMap.Entry<Match>> it = matches.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Match> entry = it.next();
            long pos = entry.getLongKey();
            Match match = entry.getValue();
            int faces = 0x3F;
            int edges = 0xFFF;
            if (merge) {
                int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                faces = 0;
                for (int d = 0; d < 6; d++) {
                    same[d] = matches.get(BlockPos.asLong(x + DX[d], y + DY[d], z + DZ[d])) == match;
                    if (!same[d]) faces |= 1 << d;
                }
                edges = 0;
                if (faces != 0) {
                    for (int e = 0; e < 12; e++) {
                        int a = EDGES[e][0], b = EDGES[e][1];
                        boolean draw;
                        if (!same[a] && !same[b]) {
                            draw = true; // convex edge
                        } else if (same[a] != same[b]) {
                            // One side continues: it's an edge only if the shape turns inward there (concave).
                            draw = matches.get(BlockPos.asLong(x + DX[a] + DX[b], y + DY[a] + DY[b], z + DZ[a] + DZ[b])) == match;
                        } else {
                            draw = false;
                        }
                        if (draw) edges |= 1 << e;
                    }
                }
            }
            this.positions[i] = pos;
            this.cachedMatches[i] = match;
            this.faceMasks[i] = (byte) faces;
            this.edgeMasks[i] = (short) edges;
            i++;
        }
        this.size = i;
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

        // Pick what is in range once, shared by both passes.
        int[] visible = new int[Math.min(this.size, maxRendered)];
        int count = 0;
        for (int i = 0; i < this.size && count < visible.length; i++) {
            if (this.faceMasks[i] == 0) continue;
            long pos = this.positions[i];
            double dx = BlockPos.getX(pos) + 0.5 - cam.x;
            double dy = BlockPos.getY(pos) + 0.5 - cam.y;
            double dz = BlockPos.getZ(pos) + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz <= rangeSq) visible[count++] = i;
        }
        if (count == 0) return;

        if (fill && fillAlpha > 0.0f) {
            try (RenderContext ctx = new RenderContext(() -> Reference.MOD_ID + ":overlay/quads", throughWalls
                    ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL
                    : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_OFFSET_2)) {
                BufferBuilder buffer = ctx.getBuilder();
                for (int k = 0; k < count; k++) {
                    int i = visible[k];
                    int rgb = colors.getOrDefault(this.cachedMatches[i], 0xFFFFFF);
                    drawFaces(this.positions[i], this.faceMasks[i], cam, rgb, fillAlpha, buffer);
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
                    int i = visible[k];
                    int rgb = colors.getOrDefault(this.cachedMatches[i], 0xFFFFFF);
                    drawEdges(this.positions[i], this.edgeMasks[i], cam, rgb, outlineAlpha, lineWidth, buffer);
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
