package com.linklyric.common.style;

/**
 * 字内的一小段：左右边界与两端颜色。
 *
 * <p>前沿函数 {@code f(x)} 是分段线性的——羽化带之外是常量，
 * 之内是斜线。只要在它的**每个折点**处把字切开，每一段内
 * {@code f} 就是仿射函数，GPU 对两个端点做线性插值便精确等于
 * {@code f(x)}，不需要更多顶点。
 *
 * <p>这正是顶点效率的全部来源：不必固定把字切成 N 段，
 * 只需在曲率变化处切。整行通常只有一两个字跨前沿，
 * 其余字仍是单个四边形。
 *
 * @param startX     段左边界（与调用方传入的字形横坐标同一坐标系）
 * @param endX       段右边界
 * @param startColor 左端颜色（ARGB）
 * @param endColor   右端颜色
 */
public record GlyphSpan(float startX, float endX,
                        int startColor, int endColor) {

    /** 段宽是否为正。零宽段无法形成四边形，应跳过。 */
    public boolean isEmpty() {
        return endX - startX <= 1.0e-4f;
    }

    /** 判断是否为单色段，可用于跳过逐顶点写入。 */
    public boolean isSolid() {
        return startColor == endColor;
    }
}
