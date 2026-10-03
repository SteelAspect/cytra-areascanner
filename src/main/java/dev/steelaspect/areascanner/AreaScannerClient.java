package dev.steelaspect.areascanner;

import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.gui.GuiScanner;
import dev.steelaspect.areascanner.input.InputHandler;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ClientModInitializer;

public class AreaScannerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            Configs.INSTANCE.load();
            ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiScanner::new));
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        });
    }
}
