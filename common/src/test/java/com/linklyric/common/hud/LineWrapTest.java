package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折行的切分规则与跨段前沿换算。
 *
 * <p>折行没有异常可抛：断错了只会表现为「英文单词被劈开」或
 * 「第二段从头重新唱一遍」。这两种错都不会崩，只能靠断言钉住。
 */
class LineWrapTest {

    /** 测试用字宽：所有字符 9px，与 LineLayoutTest 等一系列测试同一口径。 */
    private static final int CHAR = 9;

    private static int width(int ignored) {
        return CHAR;
    }

    /** 装得下就是一段，不该多加空段。 */
    @Test
    void 装得下不切分() {
        List<LyricWrap.Segment> segments = LyricWrap.wrap("短短一句", 345, LineWrapTest::width);

        assertEquals(1, segments.size());
        assertEquals("短短一句", segments.get(0).text());
        assertFalse(segments.get(0).truncated(), "未超宽不应截断");
    }

    /** 中文可在任意字间断，按可用宽度均分。 */
    @Test
    void 中文按宽度切分() {
        // 10 字 × 9px = 90px，限 36px → 每段 4 字
        List<LyricWrap.Segment> segments = LyricWrap.wrap("一二三四五六七八九十", 36, LineWrapTest::width);

        assertEquals(3, segments.size());
        assertEquals("一二三四", segments.get(0).text());
        assertEquals("五六七八", segments.get(1).text());
        assertEquals("九十", segments.get(2).text());
        // 每段都不超可用宽度
        for (LyricWrap.Segment s : segments) {
            assertTrue(s.width() <= 36, "段宽超限：" + s.width());
        }
    }

    /**
     * 英文必须在空格处断，不能把单词劈成两半。
     *
     * <p>这是断点规则唯一真正重要的地方：{@code "lost in the echo"}
     * 若在 {@code "ech" + "o"} 处断开，读起来就是一个不存在的词。
     */
    @Test
    void 英文在空格处断行() {
        // "lost in the echo" 共 16 字符 = 144px，限 63px（7 字）
        List<LyricWrap.Segment> segments = LyricWrap.wrap("lost in the echo", 63, LineWrapTest::width);

        assertTrue(segments.size() > 1, "应折成多段，实际 " + segments.size());
        for (LyricWrap.Segment s : segments) {
            String body = s.text();
            // 除最后一段外，每段不得以半个单词结尾
            if (s != segments.get(segments.size() - 1)) {
                assertFalse(body.endsWith("ec") || body.endsWith("ech"),
                        "单词被劈开：" + body);
            }
        }
    }

    /** 段数用尽必须截断并带省略号，且不得超过可用宽度。 */
    @Test
    void 超出段数上限截断() {
        // 40 字，每段 2 字 → 需要 20 段，远超 MAX_LINES
        String text = "一".repeat(40);
        List<LyricWrap.Segment> segments = LyricWrap.wrap(text, 18, LineWrapTest::width);

        assertEquals(LyricWrap.MAX_LINES, segments.size(),
                "段数应被封顶在 MAX_LINES");
        LyricWrap.Segment last = segments.get(segments.size() - 1);
        assertTrue(last.truncated(), "末段应标记为截断");
        assertTrue(last.text().endsWith("…"), "截断应带省略号，实际=" + last.text());
        assertTrue(last.width() <= 18,
                "含省略号的段宽不得超限，实际=" + last.width());
    }

    /** 整段宽度必须与逐字累加一致，否则居中会偏。 */
    @Test
    void 段宽与文本长度一致() {
        List<LyricWrap.Segment> segments = LyricWrap.wrap("一二三四五六", 27, LineWrapTest::width);

        for (LyricWrap.Segment s : segments) {
            assertEquals(s.text().length() * CHAR, s.width(),
                    "段宽应与字数乘字宽一致：" + s.text());
        }
    }

    /**
     * 段首像素偏移必须等于前面各段宽度之和。
     *
     * <p>这是跨段前沿换算的唯一依据：偏了就说明有段的宽度没被计入，
     * 越往后的段局部前沿越偏小，表现为「后面的行迟迟不变色」。
     */
    @Test
    void 段首偏移逐段累加() {
        List<LyricWrap.Segment> segments = LyricWrap.wrap("一二三四五六七八九", 27, LineWrapTest::width);

        int expected = 0;
        for (LyricWrap.Segment s : segments) {
            assertEquals(expected, s.startPx(),
                    "段首偏移应等于前段宽度之和：" + s.text());
            expected += s.width();
        }
    }

    /**
     * 局部前沿在段边界处连续。
     *
     * <p>全局前沿推进到第一段末尾时，第二段的局部前沿必须恰好是 0，
     * 而不是负数被夹住、也不是从中间开始。这是「唱到行尾再从头起步」
     * 那类倒退的判据。
     */
    @Test
    void 局部前沿跨段连续() {
        List<LyricWrap.Segment> segments = LyricWrap.wrap("一二三四五六七八", 36, LineWrapTest::width);
        assertTrue(segments.size() >= 2, "用例应至少折成两段");

        LyricWrap.Segment first = segments.get(0);
        LyricWrap.Segment second = segments.get(1);

        // 前沿恰好落在第一段末尾
        assertEquals(first.width(), first.localSung(first.width()), 1e-4f,
                "第一段末端应染满");
        assertEquals(0f, second.localSung(first.width()), 1e-4f,
                "第二段在第一段染满时应恰好从 0 开始");
        // 全句唱满则各段都满
        int total = segments.get(segments.size() - 1).endPx();
        for (LyricWrap.Segment s : segments) {
            assertEquals(s.width(), s.localSung(total), 1e-4f,
                    "整句唱满时每段都应染满：" + s.text());
        }
        // 负数与超界的全局前沿都要被夹住
        assertEquals(0f, first.localSung(-500f), 1e-4f, "负前沿应夹到 0");
        assertEquals(second.width(), second.localSung(total + 999f), 1e-4f,
                "超界前沿应夹到段宽");
    }

    /**
     * 折行改变行高：同一句的各段都是主行字号。
     *
     * <p>策略必须知道「前几行属于同一句」，否则第二段会被当成副行
     * 缩到 0.8，一块歌词里同一句话出现两种字号。
     */
    @Test
    void 折行段按主行字号计高() {
        // 两句：主行折成两段，第三格是副行
        LineLayout.Block block = LineLayout.compute(100, 3, 2,
                LyricGeometry.LINE_HEIGHT, 2, 0.8f);

        assertEquals(LyricGeometry.LINE_HEIGHT,
                LineLayout.heightOf(0, 2, LyricGeometry.LINE_HEIGHT, 0.8f), 1e-4f,
                "第一段应为主行字号");
        assertEquals(LyricGeometry.LINE_HEIGHT,
                LineLayout.heightOf(1, 2, LyricGeometry.LINE_HEIGHT, 0.8f), 1e-4f,
                "第二段也应是主行字号");
        // 副行仍缩一档：9 × 0.8 = 7.2
        assertEquals(7.2f,
                LineLayout.heightOf(2, 2, LyricGeometry.LINE_HEIGHT, 0.8f), 1e-4f,
                "其后副行仍按比例缩小");

        // 块高 = 9 + 2 + 9 + 2 + 7.2 = 29.2 → 29
        assertEquals(29, block.height(), "块高应含两段全字号");
    }

    /**
     * 关闭折行时必须逐像素还原旧行为。
     *
     * <p>判据不是「看起来差不多」，而是**同一条长句仍按原样滚动**：
     * 若折行分支被无条件启用，这里会看到多行，滚动也不会发生。
     */
    @Test
    void 关闭折行时仍按原样滚动() {
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(line(text, 0, 60_000)));

        WrapRecorder painter = new WrapRecorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new Feed(timeline, 40_000L));
        renderer.setStyle(com.linklyric.common.style.LyricStyleConfig.DEFAULT.withWrap(false));
        for (int i = 0; i < 40; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        // 不折行：所有绘制都发生在同一行 y 上，且该行被整段提交（滚动路径）
        assertEquals(1, painter.ys.stream().distinct().count(),
                "关闭折行时不应出现多行，实际 y=" + painter.ys.stream().distinct().toList());
        assertTrue(painter.clipRects.size() > 0,
                "关闭折行且超宽时应仍在滚动（有裁剪），实际无裁剪");
    }

    /**
     * 开启折行时同一句被排成多行，且不再横向滚动。
     *
     * <p>折行与滚动互斥：两者叠加会让文字在两处同时移动。
     */
    @Test
    void 开启折行后换成多行() {
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(line(text, 0, 60_000)));

        WrapRecorder painter = new WrapRecorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new Feed(timeline, 40_000L));
        renderer.setStyle(com.linklyric.common.style.LyricStyleConfig.DEFAULT.withWrap(true));
        renderer.render(painter);

        long distinctY = painter.ys.stream().distinct().count();
        assertTrue(distinctY >= 2,
                "开启折行后应出现多行，实际不同 y 数=" + distinctY + "，y="
                        + painter.ys.stream().distinct().toList());
    }

    /** 每段的绘制起点都不得超出可用宽度范围之外，即不会画到面板外。 */
    @Test
    void 折行各行不超可用宽度() {
        String text = "一".repeat(60);
        LyricTimeline timeline = new LyricTimeline(List.of(line(text, 0, 60_000)));

        WrapRecorder painter = new WrapRecorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new Feed(timeline, 40_000L));
        renderer.setStyle(com.linklyric.common.style.LyricStyleConfig.DEFAULT.withWrap(true));
        renderer.render(painter);

        int maxWidth = (int) (480 * 0.72f);
        for (int i = 0; i < painter.texts.size(); i++) {
            int width = painter.texts.get(i).length() * CHAR;
            int x = painter.xs.get(i);
            assertTrue(x >= 0, "绘制起点不应为负，实际 x=" + x);
            assertTrue(width <= maxWidth,
                    "单段宽度不得超过可用宽度，实际=" + width + " 上限=" + maxWidth);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(4L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LyricLine line(String text, long start, long end) {
        return new LyricLine(text, "", start, end, List.of(), "");
    }

    private record Feed(LyricTimeline timeline, long position) implements LyricFeed {
        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public com.linklyric.core.sync.PlayerSnapshot pollSnapshot() {
            return new com.linklyric.core.sync.PlayerSnapshot("test",
                    com.linklyric.core.sync.TrackIdentity.of("曲目", "", 0L),
                    position, true, 0L, System.nanoTime(),
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

    /** 只关心「画在哪一行、多宽」，不需要颜色与抬升。 */
    private static final class WrapRecorder implements FloatingLyricRenderer.Painter {
        final List<String> texts = new java.util.ArrayList<>();
        final List<Integer> xs = new java.util.ArrayList<>();
        final List<Integer> ys = new java.util.ArrayList<>();
        final List<int[]> clipRects = new java.util.ArrayList<>();

        void reset() {
            texts.clear();
            xs.clear();
            ys.clear();
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
            return text == null ? 0 : text.length() * CHAR;
        }

        @Override
        public int charWidth(char c) {
            return CHAR;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            texts.add(text);
            xs.add(x);
            ys.add(y);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            texts.add(text);
            xs.add(x);
            ys.add(y);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            texts.add(text);
            xs.add(x);
            ys.add(y);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                      com.linklyric.common.style.GlyphColors colors,
                                      boolean shadow) {
            texts.add(glyph);
            xs.add(Math.round(x));
            ys.add(y);
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
            clipRects.add(new int[]{x, width});
        }

        @Override
        public void disableScissor() {
        }
    }
}
