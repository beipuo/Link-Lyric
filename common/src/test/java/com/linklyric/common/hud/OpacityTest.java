package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整体不透明度。
 *
 * <p>必须作用于**所有**行，而不只是主行。
 * 分散到各条绘制路径很容易漏掉某一条（后续行、间奏、降级绘制），
 * 表现为调低之后还有一行是亮的。
 */
class OpacityTest {

    /** 默认必须是全不透明，不能让老用户升级后突然变淡。 */
    @Test
    void 默认全不透明() {
        assertEquals(1f, LyricStyleConfig.DEFAULT.opacity(), 1e-6f);
    }

    /** 下限夹紧：不允许调到 0（那等于歌词消失）。 */
    @Test
    void 下限夹紧() {
        assertEquals(LyricStyleConfig.MIN_OPACITY,
                LyricStyleConfig.DEFAULT.withOpacity(0f).opacity(), 1e-6f);
        assertEquals(LyricStyleConfig.MIN_OPACITY,
                LyricStyleConfig.DEFAULT.withOpacity(-3f).opacity(), 1e-6f);
    }

    /** 上限夹紧：不允许超过 1。 */
    @Test
    void 上限夹紧() {
        assertEquals(1f,
                LyricStyleConfig.DEFAULT.withOpacity(5f).opacity(), 1e-6f);
    }

    /** 调低后，所有绘制都应变淡。 */
    @Test
    void 调低后全部变淡() {
        Recorder r = new Recorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT
                .withOpacity(0.5f)
                .withLineCount(2));

        for (int i = 0; i < 3; i++) {
            r.reset();
            renderer.render(r);
        }

        assertTrue(!r.alphas.isEmpty(), "应有绘制发生");
        // 逐字渲染时已唱层为 255*0.5≈128，未唱层更低。
        int max = r.alphas.stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(max <= 130,
                "最高 alpha 应约等于 255×0.5，实际=" + max);
        assertTrue(max > 0, "不应完全透明");
    }

    /** 全不透明时，已唱字必须达到 alpha 255。 */
    @Test
    void 全不透明时已唱满值() {
        Recorder r = new Recorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT
                .withOpacity(1f)
                .withLineCount(1));

        for (int i = 0; i < 3; i++) {
            r.reset();
            renderer.render(r);
        }

        int max = r.alphas.stream().mapToInt(Integer::intValue).max().orElse(0);
        assertEquals(255, max, "已唱字应完全不透明，实际=" + max);
    }

    private static final class Recorder
            implements FloatingLyricRenderer.Painter {

        final List<Integer> alphas = new ArrayList<>();

        void reset() {
            alphas.clear();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            alphas.add((color >>> 24) & 0xFF);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            alphas.add((color >>> 24) & 0xFF);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            alphas.add((color >>> 24) & 0xFF);
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale,
                                   int color, boolean shadow) {
            alphas.add((color >>> 24) & 0xFF);
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
        public int textWidthScaled(String text, float scale) {
            return Math.round(textWidth(text) * scale);
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
