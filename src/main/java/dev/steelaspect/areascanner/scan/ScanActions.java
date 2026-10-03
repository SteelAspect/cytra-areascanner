package dev.steelaspect.areascanner.scan;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.Preset;
import dev.steelaspect.areascanner.config.ScanLists;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** User actions shared by the GUI buttons and the hotkeys. */
public final class ScanActions {
    private ScanActions() {
    }

    // ---------------------------------------------------------------- custom list

    /** Saves and lets the scanner pick up added/removed/toggled custom entries. */
    public static void customListChanged() {
        Configs.INSTANCE.save();
        ScanManager.onSettingsChanged();
    }

    public static void customColorChanged() {
        Configs.INSTANCE.save();
        ScanManager.onSettingsChanged();
    }

    public static void addCustomBlock(String blockId) {
        if (ScanLists.addCustom(blockId)) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, Reference.MOD_ID + ".message.added_custom", blockId);
            customListChanged();
        } else {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, Reference.MOD_ID + ".message.already_custom", blockId);
        }
    }

    public static void addLookedAtBlock() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (mc.level == null || !(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, Reference.MOD_ID + ".message.not_looking_at_block");
            return;
        }
        BlockState state = mc.level.getBlockState(blockHit.getBlockPos());
        if (state.isAir()) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, Reference.MOD_ID + ".message.not_looking_at_block");
            return;
        }
        addCustomBlock(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    // ---------------------------------------------------------------- presets

    public static void savePreset(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.ERROR, Reference.MOD_ID + ".message.preset_name_empty");
            return;
        }
        Preset existing = ScanLists.findPreset(trimmed);
        Preset preset = Preset.capture(existing != null ? existing.name : trimmed);
        if (existing != null) {
            ScanLists.presets().set(ScanLists.presets().indexOf(existing), preset);
        } else {
            ScanLists.presets().add(preset);
        }
        Configs.INSTANCE.save();
        InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, Reference.MOD_ID + ".message.preset_saved", preset.name);
    }

    public static void loadPreset(Preset preset) {
        preset.apply();
        Configs.INSTANCE.save();
        ScanManager.onSettingsChanged();
        InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, Reference.MOD_ID + ".message.preset_loaded", preset.name);
    }

    public static void deletePreset(Preset preset) {
        ScanLists.presets().remove(preset);
        Configs.INSTANCE.save();
        InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, Reference.MOD_ID + ".message.preset_deleted", preset.name);
    }
}
