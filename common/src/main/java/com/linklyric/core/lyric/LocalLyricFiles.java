// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.core.lyric;

import com.linklyric.core.sync.TrackIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Looks up the current track in a dedicated lyric directory. Call off the render thread. */
public final class LocalLyricFiles {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*]");
    private final Path directory;

    public LocalLyricFiles(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public LyricTimeline load(TrackIdentity track) {
        if (!track.isUsable()) return LyricTimeline.empty();
        String name = track.artist().isEmpty() ? track.title() : track.artist() + " - " + track.title();
        String stem = UNSAFE.matcher(Normalizer.normalize(name, Normalizer.Form.NFC)).replaceAll("_");
        String lrc = read(stem + ".lrc");
        return lrc == null ? LyricTimeline.empty()
                : LrcParser.parse(lrc, read(stem + ".translation.lrc"), track.durationMs());
    }

    private String read(String name) {
        try {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] content = input.readNBytes(MAX_FILE_BYTES + 1);
                if (content.length <= MAX_FILE_BYTES) {
                    return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
                }
            }
        } catch (IOException | InvalidPathException | SecurityException ignored) {
            // A missing or unreadable file is equivalent to no lyrics for this format.
        }
        return null;
    }
}
