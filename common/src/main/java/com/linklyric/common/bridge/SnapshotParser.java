package com.linklyric.common.bridge;

import com.linklyric.core.capability.Capability;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 Link MC 插件输出的快照 JSON。
 *
 * <p>不引入 JSON 库：结构固定且扁平，手写解析可以避免把 gson 这类依赖
 * 打进模组包（Minecraft 运行时不保证提供）。解析器只做「取字段」，
 * 遇到任何异常一律降级为空快照，绝不抛给调用方。
 *
 * <p>字段契约见 {@code Salt Music/Link MC} 的 {@code BridgeServer.renderJson()}。
 */
public final class SnapshotParser {

    private SnapshotParser() {
    }

    /**
     * 解析完整快照。失败时返回 {@code hasTrack() == false} 的空快照。
     *
     * @param json        原始响应体，可为 null
     * @param sourceId    数据源标识（由调用方给出，不从 JSON 读，避免自我欺骗）
     * @param receivedAtNanos 本地接收时刻，用于诊断时钟偏移
     */
    public static PlayerSnapshot parseSnapshot(String json, String sourceId, long receivedAtNanos) {
        if (json == null || json.isEmpty()) {
            return emptySnapshot(sourceId, receivedAtNanos);
        }
        try {
            String trackJson = objectAt(json, "track");
            TrackIdentity track = trackJson == null
                    ? TrackIdentity.of("", "", 0L)
                    : new TrackIdentity(
                            stringAt(trackJson, "title"),
                            stringAt(trackJson, "artist"),
                            stringAt(trackJson, "album"),
                            longAt(trackJson, "durationMs"));

            // 歌词不走这里：它体积远大于快照且只在换曲时变化，
            // 由 parseLyric 单独取一次，避免每 200ms 复制一遍整份时间轴。
            CapabilitySet capabilities = parseCapabilities(arrayAt(json, "capabilities"));

            return new PlayerSnapshot(
                    sourceId,
                    track,
                    longAt(json, "positionMs"),
                    boolAt(json, "playing"),
                    longAt(json, "seq"),
                    receivedAtNanos,
                    capabilities);
        } catch (RuntimeException e) {
            return emptySnapshot(sourceId, receivedAtNanos);
        }
    }

    /**
     * 解析歌词时间轴。歌词与快照分两次读取：时间轴体积远大于快照，且只在换曲时变化。
     *
     * <p>返回空时间轴表示「当前无歌词」，调用方不应据此清空已显示的内容——
     * 是否清空由换曲逻辑决定。
     */
    public static LyricTimeline parseLyric(String json) {
        if (json == null || json.isEmpty()) {
            return LyricTimeline.empty();
        }
        try {
            String lyricJson = objectAt(json, "lyric");
            if (lyricJson == null) {
                return LyricTimeline.empty();
            }
            String linesJson = arrayAt(lyricJson, "lines");
            if (linesJson == null) {
                return LyricTimeline.empty();
            }
            List<LyricLine> lines = new ArrayList<>();
            for (String lineJson : splitArray(linesJson)) {
                if (lineJson == null || lineJson.isEmpty()) {
                    continue;
                }
                String text = stringAt(lineJson, "text");
                if (text.isEmpty()) {
                    continue;
                }
                List<LyricWord> words = new ArrayList<>();
                String wordsJson = arrayAt(lineJson, "words");
                if (wordsJson != null) {
                    for (String wordJson : splitArray(wordsJson)) {
                        if (wordJson == null || wordJson.isEmpty()) {
                            continue;
                        }
                        String wordText = stringAt(wordJson, "text");
                        if (wordText.isEmpty()) {
                            continue;
                        }
                        words.add(new LyricWord(
                                wordText,
                                longAt(wordJson, "startMs"),
                                longAt(wordJson, "endMs")));
                    }
                }
                lines.add(new LyricLine(
                        text,
                        stringAt(lineJson, "translation"),
                        longAt(lineJson, "startMs"),
                        longAt(lineJson, "endMs"),
                        words,
                        stringAt(lineJson, "speaker")));
            }
            return lines.isEmpty() ? LyricTimeline.empty() : new LyricTimeline(lines);
        } catch (RuntimeException e) {
            return LyricTimeline.empty();
        }
    }

    private static PlayerSnapshot emptySnapshot(String sourceId, long receivedAtNanos) {
        return new PlayerSnapshot(
                sourceId, TrackIdentity.of("", "", 0L), 0L, false, 0L, receivedAtNanos, CapabilitySet.empty());
    }

    private static CapabilitySet parseCapabilities(String arrayJson) {
        if (arrayJson == null || arrayJson.isEmpty()) {
            return CapabilitySet.empty();
        }
        List<Capability> found = new ArrayList<>();
        for (String raw : splitArray(arrayJson)) {
            String name = stripQuotes(raw);
            if (name.isEmpty()) {
                continue;
            }
            try {
                found.add(Capability.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // 数据源声明了本端不认识的能力：忽略而非失败，便于双向升级
            }
        }
        return found.isEmpty()
                ? CapabilitySet.empty()
                : CapabilitySet.of(found.toArray(new Capability[0]));
    }

    // ---------------- 极简 JSON 取值 ----------------

    /** 取出指定键的对象或数组字面量（含外层括号）。找不到返回 null。 */
    private static String objectAt(String json, String key) {
        int start = indexOfValue(json, key);
        if (start < 0) {
            return null;
        }
        char open = json.charAt(start);
        if (open != '{' && open != '[') {
            return null;
        }
        return sliceBalanced(json, start);
    }

    /** 取出数组字面量。 */
    private static String arrayAt(String json, String key) {
        int start = indexOfValue(json, key);
        if (start < 0 || json.charAt(start) != '[') {
            return null;
        }
        return sliceBalanced(json, start);
    }

    /** 取出字符串值，未找到时返回空串。 */
    private static String stringAt(String json, String key) {
        int start = indexOfValue(json, key);
        if (start < 0 || json.charAt(start) != '"') {
            return "";
        }
        return readString(json, start);
    }

    private static long longAt(String json, String key) {
        int start = indexOfValue(json, key);
        if (start < 0) {
            return 0L;
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9')) {
                end++;
            } else {
                break;
            }
        }
        if (end == start) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean boolAt(String json, String key) {
        int start = indexOfValue(json, key);
        return start >= 0 && json.startsWith("true", start);
    }

    /**
     * 定位某个键对应的值起始位置。
     *
     * <p>直接搜 {@code "key"} 会在值里出现同名文本时误命中，
     * 因此要求冒号紧随其后，且键必须被引号完整包裹。
     */
    private static int indexOfValue(String json, String key) {
        String needle = '"' + key + '"';
        int from = 0;
        while (true) {
            int idx = json.indexOf(needle, from);
            if (idx < 0) {
                return -1;
            }
            int cursor = idx + needle.length();
            while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
                cursor++;
            }
            if (cursor < json.length() && json.charAt(cursor) == ':') {
                cursor++;
                while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
                    cursor++;
                }
                return cursor < json.length() ? cursor : -1;
            }
            from = idx + needle.length();
        }
    }

    /** 从 open 位置起截取配平的括号片段，正确处理字符串内的括号与转义。 */
    private static String sliceBalanced(String json, int open) {
        char openChar = json.charAt(open);
        char closeChar = openChar == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return json.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    /** 把数组字面量拆成元素文本（对象/字符串/数字原样保留）。 */
    private static List<String> splitArray(String arrayJson) {
        List<String> out = new ArrayList<>();
        if (arrayJson.length() < 2) {
            return out;
        }
        int i = 1;
        int end = arrayJson.length() - 1;
        while (i < end) {
            // 先吃掉分隔符与空白：JSON 常见换行缩进，不跳过会被当成元素起点
            char c = arrayJson.charAt(i);
            if (c == ',' || c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                i++;
                continue;
            }
            if (c == '{' || c == '[') {
                // 必须在完整串上切片：截断尾部会丢失闭合括号，导致配平失败
                String slice = sliceBalanced(arrayJson, i);
                if (slice == null) {
                    break;
                }
                out.add(slice);
                i += slice.length();
            } else if (c == '"') {
                int close = stringEnd(arrayJson, i);
                if (close < 0) {
                    break;
                }
                out.add(arrayJson.substring(i, close + 1));
                i = close + 1;
            } else {
                int start = i;
                while (i < end && arrayJson.charAt(i) != ',') {
                    i++;
                }
                String raw = arrayJson.substring(start, i).trim();
                if (!raw.isEmpty()) {
                    out.add(raw);
                }
            }
        }
        return out;
    }

    private static int stringEnd(String json, int quoteStart) {
        boolean escaped = false;
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /** 读出字符串值并反转义。 */
    private static String readString(String json, int quoteStart) {
        int end = stringEnd(json, quoteStart);
        if (end < 0) {
            return "";
        }
        return unescape(json.substring(quoteStart + 1, end));
    }

    private static String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            return unescape(trimmed.substring(1, trimmed.length() - 1));
        }
        return trimmed;
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                sb.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        try {
                            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append(next);
                        }
                    } else {
                        sb.append(next);
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
