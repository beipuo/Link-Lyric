package com.linklyric.core.protocol;

/**
 * 跨进程契约的版本与端点常量。
 *
 * <p>适配器（任意语言、任意进程）与 Minecraft 模组（Java，游戏 JVM）之间
 * 通过本机回环通信，因此 {@link #VERSION} 必须随载荷结构变化而递增。
 * 消费端遇到未知版本时应降级为「仅显示整行歌词」，而不是拒绝连接。
 */
public final class Protocol {

    /**
     * 当前载荷契约版本。
     *
     * <p>v2：引入 {@code sourceId} 与 {@code capabilities}，快照改为携带曲目身份对象。
     */
    public static final int VERSION = 2;

    /** 默认回环监听端口。模组起服务端，适配器作为客户端接入。 */
    public static final int DEFAULT_PORT = 35400;

    /** WebSocket 路径。刻意与具体播放器无关。 */
    public static final String WS_PATH = "/lyric";

    /** 单次载荷允许的最大字符数，防止畸形数据撑爆渲染层。 */
    public static final int MAX_PAYLOAD_CHARS = 4 * 1024 * 1024;

    /** 收到新锚点后允许的最大外推时长；超出即视为陈旧数据并静默隐藏。 */
    public static final long STALE_AFTER_MS = 30_000L;

    /** 命令等待回执的超时。超时后回滚界面的选中态。 */
    public static final long COMMAND_TIMEOUT_MS = 2_000L;

    private Protocol() {
    }
}
