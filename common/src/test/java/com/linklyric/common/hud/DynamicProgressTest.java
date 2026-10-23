package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时钟外推与像素裁剪的验证。
 *
 * <p>这两个是「逐字歌词没有动态染色」的直接原因：
 * 用轮询位置驱动会让高亮每 200ms 跳一次；不支持裁剪则无法表达
 * 正在唱的那个字的中间状态。
 */
class DynamicProgressTest {

    private static LyricLine lineWithWords(String text, long start, long end, LyricWord... words) {
        return new LyricLine(text, "", start, end, List.of(words), "");
    }

    /**
     * 数据源位置固定不变时，渲染位置仍应随时间前进。
     *
     * <p>这是外推是否生效的判定：若仍用轮询值，两次渲染的位置会完全相同，
     * 高亮也就停在同一处不动。
     */
    @Test
    void 位置固定时渲染仍随时间推进() throws Exception {
        LyricLine line = lineWithWords("一二三四五", 0, 5000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000),
                new LyricWord("三", 2000, 3000),
                new LyricWord("四", 3000, 4000),
                new LyricWord("五", 4000, 5000));
        LyricTimeline timeline = new LyricTimeline(List.of(line));

        // 数据源永远报告同一位置（首个单元中段），且处于播放中。
        // 取中段而非 0：位置 0 恰好是行起点，剩余时间全在本单元内，不适合观测增长。
        MutableFeed feed = new MutableFeed(timeline, 500L, true);
        ScissorPainter painter = new ScissorPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);

        renderer.render(painter);
        int first = painter.lastScissorWidth();

        Thread.sleep(200L);
        painter.reset();
        renderer.render(painter);
        int second = painter.lastScissorWidth();

        assertTrue(second > first,
                "位置固定但播放在进行时，已唱宽度必须增长。first=" + first + " second=" + second);
    }

    /** 暂停后外推必须停止，否则高亮会一直往前跑。 */
    @Test
    void 暂停后不再推进() throws Exception {
        LyricLine line = lineWithWords("一二三四五", 0, 5000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000),
                new LyricWord("三", 2000, 3000),
                new LyricWord("四", 3000, 4000),
                new LyricWord("五", 4000, 5000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 500L, false);
        ScissorPainter painter = new ScissorPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);

        renderer.render(painter);
        int first = painter.lastScissorWidth();
        Thread.sleep(120L);
        painter.reset();
        renderer.render(painter);

        assertEquals(first, painter.lastScissorWidth(), "暂停时已唱宽度不应变化");
    }

    /** 支持裁剪时，正在唱的字可以只覆盖一部分宽度。 */
    @Test
    void 支持裁剪时按像素覆盖() {
        // 单字 1000ms，位置取 1/4 处，期望覆盖约 25% 宽度而非整字
        LyricLine line = lineWithWords("一二", 0, 2000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 250L, false);
        ScissorPainter painter = new ScissorPainter();
        new FloatingLyricRenderer(feed).render(painter);

        int width = painter.lastScissorWidth();
        int wordWidth = painter.textWidth("一");

        assertTrue(width > 0, "已开始演唱应有正宽度");
        assertTrue(width < wordWidth,
                "位置在字中间时，覆盖宽度应小于整字宽度。width=" + width + " word=" + wordWidth);
    }

    /** 不支持裁剪时必须降级为整字覆盖，且不得绘制半截。 */
    @Test
    void 不支持裁剪时降级为整字() {
        LyricLine line = lineWithWords("一二三", 0, 3000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000),
                new LyricWord("三", 2000, 3000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 1500L, false);
        NoScissorPainter painter = new NoScissorPainter();
        new FloatingLyricRenderer(feed).render(painter);

        // 第二个字正在唱：只应画出完整的第一字
        assertTrue(painter.hasColor(FloatingLyricRenderer.COLOR_SUNG), "应有已唱内容");
        assertFalse(painter.sawScissor, "不应尝试调用裁剪");
    }

    /** 底板必须绘制，否则明亮场景下文字不可读。 */
    @Test
    void 绘制底板() {
        LyricLine line = lineWithWords("字", 0, 1000, new LyricWord("字", 0, 1000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 500L, false);
        ScissorPainter painter = new ScissorPainter();
        new FloatingLyricRenderer(feed).render(painter);

        assertEquals(1, painter.fills, "应绘制一块底板");
    }

    // ---------------- 替身 ----------------

    private static final class MutableFeed implements LyricFeed {
        private final LyricTimeline timeline;
        private final long positionMs;
        private final boolean playing;

        MutableFeed(LyricTimeline timeline, long positionMs, boolean playing) {
            this.timeline = timeline;
            this.positionMs = positionMs;
            this.playing = playing;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test", TrackIdentity.of("t", "a", 0L),
                    positionMs, playing, 1L, 0L, CapabilitySet.empty());
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

    private static class ScissorPainter implements FloatingLyricRenderer.Painter {
        private static final int CHAR_WIDTH = 10;
        final List<Integer> scissorWidths = new ArrayList<>();

        void reset() {
            scissorWidths.clear();
        }
        int fills;
        boolean sawScissor;

        /** 只关心实心层：它是第一次裁剪，后续裁剪属于羽化过渡。 */
        int lastScissorWidth() {
            return scissorWidths.isEmpty() ? 0 : scissorWidths.get(0);
        }

        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            fills++;
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            sawScissor = true;
            scissorWidths.add(width);
        }

        @Override
        public void disableScissor() {
        }
    }

    private static final class NoScissorPainter implements FloatingLyricRenderer.Painter {
        private static final int CHAR_WIDTH = 10;
        final List<Integer> colors = new ArrayList<>();
        boolean sawScissor;

        boolean hasColor(int color) {
            return colors.contains(color);
        }

        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            colors.add(color);
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return false;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            sawScissor = true;
        }
    }
}
