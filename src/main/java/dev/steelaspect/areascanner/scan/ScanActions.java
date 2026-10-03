package dev.steelaspect.areascanner.scan;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.Preset;
import dev.steelaspect.areascanner.config.ScanLists;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import dev.steelaspect.areascanner.render.ScanRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ---------------------------------------------------------------- next match

    /** Matches already pointed at, so repeated presses cycle outward instead of staying on the nearest one. */
    private static final LongOpenHashSet VISITED = new LongOpenHashSet();

    public static void resetCycle() {
        VISITED.clear();
    }

    /** Turns the camera to the nearest match not visited yet. The player is not moved. */
    public static void nextMatch() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!ScanManager.isActive()) {
            InfoUtils.printActionbarMessage(Reference.MOD_ID + ".message.not_active");
            return;
        }
        Long2ObjectMap<Match> matches = ScanManager.matches();
        if (matches.isEmpty()) {
            InfoUtils.printActionbarMessage(Reference.MOD_ID + ".message.no_matches");
            return;
        }
        VISITED.removeIf(p -> !matches.containsKey(p));
        if (VISITED.size() >= matches.size()) VISITED.clear();

        Vec3 eye = player.getEyePosition();
        long best = 0;
        Match bestMatch = null;
        double bestDist = Double.MAX_VALUE;
        for (ObjectIterator<Long2ObjectMap.Entry<Match>> it = ScanManager.matches().long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Match> e = it.next();
            long pos = e.getLongKey();
            if (VISITED.contains(pos)) continue;
            double dx = BlockPos.getX(pos) + 0.5 - eye.x;
            double dy = BlockPos.getY(pos) + 0.5 - eye.y;
            double dz = BlockPos.getZ(pos) + 0.5 - eye.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = pos;
                bestMatch = e.getValue();
            }
        }
        if (bestMatch == null) return;
        VISITED.add(best);

        double dx = BlockPos.getX(best) + 0.5 - eye.x;
        double dy = BlockPos.getY(best) + 0.5 - eye.y;
        double dz = BlockPos.getZ(best) + 0.5 - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f);
        float pitch = Mth.clamp((float) (-(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG)), -90.0f, 90.0f);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);

        BlockPos p = BlockPos.of(best);
        int remaining = matches.size() - VISITED.size();
        InfoUtils.printActionbarMessage(Reference.MOD_ID + ".message.next_match",
                p.getX() + " " + p.getY() + " " + p.getZ(), groupName(bestMatch), remaining);
    }

    // ---------------------------------------------------------------- export

    private static String groupName(Match match) {
        if (match.category == Category.CUSTOM && match.block != null) {
            return BuiltInRegistries.BLOCK.getKey(match.block).toString();
        }
        return StringUtils.translate(Reference.MOD_ID + ".group." + match.category.key);
    }

    /** Positions per group: Unmovable, Liquids, then one group per custom block; sorted by Y, Z, X. */
    private static Map<Match, LongArrayList> grouped() {
        Map<Match, LongArrayList> map = new LinkedHashMap<>();
        map.put(Matcher.UNMOVABLE, new LongArrayList());
        map.put(Matcher.LIQUID, new LongArrayList());
        for (ObjectIterator<Long2ObjectMap.Entry<Match>> it = ScanManager.matches().long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Match> e = it.next();
            map.computeIfAbsent(e.getValue(), k -> new LongArrayList()).add(e.getLongKey());
        }
        map.values().removeIf(LongArrayList::isEmpty);
        for (LongArrayList list : map.values()) {
            list.sort((a, b) -> {
                int c = Integer.compare(BlockPos.getY(a), BlockPos.getY(b));
                if (c == 0) c = Integer.compare(BlockPos.getZ(a), BlockPos.getZ(b));
                if (c == 0) c = Integer.compare(BlockPos.getX(a), BlockPos.getX(b));
                return c;
            });
        }
        return map;
    }

    private static String coords(long pos) {
        return BlockPos.getX(pos) + " " + BlockPos.getY(pos) + " " + BlockPos.getZ(pos);
    }

    private static boolean checkExportable() {
        if (!ScanManager.isActive()) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, Reference.MOD_ID + ".message.not_active");
            return false;
        }
        if (ScanManager.totalMatches() == 0) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, Reference.MOD_ID + ".message.no_matches");
            return false;
        }
        return true;
    }

    /** Prints the coordinates per group in chat (client-side only, nothing is sent to the server). */
    public static void exportChat() {
        Minecraft mc = Minecraft.getInstance();
        if (!checkExportable()) return;
        int limit = Configs.CHAT_EXPORT_LIMIT.getIntegerValue();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(StringUtils.translate(Reference.MOD_ID + ".export.header", ScanManager.totalMatches())).withStyle(ChatFormatting.GOLD));
        for (Map.Entry<Match, LongArrayList> group : grouped().entrySet()) {
            LongArrayList list = group.getValue();
            int rgb = ScanRenderer.colorOf(group.getKey());
            lines.add(Component.literal(StringUtils.translate(Reference.MOD_ID + ".export.group", groupName(group.getKey()), list.size()))
                    .withStyle(s -> s.withColor(TextColor.fromRgb(rgb)).withBold(true)));
            int shown = Math.min(limit, list.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (sb.length() > 0) sb.append(", ");
                sb.append('[').append(coords(list.getLong(i))).append(']');
                if ((i + 1) % 5 == 0 || i == shown - 1) {
                    lines.add(Component.literal("  " + sb).withStyle(ChatFormatting.GRAY));
                    sb.setLength(0);
                }
            }
            if (list.size() > shown) {
                lines.add(Component.literal(StringUtils.translate(Reference.MOD_ID + ".export.more", list.size() - shown)).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        for (Component line : lines) mc.gui.getChat().addMessage(line);
    }

    /** Copies every match, grouped, to the clipboard. */
    public static void exportClipboard() {
        Minecraft mc = Minecraft.getInstance();
        if (!checkExportable()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Match, LongArrayList> group : grouped().entrySet()) {
            LongArrayList list = group.getValue();
            sb.append(groupName(group.getKey())).append(" (").append(list.size()).append(")\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append(coords(list.getLong(i))).append('\n');
            }
            sb.append('\n');
        }
        mc.keyboardHandler.setClipboard(sb.toString().trim());
        InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, Reference.MOD_ID + ".message.exported_clipboard", ScanManager.totalMatches());
    }
}
