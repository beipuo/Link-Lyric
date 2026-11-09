// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.common.music.platform;

import com.linklyric.common.music.MusicProvider;
import com.linklyric.common.music.MusicSnapshot.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** One child process, no listening socket. All protocol I/O stays off the game thread. */
public final class WindowsMediaProvider implements MusicProvider {
    private static final int MAX_SESSIONS = 8;
    private static final int MAX_LINE = Artwork.MAX_BYTES * 4 / 3 + 16_384;
    private static final String HELLO = "HELLO 1";
    private final Path executable;
    private final HashMap<String, CachedCover> covers = new HashMap<>();
    private Connection connection;
    private boolean closed;

    private record CachedCover(String track, Artwork artwork) {}

    public WindowsMediaProvider(Path executable) { this.executable = java.util.Objects.requireNonNull(executable); }

    @Override public String id() { return "windows-smtc"; }

    @Override public Poll poll() throws IOException {
        Connection active = null;
        try {
            active = connect();
            if (!active.ready) {
                active.send(HELLO);
                String hello = active.receive(System.nanoTime() + TimeUnit.SECONDS.toNanos(8));
                if ("UNSUPPORTED".equals(hello)) throw new Unsupported("Windows version lacks GSMTC");
                if (!HELLO.equals(hello)) throw new Unsupported("Windows media protocol version mismatch");
                active.ready = true;
            }
            active.send("POLL META");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            String header = active.receive(deadline);
            if (!header.startsWith("BEGIN\t")) throw new IOException("Missing snapshot header");
            var sessions = new ArrayList<Session>();
            var seen = new HashSet<String>();
            for (;;) {
                String line = active.receive(deadline);
                if (line.equals("END")) break;
                if (sessions.size() == MAX_SESSIONS) throw new IOException("Too many media sessions");
                var session = decode(line);
                if (!seen.add(session.id())) throw new IOException("Duplicate media session");
                sessions.add(session);
            }
            covers.keySet().retainAll(seen);
            return new Poll(sessions, decodeText(header.substring(6)));
        } catch (IOException | RuntimeException e) {
            disconnect(active);
            covers.clear();
            if (e instanceof IOException io) throw io;
            throw new IOException("Malformed media response", e);
        }
    }

    private Session decode(String line) throws IOException {
        var fields = line.split("\t", -1);
        if (fields.length != 11 || !fields[0].equals("S")) throw new IOException("Malformed session");
        String id = decodeText(fields[1]);
        String key = fields[2];
        Track track = key.isEmpty() ? null : Track.of(key, decodeText(fields[3]), decodeText(fields[4]), decodeText(fields[5]));
        PlaybackStatus status = switch (fields[6]) {
            case "PLAYING" -> PlaybackStatus.PLAYING;
            case "PAUSED" -> PlaybackStatus.PAUSED;
            case "STOPPED", "CLOSED" -> PlaybackStatus.STOPPED;
            case "CHANGING" -> PlaybackStatus.BUFFERING;
            default -> PlaybackStatus.UNKNOWN;
        };
        var capabilities = EnumSet.noneOf(Capability.class);
        if (track != null) capabilities.add(Capability.METADATA);
        if (status != PlaybackStatus.UNKNOWN) capabilities.add(Capability.PLAYBACK_STATE);
        long position = Long.parseLong(fields[7]), duration = Long.parseLong(fields[8]);
        if (position >= 0) capabilities.add(Capability.TIMELINE);
        Artwork art = null;
        if (track != null) {
            CachedCover cached = covers.get(id);
            String value = fields[10];
            if (value.equals("=")) {
                if (cached != null && cached.track().equals(key)) art = cached.artwork();
            } else if (!value.equals("-")) {
                try { art = new Artwork(Base64.getDecoder().decode(value)); }
                catch (IllegalArgumentException ignored) { /* A cover failure does not hide playback. */ }
            }
            covers.put(id, new CachedCover(key, art));
        } else covers.remove(id);
        if (art != null) capabilities.add(Capability.ARTWORK);
        return new Session(id, track, status, position < 0 ? OptionalLong.empty() : OptionalLong.of(position),
                duration <= 0 ? OptionalLong.empty() : OptionalLong.of(duration),
                fields[9].isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(fields[9])),
                System.nanoTime(), art, capabilities);
    }

    private synchronized Connection connect() throws IOException {
        if (closed) throw new IOException("Media provider closed");
        if (connection != null) return connection;
        if (!java.nio.file.Files.isRegularFile(executable)) throw new Unsupported("Configured Windows bridge is missing");
        connection = new Connection(new ProcessBuilder(List.of(executable.toAbsolutePath().toString()))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start());
        return connection;
    }

    private synchronized void disconnect(Connection expected) {
        if (expected == null || expected != connection) return;
        connection = null;
        expected.close();
    }

    @Override public synchronized void close() {
        closed = true;
        disconnect(connection);
    }

    private static String decodeText(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static final class Connection {
        private static final String EOF = new String();
        private final Process process;
        private final ArrayBlockingQueue<String> lines = new ArrayBlockingQueue<>(MAX_SESSIONS + 3);
        private final Thread reader;
        private boolean ready;

        private Connection(Process process) {
            this.process = process;
            reader = new Thread(this::read, "shb-media-pipe");
            reader.setDaemon(true);
            reader.start();
        }

        private void send(String command) throws IOException {
            var input = process.getOutputStream();
            input.write((command + "\n").getBytes(StandardCharsets.US_ASCII));
            input.flush();
        }

        private String receive(long deadline) throws IOException {
            try {
                String line = lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null || line == EOF) throw new IOException("Media bridge disconnected or timed out");
                return line;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Media bridge interrupted", e);
            }
        }

        private void read() {
            try (var stream = new BufferedInputStream(process.getInputStream())) {
                for (;;) {
                    String line = readLine(stream);
                    if (line == null || !lines.offer(line)) break;
                }
            } catch (IOException ignored) {
                // The media worker handles EOF, cancellation and retry.
            } finally {
                lines.offer(EOF);
            }
        }

        private static String readLine(InputStream stream) throws IOException {
            var bytes = new ByteArrayOutputStream(256);
            for (int b; (b = stream.read()) >= 0;) {
                if (b == '\n') return bytes.toString(StandardCharsets.US_ASCII);
                if (b != '\r') bytes.write(b);
                if (bytes.size() > MAX_LINE) throw new IOException("Oversized media frame");
            }
            return null;
        }

        private void close() {
            try {
                process.getOutputStream().close();
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (IOException ignored) {
                process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            } finally {
                reader.interrupt();
                lines.clear();
                lines.offer(EOF);
            }
        }
    }
}
