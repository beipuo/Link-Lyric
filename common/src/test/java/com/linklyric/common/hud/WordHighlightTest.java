package com.linklyric.common.hud;

import com.linklyric.common.bridge.SnapshotParser;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字高亮的渲染验证。
 *
 * <p>焦点是「已唱宽度」的计算：它决定高亮推进到哪个像素，
 * 算错就会表现为整字闪变、高亮越界或提前唱完。
 */
class WordHighlightTest {

    /** 每字符固定 10 像素，便于断言精确宽度。 */
    private static final int CHAR_WIDTH = 10;

    private static LyricLine line(String text, long start, long end, LyricWord... words) {
        return new LyricLine(text, "", start, end, List.of(words), "");
    }

    private static FloatingLyricRenderer rendererFor(LyricLine line, long positionMs) {
        LyricTimeline timeline = new LyricTimeline(List.of(line));
        return new FloatingLyricRenderer(new FixedFeed(timeline, positionMs));
    }

    @Test
    void 已唱完的字用已唱色绘制() {
        // 三个字，各 100ms
        LyricLine l = line("一二三", 0, 300,
                new LyricWord("一", 0, 100),
                new LyricWord("二", 100, 200),
                new LyricWord("三", 200, 300));
        RecordingPainter p = new RecordingPainter();

        // 位置 250：前两个字唱完，第三个正在唱
        assertTrue(rendererFor(l, 250L).render(p));

        // 整行先以未唱色画一次
        assertTrue(p.has(COLOR_UNSUNG, "一二三"), "整行应以未唱色打底");

        // 前两个字应以已唱色出现
        assertTrue(p.has(COLOR_SUNG, "一"), "第一个字应已高亮");
        assertTrue(p.has(COLOR_SUNG, "二"), "第二个字应已高亮");
    }

    @Test
    void 未开始的行不出现任何已唱色() {
        LyricLine l = line("一二三", 1000, 1300,
                new LyricWord("一", 1000, 1100),
                new LyricWord("二", 1100, 1200),
                new LyricWord("三", 1200, 1300));
        RecordingPainter p = new RecordingPainter();

        rendererFor(l, 1000L).render(p);

        assertFalse(p.hasColor(COLOR_SUNG), "刚开始时不应有任何字被高亮");
    }

    @Test
    void 全部唱完后整行高亮() {
        LyricLine l = line("一二", 0, 200,
                new LyricWord("一", 0, 100),
                new LyricWord("二", 100, 200));
        RecordingPainter p = new RecordingPainter();

        rendererFor(l, 500L).render(p);

        assertTrue(p.has(COLOR_SUNG, "一"));
        assertTrue(p.has(COLOR_SUNG, "二"));
    }

    @Test
    void 单字高亮宽度随时间推进() {
        // 一个字占 400ms，位置取中点
        LyricLine l = line("字", 0, 400, new LyricWord("字", 0, 400));

        RecordingPainter early = new RecordingPainter();
        rendererFor(l, 100L).render(early);

        RecordingPainter late = new RecordingPainter();
        rendererFor(l, 300L).render(late);

        // 同一字符在更晚位置应占用更大宽度（允许因整字取整而相等，但不会更小）
        int earlyWidth = early.widthOf(COLOR_SUNG, "字");
        int lateWidth = late.widthOf(COLOR_SUNG, "字");
        assertTrue(lateWidth >= earlyWidth, "高亮宽度应随时间不减");
    }

    @Test
    void 无逐字数据时退化为整行切换() {
        // words 为空 → LyricLine 会合成一个覆盖全行的单元
        LyricLine l = new LyricLine("整行", "", 0, 1000, List.of(), "");
        assertEquals(1, l.words().size(), "空 words 应被合成为单个整行单元");

        RecordingPainter before = new RecordingPainter();
        rendererFor(l, 500L).render(before);
        assertFalse(before.hasColor(COLOR_SUNG), "行未结束时不应整行高亮");

        RecordingPainter after = new RecordingPainter();
        rendererFor(l, 1500L).render(after);
        assertTrue(after.hasColor(COLOR_SUNG), "行结束后应整行高亮");
    }

    @Test
    void 文本含空格与标点时高亮不越界() {
        LyricLine l = line("a b,c", 0, 500,
                new LyricWord("a ", 0, 100),
                new LyricWord("b,", 100, 200),
                new LyricWord("c", 200, 500));
        RecordingPainter p = new RecordingPainter();

        rendererFor(l, 300L).render(p);

        // 已唱累计宽度不得超过整行宽度
        int sungWidth = p.totalWidthIn(COLOR_SUNG);
        int lineWidth = p.textWidth("a b,c");
        assertTrue(sungWidth <= lineWidth, "已唱宽度不应超过整行宽度");
    }

    @Test
    void 超宽文本截断后高亮不超过可见范围() {
        StringBuilder longText = new StringBuilder();
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            longText.append('字');
            words.add(new LyricWord("字", i * 10L, i * 10L + 10L));
        }
        LyricLine l = new LyricLine(longText.toString(), "", 0, 2000, words, "");

        RecordingPainter p = new RecordingPainter();
        // 位置在末尾，全部已唱，但屏幕只容得下约 30 字
        rendererFor(l, 5000L).render(p);

        int visibleLimit = (int) (RecordingPainter.SCREEN_WIDTH * 0.72f);
        assertTrue(p.totalWidthIn(COLOR_SUNG) <= visibleLimit + CHAR_WIDTH,
                "截断后高亮宽度不应超出可见宽度");
    }

    @Test
    void 数据源不可用时不绘制() {
        LyricTimeline timeline = new LyricTimeline(List.of(line("字", 0, 100, new LyricWord("字", 0, 100))));
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new FixedFeed(timeline, 50L, false));
        RecordingPainter p = new RecordingPainter();

        assertFalse(renderer.render(p));
        assertTrue(p.calls.isEmpty());
    }

    // ---------------- 测试替身 ----------------

    private static final class FixedFeed implements LyricFeed {
        private final LyricTimeline timeline;
        private final long positionMs;
        private final boolean alive;

        FixedFeed(LyricTimeline timeline, long positionMs) {
            this(timeline, positionMs, true);
        }

        FixedFeed(LyricTimeline timeline, long positionMs, boolean alive) {
            this.timeline = timeline;
            this.positionMs = positionMs;
            this.alive = alive;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test",
                    com.linklyric.core.sync.TrackIdentity.of("t", "a", 0L),
                    positionMs, true, 1L, 0L,
                    com.linklyric.core.capability.CapabilitySet.empty());
        }

        @Override
        public LyricTimeline lyric() {
            return timeline;
        }

        @Override
        public long lyricSignature() {
            return 1L;
        }
    }

    /** 记录每次绘制调用，便于断言颜色与宽度。 */
    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {
        static final int SCREEN_WIDTH = 400;
        static final int SCREEN_HEIGHT = 300;

        final List<Call> calls = new ArrayList<>();

        boolean has(int color, String text) {
            for (Call c : calls) {
                if (c.color == color && c.text.equals(text)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasColor(int color) {
            for (Call c : calls) {
                if (c.color == color) {
                    return true;
                }
            }
            return false;
        }

        int widthOf(int color, String text) {
            for (Call c : calls) {
                if (c.color == color && c.text.equals(text)) {
                    return c.width;
                }
            }
            return -1;
        }

        int totalWidthIn(int color) {
            int total = 0;
            for (Call c : calls) {
                if (c.color == color) {
                    total += c.width;
                }
            }
            return total;
        }

        @Override
        public int screenWidth() {
            return SCREEN_WIDTH;
        }

        @Override
        public int screenHeight() {
            return SCREEN_HEIGHT;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            // 测试替身不校验底板
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            calls.add(new Call(text, x, y, color, textWidth(text)));
        }
    }

    private record Call(String text, int x, int y, int color, int width) {
    }

    private static final int COLOR_SUNG = FloatingLyricRenderer.COLOR_SUNG;
    private static final int COLOR_UNSUNG = FloatingLyricRenderer.COLOR_UNSUNG;
}
