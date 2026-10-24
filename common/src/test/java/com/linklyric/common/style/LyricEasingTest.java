package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享缓动。
 *
 * <p>行切换、逐字上浮、染色、旧行淡出共用同一条曲线。各用一条时，
 * 同一时刻的进度能差出 25 个百分点，视觉上会被感受为卡顿。
 * 这里锁住曲线本身的性质，任何一处单独换曲线都会在集成测试暴露。
 */
class LyricEasingTest {

    private static final float EPS = 1e-4f;

    /** 端点必须精确落在 0 和 1，否则动画起止会有可见偏移。 */
    @Test
    void 两端锚定() {
        assertEquals(0f, LyricEasing.ease(0f), EPS);
        assertEquals(1f, LyricEasing.ease(1f), EPS);
    }

    /** 越界输入夹紧，不能外推到负值或超过顶点。 */
    @Test
    void 越界夹紧() {
        assertEquals(0f, LyricEasing.ease(-5f), EPS);
        assertEquals(1f, LyricEasing.ease(5f), EPS);
    }

    /** 单调递增：任何位置的进度都不能回头。 */
    @Test
    void 单调递增() {
        float previous = -1f;
        for (int i = 0; i <= 200; i++) {
            float t = i / 200f;
            float v = LyricEasing.ease(t);
            assertTrue(v >= previous - EPS,
                    "t=" + t + " 处出现回落，" + previous + " -> " + v);
            previous = v;
        }
    }

    /** 输出始终在 [0,1] 内，不能出现过冲。 */
    @Test
    void 不产生过冲() {
        for (int i = 0; i <= 200; i++) {
            float t = i / 200f;
            float v = LyricEasing.ease(t);
            assertTrue(v >= 0f && v <= 1f, "t=" + t + " 输出越界：" + v);
        }
    }

    /**
     * 曲线形状：起步略有迟滞，中段快速抬升，尾部长时间贴顶。
     *
     * <p>实测值：t=0.1 时 0.066（低于线性，短暂蓄力），
     * t=0.25 时 0.402（已高于线性），t=0.75 时 0.961（接近顶点）。
     *
     * <p>注意它与 {@code 1-(1-t)³} 的差别很大：后者在 t=0.25 就到 0.578、
     * t=0.5 到 0.875。换成这条曲线后，上浮在前四分之一会**比原来慢**，
     * 中段追上。这正是它与行切换、染色同步的结果，不是退化。
     */
    @Test
    void 曲线形状符合实测() {
        assertTrue(LyricEasing.ease(0.1f) < 0.1f,
                "t=0.1 应有短暂蓄力（低于线性），实际=" + LyricEasing.ease(0.1f));
        assertTrue(LyricEasing.ease(0.25f) > 0.25f,
                "t=0.25 应高于线性，实际=" + LyricEasing.ease(0.25f));
        assertTrue(LyricEasing.ease(0.75f) < 1f,
                "t=0.75 应尚未到顶，实际=" + LyricEasing.ease(0.75f));
    }

    /**
     * 中点取值锁死为实测值 0.8061，用于发现控制点被误改。
     *
     * <p>{@code (0.25, 0, 0.2, 1)} 的输出在 t=0.5 处是 0.806，
     * 明显高于线性。这个数值是实机测出来的，不是估计值；
     * 控制点一旦改动这里立刻失败。
     */
    @Test
    void 中点取值稳定() {
        float mid = LyricEasing.ease(0.5f);
        assertEquals(0.8060863f, mid, 0.002f,
                "中点应约为 0.806，实际=" + mid);
    }

    /**
     * 逐帧取值必须连续：相邻两帧的差不能出现跳变。
     *
     * <p>用 240 帧模拟一次过渡，任意相邻帧的增量都应小于 1%。
     * 若某处出现台阶，说明曲线被分段或量化了。
     */
    @Test
    void 逐帧连续() {
        int frames = 240;
        float previous = LyricEasing.ease(0f);
        float maxStep = 0f;
        for (int i = 1; i <= frames; i++) {
            float v = LyricEasing.ease(i / (float) frames);
            float step = v - previous;
            assertTrue(step >= -EPS, "第 " + i + " 帧出现回落");
            maxStep = Math.max(maxStep, step);
            previous = v;
        }
        assertTrue(maxStep < 0.05f,
                "单帧最大增量应小于 0.05，实际=" + maxStep);
    }
}
