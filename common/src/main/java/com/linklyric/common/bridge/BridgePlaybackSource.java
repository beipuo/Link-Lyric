package com.linklyric.common.bridge;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.source.PlaybackSource;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
public final class BridgePlaybackSource implements PlaybackSource, com.linklyric.common.hud.LyricFeed {

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

    @Override
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
        return alive;
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
    private static int lyricContentSignature(String body) {
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
                    return body.substring(start, i + 1).hashCode();
                }
            }
        }
        return 0;
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

    private String get(String path) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
