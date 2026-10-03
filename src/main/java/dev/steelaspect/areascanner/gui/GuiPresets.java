package dev.steelaspect.areascanner.gui;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Preset;
import dev.steelaspect.areascanner.config.ScanLists;
import dev.steelaspect.areascanner.scan.ScanActions;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Save the current groups + custom list as a named preset, and load/overwrite/delete presets. */
public class GuiPresets extends GuiBase {
    private static final int LIST_TOP = 80;
    private static final int ROW_HEIGHT = 22;

    private static String nameText = "";
    private static int scroll;

    public GuiPresets(@Nullable Screen parent) {
        this.title = StringUtils.translate(Reference.MOD_ID + ".gui.title.presets");
        this.setParent(parent);
    }

    @Override
    public void initGui() {
        super.initGui();
        ButtonGeneric back = new ButtonGeneric(12, 26, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.back"));
        this.addButton(back, (b, m) -> this.closeGui(true));

        this.addLabel(12, 52, 200, 10, 0xFFC0C0C0, StringUtils.translate(Reference.MOD_ID + ".gui.label.preset_name"));
        int fieldWidth = Math.min(200, this.width - 200);
        GuiTextFieldGeneric field = new GuiTextFieldGeneric(90, 50, fieldWidth, 16, this.font);
        field.setMaxLengthWrapper(64);
        field.setValueWrapper(nameText);
        this.addTextField(field, f -> {
            nameText = f.getValueWrapper();
            return true;
        });
        ButtonGeneric save = new ButtonGeneric(90 + fieldWidth + 4, 48, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.save_preset"));
        this.addButton(save, (b, m) -> {
            ScanActions.savePreset(nameText);
            this.initGui();
        });

        List<Preset> presets = ScanLists.presets();
        if (presets.isEmpty()) {
            this.addLabel(12, LIST_TOP + 4, 300, 10, 0xFFA0A0A0, StringUtils.translate(Reference.MOD_ID + ".gui.label.empty_presets"));
            return;
        }
        int rows = Math.max(1, (this.height - LIST_TOP - 8) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, presets.size() - rows));
        String load = StringUtils.translate(Reference.MOD_ID + ".gui.button.load");
        String overwrite = StringUtils.translate(Reference.MOD_ID + ".gui.button.overwrite");
        String delete = StringUtils.translate(Reference.MOD_ID + ".gui.button.delete");
        int loadW = this.getStringWidth(load) + 10;
        int overwriteW = this.getStringWidth(overwrite) + 10;
        int deleteW = this.getStringWidth(delete) + 10;
        int y = LIST_TOP;
        for (int i = scroll; i < presets.size() && i < scroll + rows; i++) {
            Preset preset = presets.get(i);
            int x = this.width - 12 - deleteW;
            this.addButton(new ButtonGeneric(x, y, deleteW, 20, delete), (b, m) -> {
                ScanActions.deletePreset(preset);
                this.initGui();
            });
            x -= overwriteW + 2;
            this.addButton(new ButtonGeneric(x, y, overwriteW, 20, overwrite), (b, m) -> {
                ScanActions.savePreset(preset.name);
                this.initGui();
            });
            x -= loadW + 2;
            this.addButton(new ButtonGeneric(x, y, loadW, 20, load), (b, m) -> {
                ScanActions.loadPreset(preset);
                this.initGui();
            });
            String info = StringUtils.translate(Reference.MOD_ID + ".gui.label.preset_info",
                    onOff(preset.unmovable), onOff(preset.liquids),
                    preset.custom ? String.valueOf(preset.entries.stream().filter(e -> e.enabled).count()) : onOff(false));
            this.addLabel(12, y + 1, x - 16, 10, 0xFFFFFFFF, preset.name);
            this.addLabel(12, y + 11, x - 16, 10, 0xFF909090, info);
            y += ROW_HEIGHT;
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY >= LIST_TOP) {
            scroll += verticalAmount > 0 ? -1 : 1;
            this.initGui();
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
