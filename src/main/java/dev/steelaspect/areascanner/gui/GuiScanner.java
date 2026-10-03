package dev.steelaspect.areascanner.gui;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.scan.Category;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The Area Scanner screen opened from Litematica's main menu. */
public class GuiScanner extends GuiConfigsBase {
    private static Tab tab = Tab.GROUPS;

    public GuiScanner(@Nullable Screen parent) {
        super(10, 72, Reference.MOD_ID, parent, Reference.MOD_ID + ".gui.title.scanner");
    }

    public GuiScanner() {
        this(null);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;
        for (Tab t : Tab.values()) {
            ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(t.translationKey));
            button.setEnabled(t != tab);
            this.addButton(button, new TabListener(t, this));
            x += button.getWidth() + 2;
        }
        this.createListButtons(x + 8, y);

        this.createActionButtons(10, 48);
    }

    /** Buttons that open the custom list and preset screens. */
    protected void createListButtons(int x, int y) {
        ButtonGeneric custom = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.custom_list"));
        this.addButton(custom, (b, m) -> GuiBase.openGui(new GuiCustomList(this)));
        x += custom.getWidth() + 2;
        ButtonGeneric presets = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.presets"));
        this.addButton(presets, (b, m) -> GuiBase.openGui(new GuiPresets(this)));
    }

    /** Row of scan action buttons above the option list. */
    protected void createActionButtons(int x, int y) {
        String scanKey = ScanManager.isActive() ? "rescan" : "scan";
        ButtonGeneric scan = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button." + scanKey));
        this.addButton(scan, (b, m) -> {
            ScanManager.start();
            this.initGui();
        });
        x += scan.getWidth() + 2;
        ButtonGeneric stop = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.stop"));
        stop.setEnabled(ScanManager.isActive());
        this.addButton(stop, (b, m) -> {
            ScanManager.stop(true);
            this.initGui();
        });
        x += stop.getWidth() + 2;
        this.statusX = this.createExportButtons(x, y) + 6;
    }

    /** Adds the export buttons; returns the x after the last one. */
    protected int createExportButtons(int x, int y) {
        return x;
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        String status;
        if (!ScanManager.isActive()) {
            int boxes = ScanManager.selectionBoxCount();
            String sel = boxes > 0 ? boxes + " box(es)" : StringUtils.translate(Reference.MOD_ID + ".gui.label.status.no_selection");
            status = StringUtils.translate(Reference.MOD_ID + ".gui.label.status.idle", sel);
        } else if (ScanManager.isScanning()) {
            status = StringUtils.translate(Reference.MOD_ID + ".gui.label.status.scanning",
                    (int) (ScanManager.progress() * 100), ScanManager.totalMatches());
        } else {
            status = StringUtils.translate(Reference.MOD_ID + ".gui.label.status.live",
                    ScanManager.count(Category.UNMOVABLE), ScanManager.count(Category.LIQUID), ScanManager.count(Category.CUSTOM));
        }
        if (ScanManager.isActive() && ScanManager.pendingChunks() > 0) {
            status += StringUtils.translate(Reference.MOD_ID + ".gui.label.status.pending", ScanManager.pendingChunks());
        }
        ctx.drawString(this.font, status, this.statusX, 54, 0xFFE0E0E0);
    }

    private int statusX = 10;

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS ? 204 : 140;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case GROUPS -> Configs.GROUPS;
            case RENDER -> Configs.RENDER;
            case SCANNER -> Configs.SCANNER;
            case HOTKEYS -> Configs.HOTKEY_TAB;
        };
        return ConfigOptionWrapper.createFor(configs);
    }

    private enum Tab {
        GROUPS(Reference.MOD_ID + ".gui.button.tab.groups"),
        RENDER(Reference.MOD_ID + ".gui.button.tab.render"),
        SCANNER(Reference.MOD_ID + ".gui.button.tab.scanner"),
        HOTKEYS(Reference.MOD_ID + ".gui.button.tab.hotkeys");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private record TabListener(Tab tab, GuiScanner parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GuiScanner.tab = this.tab;
            this.parent.reCreateListWidget();
            this.parent.getListWidget().resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
