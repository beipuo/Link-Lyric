package com.linklyric.common.hud;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.common.style.GradientColorizer;
import com.linklyric.common.style.LyricStyleConfig.Weight;
import com.linklyric.common.hud.effect.EmphasisGroup;
import com.linklyric.common.hud.effect.EmphasisLayout;
import com.linklyric.common.hud.effect.LyricEffectEngine;
import com.linklyric.common.hud.effect.UnitTransform;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;

import java.util.List;

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

    /**
     * 强调上浮幅度，相对字号。
     *
     * <p>用于估算动画需要的垂直空间；实际幅度由特效按组参数缩放。
     */
    private static final float EMPHASIS_LIFT_EM = 0.03f;

    /** 动画强度下限：空间再紧也保留一点动感。 */
    private static final float MIN_STRENGTH = 0.2f;

    private final FloatingLyricRenderer.Painter painter;
    private final Weight weight;
    private final boolean shadow;
    private final LyricEffectEngine effects;

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow) {
        this(painter, weight, shadow, null);
    }

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow,
                 LyricEffectEngine effects) {
        this.painter = painter;
        this.weight = weight;
        this.shadow = shadow;
        this.effects = effects;
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

        List<LyricWord> words = line.words();
        EmphasisGroup[] groups = emphasisGroups(words);
        int lineWidth = painter.textWidth(visible);

        // 每帧只算一次全局参数；强度按可用行高收缩，避免抬升把字挤出裁剪区。
        if (effects != null) {
            effects.beginFrame(lineWidth, sungWidth, textSizePx(), positionMs,
                    motionStrength(textY));
        }

        int[] charWord = mapCharsToWords(words, visible.length());
        int cursor = 0;
        int wordCursor = 0;
        int currentWord = -1;
        for (int i = 0; i < visible.length(); i++) {
            String ch = visible.substring(i, i + 1);
            int charX = textX + cursor;
            int charWidth = painter.textWidth(ch);
            cursor += charWidth;
            int charRight = charX + charWidth;

            int word = charWord[i];
            if (word != currentWord) {
                currentWord = word;
                wordCursor = 0;
            }

            UnitTransform transform = UnitTransform.IDENTITY;
            if (effects != null && word >= 0) {
                LyricWord source = words.get(word);
                EmphasisGroup group = groups[word];
                transform = effects.prepare(
                        i, 1, charX - textX, charRight - textX,
                        EmphasisLayout.isCjk(ch), source.startMs(), source.endMs(),
                        group, wordCursor);
            }
            wordCursor++;

            if (charRight <= viewportLeft || charX >= viewRight) {
                continue;
            }

            // 传入整行宽度：过渡宽度按行宽比例推算，
            // 使长句与短句的羽化观感一致。
            // 段坐标保持**绝对**（相对行首）：染色计算必须用统一坐标系，
            // 否则每个字各自归零会让前沿函数在每个字边界处跳变。
            // 转成字形局部坐标由绘制层负责，这里额外给出行锚点。
            GlyphSpan[] spans = GradientColorizer.spansForChar(
                    charX, charRight, edge, sung, unsung, lineWidth);
            glyph(ch, charX, textY, lifts[i] + transform.dy, viewportLeft, viewRight,
                    spans, transform.dx, transform.bloom);
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

    /**
     * 绘制单个字，左右两端可不同色，可带位移与辉光。
     *
     * <p>位移由特效给出，渐变由染色给出：前者动字形，后者动颜色，两者互不干扰。
     */
    private void glyph(String ch, int charX, int textY, float lift,
                       int viewportLeft, int viewRight, GlyphSpan[] spans,
                       float dx, float bloomLevel) {
        int charWidth = painter.textWidth(ch);
        // 保留浮点坐标，不取整。
        //
        // 9px 位图字形在非整数位置会糊，所以过去这里必须 Math.round；
        // 代价是 1px 以内的位移会被整成 0 或 1，动画看起来一跳一跳。
        // 改用 SDF 后字形在任意小数位置都锐利，取整只带来跳变，因此去掉。
        // 整数平移仍由下面的 scissor 判断保证不越界。
        float drawX = charX + dx;
        int left = Math.max((int) Math.floor(drawX), viewportLeft);
        int right = Math.min((int) Math.ceil(drawX + charWidth), viewRight);
        if (right - left <= 0) {
            return;
        }

        boolean full = drawX >= viewportLeft && drawX + charWidth <= viewRight;
        if (!full) {
            enableScissor(left, textY, right - left);
        }

        // 一律按常规字重绘制。
        //
        // 加粗已移除：MC 的粗体是同一字形右移一个纹素再画一遍，
        // 在 9px 位图字形上两份轮廓只错开 1px，叠出 8px，
        // 看起来就是同一个字重了一次。逐字绘制路径没有原版
        // drawString 那样能让两份合成一个观感的余地。
        GlyphSpan[] shown = bloom(spans, bloomLevel);
        if (shown.length == 1 && shown[0].isSolid()) {
            // 单色：走平台原有的整字绘制，省掉逐顶点写入。
            // 传浮点横坐标，与渐变路径保持同样的亚像素精度。
            painter.drawGlyphGradient(ch, drawX, textY, lift,
                    new GlyphColors(shown[0].startColor(), shown[0].endColor()), shadow);
        } else {
            // 段边界落在前沿函数的折点上，段内插值即精确。
            //
            // originX 必须传 charX：spans 是以 charX 为原点生成的
            // （spansForChar(charX, charRight, ...)），减去它正好得到
            // 字形局部坐标。若改传 textX（行首），每个字会多偏移
            // 自身之前的累计宽度，被推出裁剪区而看起来完全透明。
            painter.drawGlyphSpans(ch, drawX, textY, lift, shown, Math.round(charX), shadow);
        }

        if (!full) {
            painter.disableScissor();
        }
    }

    /**
     * 把颜色朝白色提亮。
     *
     * <p>强调动画的「发光」用提亮而不是光晕：位图字体不能被位移或缩放，
     * 但颜色可以自由改。提亮沿 RGB 三通道向 255 插值、保留原 alpha，
     * 字形边缘仍取自图集，所以字始终清晰。
     */
    private static GlyphSpan[] bloom(GlyphSpan[] spans, float level) {
        if (level <= 0f || spans.length == 0) {
            return spans;
        }
        float t = Math.min(1f, level);
        GlyphSpan[] out = new GlyphSpan[spans.length];
        for (int i = 0; i < spans.length; i++) {
            GlyphSpan s = spans[i];
            out[i] = new GlyphSpan(s.startX(), s.endX(),
                    liftTowardWhite(s.startColor(), t),
                    liftTowardWhite(s.endColor(), t));
        }
        return out;
    }

    /** 保留旧的整字提亮入口，供整行绘制路径使用。 */
    private static GlyphColors bloom(GlyphColors colors, float level) {
        if (level <= 0f) {
            return colors;
        }
        float t = Math.min(1f, level);
        return new GlyphColors(liftTowardWhite(colors.left(), t),
                liftTowardWhite(colors.right(), t));
    }

    private static int liftTowardWhite(int argb, float t) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        r = Math.round(r + (255 - r) * t);
        g = Math.round(g + (255 - g) * t);
        b = Math.round(b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 为整行算出强调分组；词序列为空时返回全 null。 */
    private static EmphasisGroup[] emphasisGroups(List<LyricWord> words) {
        if (words == null || words.isEmpty()) {
            return new EmphasisGroup[0];
        }
        return EmphasisLayout.groups(words);
    }

    /**
     * 把行的每个字符映射到它所属的词序号。
     *
     * <p>词按出现顺序铺在行文本上，与渲染时逐字遍历的顺序一致。
     * 对不上的位置填 -1，渲染端据此跳过特效。
     */
    private static int[] mapCharsToWords(List<LyricWord> words, int charCount) {
        int[] map = new int[charCount];
        java.util.Arrays.fill(map, -1);
        if (words == null) {
            return map;
        }
        int at = 0;
        for (int w = 0; w < words.size(); w++) {
            int length = words.get(w).text().length();
            for (int k = 0; k < length && at < charCount; k++) {
                map[at++] = w;
            }
        }
        return map;
    }

    /**
     * 动画强度。
     *
     * <p>HUD 的可用高度未必容得下完整的抬升：把可用高度与动画所需高度之比
     * 压到 0.2 以上，空间越小幅度越轻，字不会被推出边界，同时仍保留一点动感。
     */
    private float motionStrength(int textY) {
        int available = painter.screenHeight() - textY - LyricGeometry.LINE_HEIGHT;
        if (available <= 0) {
            return MIN_STRENGTH;
        }
        float needed = textSizePx() * (1f + EMPHASIS_LIFT_EM);
        if (needed <= 0f) {
            return 1f;
        }
        return Math.min(1f, Math.max(MIN_STRENGTH, available / needed));
    }

    /** 当前字号，像素；取不到时按行高估算。 */
    private float textSizePx() {
        return painter.lineHeight() > 0 ? painter.lineHeight() : LyricGeometry.LINE_HEIGHT;
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

    /**
     * 整行绘制的文本准备。
     *
     * <p>返回原文。曾经在这里按字重拼 {@code §l}/{@code §r}，
     * 加粗移除后不再有任何修饰；保留这个方法是因为调用点较多，
     * 且未来若需要加回样式，这里是唯一的插入点。
     */
    private String styled(String text) {
        return text;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
