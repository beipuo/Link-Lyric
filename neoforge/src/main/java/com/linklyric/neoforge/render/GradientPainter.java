package com.linklyric.neoforge.render;

import com.linklyric.common.style.GlyphColors;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * 用逐顶点颜色绘制一行中的单个字形，实现字内渐变染色。
 *
 * <h2>与原版的对应关系</h2>
 *
 * <p>原版一段文字走 {@code Font.drawString} → {@code BakedGlyph.render}，
 * 后者把颜色作为标量参数，一个字形整体一色。这里不复用它的 render，
 * 而是按同样的几何自己写四个顶点，让左右两侧可以不同色。
 *
 * <p>阴影同样自己写：原版是先用阴影色画一遍，再用主色在
 * {@code x + shadowOffset} 处画一遍（{@code shadowOffset} 为 -1，
 * 因此阴影落在左上）。这里保持完全一致的顺序与偏移，
 * 否则开关阴影时字会横向跳动。
 *
 * <h2>降级</h2>
 *
 * <p>字段解析失败或取不到字形时，{@link #draw} 返回 false，
 * 调用方退回普通 {@code drawString}。宁可颜色不准，不能让 HUD 消失。
 */
public final class GradientPainter {

    /** 阴影相对主色的水平偏移。取自原版 renderChar 的浮点参数。 */
    private static final float SHADOW_OFFSET = -1.0f;

    /**
     * 阴影色系数。
     *
     * <p>原版用 {@code ARGB.scaleRGB(color, 0.25F)}：把 RGB 各乘 0.25，
     * 保留原 alpha。这里逐顶点上色，必须自己做这一步，
     * 否则阴影会和主色一样亮，看起来像双影。
     */
    private static final float SHADOW_RGB_SCALE = 0.25f;

    private GradientPainter() {
    }

    /**
     * 绘制一个字形。
     *
     * @param graphics 用于取缓冲区与矩阵
     * @param font     客户端字体
     * @param glyph    字符串中的一个字符（可能是代理对的一半，调用方保证完整）
     * @param x        字形左边缘横坐标
     * @param y        基线纵坐标
     * @param colors   左右端点颜色
     * @param shadow   是否绘制阴影
     * @return 是否成功绘制。false 表示需要调用方退回原版路径
     */
    public static boolean draw(GuiGraphics graphics, Font font, String glyph,
                                float x, float y, GlyphColors colors,
                                boolean shadow) {
        if (glyph.isEmpty()) {
            return true;
        }
        int cp = glyph.codePointAt(0);
        BakedGlyph baked = GlyphLookup.glyph(font, cp);
        if (baked == null) {
            return false;
        }
        RenderType type = GradientGlyphWriter.renderType(baked, false);
        if (type == null) {
            return false;
        }
        var buffer = graphics.bufferSource();
        VertexConsumer consumer = buffer.getBuffer(type);
        Matrix4f pose = graphics.pose().last().pose();
        int light = 0xF000F0;

        // 阴影先画，主色后画：原版顺序如此。反过来的话主色会被阴影覆盖。
        if (shadow) {
            GlyphColors shadowColors = new GlyphColors(
                    shadowOf(colors.left()), shadowOf(colors.right()));
            GradientGlyphWriter.write(consumer, pose, baked,
                    x + SHADOW_OFFSET, y, shadowColors, light);
        }
        if (!GradientGlyphWriter.write(consumer, pose, baked, x, y, colors, light)) {
            return false;
        }
        return true;
    }

    /**
     * 字形步进宽度。
     *
     * <p>减去斜体补偿之外的部分：{@code getAdvance} 返回的正是原版推进量，
     * 直接用即可，不要叠加 boldOffset（那由 Info 内部处理）。
     */
    public static float advance(Font font, String glyph, boolean bold) {
        if (glyph.isEmpty()) {
            return 0f;
        }
        return GlyphLookup.advance(font, glyph.codePointAt(0), bold);
    }

    /** 主色转阴影色：RGB 乘 0.25，alpha 保持不变。 */
    private static int shadowOf(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.round(((argb >> 16) & 0xFF) * SHADOW_RGB_SCALE);
        int g = Math.round(((argb >> 8) & 0xFF) * SHADOW_RGB_SCALE);
        int b = Math.round((argb & 0xFF) * SHADOW_RGB_SCALE);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 预热反射通道，避免首帧在渲染路径上做类解析。 */
    public static void warmup() {
        GradientGlyphWriter.available();
        GlyphLookup.available();
    }

    /** 便捷判断：当前环境能否做字内渐变。 */
    public static boolean usable() {
        return GradientGlyphWriter.available() && GlyphLookup.available();
    }

    /** 供平台层取客户端实例的辅助方法，避免各处重复。 */
    public static Minecraft client() {
        return Minecraft.getInstance();
    }
}