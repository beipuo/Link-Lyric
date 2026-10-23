package com.linklyric.core.sync;

import com.linklyric.core.capability.CapabilitySet;

/**
 * 一次播放状态的完整快照，对应数据源的一次「曲目变更」事件。
 *
 * <p>只承载事实，不承载推导结果：当前行索引、逐字进度等一律由
 * {@link PlaybackClock} 与 {@code LyricTimeline} 在本地算出，避免两份真相。
 *
 * @param sourceId     产生该快照的数据源标识，用于配置归属与问题定位
 * @param track        曲目身份，歌词匹配的唯一依据
 * @param positionMs   发送该快照时的播放位置（毫秒）
 * @param playing      是否正在播放
 * @param sequence     单调递增序号，用于丢弃乱序或过期的快照
 * @param sentAtNanos  发送端的本地时钟（纳秒），仅用于诊断，不参与外推
 * @param capabilities 该数据源本次会话的能力声明，决定 UI 显示哪些元素
 */
public record PlayerSnapshot(
        String sourceId,
        TrackIdentity track,
        long positionMs,
        boolean playing,
        long sequence,
        long sentAtNanos,
        CapabilitySet capabilities) {

    public PlayerSnapshot {
        sourceId = sourceId == null ? "" : sourceId;
        track = track == null ? TrackIdentity.of("", "", 0L) : track;
        positionMs = Math.max(0L, positionMs);
        capabilities = capabilities == null ? CapabilitySet.empty() : capabilities;
    }

    /** 是否携带可展示的曲目信息。空快照表示「没有活动会话」，UI 应整体隐藏。 */
    public boolean hasTrack() {
        return track.isUsable();
    }

    public boolean hasSource() {
        return !sourceId.isEmpty();
    }

    /** 是否可用于驱动逐字动画。缺进度时只能整行切换。 */
    public boolean canDrivePreciseProgress() {
        return capabilities.has(com.linklyric.core.capability.Capability.PLAYBACK_POSITION);
    }
}
