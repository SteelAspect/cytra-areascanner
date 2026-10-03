package dev.steelaspect.areascanner.gui;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.CustomEntry;
import dev.steelaspect.areascanner.config.ScanLists;
import dev.steelaspect.areascanner.scan.ScanActions;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetColorIndicator;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Edits the custom scan list: add blocks by id (with registry autocomplete), colour, toggle, remove. */
public class GuiCustomList extends GuiBase {
    private static final int LIST_TOP = 92;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_SUGGESTIONS = 8;
    private static final int SUGGESTION_HEIGHT = 11;

    private static String inputText = "";
    private static int scroll;

    private GuiTextFieldGeneric textField;
    private final List<String> suggestions = new ArrayList<>();
    private int fieldX;
    private int fieldY;
    private int fieldWidth;

    public GuiCustomList(@Nullable Screen parent) {
        this.title = StringUtils.translate(Reference.MOD_ID + ".gui.title.custom_list");
        this.setParent(parent);
    }

    @Override
    public void initGui() {
        super.initGui();
        int x = 12;
        int y = 26;

        ButtonGeneric back = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.back"));
        this.addButton(back, (b, m) -> this.closeGui(true));
        x += back.getWidth() + 4;

        String state = StringUtils.translate(Reference.MOD_ID + ".gui.button." + (Configs.CUSTOM_ENABLED.getBooleanValue() ? "on" : "off"));
        ButtonGeneric master = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.custom_master", state));
        master.setHoverStrings(Reference.MOD_ID + ".config.comment.customEnabled");
        this.addButton(master, (b, m) -> {
            Configs.CUSTOM_ENABLED.toggleBooleanValue();
            ScanActions.customListChanged();
            this.initGui();
        });
        x += master.getWidth() + 4;

        ButtonGeneric lookedAt = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(Reference.MOD_ID + ".gui.button.add_looked_at"));
        lookedAt.setHoverStrings(Reference.MOD_ID + ".config.comment.addLookedAt");
        this.addButton(lookedAt, (b, m) -> {
            ScanActions.addLookedAtBlock();
            this.initGui();
        });

        this.addLabel(12, 52, 300, 10, 0xFFC0C0C0, StringUtils.translate(Reference.MOD_ID + ".gui.label.block_id"));
        this.fieldX = 12;
        this.fieldY = 64;
        this.fieldWidth = Math.min(260, this.width - 100);
        this.textField = new GuiTextFieldGeneric(this.fieldX, this.fieldY, this.fieldWidth, 18, this.font);
        this.textField.setMaxLengthWrapper(256);
        this.textField.setValueWrapper(inputText);
        this.addTextField(this.textField, field -> {
            inputText = field.getValueWrapper();
            this.updateSuggestions();
            return true;
        });
        this.textField.setFocusedWrapper(true);
        ButtonGeneric add = new ButtonGeneric(this.fieldX + this.fieldWidth + 4, this.fieldY - 1, -1, 20,
                StringUtils.translate(Reference.MOD_ID + ".gui.button.add"));
        this.addButton(add, (b, m) -> this.addFromField());

        this.createRows();
        this.updateSuggestions();
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_TOP - 8) / ROW_HEIGHT);
    }

    private void createRows() {
        List<CustomEntry> list = ScanLists.custom();
        if (list.isEmpty()) {
            this.addLabel(12, LIST_TOP + 4, this.width - 24, 10, 0xFFA0A0A0, StringUtils.translate(Reference.MOD_ID + ".gui.label.empty_custom"));
            return;
        }
        int rows = this.visibleRows();
        scroll = Math.max(0, Math.min(scroll, list.size() - rows));
        int y = LIST_TOP;
        int removeWidth = this.getStringWidth(StringUtils.translate(Reference.MOD_ID + ".gui.button.remove")) + 10;
        int toggleWidth = 40;
        int rightX = this.width - 12;
        for (int i = scroll; i < list.size() && i < scroll + rows; i++) {
            CustomEntry entry = list.get(i);
            this.addWidget(new WidgetColorIndicator(12, y + 1, 18, 18, Color4f.fromColor(entry.color, 1.0f), color -> {
                entry.color = color & 0xFFFFFF;
                ScanActions.customColorChanged();
            }));
            boolean known = entry.block() != null;
            String label = (entry.enabled ? GuiBase.TXT_WHITE : GuiBase.TXT_GRAY) + entry.blockId
                    + (known ? "" : " " + GuiBase.TXT_RED + StringUtils.translate(Reference.MOD_ID + ".gui.label.unknown_block"));
            this.addLabel(36, y + 5, rightX - removeWidth - toggleWidth - 48, 10, 0xFFFFFFFF, label);

            ButtonGeneric toggle = new ButtonGeneric(rightX - removeWidth - toggleWidth - 4, y, toggleWidth, 20,
                    StringUtils.translate(Reference.MOD_ID + ".gui.button." + (entry.enabled ? "on" : "off")));
            this.addButton(toggle, (b, m) -> {
                entry.enabled = !entry.enabled;
                ScanActions.customListChanged();
                this.initGui();
            });
            ButtonGeneric remove = new ButtonGeneric(rightX - removeWidth, y, removeWidth, 20,
                    StringUtils.translate(Reference.MOD_ID + ".gui.button.remove"));
            this.addButton(remove, (b, m) -> {
                ScanLists.custom().remove(entry);
                ScanActions.customListChanged();
                this.initGui();
            });
            y += ROW_HEIGHT;
        }
    }

    // ---------------------------------------------------------------- adding / autocomplete

    private void addFromField() {
        String id = normalize(inputText);
        if (id.isEmpty()) return;
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !BuiltInRegistries.BLOCK.containsKey(parsed)) {
            this.addMessage(Message.MessageType.ERROR, Reference.MOD_ID + ".message.unknown_block", id);
            return;
        }
        ScanActions.addCustomBlock(parsed.toString());
        inputText = "";
        int rows = this.visibleRows();
        scroll = Math.max(0, ScanLists.custom().size() - rows); // show the new entry
        this.initGui();
    }

    private static String normalize(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (!s.isEmpty() && !s.contains(":")) s = "minecraft:" + s;
        return s;
    }

    private void updateSuggestions() {
        this.suggestions.clear();
        String query = inputText.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return;
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            String full = id.toString();
            if (full.equals(normalize(query))) continue;
            if (id.getPath().startsWith(query) || full.startsWith(query)) prefix.add(full);
            else if (full.contains(query)) contains.add(full);
        }
        prefix.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        contains.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        for (String s : prefix) {
            if (this.suggestions.size() >= MAX_SUGGESTIONS) return;
            this.suggestions.add(s);
        }
        for (String s : contains) {
            if (this.suggestions.size() >= MAX_SUGGESTIONS) return;
            this.suggestions.add(s);
        }
    }

    private void acceptSuggestion(String suggestion) {
        inputText = suggestion;
        this.textField.setValueWrapper(suggestion);
        this.textField.setFocusedWrapper(true);
        this.updateSuggestions();
    }

    private int suggestionTop() {
        return this.fieldY + 19;
    }

    @Override
    public boolean onKeyTyped(KeyEvent input) {
        if (this.textField != null && this.textField.isFocused()) {
            if (input.key() == GLFW.GLFW_KEY_TAB) {
                if (!this.suggestions.isEmpty()) this.acceptSuggestion(this.suggestions.get(0));
                return true;
            }
            if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
                this.addFromField();
                return true;
            }
        }
        return super.onKeyTyped(input);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (!this.suggestions.isEmpty()) {
            int top = this.suggestionTop();
            int x = (int) click.x();
            int y = (int) click.y();
            if (x >= this.fieldX && x < this.fieldX + this.fieldWidth && y >= top && y < top + this.suggestions.size() * SUGGESTION_HEIGHT) {
                this.acceptSuggestion(this.suggestions.get((y - top) / SUGGESTION_HEIGHT));
                return true;
            }
        }
        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY >= LIST_TOP && ScanLists.custom().size() > this.visibleRows()) {
            scroll += verticalAmount > 0 ? -1 : 1;
            this.initGui();
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        if (this.suggestions.isEmpty() || this.textField == null || !this.textField.isFocused()) return;
        // Autocomplete dropdown, on its own layer above the list rows.
        graphics.nextStratum();
        int top = this.suggestionTop();
        int bottom = top + this.suggestions.size() * SUGGESTION_HEIGHT;
        graphics.fill(this.fieldX, top, this.fieldX + this.fieldWidth, bottom + 1, 0xF0101010);
        graphics.fill(this.fieldX, bottom, this.fieldX + this.fieldWidth, bottom + 1, 0xFF808080);
        for (int i = 0; i < this.suggestions.size(); i++) {
            int y = top + i * SUGGESTION_HEIGHT;
            boolean hovered = mouseX >= this.fieldX && mouseX < this.fieldX + this.fieldWidth && mouseY >= y && mouseY < y + SUGGESTION_HEIGHT;
            if (hovered) graphics.fill(this.fieldX, y, this.fieldX + this.fieldWidth, y + SUGGESTION_HEIGHT, 0xFF404040);
            int color = i == 0 ? 0xFFFFFF55 : 0xFFE0E0E0;
            graphics.drawString(this.font, this.suggestions.get(i), this.fieldX + 3, y + 2, color);
        }
    }
}
