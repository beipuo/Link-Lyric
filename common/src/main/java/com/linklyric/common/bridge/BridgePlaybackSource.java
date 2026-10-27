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

    /**
     * 默认端口取自 {@link Protocol#DEFAULT_PORT}。
     *
     * <p>两个平台入口此前各自写了一份 {@code BRIDGE_PORT = 35400}，
     * 与协议常量三处并存。改端口要同时改三个地方，漏一个就两端不一致。
     */
    public static int defaultPort() {
        return Protocol.DEFAULT_PORT;
    }

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
    /**
     * 上次收到的歌词指纹（服务端 ETag），用于条件请求。
     *
     * <p>null 表示还没有可用的指纹，此时不发 {@code If-None-Match}。
     * 刻意与 {@link #lyricSignature} 分开：前者是服务端算的、
     * 用来省流量的字符串，后者是本端算的、用来决定要不要重解析的整数。
     */
    private volatile String lyricEtag;
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
    public synchronized void start() {
        if (worker != null) return;
        Thread thread = new Thread(this::loop, "link-lyric-bridge");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public synchronized void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        alive = false;
        latest.set(null);
        latestLyric.set(LyricTimeline.empty());
        capabilities.set(CapabilitySet.empty());
        lyricSignature = 0;
        lyricEtag = null;
        consecutiveFailures = 0;
    }

    private synchronized boolean currentWorker() {
        return worker == Thread.currentThread();
    }

    private void loop() {
        while (currentWorker()) {
            pollOnce();
            if (!currentWorker()) return;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() {
        String knownEtag;
        int previousSignature;
        boolean cached;
        synchronized (this) {
            if (!currentWorker()) return;
            knownEtag = lyricEtag;
            previousSignature = lyricSignature;
            cached = latest.get() != null;
        }
        Fetch fetch = get(Protocol.WS_PATH, knownEtag);
        SnapshotParser.Parsed parsed = null;
        if (fetch != null) {
            try {
                parsed = SnapshotParser.parseResponse(fetch.body(), sourceId, System.nanoTime(),
                        previousSignature, cached);
            } catch (RuntimeException ignored) { }
        }
        synchronized (this) {
            if (!currentWorker()) return;
            if (parsed == null || (parsed.omitted() && (knownEtag == null
                    || (fetch.etag() != null && !knownEtag.equals(fetch.etag()))))) {
                onFailure();
                return;
            }
            if (!parsed.omitted()) {
                if (parsed.timeline() != null) latestLyric.set(parsed.timeline());
                lyricSignature = parsed.signature();
                lyricEtag = fetch.etag();
            }
            capabilities.set(parsed.snapshot().capabilities());
            latest.set(parsed.snapshot());
            consecutiveFailures = 0;
            alive = true;
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
        return SnapshotParser.lyricSignature(body);
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
            lyricEtag = null;
            capabilities.set(CapabilitySet.empty());
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
    /** 一次 GET 的结果：正文与服务端声明的歌词指纹。 */
    private record Fetch(String body, String etag) {
    }

    private Fetch get(String path, String knownEtag) {
        HttpURLConnection connection = null;
        boolean bodyFullyRead = false;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            // 条件请求：告诉服务端上一份歌词的指纹，未变时它只回一个省略标记，
            // 省掉整份歌词子树（实测 25 KB / 次）。服务端不认识这个头时
            // 会照常返回完整内容，因此对旧适配器安全。
            if (knownEtag != null) {
                connection.setRequestProperty("If-None-Match", "\"" + knownEtag + "\"");
            }

            int code = connection.getResponseCode();
            if (code != 200) {
                // 非 200 也有响应体，不读掉它连接就不能复用。
                drainQuietly(connection.getErrorStream());
                return null;
            }
            String etag = connection.getHeaderField("ETag");
            try (InputStream in = connection.getInputStream()) {
                String body = readBounded(in);
                // readBounded 返回 null 表示超限，此时流里还有未读字节，
                // 连接不能复用，交给 finally 去 disconnect。
                bodyFullyRead = body != null;
                return body == null ? null : new Fetch(body, stripEtag(etag));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null && !bodyFullyRead) {
                connection.disconnect();
            }
        }
    }

    /** 去掉 ETag 两侧的引号与弱校验前缀；无值时返回 null。 */
    static String stripEtag(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2).trim();
        }
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    /** 服务端是否声明本轮省略了歌词子树。包级可见，供测试直接验证。 */
    static boolean lyricOmitted(String body) {
        return SnapshotParser.lyricOmitted(body);
    }

    /**
     * 是否需要用本轮响应重建歌词时间轴。
     *
     * <p>抽成纯函数是为了能被测到。此前这段判定直接写在 {@code pollOnce}
     * 里，而它是私有的、依赖 HTTP 与后台线程，测试根本走不进去——
     * 于是「把判定改回指纹比较」这种致命退化能安然通过全部测试。
     *
     * <p>三条规则，顺序有意义：
     * 1. 服务端说省略了 → 不重建。此时指纹必为 0，绝不能让 0 参与比较。
     * 2. 指纹与上轮相同 → 不重建（歌词确实没变）。
     * 3. 其余情况 → 重建。
     *
     * @param body        本轮响应正文
     * @param signature   本轮算出的歌词指纹
     * @param lastSignature 上轮采用的指纹
     */
    static boolean needsReparse(String body, int signature, int lastSignature) {
        if (lyricOmitted(body)) {
            return false;
        }
        return signature != lastSignature;
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
