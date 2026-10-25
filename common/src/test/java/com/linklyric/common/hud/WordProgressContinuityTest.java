package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字推进的连续性：字与字之间不应出现速度凹陷。
 *
 * <p>这是「两个字之间衔接一顿」的回归测试。
 *
 * <p>曾把 {@code smoothstep} 套在每个字各自的进度上。smoothstep 在
 * 两端导数为零，于是每个字都是「静止起步—中段猛冲—静止收尾」，
 * 唱到字边界时速度先归零再重新爬升，观感就是一顿一顿。
 * 一条连续的时间线被切成了 N 段各自起停的曲线。
 */
class WordProgressContinuityTest {

    /** 每字 100ms，行长 600ms；字体每字 100px，便于分辨速度。 */
    private static final int CHAR_W = 100;

    @Test
    void 字边界处推进速度不落入零() {
        String text = "一二三四五六";
        LyricLine line = new LyricLine(text, "", 0, 600L,
                List.of(
                        new LyricWord("一", 0, 100),
                        new LyricWord("二", 100, 200),
                        new LyricWord("三", 200, 300),
                        new LyricWord("四", 300, 400),
                        new LyricWord("五", 400, 500),
                        new LyricWord("六", 500, 600)),
                "");

        // 恒速推进的 Feed：位置由构造时的值决定，这里逐次新建以便取不同时刻。
        double rateBefore = rateAt(line, 80L, 100L);
        double rateAfter = rateAt(line, 100L, 120L);
        double rateMid = rateAt(line, 140L, 160L);

        // 匀速时每毫秒推进 1px（100px / 100ms）。
        double expected = CHAR_W / 100.0;
        assertTrue(rateMid > 0.8 * expected,
                "字中段应接近匀速，实际=" + rateMid + " 期望≈" + expected);
        assertTrue(rateBefore > 0.8 * expected,
                "字边界前不应减速，实际=" + rateBefore);
        assertTrue(rateAfter > 0.8 * expected,
                "字边界后不应低速重启，实际=" + rateAfter);
    }

    /** 某区间内已唱宽度的平均推进速度（像素/毫秒）。 */
    private static double rateAt(LyricLine line, long from, long to) {
        float w0 = widthAt(line, from);
        float w1 = widthAt(line, to);
        return (w1 - w0) / (to - from);
    }

    /** 构造一个只在该时刻渲染一帧的渲染器，取已唱宽度。 */
    private static float widthAt(LyricLine line, long positionMs) {
        LyricTimeline timeline = new LyricTimeline(List.of(line));
        FixedWidthPainter painter = new FixedWidthPainter(CHAR_W);
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                new FrozenFeed(timeline, positionMs));
        renderer.render(painter);
        return renderer.currentSungWidth();
    }

    /**
     * 位置恒定的数据源。
     *
     * <p>关掉外推（第二个快照与第一个相同），使已唱宽度
     * 直接对应给定时刻，便于精确比较速度。
     */
    private static final class FrozenFeed implements LyricFeed {

        private final LyricTimeline timeline;
        private final long positionMs;

        FrozenFeed(LyricTimeline timeline, long positionMs) {
            this.timeline = timeline;
            this.positionMs = positionMs;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test", TrackIdentity.of("t", "a", 0L),
                    positionMs, false, 1L, 0L, CapabilitySet.empty());
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

    /** 每个字固定宽度，便于把像素增量换算回时间比例。 */
    private static final class FixedWidthPainter
            implements FloatingLyricRenderer.Painter {

        private final int charW;

        FixedWidthPainter(int charW) {
            this.charW = charW;
        }

        @Override
        public int screenWidth() {
            return 800;
        }

        @Override
        public int screenHeight() {
            return 600;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * charW;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }
    }
}
