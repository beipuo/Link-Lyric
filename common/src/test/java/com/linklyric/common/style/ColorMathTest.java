package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 颜色插值。
 *
 * <p>用插值而不是 alpha 递减，是因为后者等于对**背景**混合：
 * 字形连同阴影一起变淡，深色背景上就成了一道竖向暗痕而非颜色过渡。
 */
class ColorMathTest {

    /** 端点必须精确。 */
    @Test
    void 端点精确() {
        int from = 0xFF7FD4FF;
        int to = 0xFFD8D8D8;
        assertEquals(from, ColorMath.lerp(from, to, 0f));
        assertEquals(to, ColorMath.lerp(from, to, 1f));
    }

    /** 中点应落在两端的中间，且各通道独立插值。 */
    @Test
    void 中点取中() {
        int from = 0xFF000000;
        int to = 0xFFFFFFFF;
        int mid = ColorMath.lerp(from, to, 0.5f);
        assertEquals(0xFF, (mid >>> 24) & 0xFF, "alpha 应插到中值");
        // 255 * 0.5 = 127.5，四舍五入为 128。
        assertEquals(0x80, (mid >>> 16) & 0xFF, "红通道应插到中值");
        assertEquals(0x80, (mid >>> 8) & 0xFF);
        assertEquals(0x80, mid & 0xFF);
    }

    /** 透明度也要插值，否则淡入淡出会跳。 */
    @Test
    void 透明度插值() {
        int from = 0x00FFFFFF;
        int to = 0xFFFFFFFF;
        int mid = ColorMath.lerp(from, to, 0.5f);
        assertEquals(0x80, (mid >>> 24) & 0xFF, "alpha 应为 128");
    }

    /** 越界比例必须夹紧，不能外插出色域。 */
    @Test
    void 越界夹紧() {
        int from = 0xFF102030;
        int to = 0xFF405060;
        assertEquals(from, ColorMath.lerp(from, to, -1f));
        assertEquals(to, ColorMath.lerp(from, to, 2f));
        assertEquals(from, ColorMath.lerp(from, to, Float.NaN));
    }

    /** 单调性：比例递增时每个通道都应朝目标推进，不能来回跳。 */
    @Test
    void 通道单调推进() {
        int from = 0xFF7FD4FF;
        int to = 0xFFD8D8D8;
        int prevR = (from >>> 16) & 0xFF;
        for (float t = 0.1f; t <= 1.0f; t += 0.1f) {
            int c = ColorMath.lerp(from, to, t);
            int r = (c >>> 16) & 0xFF;
            assertTrue(r >= prevR, "红通道应单调不减，t=" + t + " 实际=" + r);
            prevR = r;
        }
    }

    /** 已唱色偏蓝、未唱色偏灰：插值中途 blue 通道应下降。 */
    @Test
    void 蓝通道随比例下降() {
        int sung = 0xFF7FD4FF;
        int unsung = 0xFFD8D8D8;
        int near = ColorMath.lerp(sung, unsung, 0.15f) & 0xFF;
        int far = ColorMath.lerp(sung, unsung, 1.0f) & 0xFF;
        assertTrue(far < near, "越接近未唱色，蓝通道应越低");
    }
}
