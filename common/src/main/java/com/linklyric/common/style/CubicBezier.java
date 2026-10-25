package com.linklyric.common.style;

/**
 * 三次贝塞尔缓动求解器。
 *
 * <p>以 x 为时间轴求 y，算法与 {@code bezier-easing} 一致：先牛顿迭代求参数 t，
 * 不收敛时用二分兜底。纯函数、无分配。
 *
 * <h2>为什么放在这里</h2>
 *
 * <p>行切换与逐字上浮用的是<b>固定</b>控制点 {@code (0.25, 0, 0.2, 1)}，
 * 由 {@link LyricEasing} 包一层；强调特效用的是<b>分段</b>两组控制点
 * （{@code EmpEasing}）。形状不同，但「x 反解 t 再取 y」这一步完全一样。
 *
 * <p>这步曾各写一份，两份实现的最大偏差实测 1.4e-5——同一算法的两遍手抄，
 * 任何一次改实现（多加一轮迭代、改收敛判据）都会让两条曲线悄悄分开。
 * 因此只保留这一份，两个用途都调它。
 */
public final class CubicBezier {

    /** 牛顿迭代轮数。四轮足以把误差压到 1e-5 以下。 */
    private static final int NEWTON_ITERATIONS = 4;

    /** 斜率小于此值时牛顿法会振荡，直接取当前值。 */
    private static final float NEWTON_MIN_SLOPE = 0.001f;

    /** 判定牛顿法已经收敛的 x 误差。 */
    private static final float SUBDIVISION_PRECISION = 0.0000001f;

    /** 二分兜底的迭代次数。 */
    private static final int SUBDIVISION_MAX_ITERATIONS = 10;

    private CubicBezier() {
    }

    /**
     * 求值。
     *
     * @param x  时间轴进度，0..1
     * @param x1 控制点 1 的 x
     * @param y1 控制点 1 的 y
     * @param x2 控制点 2 的 x
     * @param y2 控制点 2 的 y
     */
    public static float eval(float x, float x1, float y1, float x2, float y2) {
        float t = x;
        for (int i = 0; i < NEWTON_ITERATIONS; i++) {
            float slope = slope(t, x1, x2);
            if (Math.abs(slope) < NEWTON_MIN_SLOPE) {
                return bezier(t, y1, y2);
            }
            float dx = bezier(t, x1, x2) - x;
            t -= dx / slope;
        }
        if (Math.abs(bezier(t, x1, x2) - x) < SUBDIVISION_PRECISION) {
            return bezier(t, y1, y2);
        }
        float low = 0f;
        float high = 1f;
        for (int i = 0; i < SUBDIVISION_MAX_ITERATIONS; i++) {
            float current = bezier(t, x1, x2);
            if (current > x) {
                high = t;
            } else {
                low = t;
            }
            t = (low + high) / 2f;
        }
        return bezier(t, y1, y2);
    }

    /** 一维三阶贝塞尔在参数 t 处的导数。 */
    private static float slope(float t, float a, float b) {
        float u = 1f - t;
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b);
    }

    /** 端点固定为 0 与 1 的一维三阶贝塞尔。 */
    private static float bezier(float t, float a, float b) {
        float u = 1f - t;
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t;
    }
}
