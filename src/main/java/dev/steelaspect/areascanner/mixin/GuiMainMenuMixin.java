package dev.steelaspect.areascanner.mixin;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.gui.GuiScanner;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an "Area Scanner" button to Litematica's main menu, in the free slot under "Configuration menu". */
@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class GuiMainMenuMixin {
    @Shadow
    private int getButtonWidth() {
        throw new AssertionError();
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void cytraAreaScanner$addButton(CallbackInfo ci) {
        GuiBase self = (GuiBase) (Object) this;
        int width = this.getButtonWidth();
        // Same column as Litematica's Configuration button (x = 12 + width + 20, y = 30), one row below it.
        int x = 12 + width + 20;
        int y = 52;
        String label = StringUtils.translate(Reference.MOD_ID + ".gui.button.area_scanner");
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        button.setHoverStrings(Reference.MOD_ID + ".gui.button.hover.area_scanner");
        self.addButton(button, (b, mouseButton) -> {
            GuiScanner gui = new GuiScanner();
            gui.setParent(self);
            GuiBase.openGui(gui);
        });
    }
}
