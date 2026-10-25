package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位置外推的四条约束。
 *
 * <h2>为什么必须注入时刻</h2>
 *
 * <p>这套逻辑此前一条测试都没有，原因是时钟内部自己读 {@code System.nanoTime()}，
 * 测试没法让「时间过去 5 秒」这件事发生——只能测到测试循环自己耗掉的那几微秒，
 * 于是任何关于推进、上限、回退的断言都形同虚设。
 *
 * <p>改为时刻由调用方给出后，这里用显式时间轴把行为钉死。
 */
class PositionTrackerTest {

    private static final long MS = 1_000_000L;

    /** 一枚按毫秒推进的时间轴，避免测试里散落纳秒换算。 */
    private long nowMs;

    private long now() {
        return nowMs * MS;
    }

    /** 建立「已校准、正在播放」的基线，返回追踪器。 */
    private PositionTracker primed(long startMs) {
        PositionTracker t = new PositionTracker();
        t.calibrate(startMs, true, now());
        return t;
    }

    /**
     * 上报间隙内必须平滑推进。
     *
     * <p>这是外推存在的全部理由：轮询每秒一次，若直接采信上报值，
     * 高亮一秒才动一格。
     */
    @Test
    void 上报间隙内持续前进() {
        nowMs = 0L;
        PositionTracker t = primed(10_000L);

        long previous = -1L;
        for (int i = 0; i < 10; i++) {
            nowMs += 16L;
            long pos = t.position(10_000L, now());
            assertTrue(pos > previous,
                    "第 " + i + " 帧位置应仍在前进，上一帧=" + previous + " 本帧=" + pos);
            previous = pos;
        }
        // 160ms 过去了，位置应推进约 160ms（允许外推上限的微小裁剪）
        assertTrue(previous >= 10_100L,
                "160ms 内应推进约 160ms，实际到 " + previous);
    }

    /**
     * 外推不得无界前冲。
     *
     * <p>数据源卡住（上报值长时间不变）时，位置必须被上限挡住，
     * 否则恢复瞬间歌词会一次性跳过好几行。
     */
    @Test
    void 上报卡住时位置不无界前冲() {
        nowMs = 0L;
        PositionTracker t = primed(10_000L);

        // 上报值一直停在 10000，本地推进 30 秒
        nowMs += 30_000L;
        long pos = t.position(10_000L, now());

        long maxReasonable = 10_000L
                + PositionTracker.MAX_PERIOD_MS + PositionTracker.LEAD_MARGIN_MS * 2;
        assertTrue(pos <= maxReasonable,
                "上报卡住 30 秒后位置应被上限挡住，实际上限附近=" + maxReasonable + " 实际=" + pos);
    }

    /**
     * 上限必须随本地时间推进，不能钉死在「上报值 + 余量」。
     *
     * <p>钉死的话一旦追上就再无前进余地，高亮会停住等下一次上报——
     * 这正是位置外推最常见的退化形态。
     */
    @Test
    void 上限随时间推进而非固定() {
        nowMs = 0L;
        PositionTracker t = primed(10_000L);
        nowMs += 16L;
        long early = t.position(10_000L, now());

        nowMs += 500L;
        long later = t.position(10_000L, now());

        assertTrue(later > early,
                "同一上报值下，500ms 后的位置应高于 16ms 时，早=" + early + " 晚=" + later);
    }

    /**
     * 上报值跳到本地推算前面时，时钟必须重锚。
     *
     * <p>此前这里只把返回值改成上报值、不动时钟，于是外推永远落在上报值下方，
     * 每帧都被夹到同一个数——帧间完全不平滑，正是这套代码要避免的现象。
     */
    @Test
    void 上报值超前时重锚并保持平滑() {
        nowMs = 0L;
        PositionTracker t = primed(10_000L);

        // 上报突然跳到 12500（远超 1500ms 阈值之外会走 seek 分支，
        // 这里刻意取阈值内的 1200ms，确保走的是「超前」而非 seek）
        long reported = 11_200L;
        t.calibrate(reported, true, now());
        long atJump = t.position(reported, now());

        // 跳变之后继续按帧推进，位置必须继续增长
        nowMs += 16L;
        long next = t.position(reported, now());
        assertTrue(next >= atJump,
                "跳变后仍应前进或保持，跳变时=" + atJump + " 下一帧=" + next);

        nowMs += 160L;
        long later = t.position(reported, now());
        assertTrue(later > atJump,
                "跳变后 160ms 应已推进，跳变时=" + atJump + " 160ms 后=" + later);
    }

    /**
     * 播放中的小幅回退被吃掉，大跨度回落放行。
     *
     * <p>小幅回退来自上报抖动，放行会让高亮闪烁；
     * 大跨度回落是切行、seek、换曲，吃掉就永久卡在旧行上。
     */
    @Test
    void 小幅回退被吸收大跨度回落放行() {
        nowMs = 0L;
        PositionTracker t = primed(100_000L);
        nowMs += 100L;
        long high = t.position(100_000L, now());
        assertTrue(high > 100_000L, "基线应已推进");

        // 小幅回退：上报值只回落 200ms，在 lead 之内
        nowMs += 16L;
        long small = t.position(high - 200L, now());
        assertTrue(small >= high,
                "小幅回退应被吸收，不应低于上一帧 " + high + "，实际=" + small);

        // 大跨度回落：换曲/seek，回落 10 秒
        nowMs += 16L;
        long big = t.position(high - 10_000L, now());
        assertTrue(big < high,
                "大跨度回落必须放行，不应卡在 " + high + "，实际=" + big);
    }

    /**
     * 暂停后位置冻结：暂停期间外推必须停止。
     *
     * <p>否则暂停时高亮会继续往前走，与音频脱节。
     */
    @Test
    void 暂停时位置冻结() {
        nowMs = 0L;
        PositionTracker t = primed(10_000L);
        nowMs += 100L;
        t.position(10_000L, now());

        t.calibrate(10_100L, false, now());
        long paused = t.position(10_100L, now());

        nowMs += 5_000L;
        long stillPaused = t.position(10_100L, now());

        assertEquals(paused, stillPaused,
                "暂停期间位置不应推进，暂停时=" + paused + " 5 秒后=" + stillPaused);
    }

    /**
     * 上报周期按实测自适应。
     *
     * <p>周期估计偏大，外推上限就偏松，位置会跑到音频前面；
     * 偏小则每轮都被削，位置勉强跟住。必须跟着实测走。
     */
    @Test
    void 上报周期按实测自适应() {
        nowMs = 0L;
        PositionTracker t = primed(0L);

        // 每 300ms 上报一次，共十轮
        long reported = 0L;
        for (int i = 0; i < 10; i++) {
            nowMs += 300L;
            reported += 300L;
            t.calibrate(reported, true, now());
            t.position(reported, now());
        }

        // 周期应已收敛到 300 附近：此后停一次上报、只推进 300ms，
        // 位置仍应能从上报值附近继续前进（上限 = 300 + 400）
        nowMs += 300L;
        long pos = t.position(reported, now());
        assertTrue(pos >= reported,
                "周期自适应后不应落后于上报值，上报=" + reported + " 实际=" + pos);
    }
}
