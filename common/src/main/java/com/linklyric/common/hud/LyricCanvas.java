package com.linklyric.common.hud;

import com.linklyric.common.style.GlyphColors;

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

    /** 按缩放比例绘制文本。默认实现忽略缩放。 */
    default void drawTextScaled(String text, int x, int y, float scale,
                                int color, boolean shadow) {
        drawTextScaled(text, x, y, scale, color);
    }

    default void drawTextScaled(String text, int x, int y, float scale, int color) {
        drawText(text, x, y, color);
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
