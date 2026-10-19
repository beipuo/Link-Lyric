package com.linksaltmusic.core.protocol;

/**
 * 跨进程契约的版本与端点常量。
 *
 * <p>Salt Player 插件（Kotlin，宿主 JVM）与 Minecraft 模组（Java，游戏 JVM）
 * 是两个独立进程，因此 {@link #VERSION} 必须随载荷结构变化而递增。
 * 消费端遇到未知版本时应降级为「仅显示当前行」，而不是拒绝连接。
 */
public final class Protocol {

    /** 当前载荷契约版本。改变字段语义或删除字段时必须递增。 */
    public static final int VERSION = 1;

    /** 默认回环监听端口。插件与模组两侧必须一致，且须允许用户改配。 */
    public static final int DEFAULT_PORT = 35400;

    /** WebSocket 路径。 */
    public static final String WS_PATH = "/spw";

    /** 单次载荷允许的最大字符数，防止畸形数据撑爆渲染层。 */
    public static final int MAX_PAYLOAD_CHARS = 4 * 1024 * 1024;

    /** 收到新锚点后允许的最大外推时长；超出即视为陈旧数据。 */
    public static final long STALE_AFTER_MS = 30_000L;

    private Protocol() {
    }
}
