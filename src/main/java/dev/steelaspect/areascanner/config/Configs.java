package dev.steelaspect.areascanner.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.steelaspect.areascanner.Reference;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** All options, saved together with the custom scan list and presets in config/cytra-areascanner.json. */
public final class Configs implements IConfigHandler {
    public static final Configs INSTANCE = new Configs();
    private static final String CONFIG_FILE_NAME = Reference.MOD_ID + ".json";
    private static final String PREFIX = Reference.MOD_ID + ".config";

    // --- Scanner ---
    /** Master switch: off = no scanning, overlay, HUD or scan hotkeys (the menu still opens to turn it back on). */
    public static final ConfigBoolean ENABLED = new ConfigBoolean("enabled", true).apply(PREFIX);
    public static final ConfigBoolean BACKGROUND_SCANNING = new ConfigBoolean("backgroundScanning", true).apply(PREFIX);
    public static final ConfigInteger BLOCKS_PER_TICK = new ConfigInteger("blocksPerTick", 32768, 1024, 1048576).apply(PREFIX);
    public static final ConfigBooleanHotkeyed SHOW_HUD = new ConfigBooleanHotkeyed("showHud", true, "").apply(PREFIX);
    public static final ConfigInteger HUD_X = new ConfigInteger("hudX", 4, 0, 4000).apply(PREFIX);
    public static final ConfigInteger HUD_Y = new ConfigInteger("hudY", 4, 0, 4000).apply(PREFIX);
    public static final ConfigInteger CHAT_EXPORT_LIMIT = new ConfigInteger("chatExportLimit", 30, 1, 1000).apply(PREFIX);

    public static final ImmutableList<IConfigBase> SCANNER = ImmutableList.of(
            ENABLED,
            BACKGROUND_SCANNING,
            BLOCKS_PER_TICK,
            SHOW_HUD,
            HUD_X,
            HUD_Y,
            CHAT_EXPORT_LIMIT
    );

    // --- Built-in preset "Unmovable & Liquids" ---
    public static final ConfigBoolean UNMOVABLE_ENABLED = new ConfigBoolean("unmovableEnabled", true).apply(PREFIX);
    public static final ConfigBoolean UNMOVABLE_BLOCK_ENTITIES = new ConfigBoolean("unmovableBlockEntities", true).apply(PREFIX);
    public static final ConfigColor UNMOVABLE_COLOR = new ConfigColor("unmovableColor", "0xFFFF3030").apply(PREFIX);
    public static final ConfigBoolean LIQUIDS_ENABLED = new ConfigBoolean("liquidsEnabled", true).apply(PREFIX);
    public static final ConfigBoolean LIQUIDS_SOURCES_ONLY = new ConfigBoolean("liquidsSourcesOnly", false).apply(PREFIX);
    public static final ConfigBoolean LIQUIDS_WATERLOGGED = new ConfigBoolean("liquidsWaterlogged", true).apply(PREFIX);
    public static final ConfigColor LIQUIDS_COLOR = new ConfigColor("liquidsColor", "0xFF3070FF").apply(PREFIX);
    public static final ConfigBoolean CUSTOM_ENABLED = new ConfigBoolean("customEnabled", true).apply(PREFIX);

    public static final ImmutableList<IConfigBase> GROUPS = ImmutableList.of(
            UNMOVABLE_ENABLED,
            UNMOVABLE_BLOCK_ENTITIES,
            UNMOVABLE_COLOR,
            LIQUIDS_ENABLED,
            LIQUIDS_SOURCES_ONLY,
            LIQUIDS_WATERLOGGED,
            LIQUIDS_COLOR,
            CUSTOM_ENABLED
    );

    // --- Rendering ---
    public static final ConfigBooleanHotkeyed RENDER_THROUGH_WALLS = new ConfigBooleanHotkeyed("renderThroughWalls", false, "").apply(PREFIX);
    public static final ConfigBoolean RENDER_FILL = new ConfigBoolean("renderFill", true).apply(PREFIX);
    public static final ConfigDouble FILL_ALPHA = new ConfigDouble("fillAlpha", 0.25, 0.0, 1.0, true).apply(PREFIX);
    public static final ConfigBoolean RENDER_OUTLINE = new ConfigBoolean("renderOutline", true).apply(PREFIX);
    public static final ConfigDouble OUTLINE_ALPHA = new ConfigDouble("outlineAlpha", 0.9, 0.0, 1.0, true).apply(PREFIX);
    public static final ConfigDouble LINE_WIDTH = new ConfigDouble("lineWidth", 2.0, 0.5, 10.0).apply(PREFIX);
    public static final ConfigBoolean MERGE_FACES = new ConfigBoolean("mergeFaces", true).apply(PREFIX);
    public static final ConfigInteger RENDER_RANGE = new ConfigInteger("renderRange", 128, 8, 1024).apply(PREFIX);
    public static final ConfigInteger MAX_RENDERED = new ConfigInteger("maxRendered", 40000, 100, 1000000).apply(PREFIX);

    public static final ImmutableList<IConfigBase> RENDER = ImmutableList.of(
            RENDER_THROUGH_WALLS,
            RENDER_FILL,
            FILL_ALPHA,
            RENDER_OUTLINE,
            OUTLINE_ALPHA,
            LINE_WIDTH,
            MERGE_FACES,
            RENDER_RANGE,
            MAX_RENDERED
    );

    /** Boolean options with a toggle hotkey. */
    public static final ImmutableList<ConfigBooleanHotkeyed> TOGGLES = ImmutableList.of(SHOW_HUD, RENDER_THROUGH_WALLS);

    // --- Hotkeys (all unbound by default) ---
    public static final ConfigHotkey OPEN_GUI = new ConfigHotkey("openGui", "").apply(PREFIX);
    public static final ConfigHotkey SCAN = new ConfigHotkey("scan", "").apply(PREFIX);
    public static final ConfigHotkey STOP = new ConfigHotkey("stop", "").apply(PREFIX);
    public static final ConfigHotkey NEXT_MATCH = new ConfigHotkey("nextMatch", "").apply(PREFIX);
    public static final ConfigHotkey ADD_LOOKED_AT = new ConfigHotkey("addLookedAt", "").apply(PREFIX);
    public static final ConfigHotkey EXPORT_CHAT = new ConfigHotkey("exportChat", "").apply(PREFIX);
    public static final ConfigHotkey EXPORT_CLIPBOARD = new ConfigHotkey("exportClipboard", "").apply(PREFIX);

    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.of(
            OPEN_GUI,
            SCAN,
            STOP,
            NEXT_MATCH,
            ADD_LOOKED_AT,
            EXPORT_CHAT,
            EXPORT_CLIPBOARD
    );

    /** Hotkey tab: plain hotkeys followed by the toggle keys. */
    public static final ImmutableList<IConfigBase> HOTKEY_TAB = ImmutableList.<IConfigBase>builder()
            .addAll(HOTKEYS).addAll(TOGGLES).build();

    private Configs() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    @Override
    public void load() {
        Path file = file();
        JsonObject root = null;
        if (Files.isRegularFile(file) && Files.isReadable(file)) {
            JsonElement element = JsonUtils.parseJsonFile(file);
            if (element != null && element.isJsonObject()) {
                root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Scanner", SCANNER);
                ConfigUtils.readConfigBase(root, "Groups", GROUPS);
                ConfigUtils.readConfigBase(root, "Render", RENDER);
                ConfigUtils.readConfigBase(root, "Hotkeys", HOTKEYS);
                ConfigUtils.readConfigBase(root, "Toggles", TOGGLES);
            }
        }
        ScanLists.read(root);
    }

    @Override
    public void save() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Reference.LOGGER.error("Could not create config directory {}", dir, e);
            return;
        }
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Scanner", SCANNER);
        ConfigUtils.writeConfigBase(root, "Groups", GROUPS);
        ConfigUtils.writeConfigBase(root, "Render", RENDER);
        ConfigUtils.writeConfigBase(root, "Hotkeys", HOTKEYS);
        ConfigUtils.writeConfigBase(root, "Toggles", TOGGLES);
        ScanLists.write(root);
        JsonUtils.writeJsonToFile(root, file());
    }

    @Override
    public void onConfigsChanged() {
        save();
        load();
        ChangeListener.fire();
    }

    /** Lets the scanner react to settings edited in the GUI without the config package depending on it. */
    public static final class ChangeListener {
        private static Runnable listener = () -> {
        };

        public static void set(Runnable r) {
            listener = r;
        }

        public static void fire() {
            listener.run();
        }
    }
}
