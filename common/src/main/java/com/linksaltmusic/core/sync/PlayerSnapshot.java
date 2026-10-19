package com.linksaltmusic.core.sync;

/**
 * 一次播放状态的完整快照，对应宿主侧的一次「曲目变更」事件。
 *
 * <p>只承载事实，不承载推导结果：当前行索引、逐字进度等一律由
 * {@code PlaybackClock} 与 {@code LyricTimeline} 在本地算出，避免两份真相。
 *
 * @param title        曲目标题
 * @param artist       艺术家
 * @param album        专辑
 * @param durationMs   曲目总时长（毫秒）
 * @param positionMs   发送该快照时的播放位置（毫秒）
 * @param playing      是否正在播放
 * @param sequence     单调递增序号，用于丢弃乱序或过期的快照
 * @param sentAtNanos  发送端的本地时钟（纳秒），仅用于诊断，不参与外推
 */
public record PlayerSnapshot(
        String title,
        String artist,
        String album,
        long durationMs,
        long positionMs,
        boolean playing,
        long sequence,
        long sentAtNanos) {

    public PlayerSnapshot {
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        album = album == null ? "" : album;
        durationMs = Math.max(0L, durationMs);
        positionMs = Math.max(0L, positionMs);
    }

    /** 是否携带可展示的曲目信息。 */
    public boolean hasTrack() {
        return !title.isEmpty() || !artist.isEmpty();
    }
}
