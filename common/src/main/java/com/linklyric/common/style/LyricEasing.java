package com.linklyric.common.style;

/**
 * 歌词动画的统一缓动：固定控制点 {@code (0.25, 0, 0.2, 1)}。
 *
 * <h2>谁在用这条曲线</h2>
 *
 * <p>行切换的推入与逐字上浮。两者都是<b>位移</b>，
 * 共用一条曲线才不会出现「字先窜上去、颜色慢慢追」的脱节感。
 *
 * <h2>谁不用</h2>
 *
 * <p>染色前沿<b>不</b>用这条曲线。前沿表达「唱到哪了」，是事实，
 * 必须与音频时间严格成正比；曲线非匀速会让口型脱节、
 * 行尾永远差一点染不满。那侧走线性加一次 smoothstep 收边。
 *
 * <p>强调特效也不走这里：它需要分段的两组控制点，见 {@code EmpEasing}。
 *
 * <h2>参数与形状</h2>
 *
 * <p>控制点是此处唯一可调的东西，由 {@link CubicBezier} 求解。
 *
 * <p>输出（见 {@code LyricEasingTest}）：
 * <pre>
 *   t     0.10    0.25    0.50    0.75    0.90
 *   y     0.066   0.402   0.806   0.961   0.994
 * </pre>
 *
 * <p>形状是「起步短暂蓄力、中段快速抬升、尾部长时间贴顶」，
 * 不是传统的 ease-out：{@code t=0.1} 时输出低于线性。
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
        return CubicBezier.eval(t, X1, Y1, X2, Y2);
    }
}
