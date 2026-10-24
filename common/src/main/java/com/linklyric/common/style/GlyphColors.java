package com.linklyric.common.style;

/**
 * 字内渐变的一对颜色。
 *
 * <p>字形是四条边的四边形，GPU 在顶点之间线性插值。
 * 给左两顶点一个颜色、右两顶点另一个颜色，就能让一个字内部
 * 出现连续渐变的染色前沿——这正是做「字内变色」需要的表达。
 *
 * <p>用一对颜色而不是「一个颜色 + 比例」是因为顶点插值本身
 * 就完成了过渡：前沿落在字内任何位置，都只是两个端点色不同而已，
 * 不需要额外的区间计算。
 *
 * @param left  字形左边缘的颜色（ARGB）
 * @param right 字形右边缘的颜色（ARGB）
 */
public record GlyphColors(int left, int right) {

    /** 两侧同色，即普通的不渐变绘制。 */
    public static GlyphColors solid(int color) {
        return new GlyphColors(color, color);
    }

    /** 是否两侧不同色。同色时可以走原版单色路径，省掉自定义顶点。 */
    public boolean isGradient() {
        return left != right;
    }
}
