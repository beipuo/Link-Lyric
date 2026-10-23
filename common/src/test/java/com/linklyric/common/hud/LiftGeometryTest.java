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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字竖坐标的量化验证。
 *
 * <p>无法直接看图，因此改为记录每一次绘制的精确坐标，
 * 用数值证明「没有重影」「未唱字不上浮」这两条观感要求。
 */
class LiftGeometryTest {

    private static final String TEXT = "相爱过如果是很好听";

    /**
     * 未唱到的字不得上浮。
     *
     * <p>HyperLyric 的 {@code computeUnitLift} 对尚未唱到的位置返回
     * {@code maxOffset}（因为相位被夹到 0），也就是整行未唱部分会整体抬起。
     * 在手机大字号下这点位移不明显，但在我们 10px 行高、4px 位移的场景下，
     * 整行悬空会非常显眼，且与「唱到的字上浮」的直觉相反。
     */
    @Test
    void 未唱到的字不上浮() {
        // 位置 1000ms：只有第 1 个字被唱到
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(timeline(), 1_000L));

        for (int i = 0; i < 30; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        int baseY = painter.baseY();
        Map<String, Integer> ys = painter.lastY();

        // 尚未唱到的尾部字符必须停在基线
        for (int i = 6; i < TEXT.length(); i++) {
            String ch = String.valueOf(TEXT.charAt(i));
            Integer y = ys.get(ch);
            if (y == null) {
                continue;
            }
            assertEquals(baseY, y,
                    "未唱到的字 '" + ch + "' 不应上浮，实际 y=" + y
                            + " 基线=" + baseY + " 全部=" + ys);
        }
    }

    /** 正在演唱的那个字应上浮，且上浮量可见。 */
    @Test
    void 正在演唱的字上浮() {
        RecordingPainter painter = new RecordingPainter();
        // 必须让上报位置随真实时间推进，否则渲染器的位置外推器
        // 从 0 起算，短时间内只推进一个字的宽度，测不到峰值。
        //
        // 峰值出现在高亮刚越过某个字的起点时（相位约 0.47），
        // 因此窗口必须完整覆盖一个字以上的行程，否则采不到。
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(liveFeed(timeline(), 3_000L));

        Map<String, Integer> best = new LinkedHashMap<>();
        int bestBase = 0;
        int bestLift = -1;
        for (int i = 0; i < 260; i++) {
            painter.reset();
            renderer.render(painter);
            int base = painter.baseY();
            Map<String, Integer> ys = painter.lastY();
            int maxLift = 0;
            for (int y : ys.values()) {
                maxLift = Math.max(maxLift, base - y);
            }
            if (maxLift > bestLift) {
                bestLift = maxLift;
                best = ys;
                bestBase = base;
            }
            sleep();
        }

        assertTrue(bestLift > 0,
                "演唱过程中应出现上浮，实际最大=0 全部=" + best);
        assertTrue(bestLift >= 2,
                "上浮量应可见（>=2px），实际=" + bestLift
                        + " 基线=" + bestBase + " 全部=" + best);
    }

    /** 上浮应随时间平滑变化，不能一步跳到底或始终不动。 */
    @Test
    void 上浮随时间连续变化() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(liveFeed(timeline(), 0L));

        List<Integer> lifts = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            painter.reset();
            renderer.render(painter);
            int base = painter.baseY();
            int sum = 0;
            for (int y : painter.lastY().values()) {
                sum += base - y;
            }
            lifts.add(sum);
            sleep();
        }

        long distinct = lifts.stream().distinct().count();
        assertTrue(distinct >= 3,
                "上浮总量应随时间变化，实际只有 " + distinct + " 种取值：" + lifts);
        assertTrue(lifts.stream().anyMatch(v -> v > 0),
                "过程中应出现非零上浮，实际=" + lifts);
    }

    /** 同一个字在底色层与高亮层必须完全重合，否则就是重影。 */
    @Test
    void 两层不产生重影() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(feed(timeline(), 4_500L));

        for (int i = 0; i < 30; i++) {
            painter.reset();
            renderer.render(painter);

            Map<String, List<Integer>> perFrame = painter.ysThisFrame();
            for (Map.Entry<String, List<Integer>> e : perFrame.entrySet()) {
                List<Integer> list = e.getValue();
                for (int k = 1; k < list.size(); k++) {
                    assertEquals(list.get(0), list.get(k),
                            "字 '" + e.getKey() + "' 两层纵坐标必须一致，实际=" + list);
                }
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(4L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LyricTimeline timeline() {
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < TEXT.length(); i++) {
            words.add(new LyricWord(String.valueOf(TEXT.charAt(i)),
                    i * 1_000L, (i + 1) * 1_000L));
        }
        return new LyricTimeline(List.of(
                new LyricLine(TEXT, "", 0L, TEXT.length() * 1_000L,
                        List.copyOf(words), "")));
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

    /**
     * 位置随真实时间推进的数据源。
     *
     * <p>渲染器内部按上报值外推，若上报值恒定，位置会停在原地，
     * 测不到演唱中段的抬升行为。这里模拟真实播放。
     */
    private static LyricFeed liveFeed(LyricTimeline timeline, long startMs) {
        long originNanos = System.nanoTime();
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                long elapsed = (System.nanoTime() - originNanos) / 1_000_000L;
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
        private final List<String> texts = new ArrayList<>();
        private final List<Integer> ys = new ArrayList<>();
        private final Map<String, List<Integer>> perFrame = new LinkedHashMap<>();

        void reset() {
            texts.clear();
            ys.clear();
            perFrame.clear();
        }

        int baseY() {
            int max = Integer.MIN_VALUE;
            for (int y : ys) {
                max = Math.max(max, y);
            }
            return max == Integer.MIN_VALUE ? 0 : max;
        }

        Map<String, Integer> lastY() {
            Map<String, Integer> map = new LinkedHashMap<>();
            for (int i = 0; i < texts.size(); i++) {
                if (texts.get(i).length() == 1) {
                    map.putIfAbsent(texts.get(i), ys.get(i));
                }
            }
            return map;
        }

        Map<String, List<Integer>> ysThisFrame() {
            return new LinkedHashMap<>(perFrame);
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
            ys.add(y);
            if (text != null && text.length() == 1) {
                perFrame.computeIfAbsent(text, k -> new ArrayList<>()).add(y);
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
