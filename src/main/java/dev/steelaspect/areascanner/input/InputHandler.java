package dev.steelaspect.areascanner.input;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.gui.GuiScanner;
import dev.steelaspect.areascanner.scan.ScanActions;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class InputHandler implements IKeybindProvider, IHotkeyCallback {
    private static final InputHandler INSTANCE = new InputHandler();

    private InputHandler() {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) {
            hotkey.getKeybind().setCallback(this);
        }
        for (ConfigBooleanHotkeyed toggle : Configs.TOGGLES) {
            toggle.getKeybind().setCallback(new KeyCallbackToggleBooleanConfigWithMessage(toggle));
        }
    }

    public static InputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
        for (ConfigBooleanHotkeyed toggle : Configs.TOGGLES) {
            manager.addKeybindToMap(toggle.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        List<IHotkey> all = new ArrayList<>(Configs.HOTKEYS);
        all.addAll(Configs.TOGGLES);
        manager.addHotkeysForCategory(Reference.MOD_NAME, Reference.MOD_ID + ".hotkeys.category.generic", all);
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        Minecraft mc = Minecraft.getInstance();
        if (key == Configs.OPEN_GUI.getKeybind()) {
            GuiBase.openGui(new GuiScanner());
            return true;
        }
        return handleScanKeys(mc, key);
    }

    private static boolean handleScanKeys(Minecraft mc, IKeybind key) {
        if (mc.player == null) return false;
        if (key == Configs.SCAN.getKeybind()) {
            ScanManager.start();
            return true;
        }
        if (key == Configs.ADD_LOOKED_AT.getKeybind()) {
            ScanActions.addLookedAtBlock();
            return true;
        }
        if (key == Configs.STOP.getKeybind()) {
            ScanManager.stop(true);
            return true;
        }
        return false;
    }
}
