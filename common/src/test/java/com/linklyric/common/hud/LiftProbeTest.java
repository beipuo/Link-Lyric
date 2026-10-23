package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class LiftProbeTest {

    @Test
    void 探测逐字路径() throws Exception {
        Recorder r = new Recorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT.withLineCount(1));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            r.reset();
            renderer.render(r);
            sb.append("frame").append(i)
              .append(" lifted=").append(r.lifted)
              .append(" plain=").append(r.plain).append("\n");
        }
        Files.writeString(Path.of("build/lift-probe.txt"), sb.toString());
    }

    private static final class Recorder
            implements FloatingLyricRenderer.Painter {
        final List<String> lifted = new ArrayList<>();
        final List<String> plain = new ArrayList<>();

        void reset() {
            lifted.clear();
            plain.clear();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            plain.add(text);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            lifted.add(text + "@" + x + ":" + String.format("%.2f", lift));
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
        public int textWidthScaled(String text, float scale) {
            return Math.round(textWidth(text) * scale);
        }

        @Override
        public int lineHeight() {
            return 12;
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale,
                                   int color, boolean shadow) {
            plain.add("[scaled]" + text);
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }
    }
}
