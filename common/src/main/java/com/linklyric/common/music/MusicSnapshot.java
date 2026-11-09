// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.Set;

/** A single observation. Unknown values are absent; the sample clock is System.nanoTime(). */
public record MusicSnapshot(String providerId, State state, Session session) {
    public enum State { INITIALIZING, AVAILABLE, NO_SESSION, TEMPORARILY_UNAVAILABLE, UNSUPPORTED, CLOSED }
    public enum PlaybackStatus { UNKNOWN, PLAYING, PAUSED, STOPPED, BUFFERING }
    public enum Capability { METADATA, PLAYBACK_STATE, TIMELINE, ARTWORK, LYRICS, PLAYBACK_CONTROL, AUDIO_FEATURES }

    public MusicSnapshot {
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(state);
        if ((state == State.AVAILABLE) != (session != null)) throw new IllegalArgumentException("session/state");
    }

    public record Track(String key, Optional<String> title, Optional<String> artist, Optional<String> album) {
        public Track {
            if (Objects.requireNonNull(key).isBlank()) throw new IllegalArgumentException("track key");
            Objects.requireNonNull(title);
            Objects.requireNonNull(artist);
            Objects.requireNonNull(album);
        }

        public static Track of(String key, String title, String artist, String album) {
            return new Track(key, text(title), text(artist), text(album));
        }

        private static Optional<String> text(String value) {
            return Optional.ofNullable(value).map(String::strip).filter(s -> !s.isEmpty());
        }
    }

    public record Session(String id, Track track, PlaybackStatus status, OptionalLong positionMs,
                          OptionalLong durationMs, OptionalDouble rate, long sampledAtNanos,
                          Artwork artwork, Set<Capability> capabilities) {
        public Session {
            if (Objects.requireNonNull(id).isBlank()) throw new IllegalArgumentException("session id");
            Objects.requireNonNull(status);
            positionMs = Objects.requireNonNull(positionMs).stream().filter(v -> v >= 0).findFirst();
            durationMs = Objects.requireNonNull(durationMs).stream().filter(v -> v > 0).findFirst();
            rate = Objects.requireNonNull(rate).stream().filter(v -> Double.isFinite(v) && v >= 0 && v <= 16).findFirst();
            capabilities = Set.copyOf(capabilities);
        }

        /** Returns -1 when the player did not publish a timeline. Stops estimating after 5 seconds. */
        public long positionAt(long nowNanos) {
            if (positionMs.isEmpty()) return -1;
            double advance = status == PlaybackStatus.PLAYING
                    ? Math.min(5000, Math.max(0, nowNanos - sampledAtNanos) / 1_000_000.0) * rate.orElse(1) : 0;
            long estimated = (long) (positionMs.getAsLong() + advance);
            return Math.min(durationMs.orElse(Long.MAX_VALUE), estimated);
        }
    }

    /** Bounded PNG payload, identified by the digest of its contents. */
    public static final class Artwork {
        public static final int MAX_BYTES = 256 * 1024;
        private final String id;
        private final byte[] png;

        public Artwork(byte[] png) {
            if (png.length == 0 || png.length > MAX_BYTES) throw new IllegalArgumentException("artwork size");
            this.png = png.clone();
            try {
                id = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(this.png));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public String id() { return id; }
        public byte[] png() { return png.clone(); }
    }
}
