package com.linklyric.common.style;

/**
 * 歌词动画的统一缓动。
 *
 * <h2>为什么需要统一</h2>
 *
 * <p>此前三条动画各用一条曲线：行切换走 {@code CubicBezierEase(0.25, 0, 0.2, 1)}，
 * 逐字上浮用 {@code 1-(1-t)³}，染色用 {@code t(2.5-2.5t+t²)}。
 * 三条曲线在 t=0.5 处的输出分别是约 0.60、0.875、0.625——
 * 也就是说同一个时间点，字已经升到 87% 高度，颜色才走到 62%。
 *
 * <p>单看每条曲线都合理，合在一起就不对：眼睛看到的是
 * 「字先窜上去，颜色慢慢追」，这种脱节会被感受为卡顿，
 * 即使每一帧都在更新。Salt 的做法是让行切换、上浮、淡入
 * 共用一个 {@code Easing} 实例，正是为了避免这个问题。
 *
 * <h2>参数来源与实测形状</h2>
 *
 * <p>控制点 {@code (0.25, 0, 0.2, 1)} 取自本仓库既有的行切换实现，
 * 而不是新发明的值。上浮与染色沿用它，等于把「已经调好的那条」
 * 推广到另外两条。三者从现在起必须同步变化，不再单独调参。
 *
 * <p>实测输出（见 {@code LyricEasingTest}）：
 * <pre>
 *   t     0.10    0.25    0.50    0.75    0.90
 *   y     0.066   0.402   0.806   0.961   0.994
 * </pre>
 *
 * <p>形状是「起步短暂蓄力、中段快速抬升、尾部长时间贴顶」。
 * 它不是传统的 ease-out：{@code t=0.1} 时输出低于线性。
 *
 * <p><b>与旧的逐字上浮曲线差别很大。</b>原先上浮用 {@code 1-(1-t)³}，
 * 在 t=0.25 就到 0.578、t=0.5 到 0.875。换成这条后，
 * 上浮的前四分之一比原来慢、中段追上。这是三者同步的必然结果：
 * 旧曲线太快，正是「字先窜上去、颜色慢慢追」的来源。
 */
public final class LyricEasing {

    /** 贝塞尔控制点 x1。 */
    private static final float X1 = 0.25f;

    /** 贝塞尔控制点 y1。 */
    private static final float Y1 = 0.0f;

    /** 贝塞尔控制点 x2。 */
    private static final float X2 = 0.2f;

    /** 贝塞尔控制点 y2。 */
    private static final float Y2 = 1.0f;

    private LyricEasing() {
    }

    /**
     * 对进度求缓动值。
     *
     * @param t 线性进度，越界会被夹紧到 [0,1]
     * @return 缓动后的进度，同样落在 [0,1]
     */
    public static float ease(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        return y(solveT(t));
    }

    /**
     * 由 x 反解贝塞尔参数 t。
     *
     * <p>{@code x} 与参数 {@code t} 不是同一个量：必须先解 {@code x(t) = 目标x}，
     * 才能取 {@code y(t)}。
     *
     * <p>用牛顿迭代加速、二分兜底。曲线在 {@code x1 = 0.25} 处导数可能接近 0，
     * 纯牛顿会在该区间来回振荡，因此导数过小时直接转二分。
     */
    private static float solveT(float x) {
        float t = x;
        for (int i = 0; i < 8; i++) {
            float dx = cx(t) - x;
            if (Math.abs(dx) < 1e-5f) {
                return t;
            }
            float slope = dcx(t);
            if (Math.abs(slope) < 1e-6f) {
                break;
            }
            t -= dx / slope;
            if (t < 0f || t > 1f) {
                break;
            }
        }
        float lo = 0f;
        float hi = 1f;
        t = x;
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) * 0.5f;
            if (cx(mid) < x) {
                lo = mid;
            } else {
                hi = mid;
            }
            t = mid;
        }
        return t;
    }

    /** 控制点 x 坐标的三次贝塞尔求值。 */
    private static float cx(float t) {
        return cubic(t, X1, X2);
    }

    /** 控制点 y 坐标的三次贝塞尔求值。 */
    private static float y(float t) {
        return cubic(t, Y1, Y2);
    }

    /** cx 对 t 的导数，供牛顿迭代使用。 */
    private static float dcx(float t) {
        float inv = 1f - t;
        return 3f * inv * inv * X1
                + 6f * inv * t * (X2 - X1)
                + 3f * t * t * (1f - X2);
    }

    /**
     * 一维三阶贝塞尔：端点固定为 0 与 1，只由两个控制点决定形状。
     *
     * @param t  参数 0..1
     * @param p1 第一个控制点
     * @param p2 第二个控制点
     */
    private static float cubic(float t, float p1, float p2) {
        float inv = 1f - t;
        return 3f * inv * inv * t * p1 + 3f * inv * t * t * p2 + t * t * t;
    }
}
