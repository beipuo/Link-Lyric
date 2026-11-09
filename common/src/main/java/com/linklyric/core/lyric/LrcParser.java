// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.core.lyric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses local LRC text into a timeline that can use any playback clock. */
public final class LrcParser {
    private static final String TIME = "([0-9]{1,9}):([0-5][0-9])(?:\\.([0-9]{1,3}))?";
    private static final Pattern LINE_TIME = Pattern.compile("\\[" + TIME + "\\]");
    private static final Pattern WORD_TIME = Pattern.compile("<" + TIME + ">");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?[0-9]{1,10})\\s*\\]",
            Pattern.CASE_INSENSITIVE);
    private static final long LAST_LINE_MS = 4000;

    private LrcParser() {}

    /**
     * Parses line timestamps and Enhanced LRC word timestamps. A separate translation file
     * is optional and is matched by start time after applying each file's offset.
     * Use zero for an unknown track duration. Untimed text and metadata are ignored.
     */
    public static LyricTimeline parse(String lyrics, String translation, long trackDurationMs) {
        Document source = read(lyrics);
        if (source.cues().isEmpty()) return LyricTimeline.empty();

        Map<Long, String> translations = new HashMap<>();
        Document translated = read(translation);
        translated.cues().forEach((start, cues) -> {
            for (Cue cue : cues) {
                String text = plainText(cue.body());
                if (!text.isEmpty()) translations.putIfAbsent(start - translated.offset(), text);
            }
        });

        List<LyricLine> lines = new ArrayList<>();
        for (var entry : source.cues().entrySet()) {
            long rawStart = entry.getKey();
            long start = rawStart - source.offset();
            Long next = source.cues().higherKey(rawStart);
            long end = next != null ? next - source.offset()
                    : trackDurationMs > start && trackDurationMs > 0 ? trackDurationMs : start + LAST_LINE_MS;
            if (end <= 0) continue;

            for (Cue cue : entry.getValue()) {
                String text = plainText(cue.body());
                if (text.isEmpty()) continue;
                String translatedText = translations.getOrDefault(start, "");
                LyricLine line = toLine(cue, text, translatedText, start, end);
                if (line.durationMs() > 0) lines.add(line);
            }
        }
        return lines.isEmpty() ? LyricTimeline.empty() : new LyricTimeline(lines);
    }

    private static Document read(String text) {
        TreeMap<Long, List<Cue>> cues = new TreeMap<>();
        long offset = 0;
        if (text == null) return new Document(cues, offset);
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        for (String line : text.split("\\R")) {
            line = line.strip();
            Matcher offsetTag = OFFSET.matcher(line);
            if (offsetTag.matches()) {
                // Positive LRC offsets advance the lyrics relative to playback.
                offset = Long.parseLong(offsetTag.group(1));
                continue;
            }

            Matcher tags = LINE_TIME.matcher(line);
            if (!tags.lookingAt()) continue;
            long first = timestamp(tags);
            var starts = new LinkedHashSet<Long>();
            int end;
            do {
                starts.add(timestamp(tags));
                end = tags.end();
                tags.region(end, line.length());
            } while (tags.lookingAt());
            Cue cue = new Cue(first, line.substring(end).strip());
            for (long start : starts) {
                cues.computeIfAbsent(start, ignored -> new ArrayList<>()).add(cue);
            }
        }
        return new Document(cues, offset);
    }

    private static LyricLine toLine(Cue cue, String text, String translation, long start, long end) {
        Matcher tags = WORD_TIME.matcher(cue.body());
        if (!tags.find()) return line(text, translation, start, end, List.of());

        List<LyricWord> words = new ArrayList<>();
        long wordStart = start;
        int cursor = 0;
        do {
            long wordEnd = start + (timestamp(tags) - cue.firstStart());
            if (wordEnd < wordStart || wordEnd > end) {
                return line(text, translation, start, end, List.of());
            }
            if (tags.start() > cursor) {
                if (wordEnd == wordStart) {
                    return line(text, translation, start, end, List.of());
                }
                words.add(new LyricWord(cue.body().substring(cursor, tags.start()), wordStart, wordEnd));
            }
            wordStart = wordEnd;
            cursor = tags.end();
        } while (tags.find());

        if (cursor == cue.body().length()) {
            end = wordStart; // A final timestamp without text marks the end of the line.
        } else if (wordStart < end) {
            words.add(new LyricWord(cue.body().substring(cursor), wordStart, end));
        } else {
            return line(text, translation, start, end, List.of());
        }
        if (words.stream().anyMatch(word -> word.durationMs() == 0)) {
            return line(text, translation, start, end, List.of());
        }
        // Preserve the exact text spans used by the glyph renderer.
        return line(WORD_TIME.matcher(cue.body()).replaceAll(""), translation, start, end, words);
    }

    private static LyricLine line(String text, String translation, long start, long end, List<LyricWord> words) {
        return new LyricLine(text, translation, start, end, words, "");
    }

    private static String plainText(String body) {
        return WORD_TIME.matcher(body).replaceAll("").strip();
    }

    private static long timestamp(Matcher tag) {
        String fraction = tag.group(3);
        int millis = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
        return Long.parseLong(tag.group(1)) * 60_000 + Integer.parseInt(tag.group(2)) * 1000L + millis;
    }

    private record Cue(long firstStart, String body) {}

    private record Document(TreeMap<Long, List<Cue>> cues, long offset) {}
}
