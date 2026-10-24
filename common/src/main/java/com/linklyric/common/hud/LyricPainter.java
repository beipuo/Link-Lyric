package com.linklyric.common.hud;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GradientColorizer;
import com.linklyric.common.style.LyricStyleConfig.Weight;
import com.linklyric.core.lyric.LyricLine;

/**
 * 歌词文本的绘制。
 *
 * <p>两层真实颜色：整行先以未唱色铺满，再把已唱段用已唱色重画。
 * 两层共用同一份坐标与抬升表，只有裁剪区不同，因此像素严格重合，
 * 得到的是真正的变色而非「蒙一层灰」。
 *
 * <p>逐字绘制时前沿交给顶点插值：一个字画一次，左右两端给不同颜色，
 * GPU 逐像素插值，因此前沿可以落在任意亚像素位置，
 * 不受裁剪只能整像素量化的限制。
 */
final class LyricPainter {

    /** 抬升与阴影所需的裁剪余量。 */
    private static final int LIFT_HEADROOM = 2;
    private static final int SHADOW_HEADROOM = 4;

    /** 未唱文字的透明度。 */
    private static final float UNSUNG_ALPHA = 0.38f;

    private final FloatingLyricRenderer.Painter painter;
    private final Weight weight;
    private final boolean shadow;

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow) {
        this.painter = painter;
        this.weight = weight;
        this.shadow = shadow;
    }

    /**
     * 绘制一行卡拉 OK 歌词。
     *
     * @param lifts 逐字抬升量；null 表示退回整段绘制
     */
    void karaoke(String visible, int textX, int textY, float sungWidth,
                 int viewportLeft, int viewRight, float[] lifts,
                 float opacity, int sungColor, int unsungColor,
                 LyricLine line, long positionMs) {
        int alpha = Math.round(255f * opacity);
        int sung = withAlpha(sungColor, alpha);
        int unsung = withAlpha(unsungColor, Math.round(alpha * UNSUNG_ALPHA));

        float edge = textX + sungWidth;

        if (lifts == null) {
            wholeLine(visible, textX, textY, sungWidth,
                    viewportLeft, viewRight, sung, unsung);
            return;
        }

        int cursor = 0;
        for (int i = 0; i < visible.length(); i++) {
            String ch = visible.substring(i, i + 1);
            int charX = textX + cursor;
            int charWidth = painter.textWidth(ch);
            cursor += charWidth;
            int charRight = charX + charWidth;

            if (charRight <= viewportLeft || charX >= viewRight) {
                continue;
            }

            GlyphColors colors = GradientColorizer.forChar(
                    charX, charRight, edge, sung, unsung);
            glyph(ch, charX, textY, lifts[i], viewportLeft, viewRight, colors);
        }
    }

    /** 整行绘制并按已唱宽度分两段上色，用于没有逐字抬升的情况。 */
    private void wholeLine(String visible, int textX, int textY, float sungWidth,
                           int viewportLeft, int viewRight,
                           int sungColor, int unsungColor) {
        int lineWidth = painter.textWidth(visible);
        int solidWidth = Math.max(0,
                Math.min((int) Math.floor(sungWidth), lineWidth));
        colorLayer(visible, textX, textY,
                viewportLeft, viewRight, textX, lineWidth, unsungColor);
        if (solidWidth > 0) {
            colorLayer(visible, textX, textY,
                    viewportLeft, viewRight, textX, solidWidth, sungColor);
        }
    }

    /** 绘制单个字，左右两端可不同色。 */
    private void glyph(String ch, int charX, int textY, float lift,
                       int viewportLeft, int viewRight, GlyphColors colors) {
        int charWidth = painter.textWidth(ch);
        int left = Math.max(charX, viewportLeft);
        int right = Math.min(charX + charWidth, viewRight);
        if (right - left <= 0) {
            return;
        }

        boolean full = left <= charX && right >= charX + charWidth;
        if (!full) {
            enableScissor(left, textY, right - left);
        }

        if (colors.isGradient()) {
            painter.drawGlyphGradient(styled(ch), charX, textY, lift, colors, shadow);
        } else {
            painter.drawTextLifted(styled(ch), charX, textY, lift,
                    colors.left(), shadow);
        }

        if (!full) {
            painter.disableScissor();
        }
    }

    /** 用指定颜色画一层文字，只显示 {@code [clipLeft, clipLeft + clipWidth)} 这一段。 */
    private void colorLayer(String visible, int textX, int textY,
                            int viewportLeft, int viewRight,
                            int clipLeft, int clipWidth, int color) {
        int left = Math.max(clipLeft, viewportLeft);
        int right = Math.min(clipLeft + clipWidth, viewRight);
        if (right - left <= 0) {
            return;
        }
        boolean needScissor = left > textX || right < textX + painter.textWidth(visible);
        if (needScissor) {
            enableScissor(left, textY, right - left);
        }
        painter.drawText(styled(visible), textX, textY, color, shadow);
        if (needScissor) {
            painter.disableScissor();
        }
    }

    /** 绘制一段文本，使用当前字重与阴影设置。 */
    void text(String text, int x, int y, int color) {
        painter.drawText(styled(text), x, y, color, shadow);
    }

    /** 绘制抬升后的文本；单字不加样式包裹，避免文本序列碎片化。 */
    void textLifted(String text, int x, int y, float lift, int color) {
        painter.drawTextLifted(styled(text), x, y, lift, color, shadow);
    }

    /** 按缩放比例绘制文本，用于副行。 */
    void textScaled(String text, int x, int y, float scale, int color) {
        painter.drawTextScaled(styled(text), x, y, scale, color, shadow);
    }

    private void enableScissor(int left, int textY, int width) {
        painter.enableScissor(left, textY - LIFT_HEADROOM, width,
                painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
    }

    private String styled(String text) {
        return weight == Weight.REGULAR ? text : "\u00a7l" + text + "\u00a7r";
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
