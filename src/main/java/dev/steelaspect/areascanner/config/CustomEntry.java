package dev.steelaspect.areascanner.config;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** One block on the custom scan list: registry id, RGB colour and an enable toggle. */
public final class CustomEntry {
    public final String blockId;
    public int color;
    public boolean enabled;

    public CustomEntry(String blockId, int color, boolean enabled) {
        this.blockId = blockId;
        this.color = color & 0xFFFFFF;
        this.enabled = enabled;
    }

    public CustomEntry copy() {
        return new CustomEntry(this.blockId, this.color, this.enabled);
    }

    /** The block, or null if the id isn't in the registry (e.g. a block from a mod that isn't installed). */
    @Nullable
    public Block block() {
        Identifier id = Identifier.tryParse(this.blockId);
        if (id == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("block", this.blockId);
        obj.addProperty("color", String.format("#%06X", this.color));
        obj.addProperty("enabled", this.enabled);
        return obj;
    }

    @Nullable
    public static CustomEntry fromJson(JsonObject obj) {
        if (!obj.has("block")) return null;
        int color = 0xFFAA00;
        if (obj.has("color")) {
            try {
                color = Integer.parseUnsignedInt(obj.get("color").getAsString().replace("#", "").replace("0x", ""), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        return new CustomEntry(obj.get("block").getAsString(), color, enabled);
    }

    /** A distinct default colour for the n-th entry (golden-angle hue steps). */
    public static int defaultColor(int index) {
        float hue = (index * 0.618034f + 0.12f) % 1.0f;
        return net.minecraft.util.Mth.hsvToRgb(hue, 0.75f, 1.0f) & 0xFFFFFF;
    }
}
