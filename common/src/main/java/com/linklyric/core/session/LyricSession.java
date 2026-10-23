package com.linklyric.core.session;

import com.linklyric.core.capability.Capability;
import com.linklyric.core.lyric.LyricCache;
import com.linklyric.core.lyric.LyricProvider;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.source.PlaybackSource;
import com.linklyric.core.sync.PlaybackClock;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import com.linklyric.core.ui.ScrollModel;

/**
 * 歌词会话：把「数据源 + 歌词源 + 时钟 + 滚动模型」编排成渲染层可消费的单一对象。
 *
 * <p>渲染层每帧只做两件事：调 {@link #tick()}，然后读 {@link #view()}。
 * 所有跨源协调、换曲检测、歌词异步加载的状态判定都在这里收敛。
 *
 * <p>线程约定：{@link #tick()} 与 {@link #view()} 只在渲染/主线程调用。
 * 歌词加载结果通过 {@link #acceptLyric} 由主线程投递，不在加载线程里改状态。
 */
public final class LyricSession {

    /** 渲染层消费的只读视图。字段全部在 {@link #tick()} 时算好，渲染时零计算。 */
    public record View(
            TrackIdentity track,
            int lineIndex,
            String text,
            String translation,
            long positionMs,
            boolean playing,
            float scrollOffset,
            boolean hasLyric,
            boolean stale) {

        static final View EMPTY = new View(
                TrackIdentity.of("", "", 0L), -1, "", "", 0L, false, 0f, false, true);
    }

    /** 换曲时触发的外部回调，参数为新的曲目身份。用于发起歌词查询。 */
    public interface TrackChangeListener {
        void onTrackChanged(TrackIdentity track);
    }

    private final PlaybackSource source;
    private final LyricProvider provider;
    private final PlaybackClock clock = new PlaybackClock();
    private final ScrollModel scroll = new ScrollModel();
    private final LyricCache cache;

    private LyricTimeline timeline = LyricTimeline.empty();
    private TrackIdentity currentTrack = TrackIdentity.of("", "", 0L);
    private long lastSnapshotNanos = System.nanoTime();
    private long lastSequence = Long.MIN_VALUE;
    private boolean lyricPending;
    private TrackChangeListener listener;

    public LyricSession(PlaybackSource source, LyricProvider provider, LyricCache cache) {
        this.source = source;
        this.provider = provider;
        this.cache = cache == null ? new LyricCache() : cache;
    }

    public void setTrackChangeListener(TrackChangeListener listener) {
        this.listener = listener;
    }

    /**
     * 拉取数据源并推进全部状态。每帧调用一次。
     *
     * <p>数据源异常在此吞掉并转为「无会话」，因为数据源断连是常态
     * （播放器退出、SMTC 会话结束），不该打断游戏。
     */
    public void tick() {
        long now = System.nanoTime();
        PlayerSnapshot snapshot;
        try {
            snapshot = source.pollSnapshot();
        } catch (RuntimeException e) {
            snapshot = null;
        }

        if (snapshot == null || !snapshot.hasTrack()) {
            currentTrack = TrackIdentity.of("", "", 0L);
            timeline = LyricTimeline.empty();
            scroll.moveTo(-1, now);
            lyricPending = false;
            return;
        }

        lastSnapshotNanos = now;

        // 丢弃乱序快照，避免网络抖动导致指针回跳
        if (snapshot.sequence() < lastSequence) {
            return;
        }
        lastSequence = snapshot.sequence();

        if (!snapshot.track().matches(currentTrack)) {
            onTrackChanged(snapshot.track());
        }

        if (snapshot.capabilities().has(Capability.PLAYBACK_POSITION)) {
            long drift = clock.driftMs(snapshot.positionMs());
            // 只在漂移明显或播放态变化时重锚，避免每帧硬拉导致逐字动画抖动
            if (drift > 250L || clock.isPlaying() != snapshot.playing()) {
                clock.anchor(snapshot.positionMs(), snapshot.playing());
            }
        } else if (clock.isPlaying() != snapshot.playing()) {
            // 无进度能力：只能跟随播放态，位置按墙钟推进并接受漂移
            if (snapshot.playing()) {
                clock.resume();
            } else {
                clock.pause();
            }
        }

        long position = clock.positionMs();
        int index = timeline.indexAt(position);
        scroll.moveTo(index, now);
        currentTrack = snapshot.track();
    }

    private void onTrackChanged(TrackIdentity track) {
        currentTrack = track;
        timeline = LyricTimeline.empty();
        scroll.snapTo(-1, System.nanoTime());

        LyricTimeline cached = cache.get(track.cacheKey());
        if (cached != null) {
            timeline = cached;
            lyricPending = false;
        } else {
            lyricPending = true;
        }

        if (listener != null) {
            listener.onTrackChanged(track);
        }
    }

    /**
     * 投递异步加载好的歌词。主线程调用。
     *
     * <p>若曲目已切换则丢弃——异步结果到达时用户可能已经切歌，
     * 直接套用会把上一首的歌词显示到新歌上。
     */
    public void acceptLyric(TrackIdentity forTrack, LyricTimeline loaded) {
        if (!forTrack.matches(currentTrack)) {
            return;
        }
        lyricPending = false;
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        timeline = loaded;
        cache.put(forTrack.cacheKey(), loaded);
    }

    /** 当前可渲染视图。所有数值均已算好。 */
    public View view() {
        long now = System.nanoTime();
        long position = clock.positionMs();
        int index = timeline.indexAt(position);
        var line = timeline.line(index);

        boolean stale = (now - lastSnapshotNanos) / 1_000_000L > 30_000L;
        boolean hasLyric = line != null;

        return new View(
                currentTrack,
                index,
                hasLyric ? line.text() : "",
                hasLyric ? line.translation() : "",
                position,
                clock.isPlaying(),
                scroll.offsetAt(now),
                hasLyric,
                stale);
    }

    /** 歌词是否还在等待加载，UI 可据此显示占位。 */
    public boolean isLyricPending() {
        return lyricPending;
    }

    public LyricProvider provider() {
        return provider;
    }

    public PlaybackSource source() {
        return source;
    }

    public TrackIdentity currentTrack() {
        return currentTrack;
    }
}
