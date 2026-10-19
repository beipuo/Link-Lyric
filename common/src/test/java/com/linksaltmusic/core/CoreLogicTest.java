package com.linksaltmusic.core;

import com.linksaltmusic.core.lyric.LyricLine;
import com.linksaltmusic.core.lyric.LyricTimeline;
import com.linksaltmusic.core.lyric.LyricWord;
import com.linksaltmusic.core.style.LyricStyle;
import com.linksaltmusic.core.sync.PlaybackClock;
import com.linksaltmusic.core.ui.ScrollModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLogicTest {

    private static LyricLine line(String text, long start, long end) {
        return new LyricLine(text, "", start, end, List.of(), "");
    }

    @Test
    @DisplayName("行级歌词会自动补一个覆盖全行的字级单元")
    void lineLevelFallsBackToSingleWord() {
        LyricLine l = line("你好", 1000, 2000);
        assertEquals(1, l.words().size());
        assertEquals("你好", l.words().get(0).text());
        assertEquals(1000, l.words().get(0).startMs());
    }

    @Test
    @DisplayName("indexAt 覆盖未开始、首行、中间、末行四种边界")
    void indexAtBoundaries() {
        LyricTimeline timeline = new LyricTimeline(List.of(
                line("a", 1000, 2000),
                line("b", 2000, 3000),
                line("c", 5000, 6000)));

        assertEquals(-1, timeline.indexAt(999));
        assertEquals(0, timeline.indexAt(1000));
        assertEquals(0, timeline.indexAt(1999));
        assertEquals(1, timeline.indexAt(2000));
        // 行间空隙仍返回上一行，由渲染层决定淡出
        assertEquals(1, timeline.indexAt(4000));
        assertEquals(2, timeline.indexAt(5500));
        assertEquals(2, timeline.indexAt(99999));
    }

    @Test
    @DisplayName("空时间轴的查询结果稳定")
    void emptyTimeline() {
        LyricTimeline timeline = LyricTimeline.empty();
        assertTrue(timeline.isEmpty());
        assertEquals(-1, timeline.indexAt(0));
        assertEquals(null, timeline.lineAt(0));
    }

    @Test
    @DisplayName("逐字进度按时间线性推进")
    void wordProgress() {
        LyricWord word = new LyricWord("字", 1000, 2000);
        assertEquals(0f, word.progressAt(1000));
        assertEquals(0.5f, word.progressAt(1500), 0.001f);
        assertEquals(1f, word.progressAt(2000));
        assertEquals(0f, word.progressAt(0));
    }

    @Test
    @DisplayName("向前短距离滚动使用缓动，且最终收敛到目标")
    void smoothForwardScroll() {
        ScrollModel model = new ScrollModel();
        model.snapTo(0, 0L);
        model.moveTo(1, 0L);

        // 缓动过程中应位于起点与终点之间
        float midway = model.offsetAt(160_000_000L);
        assertTrue(midway > 0f && midway < 1f, "应处于缓动中，实际=" + midway);
        // 过渡结束后精确落位
        assertEquals(1f, model.offsetAt(1_000_000_000L), 0.0001f);
        assertEquals(1, model.currentIndex());
    }

    @Test
    @DisplayName("向后回退与大幅度前进都直接瞬移")
    void backwardAndLargeJumpSnap() {
        ScrollModel model = new ScrollModel();
        model.snapTo(5, 0L);

        model.moveTo(4, 0L);
        assertFalse(model.isAnimating(0L));
        assertEquals(4f, model.offsetAt(0L), 0.0001f);

        model.moveTo(20, 0L);
        assertFalse(model.isAnimating(0L));
        assertEquals(20f, model.offsetAt(0L), 0.0001f);
    }

    @Test
    @DisplayName("暂停冻结位置，恢复后继续推进")
    void clockPauseAndResume() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        clock.anchor(500, true);
        Thread.sleep(30L);

        clock.pause();
        long frozen = clock.positionMs();
        assertTrue(frozen >= 500, "暂停后位置应不小于锚点");

        Thread.sleep(30L);
        assertEquals(frozen, clock.positionMs(), 5L);
        assertFalse(clock.isPlaying());

        clock.resume();
        Thread.sleep(30L);
        assertTrue(clock.positionMs() > frozen);
    }

    @Test
    @DisplayName("样式预设满足正交维度约束")
    void stylePresets() {
        assertEquals(LyricStyle.Anchor.BOTTOM_CENTER, LyricStyle.defaultStyle().anchor());
        assertEquals(LyricStyle.Chrome.PROGRESS_BAR, LyricStyle.HOTBAR.chrome());
        // 物品栏样式刻意不做逐字高亮，避免在原生 UI 上叠 scissor
        assertFalse(LyricStyle.HOTBAR.perWordHighlight());
        assertEquals("island", LyricStyle.ISLAND.id());
    }
}
