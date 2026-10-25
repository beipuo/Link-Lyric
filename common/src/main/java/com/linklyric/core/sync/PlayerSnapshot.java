package com.linklyric.core.sync;

import com.linklyric.core.capability.CapabilitySet;

/**
 * 一次播放状态的完整快照，对应数据源的一次「曲目变更」事件。
 *
 * <p>只承载事实，不承载推导结果：当前行索引、逐字进度等一律由
 * {@code PositionTracker} 与 {@code LyricTimeline} 在本地算出，避免两份真相。
 *
 * @param sourceId     产生该快照的数据源标识，用于配置归属与问题定位
 * @param track        曲目身份，歌词匹配的唯一依据
 * @param positionMs   发送该快照时的播放位置（毫秒）
 * @param playing      是否正在播放
 * @param sequence     适配器侧单调递增的序号，仅用于诊断与日志
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

    /**
     * 序号为什么**不**参与乱序判定。
     *
     * <p>注释曾写「用于丢弃乱序或过期的快照」，但全代码库没有任何地方比较它。
     * 这不只是文档与实现不符，更要紧的是：这里确实不需要那个判定。
     * 轮询是单线程串行的——同一个 worker 依次取、依次解析、依次写入原子引用，
     * 结构上不可能出现旧快照覆盖新快照。加一个永远不会触发的比较分支，
     * 只会让人以为存在并发写入，从而在别处做出错误推断。
     *
     * <p>如果将来做了并行抓取或多数据源合并，这里才是应该加判定的地方。
     */

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
