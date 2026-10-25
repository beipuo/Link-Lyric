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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进入世界时音乐已在播放，首帧必须正常。
 *
 * <h2>首帧的两个独立缺陷</h2>
 *
 * <p><b>一、首帧误触发入场上移动画。</b>
 * {@code LineTransition} 的 {@code fromIndex} 初值为 -1，
 * 而首帧的行索引总与之不同，于是「索引变了」被当成换行，
 * 过渡计时被归零。整块歌词因此从下方一个行距滑上来。
 * 此时染色已按当前播放位置就位，两者叠加就是
 * 「字在往上滑、高亮却已在句中终态」的动画错乱。
 *
 * <p><b>二、参考字宽用平均值代替。</b>
 * {@code lineWidth / charCount} 是平均每格宽度，不是字号。
 * 中文上巧合相等，英文与空格多的句子会偏小，
 * 把抬升幅度与过渡带一起压扁，逐字动画退化成整字跳动。
 *
 * <p>首帧把已唱宽度直接定位到当前播放位置是**正确**行为：
 * 单帧调用必须得到精确结果，否则「一帧即真实」的契约就破了。
 */
class FirstLoadTest {

    /** 参考字宽取行内最大字宽，不受平均值拖低。 */
    @Test
    void 参考字宽取最大字宽() {
        int[] widths = {9, 9, 2, 2, 2, 3, 2, 2};
        assertEquals(9f, LyricProgress.referenceSize(widths),
                "应取最宽的 9，而不是平均的 3.9");
    }

    /** 无法取值时返回 0，由调用方回退。 */
    @Test
    void 无法取值时返回零() {
        assertEquals(0f, LyricProgress.referenceSize(new int[0]), "空数组应返回 0");
        assertEquals(0f, LyricProgress.referenceSize(100, 0), "字数为 0 应返回 0");
    }

    /** 已唱字的抬升应大于未唱字。 */
    @Test
    void 已唱字抬升更大() {
        int[] widths = {9, 9, 9, 9, 9, 9};
        int[] starts = {0, 9, 18, 27, 36, 45};
        float size = LyricProgress.referenceSize(widths);
        float[] lifts = LyricProgress.cosineLifts(starts, widths, 27f, size);
        assertTrue(lifts != null, "应得到抬升表");
        assertTrue(lifts[0] > lifts[lifts.length - 1],
                "已唱字的抬升应大于未唱字");
    }

    /**
     * 首次出画时不做入场上移动画。
     *
     * <p>这是「进入世界时逐字动画异常」的直接断言：
     * 首帧 elapsed 被归零会让整块歌词从下方一个行距滑上来，
     * 而染色已在句中终态，两者叠加就是动画错乱。
     */
    @Test
    void 首帧不做入场上移动画() {
        LineTransition transition = new LineTransition();
        // 第一次 update 是「首次出画」，不是换行
        transition.update(0, 0L, 700L);
        assertEquals(0, transition.blockShift(700L),
                "首次出画应已就位，不得把整块歌词从下方滑上来");
        // 紧接一次真正的换行，过渡必须照常启动
        transition.update(1, 0L, 700L);
        assertEquals(LyricGeometry.LINE_STEP, transition.blockShift(700L),
                "换行仍应上移一整行");
    }

    /** 句中进入世界时，首帧高亮直接落在当前位置，不做追赶。 */
    @Test
    void 句中进入直接就位() {
        Probe p = new Probe(0.75f);
        p.renderOnce();
        float first = p.renderer.currentSungWidth();
        assertTrue(Math.abs(first - p.targetWidth()) < 1f,
                "首帧应直接落在当前位置：first=" + first
                        + " target=" + p.targetWidth());
    }

    /** 换行仍然直接定位，不重放整句。 */
    @Test
    void 换行不重放整句() throws Exception {
        String[] texts = {"一二三四五六七八九十", "甲乙丙丁戊己庚辛壬癸"};
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            long start = i * 2000L;
            List<LyricWord> ws = new ArrayList<>();
            for (int k = 0; k < texts[i].length(); k++) {
                ws.add(new LyricWord(String.valueOf(texts[i].charAt(k)),
                        start + k * 200L, start + (k + 1) * 200L));
            }
            lines.add(new LyricLine(texts[i], "", start, start + 2000L, ws, ""));
        }
        MutableFeed feed = new MutableFeed(new LyricTimeline(lines), 2600L);
        Rec rec = new Rec();
        FloatingLyricRenderer r = new FloatingLyricRenderer(feed);
        for (int i = 0; i < 4; i++) {
            r.render(rec);
            Thread.sleep(16L);
        }
        feed.at = 3600L;
        for (int i = 0; i < 4; i++) {
            r.render(rec);
            Thread.sleep(16L);
        }
        float after = r.currentSungWidth();
        assertTrue(after > 40f,
                "换行后不应从行首重放：after=" + after);
    }

    private static final class Probe {
        final Rec rec = new Rec();
        final FloatingLyricRenderer renderer;
        final String text = "一二三四五六七八九十";
        final float ratio;

        Probe(float ratio) {
            this.ratio = ratio;
            List<LyricWord> ws = new ArrayList<>();
            for (int k = 0; k < text.length(); k++) {
                ws.add(new LyricWord(String.valueOf(text.charAt(k)),
                        k * 100L, (k + 1) * 100L));
            }
            LyricLine line = new LyricLine(text, "", 0L, 1000L, ws, "");
            MutableFeed feed = new MutableFeed(new LyricTimeline(List.of(line)),
                    (long) (1000 * ratio));
            renderer = new FloatingLyricRenderer(feed);
        }

        void renderOnce() {
            renderer.render(rec);
        }

        float targetWidth() {
            return text.length() * 9 * ratio;
        }
    }

    private static final class MutableFeed implements LyricFeed {
        final LyricTimeline tl;
        long at;

        MutableFeed(LyricTimeline tl, long at) {
            this.tl = tl;
            this.at = at;
        }

        @Override public boolean isAlive() { return true; }
        @Override public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("t", TrackIdentity.of("t", "a", 0L),
                    at, true, 1L, 0L, CapabilitySet.empty());
        }
        @Override public LyricTimeline lyric() { return tl; }
        @Override public long lyricSignature() { return 1L; }
    }

    private static final class Rec implements FloatingLyricRenderer.Painter {
        final List<String> calls = new ArrayList<>();
        int curX, curW;

        @Override public int screenWidth() { return 854; }
        @Override public int screenHeight() { return 480; }
        @Override public int textWidth(String t) {
            return t == null ? 0 : t.length() * 9;
        }
        @Override public void fillRect(int x, int y, int w, int h, int c) { }
        @Override public void enableScissor(int x, int y, int w, int h) {
            curX = x;
            curW = w;
        }
        @Override public void disableScissor() { curX = 0; curW = 0; }
        @Override public boolean supportsScissor() { return true; }
        @Override public void drawText(String t, int x, int y, int c) {
            calls.add(t);
        }
        @Override public void drawText(String t, int x, int y, int c, boolean s) {
            calls.add(t);
        }
        @Override public void drawGlyphGradient(String g, float x, int y, float l,
                com.linklyric.common.style.GlyphColors c, boolean s) {
            calls.add(g);
        }
        @Override public void drawGlyphSpans(String g, float x, int y, float l,
                com.linklyric.common.style.GlyphSpan[] sp, int ox, boolean s) {
            calls.add(g);
        }
    }
}