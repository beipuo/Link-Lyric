package com.linklyric.core.capability;

/**
 * 数据源能力标记。
 *
 * <p>不同数据源能提供的功能差异极大：Salt Player 插件能给逐字时间轴，
 * Windows SMTC 只能给元数据和是否在播放，本地 LRC 文件连进度都没有。
 * 把差异显式建模成能力集合，渲染层与 UI 按能力降级，避免代码里散落
 * {@code if (source == ...)} 这种一旦加第三个数据源就会失控的判断。
 *
 * <p>**能力是「可能提供」的声明，不是「当前数据源已提供」的事实。**
 * 具体到 Salt Player：其插件 API 只暴露播放 / 暂停 / 上一首 / 下一首 /
 * 跳转，**没有队列访问**，因此 {@link #QUEUE} 与 {@link #PLAY_BY_INDEX}
 * 对它是恒为 ✗ 的（见 README 的能力表）。这两个常量保留，是给将来
 * 真能读到队列的数据源用的——声明式降级的前提是枚举本身完整。
 */
public enum Capability {

    /** 能提供曲目标题 / 艺术家 / 专辑。几乎所有数据源都具备。 */
    TRACK_METADATA,

    /** 能提供当前播放进度。缺失时歌词只能按墙钟推进，必然漂移。 */
    PLAYBACK_POSITION,

    /** 能提供曲目总时长。歌词匹配需要它来区分 Live / Remix / 伴奏版本。 */
    TRACK_DURATION,

    /** 数据源自带歌词。缺失时由外部歌词源补齐。 */
    INLINE_LYRIC,

    /** 歌词带逐字时间轴。缺失时退化为整行高亮。 */
    WORD_LEVEL_LYRIC,

    /** 能提供播放队列。缺失时歌单列表应整体隐藏，而不是显示空列表。 */
    QUEUE,

    /** 能按队列索引直接播放。缺失时「点击歌单某一首」须降级为连续 next。 */
    PLAY_BY_INDEX,

    /** 能跳转到指定位置。 */
    SEEK,

    /** 能播放 / 暂停 / 上一首 / 下一首。 */
    TRANSPORT,

    /** 能提供封面图资源。 */
    ARTWORK
}
