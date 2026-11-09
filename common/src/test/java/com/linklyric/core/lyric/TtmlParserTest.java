// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.core.lyric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtmlParserTest {
    @Test
    void resolvesRelativeTimesWithinParentsAndClipsSpans() {
        var timeline = TtmlParser.parse("""
                <tt xmlns="http://www.w3.org/ns/ttml"><body begin="1s"><div begin="2s">
                  <p begin="500ms" dur="1.5s"><span begin="0s" dur="0.5s">One</span> <span begin="500ms" end="3s">two</span></p>
                  <p begin="00:00:04.000" end="6s">Next &amp; last</p>
                </div></body></tt>
                """, 0);

        assertEquals(2, timeline.size());
        assertEquals("One two", timeline.line(0).text());
        assertEquals(List.of(new LyricWord("One ", 3500, 4000), new LyricWord("two", 4000, 5000)),
                timeline.line(0).words());
        assertEquals("Next & last", timeline.lineAt(7500).text());
        assertEquals(9000, timeline.line(1).endMs());
    }

    @Test
    void readsAbsoluteAppleMusicWordsAndInlineTranslation() {
        var timeline = TtmlParser.parse("""
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:a="http://music.apple.com/lyric-ttml-internal"
                    xmlns:m="http://www.w3.org/ns/ttml#metadata" a:timing="Word">
                  <body><div begin="00:04.000"><p begin="5.0" end="6.5" m:agent="v1"><span begin="5.0" end="5.5">你</span><span begin="00:05.5" end="6.5">好</span><span m:role="x-translation">Hello</span><span m:role="x-bg">Background</span></p></div></body>
                </tt>
                """, 0);

        var line = timeline.line(0);
        assertEquals("你好", line.text());
        assertEquals("Hello", line.translation());
        assertEquals("v1", line.speaker());
        assertEquals(List.of(new LyricWord("你", 5000, 5500), new LyricWord("好", 5500, 6500)), line.words());
    }

    @Test
    void infersMissingEndsAndIgnoresInvalidCues() {
        var timeline = TtmlParser.parse("""
                <tt><body><div><p begin="invalid">Ignored</p><p begin="2s">First</p>
                <p begin="4s">Last</p></div></body></tt>
                """, 6000);

        assertEquals(List.of(4000L, 6000L), timeline.lines().stream().map(LyricLine::endMs).toList());
    }

    @Test
    void rejectsMalformedXmlExternalEntitiesAndDeepNesting() {
        assertTrue(TtmlParser.parse("<tt><body>", 0).isEmpty());
        assertTrue(TtmlParser.parse("""
                <!DOCTYPE tt [<!ENTITY external SYSTEM "file:///private.txt">]>
                <tt><body><div><p begin="1s" end="2s">&external;</p></div></body></tt>
                """, 0).isEmpty());
        String nested = "<div>".repeat(100) + "<p begin='1s'>Text</p>" + "</div>".repeat(100);
        assertTrue(TtmlParser.parse("<tt><body>" + nested + "</body></tt>", 0).isEmpty());
    }
}
