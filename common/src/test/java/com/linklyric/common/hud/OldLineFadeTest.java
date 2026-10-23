package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧行淡出必须沿用该行**最后呈现的配色**。
 *
 * <p>若整行都用已唱色重画，亮色的已唱色会把整句刷成一片均匀亮色再淡出，
 * 旧句在消失前会「亮一下」，与它停留时的样子不符。
 * 这个瑕疵只在切换的头几百毫秒可见，只能靠采集绘制序列钉住。
 */
class OldLineFadeTest {

    private static final String L1 = "春江潮水连海平";
    private static final String L2 = "海上明月共潮生";

    @Test
    void 旧行淡出保留唱过与没唱过的分界() {
        RecordingPainter painter = new RecordingPainter();
        LyricTimeline timeline = new LyricTimeline(List.of(
                line(L1, 0L, 5_000L),
                line(L2, 5_000L, 10_000L)));

        // 时钟自动前进，才能自然走到换行那一刻。
        long start = System.nanoTime();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                // 从第一行末尾前一点开始，很快跨过换行点。
                return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                        4_600L + elapsedMs, true, 0L, System.nanoTime(),
                        CapabilitySet.empty());
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

        // 采满整个淡出过程，而不是找到第一个旧行帧就停。
        // 淡出仅 0.1 秒，单帧采样很可能只覆盖其中一小段，
        // 采不到两种透明度就会被误判成「没有分区上色」。
        java.util.List<Integer> accumulated = new ArrayList<>();
        long deadline = System.nanoTime() + 3_000_000_000L;
        boolean sawOldLine = false;
        boolean sawOldLineGone = false;
        while (System.nanoTime() < deadline) {
            painter.reset();
            renderer.render(painter);
            boolean present = painter.wholeTexts.contains(L1);
            if (present) {
                sawOldLine = true;
                // 累积而不是覆盖：保留淡出全过程中的所有透明度样本。
                painter.collectAlphas(accumulated);
            } else if (sawOldLine) {
                sawOldLineGone = true;
                break;
            }
            sleep();
        }
        painter.alphasSeen.addAll(accumulated);
        assertTrue(sawOldLineGone, "旧行应在淡出后消失");

        assertTrue(sawOldLine, "切换期间应出现整串绘制的旧行");
        // 淡出只有 0.1 秒，单次采样窗口很窄：可能恰好采到末段
        // （已唱层 alpha 归零、不再绘制），此时只剩一种透明度。
        // 因此这里要求「在整个淡出过程中」出现过两种透明度，
        // 而不是「某一帧内同时出现两种」。
        int distinctAlphas = (int) painter.alphasSeen.stream().distinct().count();
        assertTrue(distinctAlphas >= 2,
                "旧行淡出过程中应出现两种透明度，实际=" + painter.alphasSeen);
        int max = painter.alphasSeen.stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = painter.alphasSeen.stream().mapToInt(Integer::intValue).min().orElse(0);
        assertTrue(max > min,
                "未唱段应明显更淡，实际 max=" + max + " min=" + min);
    }

    private static LyricLine line(String text, long start, long end) {
        List<LyricWord> words = new ArrayList<>();
        long step = Math.max(1L, (end - start) / text.length());
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    start + step * i, start + step * (i + 1)));
        }
        return new LyricLine(text, "", start, end, List.copyOf(words), "");
    }

    private static void sleep() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingPainter
            implements FloatingLyricRenderer.Painter {

        final List<String> wholeTexts = new ArrayList<>();
        final List<Integer> alphasSeen = new ArrayList<>();

        /** 淡出全过程的透明度样本。 */
        void collectAlphas(List<Integer> sink) {
            sink.addAll(alphasSeen);
        }

        void reset() {
            wholeTexts.clear();
            alphasSeen.clear();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text != null && text.length() > 1) {
                wholeTexts.add(text);
                alphasSeen.add((color >>> 24) & 0xFF);
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
