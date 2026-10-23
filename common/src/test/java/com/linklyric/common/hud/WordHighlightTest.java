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

        // 两种颜色都要真的画出来：未唱色铺满，已唱色覆盖已唱段。
        // 只画一层蒙版的话，蓝色会被压成灰蓝，那不是变色。
        assertTrue(p.drewColor(COLOR_SUNG), "已唱段应以已唱色绘制");
        assertTrue(p.drewColor(COLOR_UNSUNG), "整行应以未唱色铺底");

        // 实心已唱段的**宽度**（不是绝对坐标）不得越过第三个字的起点：
        // 位置 250 只唱到「一二」加「三」的一半，
        // 过渡带允许略微伸进正在唱的字，故留一个字宽的余量。
        int sungWidth = p.sungClipWidth();
        assertTrue(sungWidth <= p.textWidthX("一二") + CHAR_WIDTH,
                "实心已唱段不应盖到未唱段，实际宽度=" + sungWidth
                        + " 已唱两字=" + p.textWidthX("一二"));
    }

    @Test
    void 未开始的行不出现任何已唱色() {
        LyricLine l = line("一二三", 1000, 1300,
                new LyricWord("一", 1000, 1100),
                new LyricWord("二", 1100, 1200),
                new LyricWord("三", 1200, 1300));
        RecordingPainter p = new RecordingPainter();

        rendererFor(l, 1000L).render(p);

        // 一个字都没唱到：只应有未唱色，不应出现已唱色的绘制区。
        assertEquals(0, p.sungClipWidth(),
                "刚开始时不应有已唱色覆盖，实际宽度=" + p.sungClipWidth());
    }

    @Test
    void 全部唱完后整行高亮() {
        LyricLine l = line("一二", 0, 200,
                new LyricWord("一", 0, 100),
                new LyricWord("二", 100, 200));
        RecordingPainter p = new RecordingPainter();

        rendererFor(l, 500L).render(p);

        // 唱完后整行都是已唱色，且不再有未唱色的可见段。
        assertTrue(p.drewColor(COLOR_SUNG), "唱完后应以已唱色绘制");
        assertTrue(p.sungClipWidth() >= p.textWidth("一二"),
                "唱完后已唱色应覆盖整行，实际=" + p.sungClipWidth());
    }

    @Test
    void 单字高亮宽度随时间推进() {
        // 一个字占 400ms，位置取中点
        LyricLine l = line("字", 0, 400, new LyricWord("字", 0, 400));

        RecordingPainter early = new RecordingPainter();
        rendererFor(l, 100L).render(early);

        RecordingPainter late = new RecordingPainter();
        rendererFor(l, 300L).render(late);

        // 已唱宽度由蒙版左缘反推：越晚唱到的越多，蒙版越靠右。
        int earlyWidth = early.sungWidth();
        int lateWidth = late.sungWidth();
        assertTrue(lateWidth >= earlyWidth,
                "已唱宽度应随时间不减，实际 early=" + earlyWidth + " late=" + lateWidth);
    }

    @Test
    void 无逐字数据时退化为整行切换() {
        // words 为空 → LyricLine 会合成一个覆盖全行的单元
        LyricLine l = new LyricLine("整行", "", 0, 1000, List.of(), "");
        assertEquals(1, l.words().size(), "空 words 应被合成为单个整行单元");

        RecordingPainter before = new RecordingPainter();
        rendererFor(l, 500L).render(before);
        assertTrue(before.drewColor(COLOR_UNSUNG), "行未结束应有未唱色");
        assertTrue(before.sungWidth() < before.textWidth("整行"),
                "行未结束时不应整行已唱，实际=" + before.sungWidth());

        RecordingPainter after = new RecordingPainter();
        rendererFor(l, 1500L).render(after);
        assertEquals(after.textWidth("整行"), after.sungWidth(),
                "行结束后已唱色应覆盖整行，实际=" + after.sungWidth());
    }

    @Test
    void 文本含空格与标点时高亮不越界() {
        LyricLine l = line("a b,c", 0, 500,
                new LyricWord("a ", 0, 100),
                new LyricWord("b,", 100, 200),
                new LyricWord("c", 200, 500));
        RecordingPainter p = new RecordingPainter();

        rendererFor(l, 300L).render(p);

        // 已唱宽度即已唱色的覆盖宽度，不得超过整行宽度
        int sungWidth = p.sungWidth();
        int lineWidth = p.textWidth("a b,c");
        assertTrue(sungWidth <= lineWidth,
                "已唱宽度不应超过整行宽度，实际=" + sungWidth + " 行宽=" + lineWidth);
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
        // 停在行中段：既触发截断（200 字远超屏宽），又还没唱完。
        rendererFor(l, 1000L).render(p);

        // 超宽行走滚动布局：两层颜色都必须被夹在屏幕内，
        // 否则已唱段会画到面板外、压住别的 HUD 元素。
        int visibleLimit = (int) (RecordingPainter.SCREEN_WIDTH * 0.72f);
        int availableRight = (RecordingPainter.SCREEN_WIDTH + visibleLimit) / 2;
        for (Call c : p.calls) {
            if (c.text().isEmpty()) {
                continue;
            }
            assertTrue(c.clipX() >= 0,
                    "裁剪区左缘不得为负，实际=" + c.clipX());
            // 只在真正开了裁剪（宽度不是整屏）时才校验右缘，
            // 否则会把「不裁剪的整行绘制」误判成越界。
            if (c.clipWidth() < RecordingPainter.SCREEN_WIDTH) {
                assertTrue(c.clipX() + c.clipWidth() <= availableRight,
                        "裁剪区不得越出可用宽度，实际右缘="
                                + (c.clipX() + c.clipWidth()) + " 上限=" + availableRight);
            }
        }
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

        /**
         * 只比较 RGB，不比较 alpha。
         *
         * <p>行切换期间整行会按 {@code NotCurrentAlpha} 淡入淡出，
         * 颜色本身完全正确，只有 alpha 随时间变化。若连 alpha 一起比，
         * 「是否用了这个颜色」这件事就测不出来了。
         */
        private static boolean sameRgb(int a, int b) {
            return (a & 0x00FFFFFF) == (b & 0x00FFFFFF);
        }

        /** 文本的像素宽度，用于换算已唱段的右缘。 */
        int textWidthX(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        /** 是否用指定颜色画过文字。 */
        boolean drewColor(int color) {
            for (Call c : calls) {
                if (sameRgb(c.color, color) && !c.text.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 已唱色的可见覆盖宽度。
         *
         * <p>已唱色那层总是被裁剪到「已唱段」，因此裁剪区宽度
         * 就是已唱进度；没有裁剪说明整行可见（已唱完）。
         */
        /** 第一层文字的起始横坐标，用于把绝对坐标折算成行内偏移。 */
        int textOriginX() {
            for (Call c : calls) {
                if (!c.text().isEmpty()) {
                    return c.x();
                }
            }
            return 0;
        }

        int sungClipWidth() {
            int origin = textOriginX();
            // 只统计 alpha 最高的那一档，即实心段；羽化段 alpha 更低，
            // 若混进来会把已唱宽度算大。
            int peak = 0;
            for (Call c : calls) {
                if (sameRgb(c.color, COLOR_SUNG)) {
                    peak = Math.max(peak, (c.color >>> 24) & 0xFF);
                }
            }
            int best = 0;
            for (Call c : calls) {
                if (!sameRgb(c.color, COLOR_SUNG)) {
                    continue;
                }
                if (((c.color >>> 24) & 0xFF) < peak) {
                    continue;
                }
                int right = c.clipWidth > 0 ? c.clipX + c.clipWidth : c.x + c.width;
                best = Math.max(best, right - origin);
            }
            return best;
        }

        /** 已唱色覆盖区的右缘。 */
        int leftmostSungRight() {
            return sungClipWidth() + textOriginX();
        }

        /** 已唱宽度，与 {@link #sungClipWidth()} 同义，供推进类断言使用。 */
        int sungWidth() {
            int w = sungClipWidth();
            return w > 0 ? w : (drewColor(COLOR_SUNG) ? textWidthX("") : 0);
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

        /** 当前裁剪区；未裁剪时宽度记 0，表示「整行可见、无裁剪」。 */
        private int clipX;
        private int clipWidth;

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            calls.add(new Call(text, x, y, color, textWidth(text), clipX, clipWidth));
        }

        @Override
        public boolean supportsScissor() {
            // 本类验证的是逐像素推进，必须走带裁剪的卡拉 OK 分支。
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            clipX = x;
            clipWidth = width;
        }

        @Override
        public void disableScissor() {
            clipX = 0;
            clipWidth = 0;
        }
    }

    private record Call(String text, int x, int y, int color, int width,
                        int clipX, int clipWidth) {
    }

    private static final int COLOR_SUNG = FloatingLyricRenderer.COLOR_SUNG;
    private static final int COLOR_UNSUNG = FloatingLyricRenderer.COLOR_UNSUNG;
}
