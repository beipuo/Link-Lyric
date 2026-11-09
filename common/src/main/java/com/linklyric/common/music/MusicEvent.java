// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

/** Events describe changes to the selected session, with discovery/loss for every session. */
public record MusicEvent(Type type, String sessionId, MusicSnapshot previous, MusicSnapshot current) {
    public enum Type {
        SESSION_DISCOVERED, SESSION_LOST, SESSION_CHANGED, TRACK_CHANGED, METADATA_CHANGED,
        PLAYBACK_STARTED, PLAYBACK_PAUSED, PLAYBACK_STOPPED, PLAYBACK_STATE_CHANGED,
        TIMELINE_CHANGED, TIMELINE_DISCONTINUITY, LYRICS_AVAILABLE, LYRICS_UNAVAILABLE,
        LYRIC_LINE_CHANGED, ARTWORK_CHANGED, PROVIDER_UNAVAILABLE, PROVIDER_RECOVERED
    }
}
