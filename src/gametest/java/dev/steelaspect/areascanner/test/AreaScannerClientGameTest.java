package dev.steelaspect.areascanner.test;

import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.ScanLists;
import dev.steelaspect.areascanner.gui.GuiCustomList;
import dev.steelaspect.areascanner.gui.GuiPresets;
import dev.steelaspect.areascanner.gui.GuiScanner;
import dev.steelaspect.areascanner.scan.Category;
import dev.steelaspect.areascanner.scan.ScanActions;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Builds a small test area, scans it through a two-box Litematica selection and checks the counts,
 * live updates, custom list, next match and exports. Takes screenshots of the overlay and the GUIs.
 */
public class AreaScannerClientGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("AreaScannerTest");
    private int failures;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamerule doDaylightCycle false");
            world.getServer().runCommand("time set noon");
            BlockPos feet = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).blockPosition());
            BlockPos o = feet.offset(3, 0, 3);

            // 7x3x5 stone block with matches embedded so nothing flows.
            cmd(world, "fill %s %s stone", o, o.offset(6, 2, 4));
            cmd(world, "setblock %s obsidian", o.offset(1, 0, 1));
            cmd(world, "setblock %s chest", o.offset(3, 0, 1));
            cmd(world, "setblock %s water", o.offset(5, 0, 1));
            cmd(world, "setblock %s oak_stairs[waterlogged=true]", o.offset(1, 0, 3));
            cmd(world, "setblock %s slime_block", o.offset(3, 0, 3));
            cmd(world, "setblock %s lava", o.offset(5, 0, 3));
            // Powered sticky piston facing up: extends into the block above the selection.
            cmd(world, "setblock %s redstone_block", o.offset(3, 1, 2));
            cmd(world, "setblock %s sticky_piston[facing=up]", o.offset(3, 2, 2));
            context.waitTicks(10);

            // Two boxes: x 0..3 and x 4..6 of the area.
            context.runOnClient(client -> {
                SelectionManager sm = DataManager.getSelectionManager();
                if (sm.getSelectionMode() != SelectionMode.NORMAL) sm.switchSelectionMode();
                try {
                    java.nio.file.Files.createDirectories(Path.of("areascanner-test"));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                sm.createNewSelection(Path.of("areascanner-test"), "scanner test");
                AreaSelection sel = sm.getCurrentSelection();
                sel.removeAllSubRegionBoxes();
                sel.addSubRegionBox(new Box(o, o.offset(3, 2, 4), "a"), false);
                sel.addSubRegionBox(new Box(o.offset(4, 0, 0), o.offset(6, 2, 4), "b"), false);
                ScanLists.custom().clear();
                Configs.CUSTOM_ENABLED.setBooleanValue(true);
                check("scan started", ScanManager.start());
            });
            context.waitTicks(5);
            // Obsidian, chest, extended sticky piston | water, waterlogged stairs, lava
            expect(context, "initial", 3, 3, 0);
            // Same scan on the main thread (Background Scanning off).
            context.runOnClient(client -> {
                Configs.BACKGROUND_SCANNING.setBooleanValue(false);
                ScanManager.start();
            });
            context.waitTicks(5);
            expect(context, "initial (main thread)", 3, 3, 0);
            context.runOnClient(client -> {
                Configs.BACKGROUND_SCANNING.setBooleanValue(true);
                ScanManager.start();
            });
            context.waitTicks(5);
            expect(context, "initial (background again)", 3, 3, 0);

            // Master switch: off stops the scan and refuses new ones; on allows scanning again.
            context.runOnClient(client -> Configs.ENABLED.setBooleanValue(false));
            context.waitTicks(2);
            context.runOnClient(client -> {
                check("disabled: running scan stopped", !ScanManager.isActive());
                check("disabled: start refused", !ScanManager.start());
                Configs.ENABLED.setBooleanValue(true);
                check("re-enabled: scan starts", ScanManager.start());
            });
            context.waitTicks(5);
            expect(context, "after re-enable", 3, 3, 0);

            // Live updates: remove obsidian, add crying obsidian, drain the water. Bedrock never counts as unmovable.
            cmd(world, "setblock %s stone", o.offset(1, 0, 1));
            cmd(world, "setblock %s crying_obsidian", o.offset(2, 1, 2));
            cmd(world, "setblock %s bedrock", o.offset(4, 1, 2));
            cmd(world, "setblock %s stone", o.offset(5, 0, 1));
            context.waitTicks(5);
            expect(context, "after live updates", 3, 2, 0);

            // Custom list: slime block, rescan happens automatically.
            context.runOnClient(client -> ScanActions.addCustomBlock("minecraft:slime_block"));
            context.waitTicks(5);
            expect(context, "with custom", 3, 2, 1);

            // Bedrock only shows up when it's on the custom list.
            context.runOnClient(client -> ScanActions.addCustomBlock("minecraft:bedrock"));
            context.waitTicks(5);
            expect(context, "bedrock as custom", 3, 2, 2);
            context.runOnClient(client -> {
                ScanLists.custom().removeIf(e -> e.blockId.equals("minecraft:bedrock"));
                ScanActions.customListChanged();
            });
            context.waitTicks(5);
            expect(context, "bedrock removed again", 3, 2, 1);

            // Settings: block entities off -> chest no longer counts; sources only keeps sources.
            context.runOnClient(client -> {
                Configs.UNMOVABLE_BLOCK_ENTITIES.setBooleanValue(false);
                ScanManager.onSettingsChanged();
            });
            context.waitTicks(5);
            expect(context, "no block entities", 2, 2, 1);
            context.runOnClient(client -> {
                Configs.UNMOVABLE_BLOCK_ENTITIES.setBooleanValue(true);
                ScanManager.onSettingsChanged();
            });
            context.waitTicks(5);

            // Next match turns the camera toward a match.
            context.runOnClient(client -> {
                ScanActions.nextMatch();
                Vec3 look = client.player.getViewVector(1.0f);
                Vec3 eye = client.player.getEyePosition();
                double best = -2;
                for (long pos : ScanManager.matches().keySet()) {
                    Vec3 dir = Vec3.atCenterOf(BlockPos.of(pos)).subtract(eye).normalize();
                    best = Math.max(best, dir.dot(look));
                }
                check("next match aims at a match (dot " + best + ")", best > 0.999);
            });

            // Clipboard export.
            context.runOnClient(client -> {
                ScanActions.exportClipboard();
                String clip = client.keyboardHandler.getClipboard();
                BlockPos slime = o.offset(3, 0, 3);
                check("clipboard has slime coords", clip.contains(slime.getX() + " " + slime.getY() + " " + slime.getZ()));
                check("clipboard has groups", clip.contains("Unmovable (3)") && clip.contains("minecraft:slime_block (1)"));
                ScanActions.exportChat();
            });

            // Overlay screenshot from above the area.
            world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f facing %.1f %.1f %.1f",
                    o.getX() + 3.5, o.getY() + 6, o.getZ() - 5.5, o.getX() + 3.5, o.getY() + 1.0, o.getZ() + 2.5));
            context.runOnClient(client -> {
                Configs.RENDER_THROUGH_WALLS.setBooleanValue(true);
                client.gui.getChat().clearMessages(false);
            });
            context.waitTicks(120); // let the MaLiLib messages fade
            context.runOnClient(client -> client.gui.getChat().clearMessages(false));
            context.waitTicks(2);
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("overlay")));

            // Litematica main menu has the button.
            context.runOnClient(client -> client.setScreen(new GuiMainMenu()));
            context.waitTicks(5);
            context.runOnClient(client -> check("main menu button", hasButton(client.screen, "Area Scanner")));
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("litematica-menu")));
            context.runOnClient(client -> client.setScreen(new GuiScanner()));
            context.waitTicks(5);
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("scanner-gui")));
            context.runOnClient(client -> client.setScreen(new GuiCustomList(null)));
            context.waitTicks(3);
            context.getInput().typeChars("obsi");
            context.waitTicks(3);
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("custom-list")));
            context.runOnClient(client -> client.setScreen(new GuiPresets(null)));
            context.waitTicks(3);
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("presets")));
            context.runOnClient(client -> client.setScreen(null));

            // Pending chunks: a box far outside the client's view distance is pending until it loads.
            BlockPos far = o.offset(480, 0, 0);
            world.getServer().runCommand("forceload add " + far.getX() + " " + far.getZ());
            context.waitTicks(20);
            cmd(world, "setblock %s crying_obsidian", far);
            world.getServer().runCommand("forceload remove " + far.getX() + " " + far.getZ());
            context.runOnClient(client -> {
                AreaSelection sel = DataManager.getSelectionManager().getCurrentSelection();
                sel.addSubRegionBox(new Box(far.offset(-1, 0, -1), far.offset(1, 1, 1), "far"), false);
                ScanManager.start();
            });
            context.waitTicks(5);
            context.runOnClient(client -> check("far box pending (" + ScanManager.pendingChunks() + ")",
                    ScanManager.pendingChunks() > 0 && ScanManager.count(Category.UNMOVABLE) == 3));
            world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %d %d %d", far.getX(), far.getY() + 3, far.getZ() + 3));
            for (int i = 0; i < 40; i++) {
                context.waitTicks(10);
                boolean done = context.computeOnClient(client -> ScanManager.pendingChunks() == 0 && !ScanManager.isScanning());
                if (done) break;
            }
            context.runOnClient(client -> check("far box scanned after load: pending " + ScanManager.pendingChunks()
                    + ", unmovable " + ScanManager.count(Category.UNMOVABLE),
                    ScanManager.pendingChunks() == 0 && ScanManager.count(Category.UNMOVABLE) == 4));

            context.runOnClient(client -> {
                ScanManager.stop(true);
                check("stopped clears matches", !ScanManager.isActive() && ScanManager.totalMatches() == 0);
            });
        }
        if (this.failures > 0) throw new AssertionError(this.failures + " area scanner check(s) failed, see log");
        LOG.info("All area scanner checks passed");
    }

    private void expect(ClientGameTestContext context, String label, int unmovable, int liquids, int custom) {
        context.runOnClient(client -> {
            int u = ScanManager.count(Category.UNMOVABLE), l = ScanManager.count(Category.LIQUID), c = ScanManager.count(Category.CUSTOM);
            check(label + ": expected " + unmovable + "/" + liquids + "/" + custom + ", got " + u + "/" + l + "/" + c,
                    u == unmovable && l == liquids && c == custom && !ScanManager.isScanning());
        });
    }

    private void check(String label, boolean ok) {
        if (ok) {
            LOG.info("PASS {}", label);
        } else {
            this.failures++;
            LOG.error("FAIL {}", label);
        }
    }

    private static void cmd(TestSingleplayerContext world, String format, BlockPos a) {
        world.getServer().runCommand(String.format(Locale.ROOT, format, a.getX() + " " + a.getY() + " " + a.getZ()));
    }

    private static void cmd(TestSingleplayerContext world, String format, BlockPos a, BlockPos b) {
        world.getServer().runCommand(String.format(Locale.ROOT, format,
                a.getX() + " " + a.getY() + " " + a.getZ(), b.getX() + " " + b.getY() + " " + b.getZ()));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasButton(Object screen, String label) {
        try {
            Field field = fi.dy.masa.malilib.gui.GuiBase.class.getDeclaredField("buttons");
            field.setAccessible(true);
            for (ButtonBase button : (List<ButtonBase>) field.get(screen)) {
                Field text = ButtonBase.class.getDeclaredField("displayString");
                text.setAccessible(true);
                if (label.equals(text.get(button))) return true;
            }
        } catch (ReflectiveOperationException e) {
            LOG.error("Button lookup failed", e);
        }
        return false;
    }
}
