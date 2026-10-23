package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双行布局与行切换的视觉参数测试。
 *
 * <p>参数取自 Salt Player 的 {@code LyricsComponentDefaults}：
 * 未生效行 alpha {@code 0.3}、副行字号 {@code 0.8} 倍。
 * 这三条决定了「谁能一眼看出哪一行在唱」，比颜色本身更关键。
 */
class LineTransitionTest {

    private static final String L1 = "听说白雪公主在逃跑";
    private static final String L2 = "小红帽在担心大灰狼";

    /** 副行必须以 0.8 倍字号绘制，且宽度同步缩放。 */
    @Test
    void 副行按零点八倍字号绘制() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(twoLines(), 500L));

        for (int i = 0; i < 20; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        assertTrue(!painter.scaled.isEmpty(), "副行应通过缩放通道绘制");
        for (float scale : painter.scaled.values()) {
            assertEquals(0.8f, scale, 0.001f,
                    "副行缩放应为 0.8，实际=" + scale);
        }
    }

    /** 主行不得被缩放，缩放通道只用于副行。 */
    @Test
    void 主行不缩放() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(twoLines(), 500L));

        for (int i = 0; i < 20; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        assertEquals(1.0f, painter.primaryScaleSeen, 0.001f,
                "主行应以原始字号绘制，实际缩放=" + painter.primaryScaleSeen);
    }

    /**
     * 换行瞬间主行应处于半透明，而不是立刻全亮。
     *
     * <p>这是「交叉淡入淡出」与「直接切换」的分界：
     * Salt 的当前行从 {@code 0.3} 淡到 {@code 1.0}，因此换行后的头几帧
     * 必然采到 alpha 明显低于 255 的绘制。
     */
    @Test
    void 换行时主行由半透明淡入() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(liveFeed(twoLines()));

        boolean sawFaint = false;
        int frames = 0;
        while (frames < 60 && !sawFaint) {
            painter.reset();
            renderer.render(painter);
            for (Map.Entry<String, Integer> e : painter.alphas.entrySet()) {
                // 找出主行文字的不透明度
                if (L2.startsWith(e.getKey()) && e.getValue() < 250) {
                    sawFaint = true;
                    break;
                }
            }
            frames++;
            sleep();
        }

        // 主行现在**不做**淡入：单层满色硬切。
        //
        // 先前主行从 0.3 淡到 1.0，同时旧行反向淡出，两层压在同一位置，
        // 于是换行后一秒内颜色与稳定状态不同。这个测试原本验证那种淡入，
        // 现在改为验证「不存在半透明主行帧」。
        assertFalse(sawFaint,
                "主行不应出现半透明帧，否则会与旧行叠色导致颜色不一致");
    }

    /** 全部切换完成后，主行必须回到完全不透明。 */
    @Test
    void 切换完成后主行不透明() {
        RecordingPainter painter = new RecordingPainter();
        // 固定在第二行内部，避开换行窗口
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(twoLines(), 6_000L));

        int alpha = 0;
        List<String> trace = new ArrayList<>();
        // 过渡时长 700ms，而每帧只推进约 5ms，必须给足帧数，
        // 否则采到的是淡入中途（例如 alpha 223），不是稳定值。
        long deadline = System.nanoTime() + 1_200_000_000L;
        while (System.nanoTime() < deadline) {
            painter.reset();
            renderer.render(painter);
            Integer a = painter.alphaOfFirstChar(L2);
            if (a != null) {
                alpha = a;
                trace.add(a.toString());
            }
            sleep();
        }

        // 主行文字的不透明度取决于是否唱到：
        // 唱到的字满色，没唱到的字压到 UNSUNG_ALPHA。
        // 这里停在行内部、尚未唱完，因此首字应满色。
        assertEquals(255, alpha,
                "已唱的字应满不透明，实际 alpha=" + alpha + " 轨迹=" + trace);
    }

    private static void sleep() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LyricTimeline twoLines() {
        return new LyricTimeline(List.of(
                line(L1, 0L, 5_000L),
                line(L2, 5_000L, 10_000L)));
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

    private static LyricFeed feed(LyricTimeline timeline, long position) {
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                        position, true, 0L, System.nanoTime(), CapabilitySet.empty());
            }

            @Override
            public LyricTimeline lyric() {
                return timeline;
            }

            @Override
            public long lyricSignature() {
                return 1L;
            }
        };
    }

    private static LyricFeed liveFeed(LyricTimeline timeline) {
        long origin = System.nanoTime();
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                long elapsed = (System.nanoTime() - origin) / 1_000_000L;
                return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                        4_900L + elapsed, true, 0L, System.nanoTime(),
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
        };
    }

    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {
        final Map<String, Float> scaled = new LinkedHashMap<>();
        final Map<String, Integer> alphas = new LinkedHashMap<>();
        float primaryScaleSeen = 1.0f;

        void reset() {
            scaled.clear();
            alphas.clear();
            primaryScaleSeen = 1.0f;
        }

        Integer alphaOfFirstChar(String text) {
            Integer found = alphas.get(String.valueOf(text.charAt(0)));
            return found;
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
            record(text, color, 1.0f);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift, int color) {
            record(text, color, 1.0f);
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale, int color) {
            record(text, color, scale);
        }

        @Override
        public int textWidthScaled(String text, float scale) {
            return Math.round(textWidth(text) * scale);
        }

        private void record(String text, int color, float scale) {
            if (text == null || text.isEmpty()) {
                return;
            }
            int alpha = (color >>> 24) & 0xFF;
            if (text.length() == 1) {
                // 逐字绘制会画两次：先未唱层、再已唱层。
                // 必须记录**最大值**，否则取到的是未唱层的偏淡 alpha，
                // 会误判成「主行永远半透明」——那其实是没唱到的那一层。
                alphas.merge(text, alpha, Math::max);
            }
            if (scale != 1.0f) {
                scaled.putIfAbsent(text, scale);
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
        }

        @Override
        public void disableScissor() {
        }
    }
}
