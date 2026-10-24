package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字上浮与染色同步。
 *
 * <p>Salt 的行为是「字被染到多少就升起多少」，两者是同一个量：
 * 完全染完时正好升到顶点。因此这里不引入任何时间常数——
 * 染色进度本身已是时间的函数，再叠一层缓动会让两者错位。
 */
class CharLiftWaveTest {

    private static final String LINE = "一二三四五六七八";
    private static final float CHAR_PX = 9f;
    private static final float FONT_PX = 9f;

    /** 与 FloatingLyricRenderer.LIFT_FONT_RATIO 一致。 */
    private static final float LIFT_RATIO = 0.10f;

    /**
     * 顶点抬升量，保持浮点。
     *
     * <p>取整会把 0.9 变成 1，整条曲线只剩 {0,1} 两档，
     * 动画表现为长时间不动后跳一格。绘制端同样不得再取整。
     */
    private static final float MAX_LIFT = FONT_PX * LIFT_RATIO;

    /** 由「已唱到第几个字」构造每字进度数组。 */
    private static float[] progress(float... values) {
        float[] p = new float[LINE.length()];
        for (int i = 0; i < values.length && i < p.length; i++) {
            p[i] = values[i];
        }
        return p;
    }

    /**
     * 幅度是字号乘零点一，且**保留小数**。
     *
     * <p>9px 字号应得 0.9px 而不是 1px。这个差别不是精度洁癖：
     * 取整会把 0.9 压成 1，再叠加绘制端取整，中间态全部丢失。
     */
    @Test
    void 幅度是字号乘零点一且不取整() {
        assertEquals(FONT_PX * LIFT_RATIO, MAX_LIFT, 0.0001f,
                "9px 字号应得 0.9px 幅度，实际=" + MAX_LIFT);
        assertTrue(MAX_LIFT != Math.round(MAX_LIFT),
                "幅度必须带小数，取整会让中途态丢失实际=" + MAX_LIFT);

        // 18px 字号应得 1.8px，验证确为比例而非固定像素
        float[] large = FloatingLyricRenderer.computeLifts(
                LINE.length(), 18f, progress(0f));
        assertNotNull(large);
        assertEquals(0f, large[0], 0.001f, "进度为 0 时不应抬起");

        float[] full = FloatingLyricRenderer.computeLifts(
                LINE.length(), 18f, progress(1f));
        assertNotNull(full);
        assertEquals(1.8f, full[0], 0.001f, "18px 字号染满应得 1.8px 幅度");
    }

    /**
     * 半途的抬升量必须是小数，不能落在整数台阶上。
     *
     * <p>这是「帧连续」的核心判据：进度取 0.1 到 0.9 时，
     * 抬升量应各不相同且都不为整数。若出现大量相同的整数值，
     * 说明又被量化了，动画会表现为「长时间不动、然后跳一格」。
     */
    @Test
    void 中途抬升量是连续小数() {
        float previous = -1f;
        int distinct = 0;
        for (int step = 1; step <= 9; step++) {
            float t = step / 10f;
            float[] one = FloatingLyricRenderer.computeLifts(
                    LINE.length(), FONT_PX, progress(t));
            assertNotNull(one);
            float lift = one[0];
            assertTrue(lift > 0f && lift < MAX_LIFT,
                    "进度 " + t + " 应产生中间抬升，实际=" + lift);
            assertTrue(lift != Math.round(lift),
                    "进度 " + t + " 的抬升量不应是整数，实际=" + lift);
            if (lift != previous) {
                distinct++;
            }
            previous = lift;
        }
        assertEquals(9, distinct,
                "九个进度档应产生九个不同抬升量，否则说明被量化了");
    }

    /** 一个字都没被染到，整行停在基线。 */
    @Test
    void 未染色时整行在基线() {
        float[] lifts = FloatingLyricRenderer.computeLifts(
                LINE.length(), FONT_PX, progress());

        assertNotNull(lifts);
        for (int i = 0; i < lifts.length; i++) {
            assertEquals(0f, lifts[i], 0.001f,
                    "第 " + i + " 字未染色，不应上浮，实际=" + lifts[i]);
        }
    }

    /** 染满的字必须升到顶点。 */
    @Test
    void 染满的字升到顶点() {
        float[] all = new float[LINE.length()];
        java.util.Arrays.fill(all, 1f);
        float[] lifts = FloatingLyricRenderer.computeLifts(
                LINE.length(), FONT_PX, all);

        assertNotNull(lifts);
        // 整行唱完时全部取满，不留半抬的尾巴。
        for (int i = 0; i < lifts.length; i++) {
            assertEquals(MAX_LIFT, lifts[i], 0.001f,
                    "第 " + i + " 字应已升到顶点，实际=" + lifts[i]);
        }
    }

    /** 上浮量必须与染色进度成比例：染一半就升一半。 */
    @Test
    void 上浮与染色进度成正比() {
        float[] lifts = FloatingLyricRenderer.computeLifts(
                LINE.length(), FONT_PX,
                progress(0f, 0.25f, 0.5f, 0.75f, 1f));

        assertNotNull(lifts);
        assertEquals(0f, lifts[0], 0.001f);
        assertEquals(MAX_LIFT * 0.25f, lifts[1], 0.01f);
        assertEquals(MAX_LIFT * 0.5f, lifts[2], 0.01f);
        assertEquals(MAX_LIFT * 0.75f, lifts[3], 0.01f);
        assertEquals(MAX_LIFT, lifts[4], 0.001f);
    }

    /** 已经染满的字不得回落。 */
    @Test
    void 染满后保持顶点() {
        float[] lifts = FloatingLyricRenderer.computeLifts(
                LINE.length(), FONT_PX, progress(1f, 1f, 0.6f));

        assertNotNull(lifts);
        assertEquals(MAX_LIFT, lifts[0], 0.001f);
        assertEquals(MAX_LIFT, lifts[1], 0.001f);
        assertTrue(lifts[2] < MAX_LIFT, "未染满的字应低于顶点");
    }

    /** 单字行不参与逐字抬升。 */
    @Test
    void 单字行不做逐字抬升() {
        assertNull(FloatingLyricRenderer.computeLifts(
                1, FONT_PX, progress(0f)),
                "单字行应返回 null 走整段绘制");
    }

    /** 行太长时不值得逐字绘制。 */
    @Test
    void 超长行不做逐字抬升() {
        int count = 40;
        float[] p = new float[count];
        assertNull(FloatingLyricRenderer.computeLifts(
                count, FONT_PX, p),
                "超长行应返回 null 走整段绘制");
    }

}
