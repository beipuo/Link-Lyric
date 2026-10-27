package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererFrameBenchmarkTest {
    @Test
    void longWrappedTenLineEffectsProfile() {
        if (!"1".equals(System.getenv("LINKLYRIC_BENCHMARK"))) return;
        profile();
    }

    public static void main(String[] args) {
        profile();
    }

    private static void profile() {
        String text = "长歌词Mixed script 重复循环天地之间".repeat(12);
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            var words = new ArrayList<com.linklyric.core.lyric.LyricWord>();
            for (int c = 0; c < text.length(); c++) {
                words.add(new com.linklyric.core.lyric.LyricWord(
                        text.substring(c, c + 1), i * 20000L + c * 60L,
                        i * 20000L + (c + 1) * 60L));
            }
            lines.add(new LyricLine(text, "", i * 20000L, (i + 1) * 20000L, words, ""));
        }
        var snapshot = new com.linklyric.core.sync.PlayerSnapshot(
                "benchmark", com.linklyric.core.sync.TrackIdentity.of("track", "", 0L),
                500L, false, 0L, 0L, com.linklyric.core.capability.CapabilitySet.empty());
        LyricTimeline timeline = new LyricTimeline(lines);
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new LyricFeed() {
            public boolean isAlive() { return true; }
            public com.linklyric.core.sync.PlayerSnapshot pollSnapshot() { return snapshot; }
            public LyricTimeline lyric() { return timeline; }
            public long lyricSignature() { return 1L; }
        });
        renderer.setStyle(LyricStyleConfig.DEFAULT.withLineCount(10).withWrap(true).withStroke(true));
        FloatingLyricRenderer.Painter painter = new FloatingLyricRenderer.Painter() {
            public int screenWidth() { return 480; }
            public int screenHeight() { return 270; }
            public int textWidth(String value) { return value.length() * 9; }
            public int charWidth(char value) { return 9; }
            public void drawText(String value, int x, int y, int color) { }
            public void fillRect(int x, int y, int w, int h, int color) { }
            public boolean supportsScissor() { return true; }
        };
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 3000; i++) assertTrue(renderer.render(painter));
        long beforeBytes = bean.getThreadAllocatedBytes(thread);
        long beforeNanos = System.nanoTime();
        for (int i = 0; i < 3000; i++) assertTrue(renderer.render(painter));
        long nanos = System.nanoTime() - beforeNanos;
        long bytes = bean.getThreadAllocatedBytes(thread) - beforeBytes;
        System.out.printf("HUD benchmark 3000 frames: %.1f bytes/frame, %.1f us/frame%n",
                bytes / 3000.0, nanos / 3000000.0);
    }
}
