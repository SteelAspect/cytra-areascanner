package dev.steelaspect.areascanner.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A named snapshot of the group settings plus the custom scan list. */
public final class Preset {
    public String name;
    public boolean unmovable = true;
    public boolean blockEntities = true;
    public String unmovableColor = "0xFFFF3030";
    public boolean liquids = true;
    public boolean sourcesOnly = false;
    public boolean waterlogged = true;
    public String liquidsColor = "0xFF3070FF";
    public boolean custom = true;
    public final List<CustomEntry> entries = new ArrayList<>();

    public Preset(String name) {
        this.name = name;
    }

    /** Captures the current settings. */
    public static Preset capture(String name) {
        Preset p = new Preset(name);
        p.unmovable = Configs.UNMOVABLE_ENABLED.getBooleanValue();
        p.blockEntities = Configs.UNMOVABLE_BLOCK_ENTITIES.getBooleanValue();
        p.unmovableColor = Configs.UNMOVABLE_COLOR.getStringValue();
        p.liquids = Configs.LIQUIDS_ENABLED.getBooleanValue();
        p.sourcesOnly = Configs.LIQUIDS_SOURCES_ONLY.getBooleanValue();
        p.waterlogged = Configs.LIQUIDS_WATERLOGGED.getBooleanValue();
        p.liquidsColor = Configs.LIQUIDS_COLOR.getStringValue();
        p.custom = Configs.CUSTOM_ENABLED.getBooleanValue();
        for (CustomEntry e : ScanLists.custom()) p.entries.add(e.copy());
        return p;
    }

    /** Applies this preset to the current settings (caller saves). */
    public void apply() {
        Configs.UNMOVABLE_ENABLED.setBooleanValue(this.unmovable);
        Configs.UNMOVABLE_BLOCK_ENTITIES.setBooleanValue(this.blockEntities);
        Configs.UNMOVABLE_COLOR.setValueFromString(this.unmovableColor);
        Configs.LIQUIDS_ENABLED.setBooleanValue(this.liquids);
        Configs.LIQUIDS_SOURCES_ONLY.setBooleanValue(this.sourcesOnly);
        Configs.LIQUIDS_WATERLOGGED.setBooleanValue(this.waterlogged);
        Configs.LIQUIDS_COLOR.setValueFromString(this.liquidsColor);
        Configs.CUSTOM_ENABLED.setBooleanValue(this.custom);
        List<CustomEntry> list = ScanLists.custom();
        list.clear();
        for (CustomEntry e : this.entries) list.add(e.copy());
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", this.name);
        obj.addProperty("unmovable", this.unmovable);
        obj.addProperty("blockEntities", this.blockEntities);
        obj.addProperty("unmovableColor", this.unmovableColor);
        obj.addProperty("liquids", this.liquids);
        obj.addProperty("sourcesOnly", this.sourcesOnly);
        obj.addProperty("waterlogged", this.waterlogged);
        obj.addProperty("liquidsColor", this.liquidsColor);
        obj.addProperty("custom", this.custom);
        JsonArray arr = new JsonArray();
        for (CustomEntry e : this.entries) arr.add(e.toJson());
        obj.add("entries", arr);
        return obj;
    }

    public static Preset fromJson(JsonObject obj) {
        Preset p = new Preset(obj.has("name") ? obj.get("name").getAsString() : "Preset");
        p.unmovable = bool(obj, "unmovable", p.unmovable);
        p.blockEntities = bool(obj, "blockEntities", p.blockEntities);
        p.unmovableColor = str(obj, "unmovableColor", p.unmovableColor);
        p.liquids = bool(obj, "liquids", p.liquids);
        p.sourcesOnly = bool(obj, "sourcesOnly", p.sourcesOnly);
        p.waterlogged = bool(obj, "waterlogged", p.waterlogged);
        p.liquidsColor = str(obj, "liquidsColor", p.liquidsColor);
        p.custom = bool(obj, "custom", p.custom);
        if (obj.has("entries") && obj.get("entries").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("entries")) {
                if (!el.isJsonObject()) continue;
                CustomEntry e = CustomEntry.fromJson(el.getAsJsonObject());
                if (e != null) p.entries.add(e);
            }
        }
        return p;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }
}
