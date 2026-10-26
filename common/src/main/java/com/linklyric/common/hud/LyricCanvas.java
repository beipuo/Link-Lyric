package com.linklyric.common.hud;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;

/**
 * 平台绘制适配层。
 *
 * <p>只暴露渲染真正需要的原语，使排版与染色逻辑可脱离游戏运行时测试。
 * 默认实现保证只关心部分能力的平台与测试替身仍可工作。
 */
public interface LyricCanvas {

    int screenWidth();

    int screenHeight();

    int textWidth(String text);

    /**
     * 单个码点的步进宽度。
     *
     * <p>逐字排版需要「每个字的左边界」，此前实现是
     * {@code textWidth(text.substring(0, i))} 循环一遍：既是 O(n²)
     * 的度量（每个前缀都要从头算），又每帧给每个字分配一个临时字符串。
     * 一句 30 字就是 465 次字形查找加 30 个短命字符串，全部产生在渲染热路径上。
     *
     * <p>有了这个原语，逐字左边界可以用一次增量累加求出：O(n) 度量、零分配。
     *
     * <p>默认实现仍退回前缀度量，保证第三方与测试替身不必立刻跟进；
     * 平台层覆盖后立即获得收益。两端入口均已覆盖。
     */
    default int charWidth(char c) {
        return textWidth(String.valueOf(c));
    }

    /** 绘制文本，颜色含 alpha 通道。 */
    void drawText(String text, int x, int y, int color);

    /**
     * 带阴影开关的绘制。
     *
     * @param shadow 是否画阴影；中文字形更高，阴影更容易落出裁剪区
     */
    default void drawText(String text, int x, int y, int color, boolean shadow) {
        drawText(text, x, y, color);
    }

    /**
     * 带浮点抬升的绘制。
     *
     * <p>抬升量只有约 1px，若先取整再绘制会在 0 与 1 之间跳变。
     * 默认实现取整，平台层可覆盖以支持亚像素位移。
     */
    default void drawTextLifted(String text, int x, int y, float lift,
                                int color, boolean shadow) {
        drawText(text, x, y - Math.round(lift), color, shadow);
    }

    default void drawTextLifted(String text, int x, int y, float lift, int color) {
        drawText(text, x, y - Math.round(lift), color, true);
    }

    /**
     * 绘制单个字形，左右两端可不同色。
     *
     * <p>这是字内渐变的唯一入口：GPU 在顶点之间线性插值，
     * 给左右顶点不同颜色即可让一个字内部出现连续的染色前沿。
     * 默认实现退回单色，取左端色。
     */
    default void drawGlyphGradient(String glyph, float x, int y, float lift,
                                   GlyphColors colors, boolean shadow) {
        drawTextLifted(glyph, Math.round(x), y, lift, colors.left(), shadow);
    }

    /**
     * 带外发光与描边的单字形绘制。
     *
     * <p>光晕与描边都由 SDF 的距离场在着色器里算出，不需要额外的绘制批次。
     * 两者都作用在字形轮廓上，与颜色分段无关。
     *
     * <p>默认实现忽略特效、退回普通绘制，保证不支持 SDF 的平台仍可工作。
     *
     * @param glowRadius  光晕半径，屏幕像素
     * @param glowAlpha   光晕强度，0..1
     * @param strokeWidth 描边宽度，屏幕像素；0 表示不描边
     * @param strokeArgb  描边颜色，含 alpha
     */
    default void drawGlyphGradient(String glyph, float x, int y, float lift,
                                   GlyphColors colors, boolean shadow,
                                   float glowRadius, float glowAlpha,
                                   float strokeWidth, int strokeArgb) {
        drawGlyphGradient(glyph, x, y, lift, colors, shadow, glowRadius, glowAlpha);
    }

    /** 不描边的版本，保留给既有调用点。 */
    default void drawGlyphGradient(String glyph, float x, int y, float lift,
                                   GlyphColors colors, boolean shadow,
                                   float glowRadius, float glowAlpha) {
        drawGlyphGradient(glyph, x, y, lift, colors, shadow);
    }

    /**
     * 带外发光的按段绘制。
     *
     * <p>光晕作用在字形轮廓上，与颜色分段无关，因此只接受一份半径与强度。
     */
    default void drawGlyphSpans(String glyph, float x, int y, float lift,
                                GlyphSpan[] spans, int originX, boolean shadow,
                                float glowRadius, float glowAlpha,
                                float strokeWidth, int strokeArgb) {
        drawGlyphSpans(glyph, x, y, lift, spans, originX, shadow, glowRadius, glowAlpha);
    }

    default void drawGlyphSpans(String glyph, float x, int y, float lift,
                                GlyphSpan[] spans, int originX, boolean shadow,
                                float glowRadius, float glowAlpha) {
        drawGlyphSpans(glyph, x, y, lift, spans, originX, shadow);
    }

    /**
     * 按段绘制单个字形。
     *
     * <p>前沿函数是分段线性的，段边界落在它的折点上，
     * 因此每段内两端颜色插值即精确，不需要更多顶点。
     * 默认实现退化为「取首尾颜色画一次」，仅保证不崩。
     *
     * @param spans   段列表，{@code startX}/{@code endX} 是相对行首的绝对坐标
     * @param originX 行首在屏幕上的横坐标，用于把段坐标换算成字形局部坐标
     */
    default void drawGlyphSpans(String glyph, float x, int y, float lift,
                                GlyphSpan[] spans, int originX, boolean shadow) {
        if (spans == null || spans.length == 0) {
            return;
        }
        drawGlyphGradient(glyph, x, y, lift,
                new GlyphColors(spans[0].startColor(),
                        spans[spans.length - 1].endColor()), shadow);
    }

    /** 带阴影开关的缩放绘制。 */
    default void drawTextScaled(String text, int x, int y, float scale,
                                int color, boolean shadow) {
        drawTextScaled(text, x, y, scale, color);
    }

    /**
     * 按缩放比例绘制文本。
     *
     * <p>默认实现走姿态栈，以 {@code (x, y)} 为原点缩放后绘制：
     * 这是**唯一**正确的退化方式。副行的纵向位置已由 {@code LineLayout}
     * 算好，把 scale 折算成位移会把这层布局再叠加一次，
     * 结果随 scale 漂移——那不是缩放，是错位。
     *
     * <p>平台层可覆写以获得更精确的缩放（例如直接改字号渲染），
     * 但即使不覆写，这里也不会静默丢掉 {@code scale}：
     * 此前默认实现直接 {@code drawText(text, x, y, color)}，
     * 副行缩到 0.8 倍全靠两端各自覆写，一旦漏覆写就会静默变成主行字号。
     */
    default void drawTextScaled(String text, int x, int y, float scale, int color) {
        if (Math.abs(scale - 1f) < 0.001f) {
            drawText(text, x, y, color);
            return;
        }
        Pose2D pose = pose();
        pose.pushPose();
        pose.translate(x, y);
        pose.scale(scale, scale);
        pose.translate(-x, -y);
        try {
            drawText(text, x, y, color);
        } finally {
            pose.popPose();
        }
    }

    /** 按缩放比例测量文本宽度。 */
    default int textWidthScaled(String text, float scale) {
        return Math.round(textWidth(text) * scale);
    }

    /** 绘制矩形填充，用于底板。 */
    void fillRect(int x, int y, int width, int height, int color);

    /** 坐标变换栈；不支持缩放的平台返回恒等实现。 */
    default Pose2D pose() {
        return Pose2D.IDENTITY;
    }

    /** 当前字体的行高，用于给裁剪区留足空间。 */
    default int lineHeight() {
        return LyricGeometry.LINE_HEIGHT;
    }

    /** 是否支持按像素裁剪。 */
    default boolean supportsScissor() {
        return false;
    }

    default void enableScissor(int x, int y, int width, int height) {
    }

    default void disableScissor() {
    }
}