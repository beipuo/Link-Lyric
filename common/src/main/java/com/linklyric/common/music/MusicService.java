// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.TrackIdentity;
import com.linklyric.common.music.MusicSnapshot.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns the provider and worker threads. No Minecraft classes are required. */
public final class MusicService implements AutoCloseable {
    public enum LyricsState { NO_TRACK, LOADING, AVAILABLE, UNAVAILABLE }
    public record Lyrics(LyricsState state, LyricTimeline timeline) {
        public int lineAt(long positionMs, long offsetMs) {
            if (state != LyricsState.AVAILABLE || positionMs < 0) return -1;
            long adjusted = Math.max(0, Math.min(positionMs, Long.MAX_VALUE - 60_000)
                    + Math.max(-60_000, Math.min(60_000, offsetMs)));
            var line = timeline.lineAt(adjusted);
            return line == null || adjusted >= line.endMs() ? -1 : timeline.indexAt(adjusted);
        }
    }

    /** Read this once per frame to keep the lyrics and the selected track consistent. */
    public record Frame(MusicSnapshot snapshot, Lyrics lyrics, long revision) {}
    private record LyricKey(String session, String track, TrackIdentity identity) {}
    private static final Lyrics NO_TRACK = new Lyrics(LyricsState.NO_TRACK, LyricTimeline.empty());
    private static final Lyrics LOADING = new Lyrics(LyricsState.LOADING, LyricTimeline.empty());
    private static final Lyrics UNAVAILABLE = new Lyrics(LyricsState.UNAVAILABLE, LyricTimeline.empty());
    private static final int MAX_RETRIES = 5;
    private final MusicProvider provider;
    private final List<LyricsProvider> lyricsProviders;
    private final long intervalMs;
    private final AtomicReference<Frame> frame;
    private final CopyOnWriteArrayList<Consumer<MusicEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledThreadPoolExecutor media = worker("shb-music-media");
    private final ScheduledThreadPoolExecutor lyrics = worker("shb-music-lyrics");
    private final LinkedHashMap<TrackIdentity, Lyrics> cache = new LinkedHashMap<>(16, 0.75f, true);
    private Set<String> sessionIds = Set.of();
    private LyricKey requested;
    private Future<?> pending;
    private int failures;
    private int lastLine = -1;
    private long revision;
    private boolean started;
    private volatile boolean closed;

    public MusicService(MusicProvider provider, List<LyricsProvider> lyricsProviders, Duration pollInterval) {
        this.provider = Objects.requireNonNull(provider);
        this.lyricsProviders = List.copyOf(lyricsProviders);
        this.intervalMs = Math.max(250, Math.min(4000, pollInterval.toMillis()));
        this.frame = new AtomicReference<>(new Frame(empty(State.INITIALIZING), NO_TRACK, 0));
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        media.execute(this::poll);
        media.scheduleWithFixedDelay(this::checkLine, 100, 100, TimeUnit.MILLISECONDS);
    }

    public Frame getFrame() { return frame.get(); }
    public MusicSnapshot getSnapshot() { return getFrame().snapshot(); }
    public Lyrics getCurrentLyrics() { return getFrame().lyrics(); }

    /** Called serially on the media worker. UI listeners must hand off to their own executor. */
    public synchronized AutoCloseable addListener(Consumer<MusicEvent> listener) {
        Objects.requireNonNull(listener);
        if (!closed) listeners.addIfAbsent(listener);
        return () -> removeListener(listener);
    }

    public void removeListener(Consumer<MusicEvent> listener) { listeners.remove(listener); }

    /** Retries after the automatic retry limit, for example after a user action. */
    public synchronized void retry() {
        if (!closed && started && failures > MAX_RETRIES) {
            failures = 0;
            media.execute(this::poll);
        }
    }

    /** Re-read the selected track's lyrics on the worker, including edits since its last load. */
    public synchronized void refreshLyrics() {
        var key = requested;
        if (closed || key == null || getCurrentLyrics().state() == LyricsState.LOADING) return;
        media.execute(() -> {
            synchronized (this) {
                if (!closed && key == requested && getCurrentLyrics().state() != LyricsState.LOADING) {
                    loadLyrics(getSnapshot(), key, true);
                }
            }
        });
    }

    private void poll() {
        if (closed) return;
        long delay = intervalMs;
        try {
            var sample = provider.poll();
            synchronized (this) {
                if (closed) return;
                failures = 0;
                update(sample);
            }
        } catch (MusicProvider.Unsupported ignored) {
            synchronized (this) {
                if (!closed) updateUnavailable(State.UNSUPPORTED);
            }
            return;
        } catch (Exception ignored) {
            synchronized (this) {
                if (closed) return;
                updateUnavailable(State.TEMPORARILY_UNAVAILABLE);
                if (++failures > MAX_RETRIES) return;
                delay = Math.max(intervalMs, 500L << (failures - 1));
            }
        }
        synchronized (this) {
            if (!closed) media.schedule(this::poll, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void update(MusicProvider.Poll sample) {
        MusicSnapshot old = getSnapshot();
        Session prior = old.session();
        Session selected = select(sample, prior == null ? null : prior.id());
        var next = selected == null ? empty(State.NO_SESSION) : new MusicSnapshot(provider.id(), State.AVAILABLE, selected);
        publish(next);
        var ids = new LinkedHashSet<String>();
        for (var candidate : sample.sessions()) {
            if (ids.add(candidate.id()) && !sessionIds.contains(candidate.id()))
                emit(MusicEvent.Type.SESSION_DISCOVERED, candidate.id(), old);
        }
        for (String id : sessionIds) {
            if (!ids.contains(id)) emit(MusicEvent.Type.SESSION_LOST, id, old);
        }
        sessionIds = ids;
        if (old.state() == State.TEMPORARILY_UNAVAILABLE) emit(MusicEvent.Type.PROVIDER_RECOVERED, null, old);
        changes(old, selected);
    }

    private static Session select(MusicProvider.Poll sample, String previous) {
        Session best = null;
        int highest = -1;
        for (var candidate : sample.sessions()) {
            int rank = switch (candidate.status()) {
                case PLAYING -> 12;
                case PAUSED -> 8;
                case BUFFERING -> 4;
                default -> 0;
            };
            if (candidate.id().equals(previous)) rank += 2;
            if (candidate.id().equals(sample.preferredSessionId())) rank += 1;
            if (rank > highest) {
                highest = rank;
                best = candidate;
            }
        }
        return best;
    }

    private void updateUnavailable(State state) {
        MusicSnapshot old = getSnapshot();
        if (old.state() == state) return;
        publish(empty(state));
        for (String id : sessionIds) emit(MusicEvent.Type.SESSION_LOST, id, old);
        sessionIds = Set.of();
        changes(old, null);
        emit(MusicEvent.Type.PROVIDER_UNAVAILABLE, null, old);
    }

    private void publish(MusicSnapshot next) {
        var key = lyricKey(next.session());
        if (Objects.equals(key, requested)) {
            frame.set(new Frame(withLyrics(next, getFrame().lyrics()), getFrame().lyrics(), revision));
            return;
        }
        requested = key;
        if (pending != null) pending.cancel(true);
        pending = null;
        if (key == null) {
            lastLine = -1;
            frame.set(new Frame(next, NO_TRACK, ++revision));
            return;
        }
        loadLyrics(next, key, false);
    }

    private void loadLyrics(MusicSnapshot snapshot, LyricKey key, boolean refresh) {
        lastLine = -1;
        long generation = ++revision;
        frame.set(new Frame(withLyrics(snapshot, LOADING), LOADING, generation));
        pending = lyrics.submit(() -> {
            if (refresh) cache.remove(key.identity());
            Lyrics result = resolve(key.identity());
            synchronized (this) {
                if (!closed && revision == generation) media.execute(() -> publishLyrics(generation, result));
            }
        });
    }

    private static MusicSnapshot withLyrics(MusicSnapshot snapshot, Lyrics value) {
        var session = snapshot.session();
        boolean available = value.state() == LyricsState.AVAILABLE;
        if (session == null || session.capabilities().contains(Capability.LYRICS) == available) return snapshot;
        var caps = EnumSet.noneOf(Capability.class);
        caps.addAll(session.capabilities());
        if (available) caps.add(Capability.LYRICS);
        else caps.remove(Capability.LYRICS);
        return new MusicSnapshot(snapshot.providerId(), snapshot.state(), new Session(session.id(), session.track(),
                session.status(), session.positionMs(), session.durationMs(), session.rate(), session.sampledAtNanos(),
                session.artwork(), caps));
    }

    private Lyrics resolve(TrackIdentity identity) {
        Lyrics hit = cache.get(identity);
        if (hit != null) return hit;
        for (var source : lyricsProviders) {
            if (Thread.currentThread().isInterrupted()) return UNAVAILABLE;
            try {
                var timeline = source.load(identity);
                if (timeline != null && !timeline.isEmpty() && timeline.size() <= 4096) {
                    var result = new Lyrics(LyricsState.AVAILABLE, timeline);
                    cache.put(identity, result);
                    if (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
                    return result;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return UNAVAILABLE;
            } catch (Exception ignored) {
                // Try the next authorized source. No credentials or track data are logged.
            }
        }
        return UNAVAILABLE;
    }

    private synchronized void publishLyrics(long generation, Lyrics result) {
        if (closed || revision != generation) return;
        MusicSnapshot current = getSnapshot();
        frame.set(new Frame(withLyrics(current, result), result, revision));
        emit(result.state() == LyricsState.AVAILABLE ? MusicEvent.Type.LYRICS_AVAILABLE
                : MusicEvent.Type.LYRICS_UNAVAILABLE, current.session().id(), current);
        checkLine();
    }

    private synchronized void checkLine() {
        var current = getFrame();
        if (closed || current.snapshot().session() == null) return;
        var session = current.snapshot().session();
        int line = current.lyrics().lineAt(session.positionAt(System.nanoTime()), 0);
        if (line != lastLine) {
            lastLine = line;
            emit(MusicEvent.Type.LYRIC_LINE_CHANGED, session.id(), current.snapshot());
        }
    }

    private void changes(MusicSnapshot old, Session next) {
        var prior = old.session();
        var id = next == null ? null : next.id();
        boolean sameSession = prior != null && next != null && prior.id().equals(id);
        if (!Objects.equals(prior == null ? null : prior.id(), id)) emit(MusicEvent.Type.SESSION_CHANGED, id, old);
        var before = prior == null ? null : prior.track();
        var after = next == null ? null : next.track();
        boolean sameTrack = sameSession && Objects.equals(trackKey(before), trackKey(after));
        if (!sameTrack && (before != null || after != null)) emit(MusicEvent.Type.TRACK_CHANGED, id, old);
        if (!Objects.equals(before, after)) emit(MusicEvent.Type.METADATA_CHANGED, id, old);
        var status = next == null ? PlaybackStatus.UNKNOWN : next.status();
        if (next != null && (!sameSession || prior.status() != status)) {
            emit(switch (status) {
                case PLAYING -> MusicEvent.Type.PLAYBACK_STARTED;
                case PAUSED -> MusicEvent.Type.PLAYBACK_PAUSED;
                case STOPPED -> MusicEvent.Type.PLAYBACK_STOPPED;
                default -> MusicEvent.Type.PLAYBACK_STATE_CHANGED;
            }, id, old);
        }
        if (!Objects.equals(artId(prior), artId(next))) emit(MusicEvent.Type.ARTWORK_CHANGED, id, old);
        if (next != null && (!sameTrack || !next.durationMs().equals(prior.durationMs())
                || !next.rate().equals(prior.rate()) || next.positionMs().isPresent() != prior.positionMs().isPresent()
                || prior.status() != status)) emit(MusicEvent.Type.TIMELINE_CHANGED, id, old);
        if (sameTrack && next.positionMs().isPresent() && prior.positionMs().isPresent()
                && Math.abs((double) prior.positionAt(next.sampledAtNanos()) - next.positionMs().getAsLong()) >= 1_250) {
            emit(MusicEvent.Type.TIMELINE_DISCONTINUITY, id, old);
        }
    }

    private static LyricKey lyricKey(Session session) {
        if (session == null || session.track() == null || session.track().title().isEmpty()) return null;
        var track = session.track();
        return new LyricKey(session.id(), track.key(), new TrackIdentity(track.title().get(),
                track.artist().orElse(null), track.album().orElse(null), session.durationMs().orElse(0)));
    }

    private static String trackKey(Track track) { return track == null ? null : track.key(); }
    private static String artId(Session session) {
        return session == null || session.artwork() == null ? null : session.artwork().id();
    }

    private MusicSnapshot empty(State state) { return new MusicSnapshot(provider.id(), state, null); }

    private void emit(MusicEvent.Type type, String id, MusicSnapshot previous) {
        if (closed || listeners.isEmpty()) return;
        var event = new MusicEvent(type, id, previous, getSnapshot());
        for (var listener : listeners) {
            if (closed) return;
            try { listener.accept(event); }
            catch (RuntimeException ignored) { /* A consumer cannot break the media worker. */ }
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            listeners.clear();
            frame.set(new Frame(empty(State.CLOSED), NO_TRACK, ++revision));
            lyrics.shutdownNow();
            media.shutdownNow();
        }
        provider.close();
    }

    private static ScheduledThreadPoolExecutor worker(String name) {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
