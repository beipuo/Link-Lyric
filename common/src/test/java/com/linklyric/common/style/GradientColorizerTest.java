package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字内渐变的颜色分配。
 *
 * <p>核心判据是「前沿落在字内时，该字必须是左右异色」——
 * 否则就退化成整字跳变，与旧的裁剪方案没有区别。
 */
class GradientColorizerTest {

    private static final int SUNG = 0xFF3AA0FF;
    private static final int UNSUNG = 0xFFFFFFFF;

    /** 前沿在字之前：整字未唱，两侧同色。 */
    @Test
    void 未唱到的字两侧同色() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 5f, SUNG, UNSUNG);
        assertEquals(UNSUNG, c.left());
        assertEquals(UNSUNG, c.right());
        assertFalse(c.isGradient());
    }

    /** 前沿在字之后：整字已唱，两侧同色。 */
    @Test
    void 已唱完的字两侧同色() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 25f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(SUNG, c.right());
        assertFalse(c.isGradient());
    }

    /** 前沿穿过字内：必须左右异色，这样顶点插值才能画出字内渐变。 */
    @Test
    void 前沿穿过时左右异色() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 14f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(UNSUNG, c.right());
        assertTrue(c.isGradient(), "前沿在字内时必须异色，否则就是整字跳变");
    }

    /** 前沿极其接近左边缘时，仍判为字内渐变。 */
    @Test
    void 前沿贴左边缘仍是渐变() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 10.001f, SUNG, UNSUNG);
        assertTrue(c.isGradient());
    }

    /** 前沿极其接近右边缘时，仍判为字内渐变。 */
    @Test
    void 前沿贴右边缘仍是渐变() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 18.999f, SUNG, UNSUNG);
        assertTrue(c.isGradient());
    }

    /** 边界处不应出现「整字已唱」的误判。 */
    @Test
    void 右边缘处应判为已唱完() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 19f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(SUNG, c.right());
    }

    /** 整行：只有跨前沿的那个字是渐变，其余同色。 */
    @Test
    void 整行只有跨前沿的字渐变() {
        float[] lefts = {0f, 9f, 18f, 27f};
        float[] rights = {9f, 18f, 27f, 36f};
        GlyphColors[] out = GradientColorizer.forLine(lefts, rights, 4, 14f,
                SUNG, UNSUNG);

        assertEquals(4, out.length);
        assertFalse(out[0].isGradient(), "第 0 字已唱完");
        assertTrue(out[1].isGradient(), "第 1 字跨越前沿 14");
        assertFalse(out[2].isGradient(), "第 2 字未唱到");
        assertFalse(out[3].isGradient(), "第 3 字未唱到");

        assertEquals(SUNG, out[0].left());
        assertEquals(UNSUNG, out[2].left());
    }

    /** 前沿在行首之前：整行同色未唱。 */
    @Test
    void 前沿在行首前整行未唱() {
        float[] lefts = {0f, 9f};
        float[] rights = {9f, 18f};
        GlyphColors[] out = GradientColorizer.forLine(lefts, rights, 2, -1f,
                SUNG, UNSUNG);
        for (GlyphColors c : out) {
            assertFalse(c.isGradient());
            assertEquals(UNSUNG, c.left());
        }
    }

    /** 前沿在行尾之后：整行同色已唱。 */
    @Test
    void 前沿在行尾后整行已唱() {
        float[] lefts = {0f, 9f};
        float[] rights = {9f, 18f};
        GlyphColors[] out = GradientColorizer.forLine(lefts, rights, 2, 100f,
                SUNG, UNSUNG);
        for (GlyphColors c : out) {
            assertFalse(c.isGradient());
            assertEquals(SUNG, c.left());
        }
    }

    /** 同色值对象不应被判定为渐变，这样能跳过自定义顶点。 */
    @Test
    void 同色不算渐变() {
        assertFalse(GlyphColors.solid(SUNG).isGradient());
        assertTrue(new GlyphColors(SUNG, UNSUNG).isGradient());
    }
}
