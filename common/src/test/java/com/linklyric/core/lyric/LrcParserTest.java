// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.core.lyric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LrcParserTest {
    @Test
    void sortsRepeatedCuesAndUsesBlankCuesAsEndTimes() {
        var timeline = LrcParser.parse("\uFEFF[ar:Artist]\r\n[00:08][00:01.2]Repeat\r\n"
                + "[00:03.045]Middle\r\n[00:06.05]\r\n[00:10]Last", null, 12_000);

        assertEquals(List.of("Repeat", "Middle", "Repeat", "Last"),
                timeline.lines().stream().map(LyricLine::text).toList());
        assertEquals(List.of(1200L, 3045L, 8000L, 10_000L),
                timeline.lines().stream().map(LyricLine::startMs).toList());
        assertEquals(List.of(3045L, 6050L, 10_000L, 12_000L),
                timeline.lines().stream().map(LyricLine::endMs).toList());
        assertTrue(timeline.isFinishedAt(7000));
    }

    @Test
    void shiftsWordTimestampsWithRepeatsAndHonorsTerminalMarkers() {
        var timeline = LrcParser.parse(
                "[00:01.00][00:09.00]<00:01.10>Hello <00:01.50>世界<00:02.00>\n[offset:+100]",
                null, 15_000);

        assertEquals("Hello 世界", timeline.line(0).text());
        assertEquals(900L, timeline.line(0).startMs());
        assertEquals(1900L, timeline.line(0).endMs());
        assertEquals(List.of(new LyricWord("Hello ", 1000, 1400), new LyricWord("世界", 1400, 1900)),
                timeline.line(0).words());
        assertEquals(List.of(new LyricWord("Hello ", 9000, 9400), new LyricWord("世界", 9400, 9900)),
                timeline.line(1).words());
    }

    @Test
    void matchesTranslationsAtOffsetAdjustedStarts() {
        var timeline = LrcParser.parse("[00:00.10]Discarded\n[00:00.30]First\n[00:03]Second\n[offset:500]",
                "[offset:-200]\n[00:02.30]译文\n[00:02.31]Unmatched", 0);

        assertEquals(2, timeline.size());
        assertEquals(0L, timeline.line(0).startMs());
        assertEquals("", timeline.line(0).translation());
        assertEquals("译文", timeline.line(1).translation());
        assertEquals(6500L, timeline.line(1).endMs());
    }

    @Test
    void invalidWordOrderFallsBackToWholeLineTiming() {
        var timeline = LrcParser.parse("[00:01]<00:02>One <00:01.5>two\n[00:03]Next", null, 0);

        assertEquals("One two", timeline.line(0).text());
        assertEquals(List.of(new LyricWord("One two", 1000, 3000)), timeline.line(0).words());
    }

    @Test
    void skipsEnhancedLinesThatEndBeforePlaybackStarts() {
        var timeline = LrcParser.parse("[offset:2500]\n[00:01]<00:01>Intro<00:02>\n[00:03]Verse", null, 0);

        assertEquals(1, timeline.size());
        assertEquals("Verse", timeline.line(0).text());
        assertEquals(500L, timeline.line(0).startMs());
    }

    @Test
    void emptyAndUntimedContentHaveNoTimeline() {
        assertTrue(LrcParser.parse(null, "[00:01]Translation only", 0).isEmpty());
        assertTrue(LrcParser.parse("[ti:Title]\n[00:61.00]Invalid\n[00:03]\nNo timestamp", null, 0).isEmpty());
    }
}
