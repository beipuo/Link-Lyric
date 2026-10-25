package com.linklyric.common.font;

/**
 * 文本排版所需的全部字形度量，位于 9 像素设计空间，不触碰栅格化器。
 *
 * <p>测量文本会遍历字符串里的每个码点，所以这里必须保持廉价：
 * 在这个阶段做栅格化会把一次排版变成上千次轮廓遍历。
 */
public record GlyphMetrics(float advance, float boldOffset, float shadowOffset) {

    public static GlyphMetrics of(float advance) {
        return new GlyphMetrics(advance, 1f, 1f);
    }
}
