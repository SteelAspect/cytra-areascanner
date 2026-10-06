package dev.steelaspect.areascanner.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The custom scan list and the named presets (stored in the main config file). */
public final class ScanLists {
    private static final List<CustomEntry> CUSTOM = new ArrayList<>();
    private static final List<Preset> PRESETS = new ArrayList<>();

    private ScanLists() {
    }

    public static List<CustomEntry> custom() {
        return CUSTOM;
    }

    public static List<Preset> presets() {
        return PRESETS;
    }

    @Nullable
    public static CustomEntry findCustom(String blockId) {
        for (CustomEntry e : CUSTOM) {
            if (e.blockId.equals(blockId)) return e;
        }
        return null;
    }

    /** Adds a block id to the custom list; returns false if it was already there. */
    public static boolean addCustom(String blockId) {
        if (findCustom(blockId) != null) return false;
        CUSTOM.add(new CustomEntry(blockId, CustomEntry.defaultColor(CUSTOM.size()), true));
        return true;
    }

    @Nullable
    public static Preset findPreset(String name) {
        for (Preset p : PRESETS) {
            if (p.name.equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    static void read(@Nullable JsonObject root) {
        CUSTOM.clear();
        PRESETS.clear();
        if (root != null && root.has("CustomList") && root.get("CustomList").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("CustomList")) {
                if (!el.isJsonObject()) continue;
                CustomEntry e = CustomEntry.fromJson(el.getAsJsonObject());
                if (e != null && findCustom(e.blockId) == null) CUSTOM.add(e);
            }
        }
        if (root != null && root.has("Presets") && root.get("Presets").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("Presets")) {
                if (el.isJsonObject()) PRESETS.add(Preset.fromJson(el.getAsJsonObject()));
            }
        } else {
            addExamplePresets();
        }
    }

    static void write(JsonObject root) {
        JsonArray custom = new JsonArray();
        for (CustomEntry e : CUSTOM) custom.add(e.toJson());
        root.add("CustomList", custom);
        JsonArray presets = new JsonArray();
        for (Preset p : PRESETS) presets.add(p.toJson());
        root.add("Presets", presets);
    }

    /** Example presets created on first start (the file has no Presets section yet). */
    private static void addExamplePresets() {
        Preset flying = new Preset("Flying machine clear");
        flying.liquids = false;
        flying.custom = false;
        PRESETS.add(flying);

        Preset slime = new Preset("Slime check");
        slime.unmovable = false;
        slime.liquids = false;
        slime.entries.add(new CustomEntry("minecraft:slime_block", 0x55FF55, true));
        slime.entries.add(new CustomEntry("minecraft:honey_block", 0xFFAA00, true));
        PRESETS.add(slime);
    }
}
