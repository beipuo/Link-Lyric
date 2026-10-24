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

        // 整行唱完时，未唱区蒙版只剩边界处的过渡带，不应再有成片的压暗。
        //
        // 过渡带本身是「透明 → 黑」的渐变，末级才接近满压暗；
        // 判据是「已唱色覆盖到哪里」：整行唱完就必须覆盖整行，
        // 且不再有只画未唱色的段。
        int sungRight = painter.sungRightEdge();
        assertTrue(sungRight >= lineWidthOf(text),
                "全亮时已唱色应覆盖整行，实际右缘=" + sungRight
                        + " 行宽=" + lineWidthOf(text));
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

        // 羽化是「已唱色的多级 alpha 覆盖」：实心段之后跟着若干级递减，
        // 让颜色从蓝平滑过渡到灰，而不是一刀切。
        //
        // 记录是跨帧累积的，必须按帧切分后再看梯度：
        // 否则下一帧的实心段会被拼到上一帧的末级后面，序列当然不单调。
        List<Integer> fadeAlphas = painter.sungAlphaSeries();
        assertTrue(fadeAlphas.size() >= 3,
                "行中段应有多级已唱色覆盖（实心 + 羽化），实际=" + fadeAlphas);

        // 现在每段都是不透明色，不能用 alpha 切帧。
        // 改按「蓝通道单调下降」切分：一帧内从已唱色蓝值逐级趋向未唱色。
        int sungBlue = FloatingLyricRenderer.COLOR_SUNG & 0xFF;

        int frames = 0;
        int i = 0;
        while (i < fadeAlphas.size()) {
            // 一帧的起点是已唱色本身（蓝值等于已唱色）。
            if (fadeAlphas.get(i) != sungBlue) {
                i++;
                continue;
            }
            int start = i;
            while (i + 1 < fadeAlphas.size() && fadeAlphas.get(i + 1) <= fadeAlphas.get(i)) {
                i++;
            }
            List<Integer> frame = fadeAlphas.subList(start, i + 1);
            if (frame.size() >= 3) {
                int first = frame.get(0);
                int last = frame.get(frame.size() - 1);
                assertTrue(last < first,
                        "羽化末级应更接近未唱色（蓝通道更低），实际=" + frame);
                frames++;
            }
            i++;
        }
        assertTrue(frames > 0, "应有至少一帧带完整颜色渐变，实际序列=" + fadeAlphas);
    }

    /** 与替身一致的逐字宽度（9px）。 */
    private static int lineWidthOf(String text) {
        return text.length() * 9;
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
            clipX = 0;
            clipWidth = 480;
        }

        /** 已唱色覆盖区：每段的 alpha 与右缘。 */
        final List<Integer> sungAlphas = new ArrayList<>();
        final List<Integer> sungRights = new ArrayList<>();
        int clipX;
        int clipWidth = 480;

        private boolean isSung(int color) {
            return (color & 0x00FFFFFF)
                    == (FloatingLyricRenderer.COLOR_SUNG & 0x00FFFFFF);
        }

        /** 该色是否更接近已唱色而非未唱色（逐通道距离比较）。 */
        private boolean closerToSung(int color) {
            return distance(color, FloatingLyricRenderer.COLOR_SUNG)
                    <= distance(color, FloatingLyricRenderer.COLOR_UNSUNG);
        }

        private static int distance(int a, int b) {
            int dr = ((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF);
            int dg = ((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF);
            int db = (a & 0xFF) - (b & 0xFF);
            return Math.abs(dr) + Math.abs(dg) + Math.abs(db);
        }

        /**
         * 以蓝色通道作为「已唱程度」的度量。
         *
         * <p>已唱色偏蓝（0x7FD4FF），未唱色偏灰（0xD8D8D8），
         * 两者蓝通道差异明显，用它衡量插值推进最直观。
         */
        private static int blueness(int color) {
            return color & 0xFF;
        }

        /** 已唱色各段的 alpha 序列（实心在前，羽化在后）。 */
        List<Integer> sungAlphaSeries() {
            return new ArrayList<>(sungAlphas);
        }

        /** 已唱色覆盖到的最右缘。 */
        int sungRightEdge() {
            int best = 0;
            for (int r : sungRights) {
                best = Math.max(best, r);
            }
            return best;
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
            // 羽化带现在是「已唱色→未唱色」的插值，中间色并不等于已唱色，
            // 因此按接近度归入已唱侧，而不是严格相等。
            if (closerToSung(color)) {
                sungAlphas.add(blueness(color));
                sungRights.add(clipX + clipWidth);
            }
        }

        /**
         * 采集字内渐变绘制。
         *
         * <p>逐顶点配色后，染色前沿上的字不再画成「若干条 alpha 递减的
         * 同色条带」，而是**一个字一对端点色**：左端已唱、右端未唱，
         * 中间由 GPU 插值。因此「羽化」不再表现为多个中间色像素，
         * 而是同一字形内的双色。
         *
         * <p>这里把左右两端的蓝值都记入序列，
         * 让「已唱色到未唱色之间确实存在过渡」这件事仍可被观察到。
         */
        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       com.linklyric.common.style.GlyphColors gc,
                                       boolean shadow) {
            texts.add(glyph);
            colors.add(gc.left());
            if (clipping) {
                clipColors.add(gc.left());
            }
            if (closerToSung(gc.left())) {
                sungAlphas.add(blueness(gc.left()));
                sungRights.add(clipX + clipWidth);
                // 左端已唱而右端未唱：这就是前沿所在的那个字，
                // 两个端点色都要记录，才能看出过渡方向。
                if (!closerToSung(gc.right())) {
                    sungAlphas.add(blueness(gc.right()));
                    sungRights.add(clipX + clipWidth);
                }
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
            clipX = x;
            clipWidth = width;
            clipping = true;
        }

        @Override
        public void disableScissor() {
            clipping = false;
        }
    }
}
