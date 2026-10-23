package com.linklyric.core.source;

import com.linklyric.core.capability.Capability;
import com.linklyric.core.capability.CapabilitySet;

/**
 * 各数据源的能力预设。
 *
 * <p>把这些常量集中在此，是为了让「能力差异」成为可审查的事实而不是散落的判断。
 * 每一项都来自对各数据源实际接口的核对，不是估算。
 */
public final class SourceProfiles {

    /**
     * Salt Player 插件：能力最完整的数据源。
     *
     * <p>逐字时间轴来自歌词源（TTML），队列与按索引播放取决于 WorkshopApi 是否暴露；
     * 后者尚未验证，因此暂不声明 {@link Capability#PLAY_BY_INDEX}。
     */
    public static final CapabilitySet SALT_PLAYER = CapabilitySet.of(
            Capability.TRACK_METADATA,
            Capability.TRACK_DURATION,
            Capability.PLAYBACK_POSITION,
            Capability.INLINE_LYRIC,
            Capability.WORD_LEVEL_LYRIC,
            Capability.QUEUE,
            Capability.SEEK,
            Capability.TRANSPORT,
            Capability.ARTWORK);

    /**
     * Windows SMTC：覆盖面最广，但深度最浅。
     *
     * <p>能拿到 Position 与 LastUpdatedTime（可做锚点外推），但没有歌词、
     * 没有队列、也不能按索引播放。歌词须由外部歌词源补齐。
     * 另外部分播放器把 Position 恒报 0，因此 {@link Capability#PLAYBACK_POSITION}
     * 实际可用性需在运行时按数据判断，不能盲信。
     */
    public static final CapabilitySet WINDOWS_SMTC = CapabilitySet.of(
            Capability.TRACK_METADATA,
            Capability.TRACK_DURATION,
            Capability.PLAYBACK_POSITION,
            Capability.TRANSPORT,
            Capability.ARTWORK);

    /** MPD：纯 Java 可实现，队列与按索引播放天然支持，但无歌词。 */
    public static final CapabilitySet MPD = CapabilitySet.of(
            Capability.TRACK_METADATA,
            Capability.TRACK_DURATION,
            Capability.PLAYBACK_POSITION,
            Capability.QUEUE,
            Capability.PLAY_BY_INDEX,
            Capability.SEEK,
            Capability.TRANSPORT);

    /** 本地歌词文件：只提供歌词，不提供任何播放事实。用于手工放歌词的场景。 */
    public static final CapabilitySet LOCAL_LYRIC_FILE = CapabilitySet.of(
            Capability.INLINE_LYRIC,
            Capability.WORD_LEVEL_LYRIC);

    private SourceProfiles() {
    }
}
