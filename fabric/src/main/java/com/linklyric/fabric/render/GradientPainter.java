package com.linklyric.fabric.render;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.fabric.render.sdf.SdfAtlas;
import com.linklyric.fabric.render.sdf.SdfGlyphProvider;
import com.linklyric.fabric.render.sdf.SdfGlyphWriter;
import com.linklyric.fabric.render.sdf.SdfRenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
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

        // 优先走 SDF：任意缩放都锐利，且顶点色渐变照旧生效。
        SdfAtlas.Slot slot = SdfGlyphProvider.slot(cp);
        if (slot != null) {
            return drawSdf(graphics, slot, cp, x, y, colors, shadow);
        }
        // 退回原版字形：功能降级但不崩。
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

    /** SDF 路径的单色字形。 */
    private static boolean drawSdf(GuiGraphics graphics, SdfAtlas.Slot slot, int cp,
                                   float x, float y, GlyphColors colors, boolean shadow) {
        float[] box = SdfGlyphProvider.box(cp);
        if (box == null) {
            return false;
        }
        Matrix4f pose = graphics.pose().last().pose();
        int light = 0xF000F0;

        // 整块字形就是一个段；锚点取 x，与段坐标同一坐标系。
        GlyphSpan main = new GlyphSpan(x, x + (box[2] - box[0]), colors.left(), colors.right());
        if (shadow) {
            // 阴影层独立一个 RenderType：它的柔化与字重和正文不同，
            // 混在同一批里只能有一份 uniform 生效。
            RenderType shadowType = SdfRenderType.shadow(slot.page());
            GlyphSpan shadowSpan = new GlyphSpan(main.startX(), main.endX(),
                    shadowOf(colors.left()), shadowOf(colors.right()));
            SdfGlyphWriter.writeSpans(graphics.bufferSource().getBuffer(shadowType),
                    pose, slot, box, x + SdfRenderType.shadowOffset(), y,
                    new GlyphSpan[]{shadowSpan}, x, light);
        }
        RenderType bodyType = SdfRenderType.body(slot.page());
        return SdfGlyphWriter.writeSpans(graphics.bufferSource().getBuffer(bodyType),
                pose, slot, box, x, y, new GlyphSpan[]{main}, x, light);
    }

    /**
     * 按段绘制一个字形。
     *
     * <p>与 {@link #draw} 的区别只在顶点写入方式：这里按调用方给出的
     * 段边界切分四边形，段处在前沿函数的折点上，因此每段内
     * 两端颜色插值即精确。
     *
     * <p>阴影同样按段绘制，保持与主色一致的形状——否则阴影只有单色
     * 轮廓，会在染色边界处露出不符合渐变的硬边。
     */
    public static boolean drawSpans(GuiGraphics graphics, Font font, String glyph,
                                     float x, float y, GlyphSpan[] spans,
                                     float originX, boolean shadow) {
        if (glyph.isEmpty() || spans == null || spans.length == 0) {
            return true;
        }
        int cp = glyph.codePointAt(0);

        // 优先走 SDF。段切分逻辑与原版路径完全一致——
        // 区别只在几何来源，颜色仍是逐顶点写入，所以渐变不受影响。
        SdfAtlas.Slot slot = SdfGlyphProvider.slot(cp);
        if (slot != null) {
            return drawSpansSdf(graphics, slot, cp, x, y, spans, originX, shadow);
        }

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

        if (shadow) {
            GlyphSpan[] shadowSpans = new GlyphSpan[spans.length];
            for (int i = 0; i < spans.length; i++) {
                GlyphSpan s = spans[i];
                shadowSpans[i] = new GlyphSpan(s.startX(), s.endX(),
                        shadowOf(s.startColor()), shadowOf(s.endColor()));
            }
            // 阴影几何左移一格，但段坐标的基准不变（originX 仍是主色的行锚点）。
            GradientGlyphWriter.writeSpans(consumer, pose, baked,
                    x + SHADOW_OFFSET, y, shadowSpans, originX, light);
        }
        return GradientGlyphWriter.writeSpans(consumer, pose, baked, x, y, spans, originX, light);
    }

    /** SDF 路径的按段字形。 */
    private static boolean drawSpansSdf(GuiGraphics graphics, SdfAtlas.Slot slot, int cp,
                                        float x, float y, GlyphSpan[] spans,
                                        float originX, boolean shadow) {
        float[] box = SdfGlyphProvider.box(cp);
        if (box == null) {
            return false;
        }
        Matrix4f pose = graphics.pose().last().pose();
        int light = 0xF000F0;

        if (shadow) {
            GlyphSpan[] shadowSpans = new GlyphSpan[spans.length];
            for (int i = 0; i < spans.length; i++) {
                GlyphSpan s = spans[i];
                shadowSpans[i] = new GlyphSpan(s.startX(), s.endX(),
                        shadowOf(s.startColor()), shadowOf(s.endColor()));
            }
            // 阴影几何右移一格，但 originX 仍取主色的行锚点，
            // 否则阴影偏移会被段坐标的平移抵消，画回主色上。
            RenderType shadowType = SdfRenderType.shadow(slot.page());
            SdfGlyphWriter.writeSpans(graphics.bufferSource().getBuffer(shadowType),
                    pose, slot, box, x + SdfRenderType.shadowOffset(), y,
                    shadowSpans, originX, light);
        }
        RenderType bodyType = SdfRenderType.body(slot.page());
        return SdfGlyphWriter.writeSpans(graphics.bufferSource().getBuffer(bodyType),
                pose, slot, box, x, y, spans, originX, light);
    }

    /**
     * 字形步进宽度。
     *
     * <p>减去斜体补偿之外的部分：{@code getAdvance} 返回的正是原版推进量，
     * 直接用即可，不要叠加 boldOffset（那由 Info 内部处理）。
     */
    public static float advance(Font font, String glyph) {
        if (glyph.isEmpty()) {
            return 0f;
        }
        return GlyphLookup.advance(font, glyph.codePointAt(0));
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