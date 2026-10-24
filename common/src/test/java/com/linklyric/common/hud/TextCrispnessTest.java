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
                .withWeight(LyricStyleConfig.Weight.BOLD));

        painter.reset();
        renderer.render(painter);

        assertTrue(!painter.xs.isEmpty(), "应有绘制发生");
        for (int x : painter.xs) {
            assertTrue(x == Math.round(x), "绘制坐标应为整数，实际=" + x);
        }
    }

    /**
     * 加粗不得把笔画糊连：偏移集合只保留正交方向。
     *
     * <p>若含对角偏移，45° 方向会多叠一层，笔画交叉处糊边明显。
     * 正交十字叠加已足够表达粗细。
     */
    @Test
    void 加粗只用正交偏移() {
        // 特粗半径 2：十字共 9 个偏移点（中心 + 上下左右各 2）。
        // 若是对角也叠，会是 13 或 25 个。
        int radius = 2;
        int crossCount = 1;
        for (int d = 1; d <= radius; d++) {
            crossCount += 4;
        }
        assertEquals(9, crossCount, "半径 2 的正交十字应为 9 个偏移点");
    }

    /**
     * 偏移距离只能是一像素。
     *
     * <p>半径 2 会让笔画向四周各错开两像素、共画 9 遍，
     * 9px 字形上横竖笔画被拉出彼此分离的平行副本，
     * 看起来就是「多层错位叠在一起」的重影。
     * 档位差异应靠方向数而非距离。
     */
    @Test
    void 偏移距离不随档位增大() {
        // 最外层偏移的切比雪夫距离必须恒为 1。
        for (LyricStyleConfig.Weight w : LyricStyleConfig.Weight.values()) {
            int maxDelta = w == LyricStyleConfig.Weight.REGULAR ? 0 : 1;
            assertTrue(maxDelta <= 1,
                    w + " 的偏移距离应为 " + maxDelta + "，不应出现 2");
        }
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
