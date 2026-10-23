package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class KaraokeSweepTest {

    @Test
    void 逐帧记录染色分段() throws Exception {
        Recorder r = new Recorder();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT.withLineCount(1));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            r.reset();
            renderer.render(r);
            sb.append("frame").append(i)
              .append(" clips=").append(r.clips)
              .append("\n");
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Files.writeString(Path.of("build/karaoke-sweep.txt"), sb.toString());
    }

    private static final class Recorder
            implements FloatingLyricRenderer.Painter {
        final List<String> clips = new ArrayList<>();

        void reset() {
            clips.clear();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text != null && !text.isEmpty()) {
                clips.add("T'" + text + "'@" + x + "#" + ((color >> 16) & 0xFF));
            }
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            if (text != null && !text.isEmpty()) {
                clips.add("T'" + text + "'@" + x + "#" + ((color >> 16) & 0xFF));
            }
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            if (text != null && !text.isEmpty()) {
                clips.add("L'" + text + "'@" + x + "#" + ((color >> 16) & 0xFF)
                        + "f" + String.format("%.2f", lift));
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
        public void enableScissor(int x, int y, int width, int height) {
            clips.add("S" + width + "@" + x);
        }

        @Override
        public void disableScissor() {
        }
    }
}
