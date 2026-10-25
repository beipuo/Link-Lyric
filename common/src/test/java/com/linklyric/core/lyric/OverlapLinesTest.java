package com.linklyric.core.lyric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间重叠行的查询测试。
 *
 * <p>对唱、主唱与伴唱叠加时两行时间重叠是正常数据。只按开始时间取一行，
 * 另一行连词带译文都会消失，所以这里守住「重叠时两行都能取到」。
 */
class OverlapLinesTest {

    private static LyricLine line(String text, long start, long end) {
        return new LyricLine(text, "", start, end, List.of(), "");
    }

    @Test
    void 重叠的两行同时活跃() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("主唱", 0, 3000),
                line("伴唱", 2000, 4000)));

        // 2500ms 落在两行之内。
        List<LyricLine> active = timeline.activeLinesAt(2500);
        assertEquals(2, active.size(), "重叠应两行都活跃，实际=" + active.size());
        assertEquals("主唱", active.get(0).text(), "应按开始时间升序");
        assertEquals("伴唱", active.get(1).text());
    }

    @Test
    void 无重叠时只返回当前行() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句", 0, 1000),
                line("第二句", 2000, 3000)));

        List<LyricLine> active = timeline.activeLinesAt(2500);
        assertEquals(1, active.size());
        assertEquals("第二句", active.get(0).text());
    }

    @Test
    void 前一行已结束则不参与() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句", 0, 2000),
                line("第二句", 1000, 3000)));

        // 2500ms：第一句已结束，只剩第二句。
        List<LyricLine> active = timeline.activeLinesAt(2500);
        assertEquals(1, active.size());
        assertEquals("第二句", active.get(0).text());
    }

    @Test
    void 尚未开始时不返回任何行() {
        LyricTimeline timeline = new LyricTimeline(List.of(line("第一句", 1000, 2000)));
        assertTrue(timeline.activeLinesAt(500).isEmpty());
    }

    @Test
    void 空时间轴不崩溃() {
        assertTrue(LyricTimeline.empty().activeLinesAt(1000).isEmpty());
    }

    @Test
    void 三行连续重叠全部取到() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("甲", 0, 4000),
                line("乙", 1000, 5000),
                line("丙", 2000, 6000)));

        assertEquals(3, timeline.activeLinesAt(3000).size());
        // 单行索引仍返回最后开始的那一行，保持既有语义。
        assertEquals(2, timeline.indexAt(3000));
    }
}
