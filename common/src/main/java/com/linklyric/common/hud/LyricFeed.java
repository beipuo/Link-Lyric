package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;

/**
 * 悬浮渲染器所需的最小数据视图。
 *
 * <p>刻意不直接依赖 {@code BridgePlaybackSource}：渲染层只关心「有没有数据」，
 * 不该绑定到某一种传输方式。这样既能在单测里用替身验证排版，
 * 也为后续接入其它数据源留出空间。
 */
public interface LyricFeed {

    /** 数据源当前是否可用。false 时渲染层不应绘制任何内容。 */
    boolean isAlive();

    /** 当前播放位置的快照。 */
    PlayerSnapshot pollSnapshot();

    /** 当前曲目的歌词时间轴；无歌词时返回空时间轴。 */
    LyricTimeline lyric();

    /** 时间轴指纹，变化时渲染层需要重建缓存。 */
    long lyricSignature();
}
