package dev.steelaspect.areascanner.render;

import dev.steelaspect.areascanner.Reference;
import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.scan.Category;
import dev.steelaspect.areascanner.scan.ScanManager;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;

/** "Scanner: X unmovable | Y liquids | Z custom", plus a progress bar while scanning. */
public final class HudRenderer implements IRenderer {
    public static final HudRenderer INSTANCE = new HudRenderer();
    private static final int BAR_WIDTH = 120;

    private HudRenderer() {
    }

    @Override
    public void onRenderGameOverlayPost(GuiContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (!Configs.ENABLED.getBooleanValue() || !Configs.SHOW_HUD.getBooleanValue() || !ScanManager.isActive() || mc.options.hideGui || mc.player == null) return;
        int x = Configs.HUD_X.getIntegerValue();
        int y = Configs.HUD_Y.getIntegerValue();
        String line = StringUtils.translate(Reference.MOD_ID + ".hud.line",
                ScanManager.count(Category.UNMOVABLE), ScanManager.count(Category.LIQUID), ScanManager.count(Category.CUSTOM));
        int width = mc.font.width(line);
        int lines = 1;
        String extra = null;
        if (ScanManager.pendingChunks() > 0) {
            extra = StringUtils.translate(Reference.MOD_ID + ".hud.pending", ScanManager.pendingChunks());
            width = Math.max(width, mc.font.width(extra));
            lines++;
        }
        boolean scanning = ScanManager.isScanning();
        String progressText = null;
        if (scanning) {
            progressText = StringUtils.translate(Reference.MOD_ID + ".hud.scanning", (int) (ScanManager.progress() * 100));
            width = Math.max(width, BAR_WIDTH + 4 + mc.font.width(progressText));
        }
        int height = lines * 10 + (scanning ? 8 : 0);
        ctx.fill(x - 2, y - 2, x + width + 2, y + height, 0x90000000);
        ctx.drawString(mc.font, line, x, y, 0xFFFFFFFF);
        int ty = y + 10;
        if (extra != null) {
            ctx.drawString(mc.font, extra, x, ty, 0xFFFFC040);
            ty += 10;
        }
        if (scanning) {
            int filled = (int) (BAR_WIDTH * ScanManager.progress());
            ctx.fill(x, ty + 1, x + BAR_WIDTH, ty + 6, 0xFF404040);
            ctx.fill(x, ty + 1, x + filled, ty + 6, 0xFF40C040);
            ctx.drawString(mc.font, progressText, x + BAR_WIDTH + 4, ty - 1, 0xFFC0C0C0);
        }
    }
}
