package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行切换缓动的曲线测试。
 *
 * <p>曲线取自 Salt Player 的 {@code CubicBezierEasing(0.25f, 0.0f, 0.2f, 1.0f)}，
 * 时长 700ms。这里是唯一会「看起来生硬」的地方：
 * 换成线性或四分之一圆，推进速度在起步与收尾处会突变。
 */
class TransitionCurveTest {

    @Test
    void 缓动端点精确() throws Exception {
        assertEquals(0f, ease(0f), 1e-4f, "起点应为 0");
        assertEquals(1f, ease(1f), 1e-4f, "终点应为 1");
    }

    @Test
    void 缓动单调递增() throws Exception {
        float prev = -1f;
        for (int i = 0; i <= 100; i++) {
            float x = i / 100f;
            float y = ease(x);
            assertTrue(y >= prev - 1e-5f,
                    "x=" + x + " 处出现回退：" + prev + " → " + y);
            prev = y;
        }
    }

    @Test
    void 缓动是两端慢中间快() throws Exception {
        // 端点附近斜率应明显小于中段
        float startSlope = ease(0.05f) - ease(0f);
        float midSlope = ease(0.55f) - ease(0.5f);
        assertTrue(midSlope > startSlope * 2f,
                "中段推进应快于起步：起步=" + startSlope + " 中段=" + midSlope);

        float endSlope = ease(1f) - ease(0.95f);
        assertTrue(midSlope > endSlope * 2f,
                "中段推进应快于收尾：中段=" + midSlope + " 收尾=" + endSlope);
    }

    /** 半程应越过 0.5，说明曲线在中段前就加速（x2 = 0.2 的体现）。 */
    @Test
    void 半程已超过一半() throws Exception {
        assertTrue(ease(0.5f) > 0.5f,
                "x=0.5 时应已过半，实际=" + ease(0.5f));
    }

    private static float ease(float x) throws Exception {
        Method m = FloatingLyricRenderer.class
                .getDeclaredMethod("cubicBezierEase", float.class);
        m.setAccessible(true);
        return (float) m.invoke(null, x);
    }
}
