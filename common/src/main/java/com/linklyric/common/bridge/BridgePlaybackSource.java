package com.linklyric.common.bridge;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.protocol.Protocol;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Link MC 插件的回环 HTTP 数据源。
 *
 * <p>与渲染线程的关系：{@link #pollSnapshot()} 被每帧调用，因此**绝不能**发起网络请求。
 * 实际请求由内部后台线程按固定间隔执行，结果写入原子引用；
 * {@code pollSnapshot()} 只读该引用，代价为一次 volatile 读。
 *
 * <p>断连语义：连续失败达到阈值才判定为不可用并清空显示，
 * 避免插件重载那几百毫秒里歌词闪烁消失。
 */
public final class BridgePlaybackSource implements com.linklyric.common.hud.LyricFeed {

    public static final String SOURCE_ID = "salt-player";

    /** 轮询间隔。200ms 足以让歌词跟随，又不会让本机环回产生可感负载。 */
    private static final long POLL_INTERVAL_MS = 200L;

    /** 请求超时。本机回环若 500ms 无响应，基本可判定插件已停。 */
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final int READ_TIMEOUT_MS = 800;

    /** 连续失败多少次后判定断连。按 200ms 间隔约合 1.2 秒。 */
    private static final int FAILURE_THRESHOLD = 6;

    private final String baseUrl;
    private final String sourceId;
    private final String displayName;

    private final AtomicReference<PlayerSnapshot> latest = new AtomicReference<>();
    private final AtomicReference<LyricTimeline> latestLyric = new AtomicReference<>(LyricTimeline.empty());
    private final AtomicReference<CapabilitySet> capabilities = new AtomicReference<>(CapabilitySet.empty());

    private volatile boolean alive;
    /** 用户开关；与连接状态分离。 */
    private volatile boolean enabled = true;
    private volatile int consecutiveFailures;
    private volatile int lyricSignature;
    private volatile boolean started;
    private volatile boolean stopRequested;
    private Thread worker;

    public BridgePlaybackSource(String host, int port) {
        this(host, port, SOURCE_ID, "Salt Player");
    }

    public BridgePlaybackSource(String host, int port, String sourceId, String displayName) {
        this.baseUrl = "http://" + host + ":" + port;
        this.sourceId = sourceId;
        this.displayName = displayName;
    }

    @Override
    public String id() {
        return sourceId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    /** 本次会话的能力声明；由握手快照解析得出，渲染层按此决定显示哪些元素。 */
    public CapabilitySet capabilities() {
        return capabilities.get();
    }

    @Override
    public PlayerSnapshot pollSnapshot() {
        PlayerSnapshot snapshot = latest.get();
        if (snapshot != null) {
            return snapshot;
        }
        // 尚无任何成功响应：返回空快照，UI 整体隐藏
        return new PlayerSnapshot(
                sourceId, TrackIdentity.of("", "", 0L), 0L, false, 0L, System.nanoTime(),
                capabilities.get());
    }

    /** 最近一次成功解析的歌词时间轴。歌词与快照分开取，避免每帧复制整份歌词。 */
    public LyricTimeline lyric() {
        return latestLyric.get();
    }

    /** 歌词时间轴的指纹，变化时调用方才需要重建渲染缓存。 */
    public long lyricSignature() {
        return lyricSignature;
    }

    @Override
    public boolean isAlive() {
        // 用户禁用时对外表现为「不可用」，渲染器据此停止出画；
        // 但后台线程仍在轮询，重新启用立刻有数据。
        return alive && enabled;
    }

    /**
     * 用户是否启用本数据源。
     *
     * <p>禁用后 {@link #isAlive()} 返回 false，渲染器随之停止出画；
     * 但轮询线程继续运行，因此重新启用时无需等待重连。
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** 启动后台轮询。重复调用无副作用。 */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        stopRequested = false;
        Thread thread = new Thread(this::loop, "link-lyric-bridge");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public void stop() {
        stopRequested = true;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        worker = null;
        started = false;
        alive = false;
        latest.set(null);
        latestLyric.set(LyricTimeline.empty());
    }

    private void loop() {
        while (!stopRequested) {
            try {
                pollOnce();
            } catch (Throwable t) {
                // 任何异常都不得终止轮询线程，也不得冒到游戏主线程
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() {
        String body = get("/lyric");
        if (body == null) {
            onFailure();
            return;
        }
        consecutiveFailures = 0;
        alive = true;

        long now = System.nanoTime();
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(body, sourceId, now);
        capabilities.set(snapshot.capabilities());
        latest.set(snapshot);

        // 歌词指纹必须只随歌词内容变化。
        //
        // 这里最初用的是 seq，但 seq 每次位置上报都会递增（每秒一次），
        // 结果歌词时间轴每秒被重解析一次，渲染层的换行状态因此被反复重置。
        // 副作用是高亮每帧都被直接赋成目标值、跳过平滑推进，
        // 观感就是「整行瞬间变色」而不是连续染色。
        // 改用歌词 JSON 的哈希作为指纹后，只有真正换曲或换歌词才会重建。
        int contentSignature = lyricContentSignature(body);
        if (contentSignature != lyricSignature) {
            LyricTimeline timeline = SnapshotParser.parseLyric(body);
            latestLyric.set(timeline);
            lyricSignature = contentSignature;
        }
    }

    /**
     * 提取歌词片段的稳定指纹；歌词不变时结果恒定。
     *
     * <p>只对 {@code "lyric"} 的值做哈希，必须精确截到该对象的右花括号。
     * 若截到响应末尾，会把 {@code positionMs}、{@code seq} 这些
     * 每次都变动的字段一起算进去，指纹就退化成「每次都不同」，
     * 时间轴会被不断重建，渲染层的平滑推进也就失效了。
     */
    static int lyricContentSignature(String body) {
        if (body == null) {
            return 0;
        }
        int key = body.indexOf("\"lyric\"");
        if (key < 0) {
            return 0;
        }
        int colon = body.indexOf(':', key);
        if (colon < 0) {
            return 0;
        }
        int start = -1;
        for (int i = colon + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            start = i;
            break;
        }
        if (start < 0 || body.charAt(start) != '{') {
            return 0;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return hashRange(body, start, i + 1);
                }
            }
        }
        return 0;
    }

    /**
     * 计算 {@code [from, to)} 区间的字符串哈希，不复制子串。
     *
     * <p>此前是 {@code body.substring(start, i + 1).hashCode()}：为了算一个
     * 32 位哈希，先按字符整段复制一份歌词 JSON。歌词动辄几十到几百 KB，
     * 而这里每 200ms 调一次——一条十分钟的曲子会白白搬走几百 MB。
     *
     * <p>字符串哈希的定义是 {@code h = 31*h + c}，可以逐字符累加，
     * 结果与 {@code String.hashCode()} 完全相同（已有单测固定这个等式），
     * 因此零分配。
     */
    static int hashRange(String s, int from, int to) {
        int h = 0;
        for (int i = from; i < to; i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    /**
     * 有界读取响应体。
     *
     * <p>回环端口只绑 127.0.0.1，但**任何本机进程都能监听这个端口**，
     * 一个畸形或有意的响应就能让 {@code readAllBytes()} 把整个游戏堆吃穿。
     * 载荷上限在协议里已经定义（{@link Protocol#MAX_PAYLOAD_CHARS}），
     * 但此前没有任何地方引用了它，这里才真正兑现。
     *
     * @return 响应文本；超过上限时返回 null，按一次失败处理
     */
    private static String readBounded(InputStream in) throws IOException {
        int limit = Protocol.MAX_PAYLOAD_CHARS;
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > limit) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private void onFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            alive = false;
            // 判定断连才清空，避免插件重载造成闪烁
            latest.set(null);
            latestLyric.set(LyricTimeline.empty());
            lyricSignature = 0;
        }
    }

    /**
     * 发起一次 GET 并读完整响应体。
     *
     * <p><b>不调用 {@code disconnect()}</b>。这个方法是轮询路径，每 200ms 一次；
     * 而 {@code disconnect()} 会直接关掉底层 socket，让 keep-alive 彻底失效——
     * 一条十分钟的曲子里会白白建立 3000 次 TCP 连接，外加 3000 次新旧线程
     * 与端口的分配回收（Windows 上短连接还会堆积 TIME_WAIT）。
     *
     * <p>正确做法是让连接回到连接池：把响应体读干净，然后只关流。
     * {@code HttpURLConnection} 在响应被完整读取并关闭流后会自行复用连接。
     *
     * <p>但「读干净」并不总是成立，因此每个提前返回的分支都必须自己决定：
     * 响应体没读完就 {@code disconnect()}（连接已污染，不能复用），
     * 读完了就让它自然归还。漏掉这一步会泄漏连接，比关掉 keep-alive 更糟。
     */
    private String get(String path) {
        HttpURLConnection connection = null;
        boolean bodyFullyRead = false;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            // 明确要求长连接；默认就是 keep-alive，但显式写上可以避免
            // 中间层（代理、插件自带的 HTTP 服务器）把它降级而无人察觉。
            connection.setRequestProperty("Connection", "keep-alive");

            int code = connection.getResponseCode();
            if (code != 200) {
                // 非 200 也有响应体，不读掉它连接就不能复用。
                drainQuietly(connection.getErrorStream());
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                String body = readBounded(in);
                // readBounded 返回 null 表示超限，此时流里还有未读字节，
                // 连接不能复用，交给 finally 去 disconnect。
                bodyFullyRead = body != null;
                return body;
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null && !bodyFullyRead) {
                connection.disconnect();
            }
        }
    }

    /** 读空并关闭一个流，用于把错误响应体消费掉以便复用连接。 */
    private static void drainQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try (InputStream stream = in) {
            byte[] scratch = new byte[4096];
            // 设一个上限：畸形响应可能无穷无尽，不能在这里死等。
            int budget = 256 * 1024;
            while (budget > 0 && stream.read(scratch) > 0) {
                budget -= scratch.length;
            }
        } catch (Throwable ignored) {
            // 错误响应本身就不可信，读失败无需上报
        }
    }
}
