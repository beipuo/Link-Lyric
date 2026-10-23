package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 间奏判定与羽化绘制的回归测试。
 *
 * <p>两者都属于「观感问题」，出错时不会抛异常、只是看起来别扭，
 * 因此必须有断言兜住，否则很容易在某次重构里悄悄退化。
 */
class InterludeAndFeatherTest {

    /** 判定本身不依赖渲染，直接测时间轴。 */
    @Test
    void 长空隙判定为间奏() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句", 0, 3000),
                line("第二句", 20000, 23000)));

        assertTrue(timeline.isInterludeAt(10000, 8000L), "3 秒到 20 秒之间应判为间奏");
        assertTrue(timeline.isInterludeAt(19999, 8000L), "接近下一行仍属间奏");
    }

    @Test
    void 短空隙不算间奏() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句", 0, 3000),
                line("第二句", 5000, 8000)));

        assertFalse(timeline.isInterludeAt(4000, 8000L), "2 秒空隙不足阈值，不应提示");
    }

    @Test
    void 行内与末行之后都不算间奏() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句", 0, 3000),
                line("第二句", 20000, 23000)));

        assertFalse(timeline.isInterludeAt(1500, 8000L), "行内不是间奏");
        assertFalse(timeline.isInterludeAt(30000, 8000L), "末行之后没有下一行，不判间奏");
    }

    /** 间奏期间应绘制提示文本，而不是继续显示上一句。 */
    @Test
    void 间奏期间绘制提示文本() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句", 0, 3000),
                line("第二句", 20000, 23000)));

        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 10000L, true));

        assertTrue(renderer.render(painter), "间奏期应有内容可画");
        assertTrue(painter.texts.contains("间奏"),
                "应显示间奏提示，实际绘制=" + painter.texts);
        assertFalse(painter.texts.contains("第一句"),
                "不应继续显示上一句，否则看起来像卡住");
    }

    /** 行末不再羽化：整行唱完就该全部实心。 */
    @Test
    void 行末关闭羽化() {
        String text = "一二三四五六七八";
        LyricTimeline timeline = new LyricTimeline(List.of(
                wordsLine(text, 0, 8000)));

        RecordingPainter painter = new RecordingPainter();
        // 必须真正越过行尾（行尾前一毫秒仍有最后一个字未完成，会带羽化）
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 8000L, true));
        for (int i = 0; i < 30; i++) {
            painter.reset();
            renderer.render(painter);
            try {
                Thread.sleep(4L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int lineWidth = painter.textWidth(text);
        assertEquals(1, painter.scissors.size(),
                "全亮后不应再有羽化裁剪，实际裁剪次数=" + painter.scissors.size()
                        + " 宽度=" + painter.scissors);
        assertTrue(painter.scissors.get(0) >= lineWidth - 1,
                "唯一裁剪应覆盖整行，实际=" + painter.scissors.get(0) + " 行宽=" + lineWidth);
    }

    /** 过渡带应该逐级变淡，而不是单级突变。 */
    @Test
    void 过渡带逐级变淡() {
        String text = "一二三四五六七八九十";
        LyricTimeline timeline = new LyricTimeline(List.of(
                wordsLine(text, 0, 10000)));

        RecordingPainter painter = new RecordingPainter();
        // 停在行中间，此时既有实心段也有羽化带
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 5000L, true));
        for (int i = 0; i < 8; i++) {
            painter.reset();
            renderer.render(painter);
            try {
                Thread.sleep(4L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(painter.scissors.size() >= 3,
                "行中段应有实心 + 多级羽化，实际裁剪次数=" + painter.scissors.size());

        // 羽化只发生在**带裁剪**的绘制里，因此直接从裁剪态颜色取序列。
        // 双行布局引入后，非裁剪绘制还会混入副行、离场行与未唱底色，
        // 全量检查单调性会把它们算进来。
        List<Integer> fadeAlphas = new ArrayList<>();
        for (int color : painter.clipColors) {
            fadeAlphas.add((color >>> 24) & 0xFF);
        }
        assertTrue(fadeAlphas.size() >= 3,
                "应有多级羽化裁剪，实际=" + fadeAlphas);

        // 首段为实心（255），之后逐级变淡
        for (int i = 2; i < fadeAlphas.size(); i++) {
            assertTrue(fadeAlphas.get(i) <= fadeAlphas.get(i - 1),
                    "羽化 alpha 必须递减，实际=" + fadeAlphas);
        }
        assertTrue(fadeAlphas.get(fadeAlphas.size() - 1) < 0x40,
                "末级应足够淡，实际=" + fadeAlphas);
    }

    private static LyricLine line(String text, long start, long end) {
        return new LyricLine(text, "", start, end,
                List.of(new LyricWord(text, start, end)), "");
    }

    /** 构造真正的逐字行：每个字等分时长。 */
    private static LyricLine wordsLine(String text, long start, long end) {
        List<LyricWord> words = new ArrayList<>();
        long step = (end - start) / text.length();
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    start + step * i, start + step * (i + 1)));
        }
        return new LyricLine(text, "", start, end, List.copyOf(words), "");
    }

    private record Feed(LyricTimeline timeline, long position, boolean playing)
            implements LyricFeed {
        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public com.linklyric.core.sync.PlayerSnapshot pollSnapshot() {
            return new com.linklyric.core.sync.PlayerSnapshot(
                    "test",
                    com.linklyric.core.sync.TrackIdentity.of("曲目", "", 0L),
                    position, playing, 0L, System.nanoTime(),
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

    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {
        final List<Integer> scissors = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

        /** 仅记录处于裁剪状态下的绘制颜色，即羽化层。 */
        final List<Integer> clipColors = new ArrayList<>();
        private boolean clipping;

        void reset() {
            scissors.clear();
            colors.clear();
            clipColors.clear();
            texts.clear();
            clipping = false;
        }

        @Override
        public int screenWidth() {
            return 480;
        }

        @Override
        public int screenHeight() {
            return 270;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            texts.add(text);
            colors.add(color);
            if (clipping) {
                clipColors.add(color);
            }
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            scissors.add(width);
            clipping = true;
        }

        @Override
        public void disableScissor() {
            clipping = false;
        }
    }
}
