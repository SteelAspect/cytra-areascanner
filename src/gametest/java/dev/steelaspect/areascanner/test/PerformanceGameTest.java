package dev.steelaspect.areascanner.test;

import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.scan.Category;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Large scan benchmark: ~150k water blocks in a ~1.3M block selection, scanned on the main thread and in the
 * background. Logs scan time, the slowest client tick and frame rate while scanning, then checks live updates
 * still work with that many matches.
 */
public class PerformanceGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("AreaScannerTest");
    private int failures;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("gamerule doDaylightCycle false");
            BlockPos feet = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).blockPosition());
            BlockPos o = feet.offset(-48, 0, -48);
            // 96x16x96 glass tank of water (built in slices to stay under the /fill limit).
            for (int x = 0; x < 96; x += 16) {
                fill(world, "fill %s %s glass", o.offset(x, 0, 0), o.offset(x + 15, 17, 95));
                fill(world, "fill %s %s water", o.offset(x, 1, 1).offset(x == 0 ? 1 : 0, 0, 0),
                        o.offset(Math.min(x + 15, 94), 16, 94));
            }
            context.waitTicks(40);
            context.runOnClient(client -> {
                try {
                    Files.createDirectories(Path.of("areascanner-test"));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                SelectionManager sm = DataManager.getSelectionManager();
                if (sm.getSelectionMode() != SelectionMode.NORMAL) sm.switchSelectionMode();
                sm.createNewSelection(Path.of("areascanner-test"), "perf");
                AreaSelection sel = sm.getCurrentSelection();
                sel.removeAllSubRegionBoxes();
                sel.addSubRegionBox(new Box(o.offset(-16, -8, -16), o.offset(111, 72, 111), "big"), false);
                Configs.RENDER_THROUGH_WALLS.setBooleanValue(true);
            });
            world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %d %d %d facing %d %d %d",
                    o.getX() + 48, o.getY() + 40, o.getZ() - 30, o.getX() + 48, o.getY(), o.getZ() + 48));
            context.waitTicks(20);

            int inline = run(context, "main thread", false);
            int background = run(context, "background", true);
            int liquids = context.computeOnClient(client -> ScanManager.count(Category.LIQUID));
            check("found the water (" + liquids + ")", liquids > 100_000);
            check("both modes agree (" + inline + " vs " + background + ")", inline == background);
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("perf-overlay")));

            // Live update with ~150k matches: drain one column of water.
            BlockPos hole = o.offset(48, 1, 48);
            fill(world, "fill %s %s stone", hole, hole.offset(0, 15, 0));
            context.waitTicks(5);
            int after = context.computeOnClient(client -> ScanManager.count(Category.LIQUID));
            check("live update with many matches (" + liquids + " -> " + after + ")", after == liquids - 16);
            context.runOnClient(client -> ScanManager.stop(false));
        }
        if (this.failures > 0) throw new AssertionError(this.failures + " performance check(s) failed, see log");
    }

    /** Scans the selection; logs ticks taken, slowest tick and average FPS. Returns the liquid count. */
    private int run(ClientGameTestContext context, String label, boolean background) {
        context.runOnClient(client -> {
            Configs.BACKGROUND_SCANNING.setBooleanValue(background);
            Configs.BLOCKS_PER_TICK.setIntegerValue(32768);
            ScanManager.start();
        });
        long start = System.nanoTime();
        long slowest = 0;
        int ticks = 0;
        int fpsSum = 0;
        long last = System.nanoTime();
        while (context.computeOnClient(client -> ScanManager.isScanning()) && ticks < 4000) {
            context.waitTick();
            long now = System.nanoTime();
            slowest = Math.max(slowest, now - last);
            last = now;
            ticks++;
            fpsSum += context.computeOnClient(client -> client.getFps());
        }
        // Let the overlay finish building, then measure frame rate with everything shown.
        context.waitTicks(40);
        int fpsAfter = context.computeOnClient(client -> client.getFps());
        long ms = (System.nanoTime() - start) / 1_000_000 - 40 * 50;
        int liquids = context.computeOnClient(client -> ScanManager.count(Category.LIQUID));
        LOG.info("PERF {}: {} ticks (~{} ms), slowest tick {} ms, avg FPS while scanning {}, FPS after {}, liquids {}",
                label, ticks, ms, slowest / 1_000_000, ticks > 0 ? fpsSum / ticks : -1, fpsAfter, liquids);
        return liquids;
    }

    private void check(String label, boolean ok) {
        if (ok) {
            LOG.info("PASS {}", label);
        } else {
            this.failures++;
            LOG.error("FAIL {}", label);
        }
    }

    private static void fill(TestSingleplayerContext world, String format, BlockPos a, BlockPos b) {
        world.getServer().runCommand(String.format(Locale.ROOT, format,
                a.getX() + " " + a.getY() + " " + a.getZ(), b.getX() + " " + b.getY() + " " + b.getZ()));
    }
}
