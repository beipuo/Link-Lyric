package com.linklyric.common.style;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「染色前沿」换算成每个字的绘制段。
 *
 * <p>纯计算，不依赖任何渲染接口，便于单独验证。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>裁剪只能落在整像素上，而一个汉字只有 9 像素宽，用它区分颜色时
 * 分色边界最多只有 9 个台阶，表现为「先亮一列像素，卡住好几帧，
 * 然后整段跳过去」。逐顶点配色则把颜色当成浮点量：前沿在字内
 * 走到任何位置，都只是这个字的左右两端色不同，由 GPU 逐像素插值。
 * 没有裁剪，也就没有台阶。
 *
 * <h2>为什么两个顶点不够</h2>
 *
 * <p>前沿函数是分段线性的：
 * <pre>
 *   x &lt;= edge        → 1        （纯已唱，常量）
 *   edge &lt; x &lt; edge+W → 线性降到 0（羽化带，斜线）
 *   x &gt;= edge+W      → 0        （纯未唱，常量）
 * </pre>
 * 一条直线只能过两个点，拟合不了三段折线。实测：字宽 9、羽化 4、
 * 前沿在字内 5px 处时，x=105 的像素本应 100% 已唱，两端插值只给出
 * <b>44.4%</b>，偏差达半个字宽——整字被迫全程处于渐变中，
 * 看不出边界扫过。
 *
 * <h2>解法：在折点处切分</h2>
 *
 * <p>折点只有两个：{@code edge} 与 {@code edge+W}。在它们处把字切开，
 * 每一段内 {@code f} 就是仿射函数，两个端点插值即精确。
 * 于是不需要固定把字切成 N 段，顶点只用在真正需要的地方：
 * 整行通常只有一两个字跨前沿，其余字仍是单个四边形。
 *
 * <p>实测同样的情形，切开后误差为 <b>0%</b>。
 */
public final class GradientColorizer {

    /** 过渡宽度占行宽的比例。 */
    private static final float FEATHER_RATIO = 0.1f;

    /** 过渡宽度下限（像素）。再小就看不见渐变，退化成硬边。 */
    private static final float MIN_FEATHER_PX = 1.5f;

    /** 过渡宽度上限（像素）。再大整行都在渐变，边界会糊。 */
    private static final float MAX_FEATHER_PX = 4f;

    private GradientColorizer() {
    }

    /**
     * 由行宽推算过渡宽度。
     *
     * <p>比例取自上游做法：羽化区最多占行宽的一小部分，
     * 否则句子唱到一半时整行都在渐变，看起来没有「已唱」的实感。
     */
    public static float featherFor(float lineWidth) {
        if (lineWidth <= 0f) {
            return MIN_FEATHER_PX;
        }
        float w = lineWidth * FEATHER_RATIO;
        return Math.max(MIN_FEATHER_PX, Math.min(w, MAX_FEATHER_PX));
    }

    /**
     * 计算一个字的绘制段。
     *
     * <p>返回值至少一段。不跨前沿时只有一段（单色）；
     * 跨前沿时按折点切成两段或三段。
     *
     * @param charLeft  字符左边缘相对行首的坐标（像素）
     * @param charRight 字符右边缘相对行首的坐标（像素）
     * @param edge      染色前沿相对行首的坐标（像素，可为浮点）
     * @param sungColor 已唱色（ARGB）
     * @param unsungColor 未唱色（ARGB）
     * @param lineWidth 整行宽度（像素），用于推算过渡宽度
     */
    public static GlyphSpan[] spansForChar(float charLeft, float charRight,
                                           float edge,
                                           int sungColor, int unsungColor,
                                           float lineWidth) {
        return spansForChar(charLeft, charRight, edge, sungColor, unsungColor,
                lineWidth, true);
    }

    /**
     * 带羽化开关的版本。
     *
     * <p>{@code feather=false} 时传 0 给下游，走「硬边界」分支：最多两段，
     * 切点就是前沿。
     *
     * <p>这个重载的存在本身修掉了一个历史缺陷：此前样式里的
     * {@code feather} 开关是个死开关——配置界面能改、能存盘，
     * 渲染侧却始终按 {@link #featherFor} 算出的非零宽度画渐变，
     * 关掉之后画面毫无变化，因为根本没有任何调用点把该开关传下来。
     * 现在渲染器在逐字路径上传入 {@code style.feather()}，
     * 关闭态才真正落到硬切分支。
     *
     * @param feather 是否启用交界羽化；false 表示硬切
     */
    public static GlyphSpan[] spansForChar(float charLeft, float charRight,
                                           float edge,
                                           int sungColor, int unsungColor,
                                           float lineWidth, boolean feather) {
        return spansForCharWithFeather(charLeft, charRight, edge, sungColor, unsungColor,
                feather ? featherFor(lineWidth) : 0f);
    }

    /**
     * 直接指定羽化宽度的版本。
     *
     * <p>与上面那个重载参数表相同（最后一个 float 一个是行宽、一个是羽化宽度），
     * 无法靠签名区分，因此改用不同方法名。
     */
    public static GlyphSpan[] spansForCharWithFeather(float charLeft, float charRight,
                                                      float edge,
                                                      int sungColor, int unsungColor,
                                                      float feather) {
        float width = charRight - charLeft;
        if (width <= 0f) {
            return new GlyphSpan[0];
        }
        if (feather <= 0f) {
            // 硬边界：最多两段，切点就是 edge。
            if (edge <= charLeft) {
                return new GlyphSpan[]{
                        new GlyphSpan(charLeft, charRight, unsungColor, unsungColor)};
            }
            if (edge >= charRight) {
                return new GlyphSpan[]{
                        new GlyphSpan(charLeft, charRight, sungColor, sungColor)};
            }
            return new GlyphSpan[]{
                    new GlyphSpan(charLeft, edge, sungColor, sungColor),
                    new GlyphSpan(edge, charRight, unsungColor, unsungColor)};
        }

        // 折点：羽化带的起点与终点。把它们夹进字内，成为切分位置。
        float cut0 = Math.max(charLeft, edge);
        float cut1 = Math.min(charRight, edge + feather);
        if (cut0 >= cut1) {
            // 羽化带与该字不相交：整字单色。
            int c = edge >= charRight ? sungColor : unsungColor;
            return new GlyphSpan[]{new GlyphSpan(charLeft, charRight, c, c)};
        }

        List<GlyphSpan> out = new ArrayList<>(3);
        // 前沿左侧：纯已唱。
        if (charLeft < cut0) {
            out.add(new GlyphSpan(charLeft, cut0, sungColor, sungColor));
        }
        // 羽化带：两端取该处函数值，段内线性即精确。
        out.add(new GlyphSpan(cut0, cut1,
                sample(cut0, edge, feather, sungColor, unsungColor),
                sample(cut1, edge, feather, sungColor, unsungColor)));
        // 羽化带右侧：纯未唱。
        if (cut1 < charRight) {
            out.add(new GlyphSpan(cut1, charRight, unsungColor, unsungColor));
        }
        return out.toArray(new GlyphSpan[0]);
    }

    /**
     * 前沿函数在某点的取值。
     *
     * <p>过渡带位于 {@code [edge, edge + feather]}：左侧一律纯已唱，
     * 右侧一个羽化宽度内渐变到纯未唱。
     *
     * <p>为什么不以 {@code edge} 为中心：那样前沿两侧各占半个过渡带，
     * 当前沿正好走到行末时，最后一个字的右顶点落在过渡带中点，
     * 拿到的是 50% 混合色——整行唱完了却还留着一道没唱完的痕迹。
     */
    public static int sample(float x, float edge, float feather,
                             int sungColor, int unsungColor) {
        if (feather <= 0f) {
            return x < edge ? sungColor : unsungColor;
        }
        float t = (edge + feather - x) / feather;
        if (t >= 1f) {
            return sungColor;
        }
        if (t <= 0f) {
            return unsungColor;
        }
        return lerpArgb(unsungColor, sungColor, t);
    }

    /**
     * 一个字的主色对，供只需要「整字一色或两色」的调用方使用。
     *
     * <p>取段的左右端色，语义与旧的 {@code forChar} 一致。
     */
    public static GlyphColors forChar(float charLeft, float charRight,
                                      float edge,
                                      int sungColor, int unsungColor,
                                      float lineWidth) {
        GlyphSpan[] spans = spansForChar(charLeft, charRight, edge,
                sungColor, unsungColor, lineWidth);
        if (spans.length == 0) {
            return GlyphColors.solid(unsungColor);
        }
        return new GlyphColors(spans[0].startColor(),
                spans[spans.length - 1].endColor());
    }

    /** 使用行宽推算羽化宽度的版本。 */
    public static GlyphColors forChar(float charLeft, float charRight,
                                      float edge,
                                      int sungColor, int unsungColor) {
        return forChar(charLeft, charRight, edge, sungColor, unsungColor, 0f);
    }

    /**
     * 为整行计算每个字的主色对。
     *
     * <p>保留这个入口是为了让「只需要两端色」的调用方不必理解段的概念。
     * 实际绘制应使用 {@link #spansForChar}，否则会丢掉段内的折点信息。
     */
    public static GlyphColors[] forLine(float[] charLefts, float[] charRights,
                                        int count, float edge,
                                        int sungColor, int unsungColor,
                                        float lineWidth) {
        GlyphColors[] out = new GlyphColors[count];
        for (int i = 0; i < count; i++) {
            out[i] = forChar(charLefts[i], charRights[i], edge,
                    sungColor, unsungColor, lineWidth);
        }
        return out;
    }

    /** 使用行宽推算羽化宽度的整行版本。 */
    public static GlyphColors[] forLine(float[] charLefts, float[] charRights,
                                        int count, float edge,
                                        int sungColor, int unsungColor) {
        float width = count > 0 ? charRights[count - 1] : 0f;
        return forLine(charLefts, charRights, count, edge,
                sungColor, unsungColor, width);
    }

    /**
     * ARGB 线性插值。
     *
     * <p>四个通道一起插：已唱色与未唱色的 alpha 可能不同（例如淡出状态），
     * 只插 RGB 会让过渡区的透明度突变。
     */
    public static int lerpArgb(int from, int to, float t) {
        int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t);
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = lerpChannel(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        int v = Math.round(from + (to - from) * t);
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
