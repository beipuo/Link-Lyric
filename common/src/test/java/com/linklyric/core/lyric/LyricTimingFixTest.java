package com.linklyric.core.lyric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字时间修补的回归测试。
 *
 * <p>这些用例全部来自真实歌词源的残缺形态，不是为了覆盖率凑出来的：
 * 逐字时间用尽、末端零时长、行末缺失、只有字级计时而无行长。
 */
class LyricTimingFixTest {

    private static LyricWord w(String text, long start, long end) {
        return new LyricWord(text, start, end);
    }

    @Test
    void 末端零时长的字分到行末而不是一帧跳完() {
        // 前三个字有时间，最后一个 end == start：行中间看不出问题，
        // 但行末没有下一个字可以接住填充。
        LyricLine line = new LyricLine("一二三四", "", 0, 4000, List.of(
                w("一", 0, 1000), w("二", 1000, 2000), w("三", 2000, 3000), w("四", 3000, 3000)), "");

        LyricLine fixed = LyricTimingFix.fixLine(line, Long.MAX_VALUE);
        LyricWord last = fixed.words().get(3);

        assertTrue(last.durationMs() > 0, "末端字应获得正时长，实际=" + last.durationMs());
        assertEquals(4000, last.endMs(), "应补到行末");
    }

    @Test
    void 修补后末端字在行末之前不会整行已唱() {
        LyricLine line = new LyricLine("一二三四", "", 0, 4000, List.of(
                w("一", 0, 1000), w("二", 1000, 2000), w("三", 2000, 3000), w("四", 3000, 3000)), "");
        LyricLine fixed = LyricTimingFix.fixLine(line, Long.MAX_VALUE);

        // 3000ms 时第四个字刚开始唱，不该已经满进度。
        assertEquals(0f, fixed.words().get(3).progressAt(3000), 1e-6f);
        assertEquals(0.5f, fixed.words().get(3).progressAt(3500), 1e-6f, "应扫过去而不是瞬间跳");
    }

    @Test
    void 一整段无时间的尾巴均分剩余时间() {
        LyricLine line = new LyricLine("一二三四", "", 0, 4000, List.of(
                w("一", 0, 1000), w("二", 1000, 1000), w("三", 1000, 1000), w("四", 1000, 1000)), "");

        LyricLine fixed = LyricTimingFix.fixLine(line, Long.MAX_VALUE);
        // 二三四 三个字均分 1000..4000。
        assertEquals(2000, fixed.words().get(1).endMs());
        assertEquals(3000, fixed.words().get(2).endMs());
        assertEquals(4000, fixed.words().get(3).endMs());
    }

    @Test
    void 有后续更晚的字时以那个字为界() {
        LyricLine line = new LyricLine("一二三", "", 0, 5000, List.of(
                w("一", 0, 1000), w("二", 1000, 1000), w("三", 3000, 4000)), "");

        LyricLine fixed = LyricTimingFix.fixLine(line, Long.MAX_VALUE);
        assertEquals(3000, fixed.words().get(1).endMs(), "应以第三个字的开始为界");
    }

    @Test
    void 行末不晚于该段起点时用下一行开始() {
        // 只按字计时的文件：行末等于最后一个字的开始。
        LyricLine line = new LyricLine("一二", "", 0, 2000, List.of(
                w("一", 0, 1000), w("二", 2000, 2000)), "");

        LyricLine fixed = LyricTimingFix.fixLine(line, 3000);
        assertEquals(3000, fixed.words().get(1).endMs(), "应取样下一行开始");
    }

    @Test
    void 等待超过上限时退回名义时长而不吃掉间奏() {
        // 下一行在 60 秒后：没人能拖过间奏，应退回名义时长，
        // 把这段空白交给间奏提示。
        LyricLine line = new LyricLine("一二", "", 0, 2000, List.of(
                w("一", 0, 1000), w("二", 2000, 2000)), "");

        LyricLine fixed = LyricTimingFix.fixLine(line, 60000);
        assertEquals(2000 + LyricTimingFix.NOMINAL_WORD_MS,
                fixed.words().get(1).endMs(), "应退回名义时长");
    }

    @Test
    void 行级歌词不受影响() {
        // 单个覆盖全行的单元：时长由行给出，无残缺可补。
        LyricLine line = new LyricLine("整行歌词", "", 1000, 5000, List.of(), "");
        LyricLine fixed = LyricTimingFix.fixLine(line, 9000);
        assertEquals(line, fixed, "行级歌词应原样返回");
    }

    @Test
    void 完整数据不被改动() {
        LyricLine line = new LyricLine("一二", "", 0, 2000, List.of(
                w("一", 0, 1000), w("二", 1000, 2000)), "");
        assertTrue(LyricTimingFix.fixLine(line, 5000) == line, "无需修补时应返回同一实例");
    }

    @Test
    void 时间轴构造后末端字已有正时长() {
        LyricLine broken = new LyricLine("一二三四", "", 0, 4000, List.of(
                w("一", 0, 1000), w("二", 1000, 2000), w("三", 2000, 3000), w("四", 3000, 3000)), "");
        LyricTimeline timeline = new LyricTimeline(List.of(broken));

        LyricWord last = timeline.lines().get(0).words().get(3);
        assertTrue(last.durationMs() > 0, "时间轴应已修补，实际=" + last.durationMs());
    }
}
