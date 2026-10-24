package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多行堆叠的像素级验证。
 *
 * <p>块内每行都必须各自出画，行与行之间留有空隙。
 * 堆叠若失效，所有行会压到同一条基线上，视觉上是一团糊字，
 * 而纯坐标断言看不出这种问题。
 */
class MultiLineRenderTest {

    private static final int W = 480;
    private static final int H = 270;
    private static final int SCALE = 4;

    @Test
    void 行数增加时行带随之增加() {
        int one = bands(draw(1)).size();
        int three = bands(draw(3)).size();

        assertEquals(1, one, "单行应只有一个行带");
        assertEquals(3, three,
                "三行应出现三个行带，实际=" + three);
    }

    /** 相邻行带之间必须有背景色的间隔，否则两行是压在一起的。 */
    @Test
    void 行与行之间有空隙() {
        List<int[]> bs = bands(draw(3));
        assertEquals(3, bs.size(), "应出现三个独立行带，实际=" + bs.size());
        for (int i = 1; i < bs.size(); i++) {
            int gap = bs.get(i)[0] - bs.get(i - 1)[1] - 1;
            assertTrue(gap >= 1,
                    "第 " + i + " 与第 " + (i + 1) + " 行带之间应有空隙，实际=" + gap);
        }
    }

    private static LyricLine line(String text, long startMs, long endMs) {
        return new LyricLine(text, "", startMs, endMs, List.of(), "");
    }

    private BufferedImage draw(int lineCount) {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("第一句在这里", 0L, 4_000L),
                line("第二句在这里", 4_000L, 8_000L),
                line("第三句在这里", 8_000L, 12_000L),
                line("第四句在这里", 12_000L, 16_000L)));

        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("t", TrackIdentity.of("曲", "", 0L),
                        1_000L, true, 0L, System.nanoTime(), CapabilitySet.empty());
            }

            @Override
            public LyricTimeline lyric() {
                return timeline;
            }

            @Override
            public long lyricSignature() {
                return 1L;
            }
        });
        renderer.setStyle(LyricStyleConfig.DEFAULT.withLineCount(lineCount));

        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Dialog", Font.PLAIN, 10));
        g.setColor(new Color(32, 32, 40));
        g.fillRect(0, 0, W, H);

        renderer.render(new Canvas(g));
        g.dispose();
        return image;
    }

    /** 统计非背景色的横向行带。 */
    private static List<int[]> bands(BufferedImage im) {
        int bg = 0x202028;
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int y = 0; y < im.getHeight(); y++) {
            boolean any = false;
            for (int x = 0; x < im.getWidth(); x++) {
                if ((im.getRGB(x, y) & 0xFFFFFF) != bg) {
                    any = true;
                    break;
                }
            }
            if (any && start < 0) {
                start = y;
            } else if (!any && start >= 0) {
                out.add(new int[]{start, y - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(new int[]{start, im.getHeight() - 1});
        }
        return out;
    }

    /** 用真实 Graphics2D 绘制，裁剪交给平台的 clip。 */
    private static final class Canvas implements FloatingLyricRenderer.Painter {

        private final Graphics2D g;

        Canvas(Graphics2D g) {
            this.g = g;
        }

        @Override
        public int screenWidth() {
            return W;
        }

        @Override
        public int screenHeight() {
            return H;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : g.getFontMetrics().stringWidth(text);
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            drawText(text, x, y, color, true);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            g.setColor(new Color(color, true));
            g.drawString(text, x, y);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            drawText(text, x, y - Math.round(lift), color, shadow);
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale, int color) {
            drawTextScaled(text, x, y, scale, color, true);
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale,
                                   int color, boolean shadow) {
            java.awt.geom.AffineTransform save = g.getTransform();
            g.translate(x, y);
            g.scale(scale, scale);
            drawText(text, 0, 0, color, shadow);
            g.setTransform(save);
        }

        @Override
        public int textWidthScaled(String text, float scale) {
            return Math.round(textWidth(text) * scale);
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            g.setColor(new Color(color, true));
            g.fillRect(x, y, width, height);
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            g.setClip(x, y, width, height);
        }

        @Override
        public void disableScissor() {
            g.setClip(null);
        }
    }
}
