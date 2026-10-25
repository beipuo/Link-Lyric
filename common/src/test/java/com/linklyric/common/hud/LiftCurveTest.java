package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 余弦上浮曲线的形状。
 *
 * <h2>为什么值得测</h2>
 *
 * <p>曲线的全部意义在于「两端梯度为 0」，否则前沿推进时字会在起落处
 * 弹一下。这是肉眼能看出、却很难从代码里读出来的性质，
 * 只能在采样点上验证连续性。
 */
class LiftCurveTest {

    /** 前沿左侧的字应升满。 */
    @Test
    void 前沿左侧升满() {
        float[] centers = {10f, 20f};
        float[] k = LyricProgress.cosineLiftFactors(centers, 50f, 12f);
        assertEquals(1f, k[0], "远在左侧的字应完全升起");
        assertEquals(1f, k[1], "远在左侧的字应完全升起");
    }

    /** 前沿右侧一个字宽之外应完全在基线。 */
    @Test
    void 前沿右侧落回基线() {
        float[] centers = {80f, 95f};
        float[] k = LyricProgress.cosineLiftFactors(centers, 50f, 12f);
        assertEquals(0f, k[0], "远在右侧的字应回到基线");
        assertEquals(0f, k[1], "远在右侧的字应回到基线");
    }

    /** 过渡带正中的字应恰好是一半。 */
    @Test
    void 过渡带中点取半() {
        // 前沿在 50，带宽 12：t = 0.5 的位置是 center = 50。
        float[] k = LyricProgress.cosineLiftFactors(new float[]{50f}, 50f, 12f);
        assertEquals(0.5f, k[0], 1e-5f, "t=0.5 时 cos 项为 0，系数应为 0.5");
    }

    /**
     * 系数沿位置单调递减。
     *
     * <p>若非单调，会看到字的高度出现「波浪」，与演唱方向矛盾。
     */
    @Test
    void 系数沿位置单调递减() {
        float band = 16f;
        float front = 40f;
        float[] centers = new float[40];
        for (int i = 0; i < centers.length; i++) {
            centers[i] = i * 2f;
        }
        float[] k = LyricProgress.cosineLiftFactors(centers, front, band);
        for (int i = 1; i < k.length; i++) {
            assertTrue(k[i] <= k[i - 1] + 1e-6f,
                    "第 " + i + " 个系数不应高于前一个：" + k[i - 1] + " -> " + k[i]);
        }
    }

    /**
     * 前沿缓慢移动时，相邻位置的系数不会突变。
     *
     * <p>这条正是「一跳一跳」的判据：按像素推进前沿，
     * 同一点的系数变化必须足够小，否则就是阶跃。
     */
    @Test
    void 前沿推进连续无跳变() {
        float band = 12f;
        float center = 40f;
        float previous = Float.NaN;
        float maxJump = 0f;
        for (int step = 0; step <= 200; step++) {
            float front = step * 0.5f;
            float k = LyricProgress.cosineLiftFactors(new float[]{center}, front, band)[0];
            if (!Float.isNaN(previous)) {
                maxJump = Math.max(maxJump, Math.abs(k - previous));
            }
            previous = k;
        }
        // 余弦段的最大斜率是 π/(2·band)，乘以前沿步长即为单步上界。
        // 按理论值判定而不是随手取一个阈值：0.05 这种数会把正确实现判失败。
        float step = 0.5f;
        float bound = step * (float) Math.PI / (2f * band) * 1.01f;
        assertTrue(maxJump <= bound,
                "前沿每推进 " + step + "px，系数跳变不应超过理论界 " + bound
                        + "，实际 " + maxJump);
    }

    /** 带宽非法时不产生除零，全部按升起处理。 */
    @Test
    void 零带宽不除零() {
        float[] k = LyricProgress.cosineLiftFactors(new float[]{0f, 10f}, 5f, 0f);
        assertEquals(1f, k[0]);
        assertEquals(1f, k[1]);
    }

    /**
     * 余弦曲线在过渡带内的形状不同于线性斜坡。
     *
     * <p>判据取两端：余弦在 t 接近 0 或 1 时几乎不变（梯度为 0），
     * 线性则保持恒定斜率。若两者等价，切换就没有意义，
     * 说明接线接到了同一个实现上。
     */
    @Test
    void 余弦两端比线性更平() {
        float band = 100f;
        // t=0.1 与 t=0.9 对应前沿两侧对称的位置。
        float nearLeft = LyricProgress.cosineLiftFactors(
                new float[]{10f - band * 0.5f + band * 0.1f}, 10f, band)[0];
        float nearRight = LyricProgress.cosineLiftFactors(
                new float[]{10f - band * 0.5f + band * 0.9f}, 10f, band)[0];

        // 线性斜坡在同一相位上的值：1-t。
        float linearLeft = 0.9f;
        float linearRight = 0.1f;

        assertTrue(nearLeft > linearLeft + 0.05f,
                "t=0.1 处余弦应比线性更接近 1，实际 " + nearLeft);
        assertTrue(nearRight < linearRight - 0.05f,
                "t=0.9 处余弦应比线性更接近 0，实际 " + nearRight);
    }

    /**
     * 余弦与线性的输出数组不同。
     *
     * <p>参数刻意让过渡带落在行中段，避免前沿正好卡在字中心
     * 导致两条曲线在该点恰好同值。
     */
    @Test
    void 余弦与线性输出不同() {
        // 单字宽 2，前沿落在过渡带内，采样点数足够多才能看出形状差异。
        // 早先用 5 个宽字，恰好让 t 与 1-t 成对出现，
        // 两条曲线逐点同值，测试自己制造了假阳性。
        int[] starts = new int[8];
        int[] widths = new int[8];
        for (int i = 0; i < 8; i++) {
            starts[i] = 22 + i * 2;
            widths[i] = 2;
        }
        float front = 30f;
        float fontSize = 12f;
        float[] cosine = LyricProgress.cosineLifts(starts, widths, front, fontSize);

        // 同一前沿下的线性推进：进度按 t 线性递减。
        float band = fontSize * LyricProgress.LIFT_BAND_FONT_RATIO;
        float[] linearProgress = new float[starts.length];
        for (int i = 0; i < starts.length; i++) {
            float t = (starts[i] + widths[i] * 0.5f - front + band * 0.5f) / band;
            linearProgress[i] = Math.max(0f, Math.min(1f, 1f - t));
        }
        float[] linear = LyricProgress.lifts(linearProgress.length, fontSize, linearProgress);

        float maxDiff = 0f;
        for (int i = 0; i < cosine.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(cosine[i] - linear[i]));
        }
        // 余弦在两端更平，最大差出现在 t≈0.15 与 t≈0.85，约 0.1 个抬升量。
        assertTrue(maxDiff > 0.05f,
                "余弦与线性应有可见差异，实际最大差 " + maxDiff);
    }

    /** 余弦曲线的抬升上限等于字号乘比例。 */
    @Test
    void 抬升上限受字号约束() {
        float fontSize = 20f;
        float[] lifts = LyricProgress.cosineLifts(new int[]{0, 10}, new int[]{10, 10}, 100f, fontSize);
        float expected = fontSize * LyricProgress.LIFT_FONT_RATIO;
        assertEquals(expected, lifts[0], 1e-4f, "唱满的字应达到上限");
        assertEquals(expected, lifts[1], 1e-4f, "唱满的字应达到上限");
    }
}
