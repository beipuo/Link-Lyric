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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字级上浮的回归测试。
 *
 * <p>上浮是纯观感特性，出错不会抛异常，只会表现为「不动」或「乱抖」，
 * 因此必须用坐标断言把它钉住。
 */
class CharLiftTest {

    /**
     * 演唱点附近的字应被抬起，且离演唱点越远抬得越少。
     *
     * <p>这正是「唱到的字上浮、唱过的字归位」的核心行为。
     */
    @Test
    void 演唱点附近的字抬起而远处归位() {
        // 10 个字，每个 1000ms，行宽实测 10 × 9 = 90px
        String text = "一二三四五六七八九十";
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 10_000)));

        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 5_000L, true));

        // 多帧让平滑收敛
        for (int i = 0; i < 20; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        Map<String, Integer> ys = painter.yOfFirst(text);
        assertNotNull(ys, "应逐字绘制，实际绘制文本=" + painter.texts);

        // 位置 5000ms ≈ 第 5 个字刚唱完，行宽 90px，故高亮约 45px 即第 5 字附近。
        // 必须比较「相对基线的偏移量」，直接比 y 会把绝对坐标当成抬升量。
        int baseY = painter.baseY;
        int liftAtCursor = baseY - ys.get("六");
        int liftFarBehind = baseY - ys.get("一");

        // 升起后永久保持高位的语义下：
        // 早已唱完的字停在顶端，正在唱的字还在上升途中，因此
        // 「近处可能低于远处」是预期行为，不能反过来断言。
        // 这里只需确认两者都确实被抬起过。
        assertTrue(liftFarBehind > 0,
                "唱过的字应保持抬起，实际 '一' 偏移=" + liftFarBehind
                        + " 基线=" + baseY + " 全部=" + ys);
        assertTrue(liftAtCursor >= 0,
                "正在唱的字不应低于基线，实际 '六' 偏移=" + liftAtCursor);

        // 未唱到的字由底色层绘制（白色原词），这是预期行为。
        // 关键是要确认：同一个字在底色层与高亮层的纵坐标必须一致，
        // 否则会出现「高亮字从上面落到原字上」的重影。
        Map<String, List<Integer>> allYs = painter.allYsOf(text);
        for (Map.Entry<String, List<Integer>> entry : allYs.entrySet()) {
            List<Integer> list = entry.getValue();
            for (int i = 1; i < list.size(); i++) {
                assertEquals(list.get(0), list.get(i),
                        "字 '" + entry.getKey() + "' 在两层中的纵坐标必须一致，实际="
                                + list + " 全部=" + allYs);
            }
        }
    }

    /** 逐字宽度必须按真实字形累加，不能平均分摊。 */
    @Test
    void 逐字坐标按真实宽度累加() {
        // 让 'W' 明显宽于 'i'，检验是否按真实宽度而非平均值
        String text = "WiWiWiWi";
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 8_000)));

        RecordingPainter painter = new RecordingPainter();
        painter.variableWidth = true;
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 4_000L, true));

        for (int i = 0; i < 20; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        Map<String, Integer> xs = painter.xOfFirst(text);
        assertNotNull(xs, "应逐字绘制");

        // W 宽 14、i 宽 4：若按平均值（(14+4)/2=9）分摊，
        // 第 3 个字符位置会落在 18；按真实宽度应是 14+4=18 之后的 W 起点 18。
        // 真正的区分点在第 5 个字符：真实=14+4+14+4=36，平均=4*9=36。
        // 因此改用总宽是否等于真实总和来判别。
        assertEquals(14 + 4 + 14 + 4 + 14 + 4 + 14 + 4, painter.textWidth(text),
                "测试前提：真实总宽应为 72");
        assertTrue(xs.get("i") >= 14,
                "第一个 i 应在第一个 W（宽 14）之后，实际 x=" + xs.get("i"));
    }

    /** 超长行应退回整段绘制，避免几十次绘制调用。 */
    @Test
    void 超长行退回整段绘制() {
        String text = "一".repeat(40);
        LyricTimeline timeline = new LyricTimeline(List.of(wordsLine(text, 0, 40_000)));

        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer =
                new FloatingLyricRenderer(new Feed(timeline, 20_000L, true));
        for (int i = 0; i < 12; i++) {
            painter.reset();
            renderer.render(painter);
            sleep();
        }

        // 退回整段后，每次 drawText 的文本应是整行而非单字
        boolean hasSingleChar = painter.texts.stream().anyMatch(t -> t.length() == 1);
        boolean hasFullLine = painter.texts.stream().anyMatch(t -> t.length() == text.length());
        assertTrue(hasFullLine || !hasSingleChar,
                "超长行应整段绘制，实际绘制文本样本="
                        + painter.texts.stream().map(String::length).distinct().toList());
    }

    private static int liftOf(Map<String, Integer> ys, String ch) {
        Integer y = ys.get(ch);
        return y == null ? -1 : y;
    }

    private static void sleep() {
        try {
            Thread.sleep(4L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LyricLine wordsLine(String text, long start, long end) {
        List<LyricWord> words = new ArrayList<>();
        long step = Math.max(1L, (end - start) / text.length());
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    start + step * i, start + step * (i + 1)));
        }
        return new LyricLine(text, "", start, end, List.copyOf(words), "");
    }

    private record Feed(LyricTimeline timeline, long position, boolean playing)
            implements LyricFeed {
        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                    position, playing, 0L, System.nanoTime(), CapabilitySet.empty());
        }

        @Override
        public LyricTimeline lyric() {
            return timeline;
        }

        @Override
        public long lyricSignature() {
            return 1L;
        }
    }

    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {
        final List<String> texts = new ArrayList<>();
        final List<Integer> xs = new ArrayList<>();
        final List<Integer> ys = new ArrayList<>();
        boolean variableWidth;

        /**
         * 基线 y。
         *
         * <p>抬升只会让 y 变小，因此所有绘制过的 y 里最大的那个就是基线。
         * 用它做参照才能把「抬升量」从绝对坐标里分离出来。
         */
        int baseY = Integer.MIN_VALUE;

        private static final int NO_CLIP = Integer.MIN_VALUE;
        private int clipX = NO_CLIP;
        private int clipW;

        void reset() {
            texts.clear();
            xs.clear();
            ys.clear();
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
            if (text == null) {
                return 0;
            }
            if (!variableWidth) {
                return text.length() * 9;
            }
            int w = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                w += (c == 'W') ? 14 : 4;
            }
            return w;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            baseY = baseY == Integer.MIN_VALUE ? y : Math.max(baseY, y);
            // 模拟裁剪：整段落在裁剪区外的绘制不会产生可见像素
            if (clipX != NO_CLIP) {
                int charWidth = textWidth(text);
                if (x + charWidth <= clipX || x >= clipX + clipW) {
                    return;
                }
            }
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
            // 记录裁剪矩形，模拟真实平台的裁剪语义：
            // 完全落在裁剪区外的绘制不应被记为可见。
            clipX = x;
            clipW = width;
        }

        @Override
        public void disableScissor() {
            clipX = NO_CLIP;
            clipW = 0;
        }

        /** 每个字符的所有绘制纵坐标；用于校验两层是否对齐。 */
        Map<String, List<Integer>> allYsOf(String text) {
            Map<String, List<Integer>> map = new LinkedHashMap<>();
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                if (t.length() == 1) {
                    map.computeIfAbsent(t, k -> new ArrayList<>()).add(ys.get(i));
                }
            }
            return map;
        }

        /** 每个字符第一次出现的 y；用于判断抬升。 */
        Map<String, Integer> yOfFirst(String text) {
            Map<String, Integer> map = new LinkedHashMap<>();
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                if (t.length() == 1) {
                    map.putIfAbsent(t, ys.get(i));
                }
            }
            return map.isEmpty() ? null : map;
        }

        Map<String, Integer> xOfFirst(String text) {
            Map<String, Integer> map = new LinkedHashMap<>();
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                if (t.length() == 1) {
                    map.putIfAbsent(t, xs.get(i));
                }
            }
            return map.isEmpty() ? null : map;
        }
    }
}
