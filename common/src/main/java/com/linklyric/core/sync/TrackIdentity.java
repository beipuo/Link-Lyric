package com.linklyric.core.sync;

/**
 * 曲目身份：用于把「正在播放什么」与「该显示哪份歌词」匹配起来的键。
 *
 * <p>时长是必需项而非附加项。同一首歌的专辑版、Live 版、Remix 版标题与艺术家
 * 完全相同，唯有 10~60 秒的时长差能区分——LRCLIB 的精确查询接口也把 duration
 * 作为一等参数。没有时长就只按标题匹配，错配率会高到不可接受。
 *
 * @param title      曲目标题
 * @param artist     艺术家
 * @param album      专辑，可空
 * @param durationMs 曲目时长（毫秒），0 表示未知
 */
public record TrackIdentity(String title, String artist, String album, long durationMs) {

    /** 默认时长容差。超过这个差值通常意味着是不同版本而非同一录音。 */
    public static final long DEFAULT_DURATION_TOLERANCE_MS = 2_000L;

    public TrackIdentity {
        title = normalize(title);
        artist = normalize(artist);
        album = normalize(album);
        durationMs = Math.max(0L, durationMs);
    }

    public static TrackIdentity of(String title, String artist, long durationMs) {
        return new TrackIdentity(title, artist, "", durationMs);
    }

    /** 标题为空即视为不可用于匹配，此时不应发起任何歌词查询。 */
    public boolean isUsable() {
        return !title.isEmpty();
    }

    public boolean hasDuration() {
        return durationMs > 0L;
    }

    /**
     * 判断两个身份是否指向同一录音。
     *
     * <p>标题与艺术家忽略大小写与首尾空白；时长双方都有值时必须在容差内。
     * 任一方缺时长则放宽该项，避免因数据源不报时长而完全无法匹配。
     */
    public boolean matches(TrackIdentity other, long toleranceMs) {
        if (other == null || !isUsable() || !other.isUsable()) {
            return false;
        }
        if (!title.equalsIgnoreCase(other.title)) {
            return false;
        }
        if (!artist.isEmpty() && !other.artist.isEmpty()
                && !artist.equalsIgnoreCase(other.artist)) {
            return false;
        }
        if (hasDuration() && other.hasDuration()) {
            return Math.abs(durationMs - other.durationMs) <= Math.max(0L, toleranceMs);
        }
        return true;
    }

    public boolean matches(TrackIdentity other) {
        return matches(other, DEFAULT_DURATION_TOLERANCE_MS);
    }

    /** 缓存键。不含专辑——同一录音在不同数据源上专辑字段经常缺失或不一致。 */
    public String cacheKey() {
        return (title + '\u0000' + artist + '\u0000' + durationMs).toLowerCase();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
