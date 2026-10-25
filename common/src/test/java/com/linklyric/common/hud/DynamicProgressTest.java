package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时钟外推与像素裁剪的验证。
 *
 * <p>这两个是「逐字歌词没有动态染色」的直接原因：
 * 用轮询位置驱动会让高亮每 200ms 跳一次；不支持裁剪则无法表达
 * 正在唱的那个字的中间状态。
 */
class DynamicProgressTest {

    private static LyricLine lineWithWords(String text, long start, long end, LyricWord... words) {
        return new LyricLine(text, "", start, end, List.of(words), "");
    }

    /**
     * 数据源位置固定不变时，渲染位置仍应随时间前进。
     *
     * <p>这是外推是否生效的判定：若仍用轮询值，两次渲染的位置会完全相同，
     * 高亮也就停在同一处不动。
     */
    @Test
    void 位置固定时渲染仍随时间推进() throws Exception {
        LyricLine line = lineWithWords("一二三四五", 0, 5000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000),
                new LyricWord("三", 2000, 3000),
                new LyricWord("四", 3000, 4000),
                new LyricWord("五", 4000, 5000));
        LyricTimeline timeline = new LyricTimeline(List.of(line));

        // 数据源永远报告同一位置（首个单元中段），且处于播放中。
        // 取中段而非 0：位置 0 恰好是行起点，剩余时间全在本单元内，不适合观测增长。
        MutableFeed feed = new MutableFeed(timeline, 500L, true);
        ScissorPainter painter = new ScissorPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);

        renderer.render(painter);
        float first = renderer.currentSungWidth();

        Thread.sleep(200L);
        painter.reset();
        renderer.render(painter);
        float second = renderer.currentSungWidth();

        assertTrue(second > first,
                "位置固定但播放在进行时，已唱宽度必须增长。first=" + first + " second=" + second);
    }

    /**
     * 上报周期为 1 秒时，整个周期内高亮都必须持续推进，不能中途停住。
     *
     * <p>这是「一秒一跳、中间停顿」现象的回归测试。宿主每秒才更新一次位置，
     * 若外推余量小于该周期，高亮走完余量就会停在原地等下一条上报。
     */
    @Test
    void 上报间隔一秒时高亮全程连续推进() throws Exception {
        // 两个约束：
        // 1) 必须多单元，单单元行走退化分支（全亮/全灭），本就无法插值；
        // 2) 观测窗口内的像素增量要远大于 1，否则逐帧取整会把连续推进
        //    压成平台，被误判成停顿。20 字 × 100ms = 2 秒的行，
        //    1.2 秒可推进约六成行宽，增量足够分辨。
        String wide = "一二三四五六七八九十一二三四五六七八九十";
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < wide.length(); i++) {
            words.add(new LyricWord(String.valueOf(wide.charAt(i)), i * 100L, (i + 1) * 100L));
        }
        LyricLine line = new LyricLine(wide, "", 0, wide.length() * 100L, List.copyOf(words), "");
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 0L, true);
        ScissorPainter painter = new ScissorPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);

        // 位置恒为 0（模拟上报未更新），只观测推进过程的前半段：
        // 一旦已经唱完，后续帧必然“停滞”，那是正常终态，不能算作停顿。
        // 观测窗口按「行宽是否已推进」收敛，而不是固定帧数。
        //
        // 固定 24 帧 × 50ms = 1.2 秒，在本机空闲时够用，但全套测试并行跑时
        // 单帧可能被拖到几百毫秒，一帧都没推进就被判成「应有推进」失败。
        // 这里改为：要么推进到行宽过半，要么最多等 8 秒，二者取先到者。
        int stalls = 0;
        int previous = -1;
        int lineWidth = painter.textWidth(wide);
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(50L);
            painter.reset();
            renderer.render(painter);
            int width = Math.round(renderer.currentSungWidth());
            if (previous >= 0 && width <= previous && previous < lineWidth) {
                stalls++;
            }
            previous = width;
            if (previous > lineWidth / 2) {
                break;
            }
        }

        System.out.println("末次宽度=" + previous + " 行宽=" + painter.textWidth(wide)
                + " 停滞帧数=" + stalls);

        assertTrue(previous > 0, "应有推进");

        // 允许少量抖动，但不允许长时间停住
        assertTrue(stalls <= 3, "推进中途不应反复停住，实际停滞帧数=" + stalls);
    }

    /** 暂停后外推必须停止，否则高亮会一直往前跑。 */
    @Test
    void 暂停后不再推进() throws Exception {
        LyricLine line = lineWithWords("一二三四五", 0, 5000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000),
                new LyricWord("三", 2000, 3000),
                new LyricWord("四", 3000, 4000),
                new LyricWord("五", 4000, 5000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 500L, false);
        ScissorPainter painter = new ScissorPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);

        renderer.render(painter);
        float first = renderer.currentSungWidth();
        Thread.sleep(120L);
        painter.reset();
        renderer.render(painter);

        assertEquals(first, renderer.currentSungWidth(),
                "暂停时已唱宽度不应变化");
    }

    /** 支持裁剪时，正在唱的字可以只覆盖一部分宽度。 */
    @Test
    void 支持裁剪时按像素覆盖() {
        // 单字 1000ms，位置取 1/4 处，期望覆盖约 25% 宽度而非整字
        LyricLine line = lineWithWords("一二", 0, 2000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 250L, false);
        ScissorPainter painter = new ScissorPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);
        renderer.render(painter);

        int width = Math.round(renderer.currentSungWidth());
        int wordWidth = painter.textWidth("一");

        assertTrue(width > 0, "已开始演唱应有正宽度");
        assertTrue(width < wordWidth,
                "位置在字中间时，覆盖宽度应小于整字宽度。width=" + width + " word=" + wordWidth);
    }

    /** 不支持裁剪时必须降级为整字覆盖，且不得绘制半截。 */
    @Test
    void 不支持裁剪时降级为整字() {
        LyricLine line = lineWithWords("一二三", 0, 3000,
                new LyricWord("一", 0, 1000),
                new LyricWord("二", 1000, 2000),
                new LyricWord("三", 2000, 3000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 1500L, false);
        NoScissorPainter painter = new NoScissorPainter();
        new FloatingLyricRenderer(feed).render(painter);

        // 第二个字正在唱：只应画出完整的第一字
        assertTrue(painter.hasColor(FloatingLyricRenderer.COLOR_SUNG), "应有已唱内容");
        assertFalse(painter.sawScissor, "不应尝试调用裁剪");
    }

    /**
     * 默认不绘制底板。
     *
     * <p>底板只在拖动模式出现，作为可拖区域的提示；
     * 平时留着会压住画面，也违背「不打扰」的默认。
     */
    @Test
    void 默认不绘制底板() {
        LyricLine line = lineWithWords("字", 0, 1000, new LyricWord("字", 0, 1000));
        MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)), 500L, false);
        ScissorPainter painter = new ScissorPainter();
        new FloatingLyricRenderer(feed).render(painter);

        // 按底板色单独识别，避免把其它矩形填充算进来。
        assertEquals(0, painter.backdropFills, "默认不应绘制底板");
    }

    // ---------------- 替身 ----------------

    private static final class MutableFeed implements LyricFeed {
        private final LyricTimeline timeline;
        private final long positionMs;
        private final boolean playing;

        MutableFeed(LyricTimeline timeline, long positionMs, boolean playing) {
            this.timeline = timeline;
            this.positionMs = positionMs;
            this.playing = playing;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test", TrackIdentity.of("t", "a", 0L),
                    positionMs, playing, 1L, 0L, CapabilitySet.empty());
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

    private static class ScissorPainter implements FloatingLyricRenderer.Painter {
        private static final int CHAR_WIDTH = 10;
        final List<Integer> scissorWidths = new ArrayList<>();

        void reset() {
            scissorWidths.clear();
            textOriginX = Integer.MIN_VALUE;
            sungClipWidth = 0;
            solidAlpha = 0;
            currentClipWidth = 400;
        }
        int fills;
        /** 底板绘制次数。底板是半透明黑且宽度覆盖整行区域。 */
        int backdropFills;
        boolean sawScissor;

        /**
         * 已唱宽度。
         *
         * <p>已唱色那一层总被裁剪到「已唱段」，所以记录到的
         * 已唱色裁剪宽度就是已唱进度；没有裁剪到已唱色说明整行都唱完了。
         */
        int sungWidth(int lineWidth) {
            // 取「已唱色里最宽的裁剪区」即实心段宽度，
            // 也就是真正的已唱进度；羽化段裁剪区更窄，不影响结果。
            return sungClipWidth > 0 ? Math.min(sungClipWidth, lineWidth) : 0;
        }

        int textOriginX = Integer.MIN_VALUE;
        /** 已唱色那层的裁剪宽度。 */
        int sungClipWidth;
        /** 本帧已唱色的最高 alpha，用于识别实心段。 */
        int solidAlpha;

        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (textOriginX == Integer.MIN_VALUE) {
                textOriginX = x;
            }
            // 已唱色那层的绘制宽度就是当前已唱进度
            if ((color & 0x00FFFFFF)
                    == (FloatingLyricRenderer.COLOR_SUNG & 0x00FFFFFF)) {
                // 实心段是 alpha 最高的那一段，取它的裁剪宽度。
                // 不能简单取最宽裁剪区：整行唱完时没有实心裁剪，
                // 羽化段会被误认成已唱进度。
                int alpha = (color >>> 24) & 0xFF;
                if (alpha > solidAlpha) {
                    solidAlpha = alpha;
                    sungClipWidth = currentClipWidth;
                } else if (alpha == solidAlpha && currentClipWidth > sungClipWidth) {
                    sungClipWidth = currentClipWidth;
                }
            }
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            fills++;
            if (color == FloatingLyricRenderer.COLOR_BACKDROP) {
                backdropFills++;
            }
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        /** 当前裁剪区宽度；未裁剪时视为整屏。 */
        int currentClipWidth = 400;

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            sawScissor = true;
            scissorWidths.add(width);
            currentClipWidth = width;
        }

        @Override
        public void disableScissor() {
            currentClipWidth = 400;
        }
    }

    private static final class NoScissorPainter implements FloatingLyricRenderer.Painter {
        private static final int CHAR_WIDTH = 10;
        final List<Integer> colors = new ArrayList<>();
        boolean sawScissor;

        /** 只比较 RGB：行切换期间 alpha 会变化，颜色本身不变。 */
        boolean hasColor(int color) {
            for (int c : colors) {
                if ((c & 0x00FFFFFF) == (color & 0x00FFFFFF)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            colors.add(color);
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return false;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            sawScissor = true;
        }
    }
}
