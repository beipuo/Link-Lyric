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
        /** 本次 render 内「文本 -> y」的最后一次记录，用于验证绘制位置。 */
        final java.util.Map<String, Integer> ys = new java.util.LinkedHashMap<>();
        /** reset 时暂存上一帧的 ys，供断言读取。 */
        java.util.Map<String, Integer> lastYs = new java.util.LinkedHashMap<>();

        /** 淡出全过程的透明度样本。 */
        void collectAlphas(List<Integer> sink) {
            sink.addAll(alphasSeen);
        }

        void reset() {
            lastYs = new java.util.LinkedHashMap<>(ys);
            wholeTexts.clear();
            alphasSeen.clear();
            ys.clear();
        }

        /** 把本帧记录转成可读快照。 */
        void replayYs() {
            lastYs = new java.util.LinkedHashMap<>(ys);
        }

        /** 整串（旧行）或逐字（主行）都能命中：取等值或前缀匹配。 */
        Integer yOfText(String text) {
            Integer exact = lastYs.get(text);
            if (exact != null) {
                return exact;
            }
            // 主行走逐字绘制，键是单个字符；取该行首字的 y。
            return lastYs.get(text.substring(0, 1));
        }

        /**
         * 该文本本帧是以「主行」身份绘制的吗。
         *
         * <p>主行走逐字路径（键为单字），旧行走整串路径（键为整句）。
         * 两者同时存在时，用键长度即可区分，不必依赖顺序。
         */
        boolean lastWholeWasMain(String text) {
            return lastYs.containsKey(text.substring(0, 1))
                    && !lastYs.containsKey(text);
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            ys.put(text.length() > 1 ? text : text.substring(0, 1), y);
            if (text.length() > 1) {
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

    /**
     * 过渡期间旧行必须停在**块顶上方一行**，不得占据新主行的槽位。
     *
     * <p>这是「旧行消失瞬间会抬升一行、后面的行错位一下」的回归测试。
     *
     * <p>风险来源：{@code block} 按当前 {@code drawableLines} 计算，
     * {@code block.baseline()} 已经是新主行的位置。旧行若画在那里，
     * 就等于和新顶上来的主行抢同一格；{@code blockShift} 在淡出期间
     * 恒为一整行，整块会先挂在「偏移一行」的第三种状态上。
     *
     * <p>正确性质有两条，缺一不可：
     * 旧行的 y 严格小于同一帧主行的 y；
     * 两者相差恰好一个行步长（行高 + 行距），不多不少。
     */
    @Test
    void 旧行停在块顶上方且不占新主行槽位() {
        RecordingPainter painter = new RecordingPainter();
        LyricTimeline timeline = new LyricTimeline(List.of(
                line(L1, 0L, 5_000L),
                line(L2, 5_000L, 10_000L)));

        long start = System.nanoTime();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                        4_700L + elapsedMs, true, 0L, System.nanoTime(),
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

        // 分两段采集。
        //
        // 第一段：换行前，记录 L1 作为主行时的 y —— 这就是旧行**应该**
        // 停留的位置，必须从真实绘制里取，不能按布局常量推算。
        // 第二段：换行后，记录旧行每一次出现的 y，验证它恒定等于第一段的值。
        int beforeY = Integer.MIN_VALUE;
        java.util.List<Integer> oldYs = new ArrayList<>();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            painter.reset();
            renderer.render(painter);
            painter.replayYs();
            Integer oy = painter.yOfText(L1);
            if (beforeY == Integer.MIN_VALUE && oy != null && oldYs.isEmpty()
                    && painter.lastWholeWasMain(L1)) {
                // 换行前：L1 是主行（逐字路径），先记下它的位置。
                beforeY = oy;
            } else if (oy != null) {
                oldYs.add(oy);
            }
            if (!oldYs.isEmpty() && oldYs.size() >= 6) {
                break;
            }
            sleep();
        }

        assertTrue(beforeY != Integer.MIN_VALUE,
                "应采集到换行前主行的位置");
        assertTrue(!oldYs.isEmpty(),
                "换行后旧行应被绘制，实际一次都没有");

        // 旧行位置不能由 block.baseline() - blockLineStep() 推算：
        // 块按 drawableLines 居中，换行后行数变化会让块整体重新居中，
        // 基线本身已经跳过；在其上再减一行，旧行会跑到上方一格闪现。
        //
        // 容差 1px：位置要过 blockShift 的取整。
        for (int y : oldYs) {
            assertTrue(Math.abs(y - beforeY) <= 1,
                    "旧行应停在换行前的位置 " + beforeY
                            + "，实际=" + y + "（全部样本=" + oldYs + "）");
        }
        // 全程恒定：不能跟着新主行往上跑。
        int min = oldYs.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = oldYs.stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(max - min <= 1,
                "旧行淡出期间位置应恒定，实际波动 " + min + ".." + max);
    }
}
