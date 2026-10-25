package com.linklyric.common.hud;

import com.linklyric.core.sync.PlaybackClock;

/**
 * 播放位置的帧级外推。
 *
 * <p>桥接端每 {@value #DEFAULT_PERIOD_MS} 毫秒才上报一次位置，直接采信会让
 * 高亮一秒一跳。这里保留一个本地时钟，用上报值校准它，再按帧读取，
 * 使染色进度在上报间隙内仍然连续推进。
 *
 * <p>四条约束：外推必须有上限，否则轮询滞后会让位置无界前冲；
 * 上限要随本地时间一起推进，否则追上后就停住等下一次上报；
 * 播放中不允许小幅回退，否则高亮会闪烁；
 * 大跨度回落必须放行，否则切行、seek、换曲后会永久卡在旧行上。
 *
 * <h2>时间基准必须单一</h2>
 *
 * <p>本类所有涉及「现在」的判断都只用调用方传入的 {@code nowNanos}，
 * 时钟内部不自己读一次。两处各读一次的话，差值里会混进两次读取之间的耗时，
 * 测不出真实间隔，单测也无法模拟「已经过了 5 秒」。
 */
final class PositionTracker {

    /** 上报周期初值（毫秒）。 */
    static final long DEFAULT_PERIOD_MS = 1000L;

    /** 上报周期估计的合理范围。 */
    static final long MIN_PERIOD_MS = 150L;
    static final long MAX_PERIOD_MS = 4000L;

    /** 外推余量，覆盖上报抖动与网络延迟。 */
    static final long LEAD_MARGIN_MS = 400L;

    /** 判定「已经 seek 过」的位置差阈值。超过即重锚而不是硬拉进度。 */
    static final long SEEK_THRESHOLD_MS = 1500L;

    private final PlaybackClock clock = new PlaybackClock();

    private boolean primed;

    /**
     * 上一帧时是否在播放。
     *
     * <p>名字里带 last 是因为它只在 {@link #position} 里被读取，
     * 由 {@link #calibrate} 更新——两者每帧成对出现，中间不会插入别的快照。
     */
    private boolean lastPlaying;

    /** 观测到的上报周期（毫秒），随实测自适应。 */
    private long uplinkPeriodMs = DEFAULT_PERIOD_MS;

    /** 上一次收到「位置变化」的时刻。 */
    private long lastReportChangeNanos;

    /** 上一次收到的上报位置，用于识别是否真的更新过。 */
    private long lastReportedMs = Long.MIN_VALUE;

    /** 上一帧使用的位置，用于保证播放中不回退。 */
    private long lastRenderedMs;

    /**
     * 用最新快照校准时钟。
     *
     * @param reportedMs 桥接上报的位置
     * @param playing    是否正在播放
     * @param nowNanos   当前时刻
     */
    void calibrate(long reportedMs, boolean playing, long nowNanos) {
        // 上报值变了才更新「变化时刻」，据此估计真实上报周期。
        if (lastReportedMs == Long.MIN_VALUE || reportedMs != lastReportedMs) {
            if (lastReportedMs != Long.MIN_VALUE && lastReportChangeNanos != 0L) {
                long observed = (nowNanos - lastReportChangeNanos) / 1_000_000L;
                if (observed >= MIN_PERIOD_MS && observed <= MAX_PERIOD_MS) {
                    uplinkPeriodMs = observed;
                }
            }
            lastReportedMs = reportedMs;
            lastReportChangeNanos = nowNanos;
        }
        boolean seek = primed && Math.abs(reportedMs - clock.positionMs(nowNanos)) > SEEK_THRESHOLD_MS;
        if (!primed || seek || playing != lastPlaying) {
            clock.anchor(reportedMs, playing, nowNanos);
            primed = true;
        }
        lastPlaying = playing;
    }

    /**
     * 取该帧使用的播放位置。
     *
     * <p>上限锚在「收到该上报值的时刻」上，并随本地时间一起推进，
     * 而不是固定在 {@code 上报值 + 余量}：后者一旦追上就再无前进余地，
     * 高亮会停住等下一次上报。
     */
    long position(long reportedMs, long nowNanos) {
        long extrapolated = clock.positionMs(nowNanos);
        long lead = uplinkPeriodMs + LEAD_MARGIN_MS;
        long ceiling = reportedMs + lead + msSinceReport(nowNanos);
        long result = extrapolated;
        if (extrapolated > ceiling) {
            // 外推跑过了上限：钉在上限，并把时钟重锚到上限，
            // 否则下一帧外推仍从超前的锚点算起，每帧都会被削一次。
            result = ceiling;
            clock.anchor(ceiling, lastPlaying, nowNanos);
        } else if (extrapolated < reportedMs) {
            // 上报值跳到了本地推算前面（典型成因是上报周期被低估）。
            // 必须重锚：只把返回值改成上报值而不同步时钟的话，
            // 外推会一直落在上报值下方，下一帧又被夹到上报值，
            // 表现就是位置长期贴着上报值走、帧间完全不平滑。
            result = reportedMs;
            clock.anchor(reportedMs, lastPlaying, nowNanos);
        }

        // 播放中位置只允许前进，但大跨度回落（切行、seek、换曲）必须放行，
        // 否则会永久卡在旧行上。
        if (lastPlaying && result < lastRenderedMs) {
            long regression = lastRenderedMs - result;
            if (regression <= lead) {
                return lastRenderedMs;
            }
        }
        lastRenderedMs = Math.max(0L, result);
        return lastRenderedMs;
    }

    /**
     * 距上次上报变化过去了多久。
     *
     * <p>封顶在 {@code MAX_PERIOD_MS + LEAD_MARGIN_MS}：数据源卡住时
     * 上限不该无限上抬，否则一旦恢复，位置会一次性跳出很远。
     */
    private long msSinceReport(long nowNanos) {
        if (lastReportChangeNanos == 0L) {
            return 0L;
        }
        long elapsed = (nowNanos - lastReportChangeNanos) / 1_000_000L;
        return Math.max(0L, Math.min(elapsed, MAX_PERIOD_MS + LEAD_MARGIN_MS));
    }
}
