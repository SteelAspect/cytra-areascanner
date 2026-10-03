package dev.steelaspect.areascanner;

import net.fabricmc.api.ClientModInitializer;

public class AreaScannerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Reference.LOGGER.info("{} loaded", Reference.MOD_NAME);
    }
}
