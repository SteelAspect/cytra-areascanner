package dev.steelaspect.areascanner.gui;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
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
    }

    /** Row of scan action buttons above the option list. */
    protected void createActionButtons(int x, int y) {
    }

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
