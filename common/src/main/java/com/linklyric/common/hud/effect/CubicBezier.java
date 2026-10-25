package com.linklyric.common.hud.effect;

/**
 * 三次贝塞尔缓动求解器。
 *
 * <p>以 x 为时间轴求 y，算法与 {@code bezier-easing} 一致：先牛顿迭代求参数 t，
 * 不收敛时用二分兜底。纯函数、无分配。
 */
final class CubicBezier {

    private static final int NEWTON_ITERATIONS = 4;
    private static final float NEWTON_MIN_SLOPE = 0.001f;
    private static final float SUBDIVISION_PRECISION = 0.0000001f;
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
    static float eval(float x, float x1, float y1, float x2, float y2) {
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

    private static float slope(float t, float a, float b) {
        float u = 1f - t;
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b);
    }

    private static float bezier(float t, float a, float b) {
        float u = 1f - t;
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t;
    }
}
