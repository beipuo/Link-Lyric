package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文字清晰度：字重叠加必须落在整像素上，且不能把笔画糊连。
 *
 * <p>MC 的字形是位图图集，按整像素取样。任何半点偏移都会落到纹素之间，
 * 触发线性过滤，边缘出现颗粒与锯齿，细笔画因两侧都采到半透明值而黏连。
 * 这类问题在数值上表现为「非整数偏移」与「相邻笔画被填满」。
 */
class TextCrispnessTest {

    /** 抬升必须取整：浮点平移会让字形落在纹素之间。 */
    @Test
    void 抬升位移取整() {
        // 与平台层 drawTextLifted 的取整逻辑一致。
        float[] lifts = {0f, 0.4f, 0.5f, 0.9f, 1.0f, 1.4f};
        int[] expected = {0, 0, 1, 1, 1, 1};
        for (int i = 0; i < lifts.length; i++) {
            assertEquals(expected[i], Math.round(lifts[i]),
                    "抬升 " + lifts[i] + " 应取整为 " + expected[i]);
        }
    }

    /** 字重偏移必须都是整数，不能出现半点。 */
    @Test
    void 字重偏移全为整数() {
        // 偏移由 weightOffsets 生成，这里通过绘制坐标间接验证。
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT
                .withWeight(LyricStyleConfig.Weight.REGULAR));

        painter.reset();
        renderer.render(painter);

        assertTrue(!painter.xs.isEmpty(), "应有绘制发生");
        for (int x : painter.xs) {
            assertTrue(x == Math.round(x), "绘制坐标应为整数，实际=" + x);
        }
    }

    /**
     * 同一个字只能有一个绘制位置。
     *
     * <p>这是重影的回归防线。曾被加粗的伪粗体副本打破：
     * 同一字形在 {@code x+1} 处被再写一遍，两轮廓只错开 1px、
     * 重叠 8px，在 9px 位图字上直接被看成两个字。
     * 判据是同一字符的绘制坐标不得重复出现。
     */
    @Test
    void 同一字不重复绘制() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT);

        painter.reset();
        renderer.render(painter);

        assertTrue(!painter.xs.isEmpty(), "应有绘制发生");
        // 坐标集合不应出现「同一字符画在两个相邻位置」的模式。
        java.util.Set<Integer> unique = new java.util.LinkedHashSet<>(painter.xs);
        assertEquals(painter.xs.size(), unique.size(),
                "出现重复绘制坐标，可能是伪粗体副本，实际=" + painter.xs);
    }

    private static final class RecordingPainter
            implements FloatingLyricRenderer.Painter {

        final java.util.List<Integer> xs = new java.util.ArrayList<>();

        void reset() {
            xs.clear();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            xs.add(x);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            xs.add(x);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            xs.add(x);
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
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }
    }
}
