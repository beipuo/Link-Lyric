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

        // 主行是逐字绘制的，所以不能靠「整串文本出现在一次 drawText 里」判断。
        // 副行（第 2 行起）是整串绘制，可以直接匹配。
        String drawnAll = String.join("", painter.texts);
        assertTrue(drawnAll.contains(L1), "应画出当前句，实际=" + drawnAll);
        assertTrue(painter.texts.stream().anyMatch(t -> t.contains(L2)),
                "应画出下一句作为副行（整串绘制），实际=" + painter.texts);
        assertFalse(painter.texts.stream().anyMatch(t -> t.contains(L3)),
                "默认 2 行时不应显示下下句，实际=" + painter.texts);
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

        // 主行逐字绘制，取任一单字的位置作为主行基线。
        int mainY = Integer.MIN_VALUE;
        for (int k = 0; k < painter.texts.size(); k++) {
            if (painter.texts.get(k).length() == 1
                    && L1.contains(painter.texts.get(k))) {
                mainY = painter.ys.get(k);
                break;
            }
        }
        try {
            var sb = new StringBuilder();
            sb.append("mainY=").append(mainY).append("\n");
            for (int k = 0; k < painter.texts.size(); k++) {
                sb.append("  [").append(painter.texts.get(k)).append("] y=")
                  .append(painter.ys.get(k)).append("\n");
            }
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("build", "diag-twoline2.txt"),
                    sb.toString());
        } catch (Exception ignored) {
        }
        assertTrue(mainY != Integer.MIN_VALUE, "应画出主行逐字内容");
        assertTrue(yOf.containsKey("副行"), "应画出副行，实际=" + yOf);
        assertTrue(yOf.get("副行") > mainY,
                "副行应在主行下方，实际 主=" + mainY + " 副=" + yOf.get("副行"));
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

        String all = String.join("", painter.texts);
        assertTrue(all.contains(L3), "末句应正常显示，实际=" + all);
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
            // 主行走逐字绘制（键为单字），不能用 contains(整行) 匹配。
            // mainLineBaseY 按「字符是否属于该行」判断，两种路径都适用。
            int base = painter.mainLineBaseY(L2);
            if (base != Integer.MIN_VALUE) {
                mainYs.add(base);
            }
            sleep();
        }

        assertFalse(mainYs.isEmpty(), "应采到新主行位置");
        // 切换是「旧主行先消失，其余行再上移一行补位」，因此新主行
        // 必然有一段纵向位移过程：从下一行位置升到块顶。
        //
        // 位移只发生一次、单向向上，不会来回跳，也不会与旧行擦身而过——
        // 旧行在位移开始前就已淡出完毕。
        long distinct = mainYs.stream().distinct().count();
        assertTrue(distinct >= 2,
                "新主行应有上移补位的过渡过程，实际取值=" + mainYs);

        // 位移必须单调向上（y 递减），不能出现回落。
        for (int i = 1; i < mainYs.size(); i++) {
            assertTrue(mainYs.get(i) <= mainYs.get(i - 1),
                    "上移过程中 y 应单调不增，实际=" + mainYs);
        }
    }

    /**
     * 抬升幅度必须是 {@code round(字号 × 0.1)}。
     *
     * <p>刻意用单行时间轴：有副行时主行位置还叠加了行切换偏移（最多 12px），
     * 两者混在一起量不出抬升本身。
     *
     * <p>取值来自 Salt Player 的 {@code floatUpPercentage = 0.1f}。
     * 测试替身的字号是 9px，故峰值应为 1px（旧实现的写死 5px 已废弃）。
     */
    @Test
    void 抬升幅度为字号的百分之十() {
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

        // 9px 字号 × 0.12 = 1.08，roundToInt 得 1。
        assertTrue(peak == 1,
                "峰值抬升应为 round(9 × 0.12) = 1，实际=" + peak);
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

        /**
         * 三条绘制入口必须记录同样的三元组。
         *
         * <p>曾经四参/五参 {@code drawText} 只写 {@code texts} 而不写
         * {@code xs}/{@code ys}，而断言按同一下标读三个表，
         * 于是抛 {@code IndexOutOfBoundsException}——
         * 报错位置看起来像布局算错，实际是替身记录不对齐。
         * 现在统一走 {@link #record}。
         */
        private void record(String text, int x, int y) {
            texts.add(text);
            xs.add(x);
            ys.add(y);
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            record(text, x, y);
        }

        /**
         * 逐字绘制走五参重载，必须一并记录。
         *
         * <p>只重写四参 {@code drawText} 收不到主行：主行调用的是五参
         * {@code drawTextLifted}，其默认实现转发到五参 {@code drawText}，
         * 若替身未重写四参链路的入口就会漏掉。
         */
        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            record(text, x, y);
        }

        /**
         * 记录抬升后的实际 y。
         *
         * <p>平台层画字时 y 会减去抬升量，替身必须复现这一点，
         * 否则「抬升幅度」这类断言测的是未抬升的原始基线。
         */
        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            record(text, x, y - Math.round(lift));
        }

        /**
         * 字内渐变绘制也要记录。
         *
         * <p>现在主行的前沿字走这条路径；不记录的话
         * 「副行在主行下方」「抬升幅度」这类断言会看不到主行文字，
         * 断言的失败原因看起来像布局算错，实际是替身漏收。
         */
        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       com.linklyric.common.style.GlyphColors colors,
                                       boolean shadow) {
            record(glyph, Math.round(x), y - Math.round(lift));
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
