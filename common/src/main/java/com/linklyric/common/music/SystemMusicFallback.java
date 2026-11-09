// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music;

import com.linklyric.common.hud.LyricFeed;
import com.linklyric.common.music.platform.WindowsMediaProvider;
import com.linklyric.core.lyric.LocalLyricFiles;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Optional local source for clients without Link-MC lyrics. */
public final class SystemMusicFallback implements LyricFeed, AutoCloseable {
    private final LyricFeed primary;
    private final MusicService service;
    private final LyricFeed fallback;
    private LyricTimeline lastTimeline;
    private long lastSignature;
    private boolean lastPrimary;
    private long revision;

    public SystemMusicFallback(LyricFeed primary, Path configDirectory) {
        this.primary = primary;
        service = create(configDirectory);
        fallback = service == null ? null : new MusicLyricFeed(service::getFrame);
        if (service != null) service.start();
    }

    private static MusicService create(Path configDirectory) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        String executable = System.getProperty("linklyric.windowsBridge");
        if (executable == null || executable.isBlank()) return null;
        try {
            var local = new LocalLyricFiles(configDirectory.resolve("linklyric-lyrics"));
            return new MusicService(new WindowsMediaProvider(Path.of(executable)), List.of(local::load),
                    Duration.ofMillis(750));
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    @Override public synchronized Frame frame() {
        var main = primary.frame();
        var selected = main;
        boolean usePrimary = true;
        if ((main.timeline().isEmpty() || (!main.alive() && primary.isEnabled())) && fallback != null) {
            var alternate = fallback.frame();
            if (alternate.alive()) {
                selected = alternate;
                usePrimary = false;
            }
        }
        if (lastPrimary != usePrimary || lastTimeline != selected.timeline() || lastSignature != selected.signature()) {
            lastPrimary = usePrimary;
            lastTimeline = selected.timeline();
            lastSignature = selected.signature();
            revision++;
        }
        return new Frame(selected.alive() && primary.isEnabled(), selected.snapshot(), selected.timeline(), revision);
    }

    @Override public void refreshLocalLyrics() {
        primary.refreshLocalLyrics();
        if (service != null) service.refreshLyrics();
    }

    @Override public boolean isAlive() { return frame().alive(); }
    @Override public PlayerSnapshot pollSnapshot() { return frame().snapshot(); }
    @Override public LyricTimeline lyric() { return frame().timeline(); }
    @Override public long lyricSignature() { return frame().signature(); }
    @Override public boolean isEnabled() { return primary.isEnabled(); }
    @Override public void setEnabled(boolean enabled) { primary.setEnabled(enabled); }
    @Override public String id() { return primary.id(); }
    @Override public void close() { if (service != null) service.close(); }
}
