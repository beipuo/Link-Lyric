package com.linklyric.common.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class BridgeHttpIntegrationTest {
    private static final String LYRIC = "\"lyric\":{\"lines\":[{\"text\":\"one\",\"startMs\":0,\"endMs\":100,\"words\":[]}]}";
    private static String payload(String extra) {
        return "{\"v\":2,\"type\":\"snapshot\",\"seq\":9007199254740993,\"positionMs\":9007199254740993,\"playing\":true,\"track\":{\"title\":\"Song\",\"artist\":\"Artist\",\"durationMs\":100},\"capabilities\":[\"TRACK_METADATA\"]," + extra + "}";
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    private static void send(HttpExchange exchange, String body, String tag) throws IOException {
        if (tag != null) exchange.getResponseHeaders().set("ETag", tag);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @Test
    void fullOmittedFailureAndReconnect() throws Exception {
        AtomicInteger phase = new AtomicInteger();
        BlockingQueue<String> headers = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lyric", exchange -> {
            String tag = exchange.getRequestHeaders().getFirst("If-None-Match");
            headers.add(tag == null ? "none" : tag);
            int p = phase.get();
            send(exchange, p == 2 ? "{\"v\":2}" : payload(p == 1 ? "\"lyricOmitted\":true" : LYRIC),
                    p == 2 ? "\"poison\"" : "\"first\"");
        });
        server.start();
        BridgePlaybackSource source = new BridgePlaybackSource("127.0.0.1", server.getAddress().getPort());
        try {
            source.start();
            await(source::isAlive);
            assertEquals("none", headers.poll(2, TimeUnit.SECONDS));
            assertEquals(9007199254740993L, source.pollSnapshot().positionMs());
            var timeline = source.lyric();
            var snapshot = source.pollSnapshot();
            phase.set(1);
            await(() -> source.pollSnapshot() != snapshot);
            assertSame(timeline, source.lyric());
            phase.set(2);
            await(() -> !source.isAlive());
            assertTrue(source.lyric().isEmpty());
            headers.clear();
            phase.set(3);
            assertEquals("none", headers.poll(2, TimeUnit.SECONDS));
            await(source::isAlive);
            assertFalse(source.lyric().isEmpty());
            source.stop();
            assertFalse(source.isAlive());
            assertEquals(com.linklyric.core.capability.CapabilitySet.empty(), source.capabilities());
            headers.clear();
            source.start();
            assertEquals("none", headers.poll(2, TimeUnit.SECONDS));
            await(source::isAlive);
        } finally { source.stop(); server.stop(0); }
    }

    @Test
    void lateResponseCannotPublishAfterStopOrOverwriteRestart() throws Exception {
        for (boolean restart : new boolean[]{false, true}) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger requests = new AtomicInteger();
            ExecutorService executor = Executors.newCachedThreadPool();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/lyric", exchange -> {
                int n = requests.incrementAndGet();
                if (n == 1) {
                    entered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                send(exchange, payload(LYRIC).replace("Song", n == 1 ? "Old" : "New"), "\"tag\"");
            });
            server.start();
            BridgePlaybackSource source = new BridgePlaybackSource("127.0.0.1", server.getAddress().getPort());
            try {
                source.start();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                source.stop();
                if (restart) {
                    source.start();
                    source.start();
                    await(source::isAlive);
                    assertEquals("New", source.pollSnapshot().track().title());
                }
                release.countDown();
                Thread.sleep(350);
                if (restart) assertEquals("New", source.pollSnapshot().track().title());
                else {
                    assertFalse(source.isAlive());
                    assertFalse(source.pollSnapshot().hasTrack());
                    assertTrue(source.lyric().isEmpty());
                    assertEquals(1, requests.get());
                }
            } finally { source.stop(); release.countDown(); server.stop(0); executor.shutdownNow(); }
        }
    }

    @Test
    void validatesDepthUnicodeIntegersAndReusesParsedLyric() {
        String body = payload(LYRIC);
        var first = SnapshotParser.parseResponse(body, "test", 1, 0, false);
        assertEquals(9007199254740993L, first.snapshot().positionMs());
        assertNotNull(first.timeline());
        assertNull(SnapshotParser.parseResponse(body, "test", 2, first.signature(), true).timeline());
        assertEquals(Long.MAX_VALUE, SnapshotParser.parseResponse(body.replace("9007199254740993", "9223372036854775807"), "x", 0, 0, false).snapshot().positionMs());
        for (String value : new String[]{"9223372036854775808", "1.5", "1e3", "+1", "01"})
            assertFalse(SnapshotParser.validSnapshot(body.replace("9007199254740993", value)));
        for (String digits : new String[]{"+001", "-001", "00g1", "１２３４"})
            assertFalse(SnapshotParser.validSnapshot(body.replace("Song", "\\u" + digits)));
        assertTrue(SnapshotParser.validSnapshot(body.replace("Song", "\\u0041")));
        assertTrue(SnapshotParser.validSnapshot(body.replace("\"seq\":9007199254740993", "\"seq\":-9223372036854775808")));
        assertFalse(SnapshotParser.validSnapshot(body.replace("\"seq\":9007199254740993", "\"seq\":-9223372036854775809")));
        String legacy = body.replace("\"v\":2,\"type\":\"snapshot\",", "");
        assertTrue(SnapshotParser.parseSnapshot(legacy, "x", 0).hasTrack());
        assertFalse(SnapshotParser.validSnapshot(legacy));
        assertFalse(SnapshotParser.parseSnapshot(body.replace("\"v\":2,", ""), "x", 0).hasTrack());
        assertFalse(SnapshotParser.validSnapshot(body.replace("[\"TRACK_METADATA\"]", "[123]")));
        assertTrue(SnapshotParser.validSnapshot(body.replace("TRACK_METADATA", "FUTURE_CAPABILITY")));
        String deep = "[".repeat(10000) + "0" + "]".repeat(10000);
        assertFalse(SnapshotParser.validSnapshot(payload("\"unknown\":" + deep)));
        assertFalse(SnapshotParser.validSnapshot(body.replace("\"v\":2,", "")));
        assertFalse(SnapshotParser.validSnapshot(body.replace("\"v\":2", "\"v\":3")));
        assertFalse(SnapshotParser.validSnapshot(body.replace("\"playing\":true", "\"playing\":trueish")));
        assertFalse(BridgePlaybackSource.lyricOmitted("{\"nested\":{\"lyricOmitted\":true}}"));
    }
}
