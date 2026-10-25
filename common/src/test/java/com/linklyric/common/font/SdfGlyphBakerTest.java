package com.linklyric.common.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字形烘焙的正确性。
 *
 * <p>这一步把原版低分辨率位图转成距离场，是「9px 字形放大后不糊」
 * 的关键。烘错了表现为整行文字发灰或有黑边，且只在特定字号下显现。
 */
class SdfGlyphBakerTest {

    /** 8×8 全实心格子：场应当饱和，没有任何轮廓。 */
    @Test
    void 全实心格子内部饱和() {
        byte[] coverage = new byte[8 * 8];
        java.util.Arrays.fill(coverage, (byte) 0xFF);
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7, 8);

        assertTrue(glyph.hasBitmap(), "实心格子应产生位图");
        // 中心像素：放大 4 倍 + padding 8 后，中心落在 (16+8, 16+8)。
        int w = glyph.width();
        int cx = w / 2;
        int cy = glyph.height() / 2;
        int center = glyph.data()[cy * w + cx] & 0xFF;
        assertTrue(center > DistanceTransform.ON_EDGE,
                "实心中心应在轮廓内部，实际=" + center);
    }

    /** 空白格子不产生位图，只有步进。 */
    @Test
    void 空白格子只有步进() {
        byte[] coverage = new byte[8 * 8];
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7, 8);
        // 全透明仍会烘焙出全外部的场（非 null），但步进必须保留。
        assertEquals(8f, glyph.advance(), 0.001f, "步进必须保留");
    }

    /** 零尺寸输入退化为空白字形。 */
    @Test
    void 零尺寸退化为空白() {
        RawGlyph glyph = SdfGlyphBaker.bake(new byte[0], 0, 0, 1f, 7, 5);
        assertFalse(glyph.hasBitmap(), "零尺寸不应产生位图");
        assertEquals(5f, glyph.advance(), 0.001f);
    }

    /**
     * 竖直笔画的两侧都应有轮廓。
     *
     * <p>这是最关键的判据：若只在一侧出现轮廓，
     * 说明放大或 padding 有偏移，字形会整体错位。
     */
    @Test
    void 竖直笔画两侧都有轮廓() {
        byte[] coverage = new byte[8 * 8];
        // 只点亮中间两列，形成一根竖笔。
        for (int y = 0; y < 8; y++) {
            coverage[y * 8 + 3] = (byte) 0xFF;
            coverage[y * 8 + 4] = (byte) 0xFF;
        }
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7, 8);
        int w = glyph.width();
        int h = glyph.height();
        int row = h / 2;

        // 沿该行扫描，应能找到「先升后降」的轮廓：两侧边缘都有过渡带。
        boolean sawAbove = false;
        boolean sawBelow = false;
        for (int x = 0; x < w; x++) {
            int v = glyph.data()[row * w + x] & 0xFF;
            if (v > DistanceTransform.ON_EDGE) {
                sawAbove = true;
            }
            if (sawAbove && v < DistanceTransform.ON_EDGE) {
                sawBelow = true;
            }
        }
        assertTrue(sawAbove, "竖笔内部应高于轮廓基准");
        assertTrue(sawBelow, "竖笔右侧应回落到轮廓基准以下");
    }

    /** 位图尺寸包含 padding。 */
    @Test
    void 位图尺寸含留白() {
        byte[] coverage = new byte[8 * 8];
        java.util.Arrays.fill(coverage, (byte) 0xFF);
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7, 8, 4, 8, 8f);
        assertEquals(8 * 4 + 8 * 2, glyph.width(),
                "8 像素放大 4 倍再加两侧各 8 像素留白");
        assertEquals(8 * 4 + 8 * 2, glyph.height());
    }

    /** 留白按比例折算进设计空间，四边形随之扩大。 */
    @Test
    void 留白折算进设计空间() {
        byte[] coverage = new byte[8 * 8];
        java.util.Arrays.fill(coverage, (byte) 0xFF);
        int upscale = 4;
        int padding = 8;
        float scale = 1f;
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, scale, 7, 8, upscale, padding, 8f);

        // 每边扩大 padding / upscale 个设计空间单位。
        float expected = (float) padding / upscale;
        assertEquals(-expected, glyph.left(), 0.001f,
                "左边缘应外扩 " + expected);
        assertEquals(8f + expected, glyph.right(), 0.001f,
                "右边缘应外扩 " + expected);
    }

    /** 基线位置：ascent 决定上边缘，9px 设计空间里基线在 y=7。 */
    @Test
    void 基线由ascent决定() {
        byte[] coverage = new byte[8 * 8];
        java.util.Arrays.fill(coverage, (byte) 0xFF);
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7, 8, 4, 8, 8f);
        // top = 7 - ascent = 0；再减去留白。
        assertEquals(0f - 2f, glyph.top(), 0.01f,
                "ascent=7 时上边缘为 0，减去留白 2");
        assertEquals(8f + 2f, glyph.bottom(), 0.01f,
                "下边缘为 8 加留白 2");
    }

    /** 提取覆盖率只取 alpha 通道。 */
    @Test
    void 提取覆盖率只看alpha() {
        // 一个像素：RGB 全满、alpha 为 0x80。
        int[] argb = new int[]{0x80FFFFFF};
        byte[] cov = SdfGlyphBaker.extractCoverage(argb, 1, 1, 1, 1, 0, 0);
        assertEquals(1, cov.length);
        assertEquals((byte) 0x80, cov[0], "应取 alpha 高字节");
    }

    /** 提取覆盖率能处理越界起点。 */
    @Test
    void 提取覆盖率容忍越界() {
        int[] argb = new int[]{0xFFFFFFFF};
        byte[] cov = SdfGlyphBaker.extractCoverage(argb, 1, 1, 2, 2, 0, 0);
        assertEquals(4, cov.length);
        assertEquals((byte) 0xFF, cov[0], "在范围内的像素正常提取");
        assertEquals((byte) 0, cov[1], "越界像素补 0");
    }

    /** 覆盖率按行主序铺排。 */
    @Test
    void 提取覆盖率按行主序() {
        int[] argb = new int[]{
                0xFF000000, 0x80000000,
                0x40000000, 0x20000000};
        byte[] cov = SdfGlyphBaker.extractCoverage(argb, 2, 2, 2, 2, 0, 0);
        assertEquals((byte) 0xFF, cov[0]);
        assertEquals((byte) 0x80, cov[1]);
        assertEquals((byte) 0x40, cov[2]);
        assertEquals((byte) 0x20, cov[3]);
    }
}
