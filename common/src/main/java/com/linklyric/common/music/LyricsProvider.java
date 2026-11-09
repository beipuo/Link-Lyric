// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.TrackIdentity;

/** Supply a local or explicitly authorized source; match the query before returning a timeline. */
@FunctionalInterface
public interface LyricsProvider {
    LyricTimeline load(TrackIdentity track) throws Exception;
}
