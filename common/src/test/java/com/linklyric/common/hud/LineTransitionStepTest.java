package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换行位移必须跟着用户设定的行距走。
 *
 * <h2>为什么值得单独测</h2>
 *
 * <p>位移此前写死成 {@code LyricGeometry.LINE_STEP}（行高 9 + 行距 2 = 11），
 * 而排版用的是用户可调的 {@code lineGapPx}。默认行距恰好是 2，
 * 两者相等，所以这个错在任何默认配置下都看不出来。
 *
 * <p>一旦把行距调到 12，动画会把整块往上推 11 像素，收尾再瞬间补上第 12 像素，
 * 换行末尾闪一次一像素的跳格；调小到 0 时则多推 2 像素再弹回。
 * 这类错误在界面上稍纵即逝，只能靠数值钉住。
 */
class LineTransitionStepTest {

    /** 换行刚开始时，位移应等于一整步，且随行距变化。 */
    @Test
    void 起步位移跟随行距() {
        for (int gap = LyricStyleConfigGap.MIN; gap <= LyricStyleConfigGap.MAX; gap++) {
            int step = LyricGeometry.LINE_HEIGHT + gap;
            LineTransition t = new LineTransition();
            // 首次出画不算换行，先让状态机就位
            t.update(0, 0L, 700L);
            // 再换到下一行，动画从 0 起
            t.update(1, 0L, 700L);

            int shift = t.blockShift(700L, step);
            assertEquals(step, shift,
                    "行距 " + gap + " 时起步位移应为一整步 " + step + "，实际=" + shift);
        }
    }

    /** 过渡结束时位移必须归零，否则会残留偏移。 */
    @Test
    void 结束时归零() {
        for (int gap = LyricStyleConfigGap.MIN; gap <= LyricStyleConfigGap.MAX; gap++) {
            int step = LyricGeometry.LINE_HEIGHT + gap;
            LineTransition t = new LineTransition();
            t.update(0, 0L, 700L);
            t.update(1, 0L, 700L);
            // 推满 700ms
            t.update(1, 800_000_000L, 700L);

            assertEquals(0, t.blockShift(700L, step),
                    "行距 " + gap + " 时过渡结束应完全就位");
        }
    }

    /**
     * 位移不得超出步长。
     *
     * <p>曲线两端导数趋零，中途也不会过冲；若出现超调，
     * 整块会先越过目标位置再回来，观感是「弹一下」。
     */
    @Test
    void 位移不过冲() {
        int step = LyricGeometry.LINE_HEIGHT + 5;
        LineTransition t = new LineTransition();
        t.update(0, 0L, 700L);
        t.update(1, 0L, 700L);

        for (int i = 1; i <= 70; i++) {
            t.update(1, 10_000_000L, 700L);
            int shift = t.blockShift(700L, step);
            assertTrue(shift >= 0 && shift <= step,
                    "第 " + i + " 次推进位移应在 [0," + step + "]，实际=" + shift);
        }
    }

    /** 行距的上下限以样式配置为准，测试不另立一套数字。 */
    private static final class LyricStyleConfigGap {
        static final int MIN = com.linklyric.common.style.LyricStyleConfig.MIN_LINE_GAP;
        static final int MAX = com.linklyric.common.style.LyricStyleConfig.MAX_LINE_GAP;
    }
}
