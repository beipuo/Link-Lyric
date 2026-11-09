// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Called by a dedicated media worker. close() must be safe concurrently with poll(). */
public interface MusicProvider extends AutoCloseable {
    String id();
    Poll poll() throws IOException;
    @Override void close();

    record Poll(List<MusicSnapshot.Session> sessions, String preferredSessionId) {
        public Poll { sessions = List.copyOf(sessions); }
    }

    final class Unsupported extends IOException {
        public Unsupported(String message) { super(Objects.requireNonNull(message)); }
    }
}
