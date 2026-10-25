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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换行时旧主行必须**直接消失**，不得重画。
 *
 * <p>这是「已唱完的主句消失时会以最基础的文字样式闪一帧」的回归测试。
 *
 * <p>旧行淡出要把整句重新画一遍，而整句绘制（{@code painter.drawText}）
 * 只带单一颜色：逐字抬升、逐顶点染色、强调提亮三样全无。
 * 于是唱完的主句会在原位退化成一段「裸文字」再淡出，
 * 与它停留时的样子判若两句。
 *
 * <p>判据很直接：换行之后不应再出现任何「整串绘制」的旧句，
 * 只允许逐字路径绘制当前主行。
 */
class OldLineFadeTest {

    private static final String L1 = "春江潮水连海平";
    private static final String L2 = "海上明月共潮生";

    @Test
    void 换行后旧行不再重画() {
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

        // 采样整个跨行过程：换行前后各覆盖若干帧。
        List<String> seenAfterSwitch = new ArrayList<>();
        boolean switched = false;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            painter.reset();
            renderer.render(painter);
            // 一旦看到第二句出现，就认为已经跨过换行点。
            if (painter.drawnChars.contains(L2.substring(0, 1))) {
                switched = true;
            }
            if (switched && painter.wholeTexts.contains(L1)) {
                seenAfterSwitch.add(L1);
            }
            if (switched) {
                break;
            }
            sleep();
        }

        assertTrue(switched, "应观察到换行发生");
        assertEquals(List.of(), seenAfterSwitch,
                "换行后不得再以整串形式重画旧行，实际=" + seenAfterSwitch);
    }

    /**
     * 换行时新主行与后续行的上移补位仍然保留。
     *
     * <p>旧行不再淡出，但「整块连续上移一行」必须照旧，
     * 否则换行会变成硬切，语句跳一下。
     */
    @Test
    void 换行时块仍连续上移() {
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
                        // 起点落在第一句尾段，约 600ms 后跨入第二句。
                        4_400L + elapsedMs, true, 0L, System.nanoTime(),
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

        // 先画一帧属于第一句的画面。
        //
        // 首次出画不计入换行过渡（否则进入世界时整块歌词会从下方滑上来），
        // 所以必须先把这一次消耗掉，后面真正的跨行才会产生位移。
        renderer.render(painter);
        sleep();

        // 只在进入第二句之后采集主行首字的 y。
        //
        // 第二句在成为主行之前会先以「下一句预览」的形式出现，
        // 那时它的 y 固定不动。若不按播放位置过滤，
        // 采到的全是预览行，位移永远看不到——这正是本测试曾经的假阴性。
        List<Integer> ys = new ArrayList<>();
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (System.nanoTime() < deadline) {
            painter.reset();
            renderer.render(painter);
            if (renderer.currentPositionMs() >= 5_000L) {
                Integer y = painter.frameYs.get(L2.substring(0, 1));
                if (y != null) {
                    ys.add(y);
                }
                // 过渡只有几百毫秒，采够十余帧足以覆盖起止。
                if (ys.size() >= 12) {
                    break;
                }
            }
            sleep();
        }

        assertTrue(ys.size() >= 2, "换行后应采集到多帧主行位置，实际=" + ys);
        int first = ys.get(0);
        int last = ys.get(ys.size() - 1);
        assertTrue(first > last,
                "主行应自下而上移动，实际 first=" + first + " last=" + last
                        + "（全部=" + ys + "）");
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

        /** 本帧以整串形式绘制的旧句。 */
        final List<String> wholeTexts = new ArrayList<>();
        /** 本帧逐字绘制的字符。 */
        final List<String> drawnChars = new ArrayList<>();
        /** 本帧「字符 -> y」。 */
        java.util.Map<String, Integer> frameYs = new java.util.LinkedHashMap<>();

        void reset() {
            wholeTexts.clear();
            drawnChars.clear();
            frameYs = new java.util.LinkedHashMap<>();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (text.length() > 1) {
                wholeTexts.add(text);
            }
            String key = text.substring(0, 1);
            drawnChars.add(key);
            frameYs.put(key, y);
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
