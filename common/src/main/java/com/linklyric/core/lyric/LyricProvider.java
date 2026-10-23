package com.linklyric.core.lyric;

import com.linklyric.core.sync.TrackIdentity;

/**
 * 歌词提供方。
 *
 * <p>与数据源彻底解耦：播放器不必自带歌词，歌词也不必来自播放器。
 * LRCLIB、AMLL TTML 库、本地文件、用户粘贴都只是它的不同实现。
 * 这条拆分让「播放器不支持歌词」不再是功能缺失。
 *
 * <p>实现必须自己处理网络超时与重试，且不得阻塞调用线程——
 * 内核只会在异步任务里调用它。
 */
public interface LyricProvider {

    /** 稳定标识，用于缓存归属与错误提示。 */
    String id();

    /** 展示名。 */
    String displayName();

    /**
     * 是否提供逐字时间轴。
     *
     * <p>false 时内核会把整行渲染为一个覆盖全行时长的单元，
     * 渲染层无需特判。
     */
    boolean supportsWordLevel();

    /**
     * 查询歌词。
     *
     * <p>无结果时返回 {@link LyricTimeline#empty()} 而非 null，
     * 让调用方无需分支处理。
     */
    LyricTimeline fetch(TrackIdentity track);
}
