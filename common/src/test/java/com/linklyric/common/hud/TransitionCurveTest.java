package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行切换缓动的曲线测试。
 *
 * <p>曲线取自 Salt Player 的 {@code CubicBezierEasing(0.25f, 0.0f, 0.2f, 1.0f)}，
 * 时长 700ms。此处是最容易「看起来生硬」的环节：
 * 换成线性或四分之一圆，推进速度会在起步与收尾处突变。
 */
class TransitionCurveTest {

    @Test
    void 缓动端点精确() {
        assertEquals(0f, LyricEasing.ease(0f), 1e-4f, "起点应为 0");
        assertEquals(1f, LyricEasing.ease(1f), 1e-4f, "终点应为 1");
    }

    @Test
    void 缓动单调递增() {
        float prev = -1f;
        for (int i = 0; i <= 100; i++) {
            float x = i / 100f;
            float y = LyricEasing.ease(x);
            assertTrue(y >= prev - 1e-5f,
                    "x=" + x + " 处出现回退：" + prev + " 到 " + y);
            prev = y;
        }
    }

    @Test
    void 缓动是两端慢中间快() {
        float startSlope = LyricEasing.ease(0.05f) - LyricEasing.ease(0f);
        float midSlope = LyricEasing.ease(0.55f) - LyricEasing.ease(0.5f);
        assertTrue(midSlope > startSlope * 2f,
                "中段推进应快于起步：起步=" + startSlope + " 中段=" + midSlope);

        float endSlope = LyricEasing.ease(1f) - LyricEasing.ease(0.95f);
        assertTrue(midSlope > endSlope * 2f,
                "中段推进应快于收尾：中段=" + midSlope + " 收尾=" + endSlope);
    }

    @Test
    void 半程已超过一半() {
        assertTrue(LyricEasing.ease(0.5f) > 0.5f,
                "x=0.5 时应已过半，实际=" + LyricEasing.ease(0.5f));
    }

    @Test
    void 上移位移随过渡时长缩短而收敛() {
        LineTransition transition = new LineTransition();
        transition.update(0, 0L, 700L);
        int atStart = transition.blockShift(700L);
        assertEquals(LyricGeometry.LINE_STEP, atStart, "过渡起点应偏整整一行");

        transition.update(0, 700_000_000L, 700L);
        assertEquals(0, transition.blockShift(700L), "过渡结束应完全就位");
    }

    /**
     * 换行后不保留任何旧句快照。
     *
     * <p>旧行淡出已移除：整句重画只有单一颜色，会让唱完的主句
     * 在消失前退化成「最基础的文字样式」闪一帧。
     * 状态机因此不再持有旧句内容与位置。
     */
    @Test
    void 换行不再冻结旧句() {
        LineTransition transition = new LineTransition();
        transition.update(1, 0L, 700L);
        // 换行瞬间即进入位移过渡，但没有任何「旧行还可绘制」的状态。
        assertTrue(transition.active(700L), "换行后应处于上移过渡中");
        assertEquals(LyricGeometry.LINE_STEP, transition.blockShift(700L),
                "过渡起点应偏整整一行");
    }
}
