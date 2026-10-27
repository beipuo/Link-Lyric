package com.linklyric.common.bridge;

import com.linklyric.core.capability.Capability;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.protocol.Protocol;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Dependency-free v2 reader. Direct callers also accept legacy envelopes with both v and type absent.
 * HTTP requires both fields. Unknown fields and capability names are ignored, not wrong field types.
 */
public final class SnapshotParser {
    private static final int MAX_DEPTH = 64;
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][+-]?[0-9]+)?");
    private SnapshotParser() { }

    record Parsed(PlayerSnapshot snapshot, boolean omitted, int signature, LyricTimeline timeline) { }

    static Parsed parseResponse(String json, String sourceId, long now, int previousSignature,
                                boolean cached) {
        return parse(json, sourceId, now, previousSignature, cached, true);
    }

    private static Parsed parse(String json, String sourceId, long now, int previousSignature,
                                boolean cached, boolean strictEnvelope) {
        Map<String, Value> root = root(json);
        if (strictEnvelope || root.containsKey("v") || root.containsKey("type")) {
            if (require(root, "v").integer() != 2
                    || !"snapshot".equals(require(root, "type").string()))
                throw new IllegalArgumentException("unsupported envelope");
        }
        Map<String, Value> track = require(root, "track").object();
        TrackIdentity identity = new TrackIdentity(require(track, "title").string(),
                require(track, "artist").string(), optionalString(track, "album"),
                require(track, "durationMs").integer());
        List<Capability> names = new ArrayList<>();
        if (root.containsKey("capabilities")) for (Value value : root.get("capabilities").array()) {
            String name = value.string();
            try { names.add(Capability.valueOf(name)); }
            catch (IllegalArgumentException ignored) { }
        }
        CapabilitySet capabilities = names.isEmpty() ? CapabilitySet.empty()
                : CapabilitySet.of(names.toArray(new Capability[0]));
        PlayerSnapshot snapshot = new PlayerSnapshot(sourceId, identity,
                require(root, "positionMs").integer(), require(root, "playing").bool(),
                require(root, "seq").integer(), now, capabilities);
        boolean omitted = root.containsKey("lyricOmitted") && root.get("lyricOmitted").bool();
        Value lyric = root.get("lyric");
        if (omitted && lyric != null) throw new IllegalArgumentException("omitted lyric present");
        int signature = lyric == null ? 0 : lyric.signature();
        boolean build = !omitted && (!cached || signature != previousSignature);
        LyricTimeline timeline = readLyric(lyric, build);
        return new Parsed(snapshot, omitted, signature, timeline);
    }

    public static PlayerSnapshot parseSnapshot(String json, String sourceId, long now) {
        try { return parse(json, sourceId, now, 0, true, false).snapshot(); }
        catch (RuntimeException e) {
            return new PlayerSnapshot(sourceId, TrackIdentity.of("", "", 0L), 0L, false, 0L,
                    now, CapabilitySet.empty());
        }
    }

    static boolean validSnapshot(String json) {
        try { parseResponse(json, "", 0, 0, true); return true; }
        catch (RuntimeException e) { return false; }
    }

    public static LyricTimeline parseLyric(String json) {
        try { return readLyric(root(json).get("lyric"), true); }
        catch (RuntimeException e) { return LyricTimeline.empty(); }
    }

    private static LyricTimeline readLyric(Value value, boolean build) {
        if (value == null) return build ? LyricTimeline.empty() : null;
        Map<String, Value> lyric = value.object();
        if (lyric.containsKey("wordLevel")) lyric.get("wordLevel").bool();
        List<LyricLine> lines = build ? new ArrayList<>() : null;
        for (Value lineValue : require(lyric, "lines").array()) {
            Map<String, Value> line = lineValue.object();
            String text = require(line, "text").string();
            String translation = optionalString(line, "translation");
            String speaker = optionalString(line, "speaker");
            long start = require(line, "startMs").integer();
            long end = require(line, "endMs").integer();
            List<LyricWord> words = build ? new ArrayList<>() : null;
            if (line.containsKey("words")) for (Value wordValue : line.get("words").array()) {
                Map<String, Value> word = wordValue.object();
                String wordText = require(word, "text").string();
                long wordStart = require(word, "startMs").integer();
                long wordEnd = require(word, "endMs").integer();
                if (build && !wordText.isEmpty()) words.add(new LyricWord(wordText, wordStart, wordEnd));
            }
            if (build && !text.isEmpty()) lines.add(new LyricLine(text, translation, start, end, words, speaker));
        }
        return !build ? null : lines.isEmpty() ? LyricTimeline.empty() : new LyricTimeline(lines);
    }

    static boolean lyricOmitted(String json) {
        try {
            Value value = root(json).get("lyricOmitted");
            return value != null && value.bool();
        } catch (RuntimeException e) { return false; }
    }

    static int lyricSignature(String json) {
        try {
            Value value = root(json).get("lyric");
            if (value == null) return 0;
            value.object();
            return value.signature();
        } catch (RuntimeException e) { return 0; }
    }

    private static String optionalString(Map<String, Value> map, String key) {
        return map.containsKey(key) ? map.get(key).string() : "";
    }
    private static Value require(Map<String, Value> map, String key) {
        Value value = map.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }
    private static Map<String, Value> root(String json) {
        if (json == null || json.length() > Protocol.MAX_PAYLOAD_CHARS) throw new IllegalArgumentException("payload");
        Cursor cursor = new Cursor(json);
        Map<String, Value> result = cursor.value(0).object();
        cursor.ws();
        if (cursor.pos != json.length()) throw new IllegalArgumentException("trailing data");
        return result;
    }

    private record Numeric(String token) { }
    private record Value(Object data, String source, int start, int end) {
        @SuppressWarnings("unchecked")
        Map<String, Value> object() {
            if (!(data instanceof Map)) throw new IllegalArgumentException("object");
            return (Map<String, Value>) data;
        }
        @SuppressWarnings("unchecked")
        List<Value> array() {
            if (!(data instanceof List)) throw new IllegalArgumentException("array");
            return (List<Value>) data;
        }
        String string() {
            if (!(data instanceof String text)) throw new IllegalArgumentException("string");
            return text;
        }
        long integer() {
            if (!(data instanceof Numeric number)) throw new IllegalArgumentException("integer");
            return Long.parseLong(number.token());
        }
        boolean bool() {
            if (!(data instanceof Boolean flag)) throw new IllegalArgumentException("boolean");
            return flag;
        }
        int signature() { return BridgePlaybackSource.hashRange(source, start, end); }
    }

    private static final class Cursor {
        private final String source;
        private int pos;
        Cursor(String source) { this.source = source; }
        void ws() { while (pos < source.length() && " \r\n\t".indexOf(source.charAt(pos)) >= 0) pos++; }
        boolean take(char ch) { ws(); if (pos < source.length() && source.charAt(pos) == ch) { pos++; return true; } return false; }
        void expect(char ch) { if (!take(ch)) throw new IllegalArgumentException("expected " + ch); }
        Value value(int depth) {
            if (depth >= MAX_DEPTH) throw new IllegalArgumentException("nesting limit");
            ws();
            int start = pos;
            if (pos == source.length()) throw new IllegalArgumentException("missing value");
            Object data;
            char ch = source.charAt(pos);
            if (ch == '{') {
                pos++;
                Map<String, Value> fields = new LinkedHashMap<>();
                if (!take('}')) {
                    do {
                        String key = string();
                        expect(':');
                        if (fields.putIfAbsent(key, value(depth + 1)) != null)
                            throw new IllegalArgumentException("duplicate field");
                        if (take('}')) break;
                        expect(',');
                    } while (true);
                }
                data = fields;
            } else if (ch == '[') {
                pos++;
                List<Value> values = new ArrayList<>();
                if (!take(']')) {
                    do {
                        values.add(value(depth + 1));
                        if (take(']')) break;
                        expect(',');
                    } while (true);
                }
                data = values;
            } else if (ch == '"') data = string();
            else {
                while (pos < source.length() && ",]} \r\n\t".indexOf(source.charAt(pos)) < 0) pos++;
                String token = source.substring(start, pos);
                data = switch (token) {
                    case "true" -> true;
                    case "false" -> false;
                    case "null" -> null;
                    default -> {
                        if (!NUMBER.matcher(token).matches()) throw new IllegalArgumentException("number");
                        yield new Numeric(token);
                    }
                };
            }
            return new Value(data, source, start, pos);
        }
        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < source.length()) {
                char ch = source.charAt(pos++);
                if (ch == '"') return out.toString();
                if (ch < 0x20) throw new IllegalArgumentException("control");
                if (ch != '\\') { out.append(ch); continue; }
                if (pos == source.length()) throw new IllegalArgumentException("escape");
                char escaped = source.charAt(pos++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > source.length()) throw new IllegalArgumentException("unicode");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char digit = source.charAt(pos++);
                            int hex = digit >= '0' && digit <= '9' ? digit - '0'
                                    : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                                    : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                            if (hex < 0) throw new IllegalArgumentException("unicode");
                            code = (code << 4) | hex;
                        }
                        out.append((char) code);
                    }
                    default -> throw new IllegalArgumentException("escape");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }
    }
}
