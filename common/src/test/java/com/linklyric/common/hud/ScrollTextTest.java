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
 * 长行滚动的回归测试。
 *
 * <p>滚动没有异常可抛，出问题时只会表现为「画到面板外」或「不滚」，
 * 所以必须用坐标断言把它钉住。
 */
class ScrollTextTest {

    /** 装得下就不该滚，文本应保持居中。 */
    @Test
    void 短行不滚动() {
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine("短短一句", 0, 4000)));
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 1000L, true));

        renderer.render(painter);

        // 4 字 × 9px = 36px，视口 480×0.72=345px，远未溢出。
        // 第一次 drawText 是未唱色打底，其坐标即可代表排版位置。
        int x = painter.textXs.get(0);
        int width = painter.textWidth("短短一句");
        assertEquals(480 / 2 - width / 2, x, "不滚动时应水平居中");
    }

    /** 超宽未唱完时，演唱点越过中线才开始滚动。 */
    @Test
    void 演唱过半后开始滚动() {
        // 60 字 × 9px = 540px > 345px 视口，必然溢出
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 60_000)));

        int centerX = 480 / 2;
        int viewWidth = (int) (480 * 0.72f);

        // 位置在行首：高亮为 0，不应提前滚
        RecordingPainter early = new RecordingPainter();
        FloatingLyricRenderer r1 = new FloatingLyricRenderer(new Feed(timeline, 100L, true));
        r1.render(early);
        assertEquals(centerX - viewWidth / 2, early.textXs.get(0),
                "高亮未过半时不应滚动");

        // 位置过半：高亮超过中线，文本应左移
        RecordingPainter late = new RecordingPainter();
        FloatingLyricRenderer r2 = new FloatingLyricRenderer(new Feed(timeline, 40_000L, true));
        for (int i = 0; i < 12; i++) {
            late.reset();
            r2.render(late);
            sleep();
        }
        // 滚动生效的证据不是「某个绘制坐标小于视口左边界」——
        // 已经滚出视口的字根本不会被绘制，可见坐标必然 >= 视口左边界。
        //
        // 真正的判据是「行首字被推出视口」：滚动后最早可见的字
        // 不再是第一个字。这里用采样坐标数少于总字数来间接体现，
        // 并直接校验最左可见坐标贴着视口左边界。
        int viewportLeft = centerX - viewWidth / 2;
        int shiftedX = late.textXs.stream().mapToInt(Integer::intValue).min()
                .orElse(Integer.MAX_VALUE);
        // 判据改为看**裁剪区**而不是绘制坐标。
        //
        // 滚动时行首字仍会被提交绘制，只是被裁剪区挡在视口外——
        // 这是原版 drawString 的常规做法，坐标本身可以在屏幕外。
        // 若拿未裁剪的原始坐标做断言，量到的只是「字从哪里开始」，
        // 与「玩家实际能看到什么」无关。
        //
        // 真正要保证的是：没有任何绘制能在视口左侧之外留下像素。
        for (int[] cr : late.clipRects) {
            assertTrue(cr[0] >= viewportLeft,
                    "裁剪区左缘不得越过视口左边界，实际=" + cr[0]
                            + " 视口左=" + viewportLeft);
        }
        assertTrue(!late.clipRects.isEmpty(),
                "滚动后应出现视口裁剪，实际无裁剪记录");

        // 滚动必须真的发生：最左提交坐标要小于视口左边界，
        // 说明行首已经被推出可视区。
        assertTrue(shiftedX < viewportLeft,
                "滚动后行首应被推出视口，实际最左 x=" + shiftedX
                        + " 视口左=" + viewportLeft);
    }

    /** 滚动量必须在 [视口左边界, 0] 内，不能把文本推出面板。 */
    @Test
    void 滚动量受限不越过行尾() {
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 60_000)));

        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 59_990L, true));
        for (int i = 0; i < 25; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        int viewWidth = (int) (480 * 0.72f);
        int lineWidth = painter.textWidth(text);
        int viewportLeft = 480 / 2 - viewWidth / 2;
        // 取最小 x：逐字绘制会画很多字，且会跳过滚出视口的字。
        int x = painter.textXs.stream().mapToInt(Integer::intValue).min()
                .orElse(Integer.MAX_VALUE);

        // 文本右端不能早于视口右端，否则行尾会留白
        assertTrue(x + lineWidth >= viewportLeft + viewWidth - 1,
                "不应把行尾推出视口左侧，x=" + x + " 行宽=" + lineWidth);
        assertTrue(x <= viewportLeft,
                "滚动后文本左端不应在视口右侧，x=" + x);
    }

    /** 所有绘制出的裁剪区都必须落在视口内。 */
    @Test
    void 裁剪区不越出视口() {
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 60_000)));

        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 45_000L, true));
        for (int i = 0; i < 15; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        int viewWidth = (int) (480 * 0.72f);
        int viewportLeft = 480 / 2 - viewWidth / 2;
        int viewportRight = viewportLeft + viewWidth;

        assertFalse(painter.clipRects.isEmpty(), "应有裁剪绘制");
        for (int[] rect : painter.clipRects) {
            int left = rect[0];
            int right = rect[0] + rect[1];
            assertTrue(left >= viewportLeft - 1,
                    "裁剪左边界越界: " + left + " < " + viewportLeft);
            assertTrue(right <= viewportRight + 1,
                    "裁剪右边界越界: " + right + " > " + viewportRight);
        }
    }

    /** 不支持裁剪的平台必须退回截断，不能输出整行。 */
    @Test
    void 无裁剪平台退回截断() {
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 60_000)));

        RecordingPainter painter = new RecordingPainter();
        painter.supportsScissor = false;
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 20_000L, true));
        renderer.render(painter);

        String drawn = painter.texts.get(0);
        assertTrue(drawn.length() < text.length(),
                "无法裁剪时必须截断，实际长度=" + drawn.length());
        assertTrue(drawn.endsWith("…"), "截断应带省略号，实际=" + drawn);
    }

    private static void sleep() {
        try {
            Thread.sleep(4L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LyricLine wordsLine(String text, long start, long end) {
        List<LyricWord> words = new ArrayList<>();
        long step = Math.max(1L, (end - start) / text.length());
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
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                    position, playing, 0L, System.nanoTime(), CapabilitySet.empty());
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
        final List<String> texts = new ArrayList<>();
        final List<Integer> textXs = new ArrayList<>();
        final List<int[]> clipRects = new ArrayList<>();
        boolean supportsScissor = true;

        void reset() {
            texts.clear();
            textXs.clear();
            clipRects.clear();
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
            textXs.add(x);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            texts.add(text);
            textXs.add(x);
        }

        /**
         * 逐字绘制走这个重载。
         *
         * <p>只重写四参 {@code drawText} 收不到主行数据：
         * 五参 {@code drawTextLifted} 的默认实现会转调四参 {@code drawTextLifted}，
         * 再转调四参 {@code drawText}，但主行实际调用的是**五参**版本，
         * 它默认只转发到四参 {@code drawTextLifted}，若该重载也被重写就断了链。
         * 这里直接记录，保证滚动测试能采到坐标。
         */
        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            texts.add(text);
            textXs.add(x);
        }

        /**
         * 字内渐变绘制也要采集坐标。
         *
         * <p>逐顶点配色后，落在染色前沿上的字走这条路径。
         * 不记录的话「滚动后可见字贴着视口左边界」这类断言
         * 会因为采样缺字而误判成滚动量不对。
         */
        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       com.linklyric.common.style.GlyphColors colors,
                                       boolean shadow) {
            texts.add(glyph);
            textXs.add(Math.round(x));
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return supportsScissor;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            clipRects.add(new int[]{x, width});
        }

        @Override
        public void disableScissor() {
        }
    }
}
