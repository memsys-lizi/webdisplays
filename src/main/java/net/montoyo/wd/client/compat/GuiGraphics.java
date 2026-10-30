package net.montoyo.wd.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Minimal stand-in for the 1.20 {@code net.minecraft.client.gui.GuiGraphics}, implemented on top of the
 * 1.19.2 {@link PoseStack} + {@link GuiComponent} APIs so the GUI code can stay close to upstream.
 */
public class GuiGraphics {
    private final Minecraft minecraft;
    private final PoseStack pose;
    private final MultiBufferSource.BufferSource bufferSource;

    public GuiGraphics(Minecraft minecraft, MultiBufferSource.BufferSource bufferSource) {
        this(minecraft, new PoseStack(), bufferSource);
    }

    public GuiGraphics(Minecraft minecraft, PoseStack pose, MultiBufferSource.BufferSource bufferSource) {
        this.minecraft = minecraft;
        this.pose = pose;
        this.bufferSource = bufferSource;
    }

    public static GuiGraphics of(PoseStack pose) {
        Minecraft mc = Minecraft.getInstance();
        return new GuiGraphics(mc, pose, mc.renderBuffers().bufferSource());
    }

    public PoseStack pose() {
        return pose;
    }

    public MultiBufferSource.BufferSource bufferSource() {
        return bufferSource;
    }

    public void flush() {
        RenderSystem.disableDepthTest();
        bufferSource.endBatch();
        RenderSystem.enableDepthTest();
    }

    /* ---------------- fills ---------------- */

    public void fill(int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(pose, x1, y1, x2, y2, color);
    }

    public void renderOutline(int x, int y, int w, int h, int color) {
        fill(x, y, x + w, y + 1, color);
        fill(x, y + h - 1, x + w, y + h, color);
        fill(x, y + 1, x + 1, y + h - 1, color);
        fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /* ---------------- textures ---------------- */

    public void blit(ResourceLocation tex, int x, int y, int u, int v, int w, int h) {
        blit(tex, x, y, 0, (float) u, (float) v, w, h, 256, 256);
    }

    public void blit(ResourceLocation tex, int x, int y, float u, float v, int w, int h, int texW, int texH) {
        blit(tex, x, y, 0, u, v, w, h, texW, texH);
    }

    public void blit(ResourceLocation tex, int x, int y, int blitOffset, float u, float v, int w, int h, int texW, int texH) {
        RenderSystem.setShaderTexture(0, tex);
        // note: the 1.19.2 overload takes texture height before texture width
        GuiComponent.blit(pose, x, y, blitOffset, u, v, w, h, texH, texW);
    }

    /* ---------------- text ---------------- */

    public int drawString(Font font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) return 0;
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public int drawString(Font font, Component text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    /* ---------------- items ---------------- */

    // In 1.19.2 the item renderer draws with the global model-view stack, so apply our pose to it.
    private void withPose(Runnable r) {
        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.mulPoseMatrix(pose.last().pose());
        RenderSystem.applyModelViewMatrix();
        try {
            r.run();
        } finally {
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    public void renderItem(ItemStack stack, int x, int y) {
        ItemRenderer ir = minecraft.getItemRenderer();
        withPose(() -> ir.renderAndDecorateItem(stack, x, y));
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        ItemRenderer ir = minecraft.getItemRenderer();
        withPose(() -> ir.renderGuiItemDecorations(font, stack, x, y));
    }

    /* ---------------- tooltips ---------------- */

    public void renderTooltip(Font font, ItemStack stack, int x, int y) {
        Screen screen = minecraft.screen;
        if (screen == null) return;
        screen.renderComponentTooltip(pose, screen.getTooltipFromItem(stack), x, y, font, stack);
    }

    public void renderTooltip(Font font, List<? extends FormattedCharSequence> lines, int x, int y) {
        Screen screen = minecraft.screen;
        if (screen == null) return;
        screen.renderTooltip(pose, lines, x, y, font);
    }
}
