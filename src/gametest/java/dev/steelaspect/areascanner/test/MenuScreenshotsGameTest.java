package dev.steelaspect.areascanner.test;

import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.ScanLists;
import dev.steelaspect.areascanner.gui.GuiCustomList;
import dev.steelaspect.areascanner.gui.GuiPresets;
import dev.steelaspect.areascanner.gui.GuiScanner;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiColorEditorHSV;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Screenshots of every menu and tab, for documentation / previews. */
public class MenuScreenshotsGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("AreaScannerTest");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamerule doDaylightCycle false");
            world.getServer().runCommand("time set noon");
            BlockPos feet = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).blockPosition());
            BlockPos o = feet.offset(-14, 0, 6);
            world.getServer().runCommand("gamemode spectator @a");

            // A glass pond, some obsidian, chests and slime so the overlay and HUD have something to show.
            fill(world, "fill %s %s glass hollow", o.offset(3, 0, 3), o.offset(13, 3, 13));
            fill(world, "fill %s %s water", o.offset(4, 1, 4), o.offset(12, 2, 12));
            fill(world, "fill %s %s obsidian", o.offset(16, 3, 6), o.offset(18, 6, 6));
            fill(world, "fill %s %s chest", o.offset(22, 3, 8), o.offset(25, 3, 8));
            fill(world, "fill %s %s slime_block", o.offset(16, 3, 12), o.offset(19, 3, 13));
            context.waitTicks(10);

            context.runOnClient(client -> {
                try {
                    Files.createDirectories(Path.of("areascanner-test"));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                SelectionManager sm = DataManager.getSelectionManager();
                if (sm.getSelectionMode() != SelectionMode.NORMAL) sm.switchSelectionMode();
                sm.createNewSelection(Path.of("areascanner-test"), "preview");
                AreaSelection sel = sm.getCurrentSelection();
                sel.removeAllSubRegionBoxes();
                sel.addSubRegionBox(new Box(o, o.offset(28, 8, 18), "area"), false);
                // Huge second box so the progress bar is visible for a while.
                sel.addSubRegionBox(new Box(o.offset(-30, -6, 24), o.offset(30, 20, 60), "big"), false);
                ScanLists.custom().clear();
                ScanLists.addCustom("minecraft:slime_block");
                ScanLists.addCustom("minecraft:honey_block");
                ScanLists.addCustom("minecraft:observer");
                Configs.BLOCKS_PER_TICK.setIntegerValue(1024);
                resetInput();
                Configs.RENDER_THROUGH_WALLS.setBooleanValue(true);
                ScanManager.start();
                client.gui.getChat().clearMessages(false);
            });
            world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f facing %.1f %.1f %.1f",
                    o.getX() + 14.5, o.getY() + 14, o.getZ() - 10.5, o.getX() + 14.5, o.getY() + 1.0, o.getZ() + 9.5));
            context.waitTicks(15);
            shot(context, "01-hud-scanning");
            context.runOnClient(client -> Configs.BLOCKS_PER_TICK.setIntegerValue(1048576));
            context.waitTicks(150); // finish + let messages fade
            context.runOnClient(client -> client.gui.getChat().clearMessages(false));
            context.waitTicks(2);
            shot(context, "02-overlay");

            context.runOnClient(client -> client.setScreen(new GuiMainMenu()));
            context.waitTicks(5);
            shot(context, "03-litematica-main-menu");

            String[] tabs = {"GROUPS", "RENDER", "SCANNER", "HOTKEYS"};
            for (int i = 0; i < tabs.length; i++) {
                String tab = tabs[i];
                context.runOnClient(client -> {
                    setTab(tab);
                    client.setScreen(new GuiScanner());
                });
                context.waitTicks(5);
                shot(context, String.format(Locale.ROOT, "%02d-scanner-tab-%s", 4 + i, tab.toLowerCase(Locale.ROOT)));
            }

            context.runOnClient(client -> client.setScreen(new GuiCustomList(null)));
            context.waitTicks(5);
            shot(context, "08-custom-list");
            context.getInput().typeChars("hop");
            context.waitTicks(3);
            shot(context, "09-custom-list-autocomplete");

            context.runOnClient(client -> resetInput());
            context.runOnClient(client -> client.setScreen(new GuiColorEditorHSV(Configs.LIQUIDS_COLOR, null, null)));
            context.waitTicks(5);
            shot(context, "10-color-picker");

            context.runOnClient(client -> client.setScreen(new GuiPresets(null)));
            context.waitTicks(5);
            shot(context, "11-presets");
            context.runOnClient(client -> {
                client.setScreen(null);
                ScanManager.stop(false);
            });
        }
    }

    private static void shot(ClientGameTestContext context, String name) {
        LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of(name)));
    }

    private static void fill(TestSingleplayerContext world, String format, BlockPos a, BlockPos b) {
        world.getServer().runCommand(String.format(Locale.ROOT, format,
                a.getX() + " " + a.getY() + " " + a.getZ(), b.getX() + " " + b.getY() + " " + b.getZ()));
    }

    /** Clears the remembered block-id text of the custom list screen. */
    private static void resetInput() {
        try {
            Field field = GuiCustomList.class.getDeclaredField("inputText");
            field.setAccessible(true);
            field.set(null, "");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setTab(String name) {
        try {
            Field field = GuiScanner.class.getDeclaredField("tab");
            field.setAccessible(true);
            Class<? extends Enum> type = (Class<? extends Enum>) field.getType();
            field.set(null, Enum.valueOf(type, name));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
