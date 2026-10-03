package dev.steelaspect.areascanner;

import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.gui.GuiScanner;
import dev.steelaspect.areascanner.input.InputHandler;
import dev.steelaspect.areascanner.render.HudRenderer;
import dev.steelaspect.areascanner.render.ScanRenderer;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class AreaScannerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            Configs.INSTANCE.load();
            ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiScanner::new));
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
            RenderEventHandler.getInstance().registerWorldLastRenderer(ScanRenderer.INSTANCE);
            RenderEventHandler.getInstance().registerGameOverlayRenderer(HudRenderer.INSTANCE);
            Configs.ChangeListener.set(ScanManager::onSettingsChanged);
            ScanManager.setListener(ScanRenderer.INSTANCE);
        });

        ClientTickEvents.END_CLIENT_TICK.register(ScanManager::tick);
        ClientChunkEvents.CHUNK_LOAD.register(ScanManager::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(ScanManager::onChunkUnload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ScanManager.stop(false));
    }
}
