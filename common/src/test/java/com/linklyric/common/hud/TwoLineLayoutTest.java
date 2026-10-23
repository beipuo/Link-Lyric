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
 * 双行歌词与行切换动画的回归测试。
 *
 * <p>两者都是纯观感特性，出错不抛异常，只会表现为「不显示」「不切换」，
 * 因此用坐标与绘制次数钉住行为。
 */
class TwoLineLayoutTest {

    private static final String L1 = "春风又绿江南岸";
    private static final String L2 = "明月何时照我还";
    private static final String L3 = "孤帆远影碧空尽";

    /** 两行时间轴：每行 4 秒。 */
    private static LyricTimeline timeline() {
        return new LyricTimeline(List.of(
                line(L1, 0L, 4_000L),
                line(L2, 5_000L, 9_000L),
                line(L3, 10_000L, 14_000L)));
    }

    /** 主行之外必须同时画出下一句。 */
    @Test
    void 同时显示当前句与下一句() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(timeline(), 1_000L));

        for (int i = 0; i < 30; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        List<String> drawn = painter.texts;
        assertTrue(drawn.stream().anyMatch(t -> t.contains(L1)),
                "应画出当前句，实际=" + drawn);
        assertTrue(drawn.stream().anyMatch(t -> t.contains(L2)),
                "应画出下一句作为副行，实际=" + drawn);
        assertFalse(drawn.stream().anyMatch(t -> t.contains(L3)),
                "不应显示下下句，实际=" + drawn);
    }

    /** 副行必须在主行下方，不能重叠。 */
    @Test
    void 副行位于主行下方() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(timeline(), 1_000L));

        Map<String, Integer> yOf = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            painter.reset();
            renderer.render(painter);
            for (int k = 0; k < painter.texts.size(); k++) {
                String t = painter.texts.get(k);
                if (t.contains(L2)) {
                    yOf.put("副行", painter.ys.get(k));
                } else if (t.contains(L1) && !yOf.containsKey("主行")) {
                    yOf.put("主行", painter.ys.get(k));
                }
            }
            sleep();
        }

        assertTrue(yOf.containsKey("主行") && yOf.containsKey("副行"),
                "两行都应被绘制，实际=" + yOf);
        assertTrue(yOf.get("副行") > yOf.get("主行"),
                "副行应在主行下方，实际 主=" + yOf.get("主行")
                        + " 副=" + yOf.get("副行"));
    }

    /** 最后一句没有下一句时，副行改为显示翻译（若有）。 */
    @Test
    void 末句无副行时不崩溃() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(timeline(), 12_000L));

        for (int i = 0; i < 20; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        assertTrue(painter.texts.stream().anyMatch(t -> t.contains(L3)),
                "末句应正常显示，实际=" + painter.texts);
    }

    /** 行切换应给出渐进的中间位移，而非瞬间跳到位。 */
    @Test
    void 行切换是渐进的() {
        RecordingPainter painter = new RecordingPainter();
        // 恰好越过第一行结束、第二行开始的时刻
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(liveFeed(timeline(), 4_900L));

        List<Integer> mainYs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            painter.reset();
            renderer.render(painter);
            for (int k = 0; k < painter.texts.size(); k++) {
                if (painter.texts.get(k).contains(L2)) {
                    mainYs.add(painter.ys.get(k));
                    break;
                }
            }
            sleep();
        }

        assertFalse(mainYs.isEmpty(), "应采到新主行位置");
        long distinct = mainYs.stream().distinct().count();
        assertTrue(distinct >= 2,
                "新主行位置应有过渡过程，实际取值=" + mainYs);
    }

    /**
     * 抬升必须是固定 5px。
     *
     * <p>刻意用单行时间轴：有副行时主行位置还叠加了行切换偏移（最多 12px），
     * 两者混在一起量不出抬升本身。
     */
    @Test
    void 抬升固定五像素() {
        LyricTimeline single = new LyricTimeline(List.of(line(L1, 0L, 7_000L)));

        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(liveFeed(single, 0L));

        // 只统计稳定帧：行切换的过渡偏移会叠加到主行位置，
        // 把它算进来会把 5px 抬升量成 6px。
        // 判据是「该行所有绘制的最大 y 已连续多帧不变」。
        int peak = 0;
        int stableBase = Integer.MIN_VALUE;
        int stableCount = 0;
        for (int i = 0; i < 200; i++) {
            painter.reset();
            renderer.render(painter);
            int base = painter.mainLineBaseY(L1);
            if (base == Integer.MIN_VALUE) {
                sleep();
                continue;
            }
            if (base == stableBase) {
                stableCount++;
            } else {
                stableBase = base;
                stableCount = 0;
            }
            if (stableCount >= 2) {
                for (int k = 0; k < painter.texts.size(); k++) {
                    // 只统计逐字绘制（单字符）的抬升。
                    // 离场行是整行一次性绘制，且带自己的上移，
                    // 算进来会把 5px 量成 6px。
                    if (painter.texts.get(k).length() != 1) {
                        continue;
                    }
                    peak = Math.max(peak, stableBase - painter.ys.get(k));
                }
            }
            sleep();
        }

        assertEquals(5, peak, "抬升应为固定 5px，实际=" + peak);
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
            Thread.sleep(4L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private static LyricFeed liveFeed(LyricTimeline timeline, long startMs) {
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
                        startMs + elapsed, true, 0L, System.nanoTime(),
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
        final List<String> texts = new ArrayList<>();
        final List<Integer> xs = new ArrayList<>();
        final List<Integer> ys = new ArrayList<>();

        void reset() {
            texts.clear();
            xs.clear();
            ys.clear();
        }

        int baseY() {
            int max = Integer.MIN_VALUE;
            for (int y : ys) {
                max = Math.max(max, y);
            }
            return max == Integer.MIN_VALUE ? 0 : max;
        }

        /**
         * 指定文本所在行的基线（该行所有绘制中最低的 y）。
         *
         * <p>逐字绘制时每次 drawText 只传一个字符，
         * 因此不能用 contains(整行) 判断，必须按字符归属。
         */
        int mainLineBaseY(String text) {
            int max = Integer.MIN_VALUE;
            for (int k = 0; k < texts.size(); k++) {
                String t = texts.get(k);
                if (t.isEmpty()) {
                    continue;
                }
                boolean belongs = text.contains(t) || t.contains(text);
                if (belongs) {
                    max = Math.max(max, ys.get(k));
                }
            }
            return max;
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
            xs.add(x);
            ys.add(y);
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
