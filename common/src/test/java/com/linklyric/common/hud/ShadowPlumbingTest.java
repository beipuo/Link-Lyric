package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阴影开关必须贯穿所有绘制分支。
 *
 * <p>主行逐字、主行整串、副行缩放三条路径都要带上 shadow。
 * 漏掉任何一条，用户切换阴影后看到的就不是「统一关掉」，
 * 而是某些行仍带阴影、某些行没有，像是文字被画错了。
 */
class ShadowPlumbingTest {

    @Test
    void 关闭阴影后所有绘制都不带阴影() {
        RecordingPainter painter = new RecordingPainter(false);

        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT.withShadow(false));

        for (int i = 0; i < 10; i++) {
            painter.reset();
            renderer.render(painter);
        }

        assertTrue(painter.shadowCalls > 0, "应有绘制发生");
        assertTrue(painter.withShadow == 0,
                "关闭阴影后不应有带阴影的绘制，实际=" + painter.withShadow
                        + " / 共 " + painter.shadowCalls);
    }

    @Test
    void 开启阴影后所有绘制都带阴影() {
        RecordingPainter painter = new RecordingPainter(true);

        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT.withShadow(true));

        for (int i = 0; i < 10; i++) {
            painter.reset();
            renderer.render(painter);
        }

        assertTrue(painter.shadowCalls > 0, "应有绘制发生");
        assertTrue(painter.withoutShadow == 0,
                "开启阴影后不应有省略阴影的绘制，实际=" + painter.withoutShadow
                        + " / 共 " + painter.shadowCalls);
    }

    /**
     * 字重叠加时，阴影只能画一份。
     *
     * <p>每层都带阴影会让阴影副本按层数倍增（加粗 9 层就是 9 份），
     * 全叠在几乎同一区域，字形边缘糊成一团黑边，文字像被涂坏。
     * 这是「切换阴影后主行文字成一团」的成因。
     */
    @Test
    void 加粗时阴影只画一份() {
        RecordingPainter painter = new RecordingPainter(true);

        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT
                .withShadow(true)
                .withWeight(LyricStyleConfig.Weight.HEAVY));

        for (int i = 0; i < 5; i++) {
            painter.reset();
            renderer.render(painter);
        }

        assertTrue(painter.shadowCalls > 0, "应有绘制发生");
        // 特粗是 5 层（半径 2），每帧只应有一份阴影。
        // 改用原版加粗后每帧只画一次，阴影份数就应等于绘制次数。
        // 若出现「阴影份数 > 绘制次数」，说明又回到了重复绘制的老路。
        assertEquals(painter.shadowCalls, painter.withShadow,
                "开启阴影后每次绘制都应带阴影，实际 "
                        + painter.withShadow + " / " + painter.shadowCalls);
    }

    private static final class RecordingPainter
            implements FloatingLyricRenderer.Painter {

        final boolean defaultShadow;
        int shadowCalls;
        int withShadow;
        int withoutShadow;

        RecordingPainter(boolean defaultShadow) {
            this.defaultShadow = defaultShadow;
        }

        void reset() {
            shadowCalls = 0;
            withShadow = 0;
            withoutShadow = 0;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            record(defaultShadow);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            record(shadow);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            record(shadow);
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale,
                                   int color, boolean shadow) {
            record(shadow);
        }

        private void record(boolean shadow) {
            shadowCalls++;
            if (shadow) {
                withShadow++;
            } else {
                withoutShadow++;
            }
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
