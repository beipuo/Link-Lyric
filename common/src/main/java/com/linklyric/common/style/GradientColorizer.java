package com.linklyric.common.style;

/**
 * 把「染色前沿」换算成每个字的左右端点颜色。
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
 * <h2>计算规则</h2>
 *
 * <p>设前沿位于 {@code edge}（相对行首的浮点像素）。对字符 i：
 * 左边缘 {@code x0}、右边缘 {@code x1}。
 * <ul>
 *   <li>{@code x1 <= edge}：整字已唱，左右同色 = 已唱色</li>
 *   <li>{@code x0 >= edge}：整字未唱，左右同色 = 未唱色</li>
 *   <li>否则前沿穿过字内：左端已唱色、右端未唱色，
 *       插值自然把前沿画在正确位置</li>
 * </ul>
 *
 * <p>第三种情况下左右端色只取决于「是否跨前沿」，与前沿具体位置无关——
 * 位置信息已经隐含在顶点的几何坐标里，不需要再算进颜色。
 */
public final class GradientColorizer {

    private GradientColorizer() {
    }

    /**
     * 计算一个字的左右端点颜色。
     *
     * @param charLeft  字符左边缘相对行首的坐标（像素）
     * @param charRight 字符右边缘相对行首的坐标（像素）
     * @param edge      染色前沿相对行首的坐标（像素，可为浮点）
     * @param sungColor 已唱色（ARGB）
     * @param unsungColor 未唱色（ARGB）
     */
    public static GlyphColors forChar(float charLeft, float charRight,
                                      float edge,
                                      int sungColor, int unsungColor) {
        // 整字已唱完：两侧都是已唱色。
        if (charRight <= edge) {
            return GlyphColors.solid(sungColor);
        }
        // 整字未开始：两侧都是未唱色。
        if (charLeft >= edge) {
            return GlyphColors.solid(unsungColor);
        }
        // 前沿穿过字内：左端已唱、右端未唱，交给顶点插值。
        return new GlyphColors(sungColor, unsungColor);
    }

    /**
     * 为整行计算每个字的端点颜色。
     *
     * @param charLefts  每个字符左边缘的累积坐标，长度至少 {@code count}
     * @param charRights 每个字符右边缘的累积坐标
     * @param count      字符数
     * @return 每个字一对颜色
     */
    public static GlyphColors[] forLine(float[] charLefts, float[] charRights,
                                        int count, float edge,
                                        int sungColor, int unsungColor) {
        GlyphColors[] out = new GlyphColors[count];
        for (int i = 0; i < count; i++) {
            out[i] = forChar(charLefts[i], charRights[i], edge,
                    sungColor, unsungColor);
        }
        return out;
    }
}
