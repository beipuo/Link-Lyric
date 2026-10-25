package com.linklyric.core.protocol;

/**
 * 跨进程契约的版本与端点常量。
 *
 * <p>适配器（任意语言、任意进程）与 Minecraft 模组（Java，游戏 JVM）之间
 * 通过本机回环通信，因此 {@link #VERSION} 必须随载荷结构变化而递增。
 *
 * <h2>这里只放真正被引用的常量</h2>
 *
 * <p>曾有三个常量没有任何消费者，却都在注释里承诺了具体行为：
 * {@code STALE_AFTER_MS} 声称「超出即视为陈旧数据并静默隐藏」，
 * {@code COMMAND_TIMEOUT_MS} 声称「超时后回滚界面的选中态」。
 * 两者对应的机制都不存在——断连由连续失败次数判定，
 * 指令则是单向的、根本没有回执通道。
 *
 * <p>留着它们的代价是误导：读代码的人会以为陈旧数据已被处理，
 * 于是不去补，也不会怀疑那一块。删除需要时再按真实语义加回来。
 */
public final class Protocol {

    /**
     * 当前载荷契约版本。
     *
     * <p>v2：引入 {@code sourceId} 与 {@code capabilities}，快照改为携带曲目身份对象。
     */
    public static final int VERSION = 2;

    /** 默认回环监听端口。适配器起服务端，模组作为客户端轮询。 */
    public static final int DEFAULT_PORT = 35400;

    /**
     * 轮询路径。刻意与具体播放器无关。
     *
     * <p>名字里的 {@code WS} 是历史遗留：早期设计走 WebSocket 推送，
     * 实际实现是 HTTP 轮询（{@code BridgePlaybackSource} 每 200ms 拉一次）。
     * 常量名暂不改，避免与适配器侧已发布的字符串产生无谓的版本差异；
     * 但不要据此以为这里有长连接。
     *
     * <p>消费端必须引用本常量而不是另抄一份字符串：
     * 此前 {@code BridgePlaybackSource} 里写死的是同一个字符串字面量，
     * 于是适配器侧改端点时，改这里不会有任何效果。
     */
    public static final String WS_PATH = "/lyric";

    /** 单次载荷允许的最大字符数，防止畸形数据撑爆渲染层。 */
    public static final int MAX_PAYLOAD_CHARS = 4 * 1024 * 1024;

    private Protocol() {
    }
}
