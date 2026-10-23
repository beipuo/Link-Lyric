package com.linklyric.core.source;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.sync.PlayerSnapshot;

/**
 * 播放数据源。
 *
 * <p>这是通用化的核心抽象：Salt Player 插件、Windows SMTC、MPD、本地 LRC 播报
 * 都只是它的不同实现。实现方负责把自家协议翻译成 {@link PlayerSnapshot}，
 * 内核不关心数据从哪来。
 *
 * <p>实现必须做到：{@link #pollSnapshot()} 无副作用且可高频调用；
 * 任何异常都不得向外抛出，只能返回空快照——数据源断连不该让游戏崩溃。
 */
public interface PlaybackSource {

    /** 稳定的数据源标识，如 {@code salt-player} / {@code windows-smtc} / {@code mpd}。 */
    String id();

    /** 展示名，用于配置界面。 */
    String displayName();

    /**
     * 本次会话的能力声明。
     *
     * <p>必须在连接建立后立即确定，之后不变。中途改变能力会让 UI 反复重建。
     */
    CapabilitySet capabilities();

    /** 当前快照。无活动会话时返回 {@code hasTrack() == false} 的快照，而非 null。 */
    PlayerSnapshot pollSnapshot();

    /** 数据源是否仍可用。false 时内核应停止轮询并清除显示。 */
    boolean isAlive();
}
