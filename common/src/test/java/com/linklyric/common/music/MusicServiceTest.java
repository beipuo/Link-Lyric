// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import com.linklyric.common.music.MusicSnapshot.*;
import com.linklyric.core.lyric.LrcParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class MusicServiceTest {
    private static final Duration POLL = Duration.ofMillis(250);

    @Test void timelineUsesMonotonicClockAndHonorsPauseRateDurationAndUnknown() {
        var track = Track.of("a", "Title", "Artist", null);
        long now = System.nanoTime();
        var playing = new Session("p", track, PlaybackStatus.PLAYING, OptionalLong.of(2000),
                OptionalLong.of(6000), OptionalDouble.of(1.5), now, null, Set.of(Capability.TIMELINE));
        assertEquals(2000, playing.positionAt(now - 1));
        assertEquals(3500, playing.positionAt(now + TimeUnit.SECONDS.toNanos(1)));
        assertEquals(6000, playing.positionAt(now + TimeUnit.SECONDS.toNanos(4)));
        var paused = session("p", "a", PlaybackStatus.PAUSED, 3000);
        assertEquals(3000, paused.positionAt(now + TimeUnit.SECONDS.toNanos(30)));
        assertEquals(-1, session("p", "a", PlaybackStatus.UNKNOWN, -1).positionAt(now));
        assertEquals(10000, session("p", "a", PlaybackStatus.PLAYING, 5000)
                .positionAt(now + TimeUnit.MINUTES.toNanos(1)));
    }

    @Test void selectsPlayingSessionAndDeduplicatesRepeatedStateButEmitsSeekAndResume() throws Exception {
        var mock = new Mock();
        mock.set(session("system", "a", PlaybackStatus.PAUSED, 500), session("other", "b", PlaybackStatus.PLAYING, 2500));
        var events = new CopyOnWriteArrayList<MusicEvent>();
        try (var service = new MusicService(mock, List.of(), POLL)) {
            service.addListener(e -> { throw new IllegalStateException(); });
            var subscribed = service.addListener(events::add);
            service.start();
            await(() -> count(events, MusicEvent.Type.PLAYBACK_STARTED) == 1);
            assertEquals("other", service.getSnapshot().session().id());
            assertEquals(2, count(events, MusicEvent.Type.SESSION_DISCOVERED));
            mock.set(session("system", "a", PlaybackStatus.PAUSED, 500), session("other", "b", PlaybackStatus.PAUSED, 2600));
            await(() -> count(events, MusicEvent.Type.PLAYBACK_PAUSED) == 1);
            assertEquals("other", service.getSnapshot().session().id());
            mock.set(session("other", "b", PlaybackStatus.PAUSED, 8000));
            await(() -> count(events, MusicEvent.Type.TIMELINE_DISCONTINUITY) == 1);
            mock.set(session("other", "b", PlaybackStatus.PLAYING, 8000));
            await(() -> count(events, MusicEvent.Type.PLAYBACK_STARTED) == 2);
            mock.set(session("other", "b", PlaybackStatus.STOPPED, 8000));
            await(() -> count(events, MusicEvent.Type.PLAYBACK_STOPPED) == 1);
            int polls = mock.polls.get();
            await(() -> mock.polls.get() >= polls + 2);
            assertEquals(1, count(events, MusicEvent.Type.TRACK_CHANGED));
            assertEquals(1, count(events, MusicEvent.Type.SESSION_LOST));
            assertEquals(1, count(events, MusicEvent.Type.PLAYBACK_STOPPED));
            subscribed.close();
            int size = events.size();
            mock.set();
            await(() -> service.getSnapshot().state() == State.NO_SESSION);
            assertEquals(size, events.size());
        }
        assertTrue(mock.closed.get());
    }

    @Test void supersededLyricsCannotAttachToAnotherTrack() throws Exception {
        var mock = new Mock();
        mock.set(session("one", "old", PlaybackStatus.PAUSED, 2000));
        var oldStarted = new CountDownLatch(1);
        var oldInterrupted = new CountDownLatch(1);
        var nextRelease = new CountDownLatch(1);
        try (var service = new MusicService(mock, List.of(track -> {
            if (track.title().equals("old")) {
                oldStarted.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ignored) { oldInterrupted.countDown(); }
                return LrcParser.parse("[00:01]wrong", null, 12000);
            }
            nextRelease.await();
            return LrcParser.parse("[00:01]<00:01>right<00:03>", "[00:01]translation", 12000);
        }), POLL)) {
            service.start();
            assertTrue(oldStarted.await(3, TimeUnit.SECONDS));
            mock.set(session("one", "new", PlaybackStatus.PAUSED, 2000));
            assertTrue(oldInterrupted.await(3, TimeUnit.SECONDS));
            assertEquals("new", service.getFrame().snapshot().session().track().key());
            assertEquals(MusicService.LyricsState.LOADING, service.getCurrentLyrics().state());
            nextRelease.countDown();
            await(() -> service.getCurrentLyrics().state() == MusicService.LyricsState.AVAILABLE);
            var frame = service.getFrame();
            assertEquals("right", frame.lyrics().timeline().line(0).text());
            assertTrue(frame.snapshot().session().capabilities().contains(Capability.LYRICS));
            assertEquals(0, frame.lyrics().lineAt(2000, 0));
            assertEquals(-1, frame.lyrics().lineAt(2000, -2000));
        }
    }

    @Test void failedProviderRecoversAndConcurrentReadersSeeCoherentFrames() throws Exception {
        var mock = new Mock();
        var events = new CopyOnWriteArrayList<MusicEvent>();
        mock.fail.set(true);
        try (var service = new MusicService(mock, List.of(track ->
                LrcParser.parse("[00:00]" + track.title(), null, 12000)), POLL)) {
            service.addListener(events::add);
            service.start();
            await(() -> count(events, MusicEvent.Type.PROVIDER_UNAVAILABLE) == 1);
            assertEquals(State.TEMPORARILY_UNAVAILABLE, service.getSnapshot().state());
            mock.set(session("one", "first", PlaybackStatus.PAUSED, 1200));
            mock.fail.set(false);
            await(() -> count(events, MusicEvent.Type.PROVIDER_RECOVERED) == 1);
            var stop = new AtomicBoolean();
            var reader = Executors.newSingleThreadExecutor();
            var task = reader.submit(() -> {
                while (!stop.get()) {
                    var frame = service.getFrame();
                    if (frame.lyrics().state() == MusicService.LyricsState.AVAILABLE) {
                        assertEquals(frame.snapshot().session().track().key(), frame.lyrics().timeline().line(0).text());
                    }
                    Thread.onSpinWait();
                }
            });
            try {
                await(() -> service.getCurrentLyrics().state() == MusicService.LyricsState.AVAILABLE);
                mock.set(session("one", "second", PlaybackStatus.PAUSED, 1200));
                await(() -> count(events, MusicEvent.Type.LYRICS_AVAILABLE) == 2);
                service.close();
                assertEquals(State.CLOSED, service.getFrame().snapshot().state());
            } finally {
                stop.set(true);
                reader.shutdownNow();
            }
            task.get(3, TimeUnit.SECONDS);
        }
        assertTrue(mock.closed.get());
    }

    private static long count(List<MusicEvent> events, MusicEvent.Type type) {
        return events.stream().filter(event -> event.type() == type).count();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for a media update");
    }

    private static Session session(String id, String key, PlaybackStatus status, long position) {
        return new Session(id, Track.of(key, key, "artist", null), status,
                position < 0 ? OptionalLong.empty() : OptionalLong.of(position), OptionalLong.of(12000),
                OptionalDouble.empty(), System.nanoTime(), null, Set.of(Capability.METADATA, Capability.PLAYBACK_STATE));
    }

    private static final class Mock implements MusicProvider {
        private final AtomicReference<List<Session>> current = new AtomicReference<>(List.of());
        private final AtomicBoolean fail = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger polls = new AtomicInteger();
        void set(Session... sessions) { current.set(List.of(sessions)); }
        @Override public String id() { return "mock"; }
        @Override public Poll poll() throws IOException {
            polls.incrementAndGet();
            if (fail.get()) throw new IOException("disconnected");
            return new Poll(current.get(), "system");
        }
        @Override public void close() { closed.set(true); }
    }
}
