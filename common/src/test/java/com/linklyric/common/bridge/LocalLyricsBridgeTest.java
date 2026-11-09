// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.bridge;

import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.hud.LyricFeed.Frame;
import com.linklyric.core.sync.PlaybackClock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalLyricsBridgeTest {
    @TempDir Path directory;

    @Test
    void followsPauseSeekAndTrackChangesWithLocalAndRemoteFallbacks() throws Exception {
        Files.writeString(directory.resolve("Artist - First.ttml"), """
                <tt><body><p begin="0s" end="3s"><span begin="0s" end="3s">TTML</span></p>
                <p begin="3s" end="6s">After seek</p></body></tt>
                """);
        Files.writeString(directory.resolve("Artist - First.lrc"), "[00:00]Lower priority");
        Files.writeString(directory.resolve("Artist - Next _ Mix.ttml"), "<broken");
        Files.writeString(directory.resolve("Artist - Next _ Mix.lrc"), "[00:00]LRC");
        Files.writeString(directory.resolve("Artist - Next _ Mix.translation.lrc"), "[00:00]译文");
        var response = new AtomicReference<>(payload(1, "First", 1000, true, ""));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lyric", exchange -> {
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var source = new BridgePlaybackSource("127.0.0.1", server.getAddress().getPort(), directory);
        var renderer = new FloatingLyricRenderer(source);
        var painter = new Painter();
        try {
            source.start();
            var original = await(source, 1);
            assertEquals("TTML", original.timeline().lineAt(1000).text());

            response.set(payload(2, "First", 1200, false, ""));
            var paused = await(source, 2);
            assertFalse(paused.snapshot().playing());
            var clock = new PlaybackClock();
            clock.anchor(paused.snapshot().positionMs(), paused.snapshot().playing(), 0);
            assertEquals(1200, clock.positionMs(TimeUnit.SECONDS.toNanos(2)));
            assertSame(original.timeline(), paused.timeline());
            assertEquals(original.signature(), paused.signature());
            assertTrue(renderer.render(painter));
            assertEquals(1200, renderer.currentPositionMs());

            response.set(payload(3, "First", 4000, false, ""));
            var seek = await(source, 3);
            assertSame(original.timeline(), seek.timeline());
            assertEquals("After seek", seek.timeline().lineAt(seek.snapshot().positionMs()).text());
            assertTrue(renderer.render(painter));
            assertEquals(4000, renderer.currentPositionMs());

            response.set(payload(4, "Next / Mix", 0, true, ""));
            var lrc = await(source, 4);
            assertEquals("LRC", lrc.timeline().line(0).text());
            assertEquals("译文", lrc.timeline().line(0).translation());
            assertNotEquals(seek.signature(), lrc.signature());

            response.set(payload(5, "Missing", 0, true, ""));
            assertTrue(await(source, 5).timeline().isEmpty());
            assertFalse(renderer.render(painter));

            response.set(payload(6, "First", 0, true,
                    ",\"lyric\":{\"lines\":[{\"text\":\"Player\",\"startMs\":0,\"endMs\":1000}]}"));
            assertEquals("Player", await(source, 6).timeline().line(0).text());
            response.set(payload(7, "First", 0, true, ""));
            assertEquals("TTML", await(source, 7).timeline().line(0).text());
        } finally {
            source.stop();
            server.stop(0);
        }
    }

    private static Frame await(BridgePlaybackSource source, int seq) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var frame = source.frame();
            if (frame.alive() && frame.snapshot().sequence() == seq) return frame;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Snapshot did not arrive: " + seq);
    }

    private static String payload(int seq, String title, long position, boolean playing, String lyric) {
        return """
                {"v":2,"type":"snapshot","seq":%d,"positionMs":%d,"playing":%b,
                 "track":{"title":"%s","artist":"Artist","durationMs":6000},
                 "capabilities":["PLAYBACK_POSITION"]%s}
                """.formatted(seq, position, playing, title, lyric);
    }

    private static final class Painter implements FloatingLyricRenderer.Painter {
        @Override public int screenWidth() { return 400; }
        @Override public int screenHeight() { return 300; }
        @Override public int textWidth(String text) { return text.length() * 6; }
        @Override public void drawText(String text, int x, int y, int color) {}
        @Override public void fillRect(int x, int y, int width, int height, int color) {}
    }
}
