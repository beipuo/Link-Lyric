package com.linklyric.common.hud;

import com.linklyric.core.sync.PlaybackClock;

/**
 * 播放位置的帧级外推。
 *
 * <p>桥接端每秒才上报一次位置，直接采信会让高亮一秒一跳。
 * 这里保留一个本地时钟，用上报值校准它，再按帧读取，
 * 使染色进度在上报间隙内仍然连续推进。
 *
 * <p>三条约束：外推必须有上限，否则轮询滞后会让位置无界前冲；
 * 上限要随本地时间一起推进，否则追上后就停住等下一次上报；
 * 播放中不允许回退，否则高亮会闪烁。
 */
final class PositionTracker {

    /** 上报周期初值（毫秒）。 */
    static final long DEFAULT_PERIOD_MS = 1000L;

    /** 上报周期估计的合理范围。 */
    static final long MIN_PERIOD_MS = 150L;
    static final long MAX_PERIOD_MS = 4000L;

    /** 外推余量，覆盖上报抖动与网络延迟。 */
    static final long LEAD_MARGIN_MS = 400L;

    private final PlaybackClock clock = new PlaybackClock();

    private boolean primed;
    private boolean lastPlaying;

    /** 观测到的上报周期（毫秒），随实测自适应。 */
    private long uplinkPeriodMs = DEFAULT_PERIOD_MS;

    /** 上一次收到「位置变化」的时刻。 */
    private long lastReportChangeNanos;

    /** 上一次收到的上报位置，用于识别是否真的更新过。 */
    private long lastReportedMs = Long.MIN_VALUE;

    /** 上一帧使用的位置，用于保证播放中严格单调。 */
    private long lastRenderedMs;

    /**
     * 用最新快照校准时钟。
     *
     * @param reportedMs 桥接上报的位置
     * @param playing    是否正在播放
     * @param nowNanos   当前时刻
     */
    void calibrate(long reportedMs, boolean playing, long nowNanos) {
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
        boolean seek = primed && Math.abs(reportedMs - clock.positionMs()) > 1500L;
        if (!primed || seek) {
            clock.anchor(reportedMs, playing);
            primed = true;
        }
        if (playing != lastPlaying) {
            clock.anchor(reportedMs, playing);
            lastPlaying = playing;
        }
    }

    /**
     * 取该帧使用的播放位置。
     *
     * <p>上限锚在「收到该上报值的时刻」上，并随本地时间一起推进，
     * 而不是固定在 {@code 上报值 + 余量}：后者一旦追上就再无前进余地，
     * 高亮会停住等下一次上报。
     */
    long position(long reportedMs, long nowNanos) {
        long extrapolated = clock.positionMs();
        long lead = uplinkPeriodMs + LEAD_MARGIN_MS;
        long ceiling = reportedMs + lead + msSinceReport(nowNanos);
        long result = extrapolated;
        if (extrapolated > ceiling) {
            result = ceiling;
            clock.anchor(ceiling, lastPlaying);
        } else if (extrapolated < reportedMs) {
            result = reportedMs;
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

    private long msSinceReport(long nowNanos) {
        if (lastReportChangeNanos == 0L) {
            return 0L;
        }
        long elapsed = (nowNanos - lastReportChangeNanos) / 1_000_000L;
        return Math.max(0L, Math.min(elapsed, MAX_PERIOD_MS + LEAD_MARGIN_MS));
    }
}
