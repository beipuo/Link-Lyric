package com.linklyric.common.font;

/**
 * 一张已烘焙的字形位图，可直接上传进字形图集。
 *
 * <p>平面边界沿用原版 {@code SheetGlyphInfo} 的坐标系：相对行首的笔位，
 * 基线位于 9 像素设计空间的 y = 7 处。保持这个约定意味着步进与字形位置
 * 和原版渲染器完全一致，测量代码（光标位置、换行）无需改动。
 *
 * <h2>为什要把度量与位图分开</h2>
 *
 * <p>测量文本要遍历每个字符，这一步绝不能触发栅格化，
 * 否则一次排版就变成上千次轮廓遍历。因此度量走
 * {@link GlyphMetrics}，位图走这里。
 *
 * @param data         单通道位图，{@code width * height} 字节、行主序；null 表示空白字形
 * @param width        位图宽（图集像素）
 * @param height       位图高（图集像素）
 * @param left         四边形左边缘（设计空间）
 * @param top          四边形上边缘（设计空间）
 * @param right        四边形右边缘（设计空间）
 * @param bottom       四边形下边缘（设计空间）
 * @param advance      笔位步进（设计空间）
 * @param boldOffset   伪粗体重画的偏移
 * @param shadowOffset 阴影偏移
 */
public record RawGlyph(byte[] data, int width, int height,
                       float left, float top, float right, float bottom,
                       float advance, float boldOffset, float shadowOffset) {

    public static RawGlyph of(byte[] data, int width, int height,
                              float left, float top, float right, float bottom,
                              float advance) {
        return new RawGlyph(data, width, height, left, top, right, bottom,
                advance, 1f, 1f);
    }

    /** 只有步进、没有位图的字形，用于空格与空白字符。 */
    public static RawGlyph space(float advance) {
        return new RawGlyph(null, 0, 0, 0f, 0f, 0f, 0f, advance, 1f, 1f);
    }

    public boolean hasBitmap() {
        return data != null && width > 0 && height > 0;
    }
}
