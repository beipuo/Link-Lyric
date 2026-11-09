// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import com.linklyric.common.hud.LyricFeed;
import com.linklyric.core.capability.Capability;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import java.util.function.Supplier;

/** Lets the existing per-word renderer read the exact same local timeline as a music card. */
public final class MusicLyricFeed implements LyricFeed {
    private static final CapabilitySet CAPABILITIES = CapabilitySet.of(Capability.PLAYBACK_POSITION);
    private static final Frame EMPTY = new Frame(false,
            new PlayerSnapshot("system-music", null, 0, false, 0, 0, CapabilitySet.empty()), LyricTimeline.empty(), 0);
    private final Supplier<MusicService.Frame> frames;

    public MusicLyricFeed(Supplier<MusicService.Frame> frames) { this.frames = frames; }

    @Override public Frame frame() {
        var current = frames.get();
        var session = current.snapshot().session();
        if (session == null || session.track() == null || session.positionMs().isEmpty()
                || current.lyrics().state() != MusicService.LyricsState.AVAILABLE) return EMPTY;
        var track = session.track();
        var identity = new TrackIdentity(track.title().orElse(null), track.artist().orElse(null),
                track.album().orElse(null), session.durationMs().orElse(0));
        long now = System.nanoTime();
        // This position is already interpolated by the service, including non-1x playback.
        var sample = new PlayerSnapshot(current.snapshot().providerId(), identity, session.positionAt(now),
                false, current.revision(), now, CAPABILITIES);
        return new Frame(true, sample, current.lyrics().timeline(), current.revision());
    }

    @Override public boolean isAlive() { return frame().alive(); }
    @Override public PlayerSnapshot pollSnapshot() { return frame().snapshot(); }
    @Override public LyricTimeline lyric() { return frame().timeline(); }
    @Override public long lyricSignature() { return frame().signature(); }
}
