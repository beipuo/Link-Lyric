package com.linklyric.core;

import com.linklyric.core.capability.Capability;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricCache;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.session.LyricSession;
import com.linklyric.core.source.PlaybackSource;
import com.linklyric.core.source.SourceProfiles;
import com.linklyric.core.style.LyricStyle;
import com.linklyric.core.sync.PlaybackClock;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import com.linklyric.core.ui.ScrollModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLogicTest {

    private static LyricLine line(String text, long start, long end) {
        return new LyricLine(text, "", start, end, List.of(), "");
    }

    // ---------------- 歌词时间轴 ----------------

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

    // ---------------- 滚动状态机 ----------------

    @Test
    @DisplayName("向前短距离滚动使用缓动，且最终收敛到目标")
    void smoothForwardScroll() {
        ScrollModel model = new ScrollModel();
        model.snapTo(0, 0L);
        model.moveTo(1, 0L);

        float midway = model.offsetAt(160_000_000L);
        assertTrue(midway > 0f && midway < 1f, "应处于缓动中，实际=" + midway);
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

    // ---------------- 时钟 ----------------

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

    // ---------------- 曲目身份匹配 ----------------

    @Test
    @DisplayName("时长差超过容差即判定为不同版本")
    void durationDistinguishesVersions() {
        TrackIdentity studio = TrackIdentity.of("Song", "Artist", 213_000L);
        TrackIdentity live = TrackIdentity.of("Song", "Artist", 267_000L);

        assertTrue(studio.matches(TrackIdentity.of("song", "artist", 214_000L)));
        assertFalse(studio.matches(live));
        // 任一方缺时长则放宽该项，避免数据源不报时长时完全无法匹配
        assertTrue(studio.matches(TrackIdentity.of("Song", "", 0L)));
    }

    @Test
    @DisplayName("缓存键忽略大小写但不含专辑")
    void cacheKeyIgnoresCaseAndAlbum() {
        TrackIdentity a = new TrackIdentity("Song", "Artist", "AlbumA", 213_000L);
        TrackIdentity b = new TrackIdentity("song", "artist", "AlbumB", 213_000L);
        assertEquals(a.cacheKey(), b.cacheKey());
    }

    /**
     * 时长落在容差内的两个身份必须共用一个缓存键。
     *
     * <p>{@code matches} 用容差比较时长，若 {@code cacheKey} 把原始毫秒直接
     * 拼进键，同一首歌在两个数据源上报 243000 与 243480 会得到两个键，
     * 缓存永远不命中，每次换曲都要重新查询一遍歌词。
     */
    @Test
    void cacheKeyBucketsDurationWithinTolerance() {
        TrackIdentity a = TrackIdentity.of("Song", "Artist", 243_000L);
        TrackIdentity b = TrackIdentity.of("Song", "Artist", 243_480L);
        assertTrue(a.matches(b), "两秒内应判定为同一录音");
        assertEquals(a.cacheKey(), b.cacheKey(), "同一录音必须共用缓存键");
    }

    // ---------------- 能力探测 ----------------

    @Test
    @DisplayName("SMTC 不声明队列与按索引播放，Salt Player 声明队列")
    void sourceProfilesReflectRealDifferences() {
        assertTrue(SourceProfiles.WINDOWS_SMTC.has(Capability.PLAYBACK_POSITION));
        assertFalse(SourceProfiles.WINDOWS_SMTC.has(Capability.QUEUE));
        assertFalse(SourceProfiles.WINDOWS_SMTC.has(Capability.WORD_LEVEL_LYRIC));

        assertTrue(SourceProfiles.SALT_PLAYER.hasAll(
                Capability.QUEUE, Capability.WORD_LEVEL_LYRIC));
        // 按索引播放尚未在 WorkshopApi 上验证，刻意不声明
        assertFalse(SourceProfiles.SALT_PLAYER.has(Capability.PLAY_BY_INDEX));

        assertTrue(SourceProfiles.MPD.hasAll(
                Capability.QUEUE, Capability.PLAY_BY_INDEX));
    }

    @Test
    @DisplayName("hasAll 是合取，hasAny 是析取")
    void capabilitySetSemantics() {
        CapabilitySet set = CapabilitySet.of(Capability.QUEUE, Capability.SEEK);
        assertTrue(set.hasAll(Capability.QUEUE, Capability.SEEK));
        assertFalse(set.hasAll(Capability.QUEUE, Capability.PLAY_BY_INDEX));
        assertTrue(set.hasAny(Capability.PLAY_BY_INDEX, Capability.SEEK));
        assertFalse(set.hasAny(Capability.ARTWORK));
        assertEquals(2, set.size());
    }

    // ---------------- 会话编排 ----------------

    /** 可编程的假数据源，用于驱动会话状态机。 */
    private static final class FakeSource implements PlaybackSource {
        PlayerSnapshot current;
        FakeSource(CapabilitySet caps) {
            current = new PlayerSnapshot("fake", TrackIdentity.of("", "", 0L),
                    0L, false, 0L, 0L, caps);
        }
        void emit(TrackIdentity track, long positionMs, boolean playing, long seq) {
            current = new PlayerSnapshot("fake", track, positionMs, playing, seq, 0L,
                    current.capabilities());
        }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public CapabilitySet capabilities() { return current.capabilities(); }
        @Override public PlayerSnapshot pollSnapshot() { return current; }
        @Override public boolean isAlive() { return true; }
    }

    @Test
    @DisplayName("空快照使视图整体隐藏，不残留上一首歌词")
    void emptySnapshotHidesEverything() {
        FakeSource source = new FakeSource(SourceProfiles.SALT_PLAYER);
        LyricSession session = new LyricSession(source, null, new LyricCache());

        source.emit(TrackIdentity.of("A", "B", 1000L), 0L, true, 1L);
        session.tick();
        session.acceptLyric(TrackIdentity.of("A", "B", 1000L),
                new LyricTimeline(List.of(line("hello", 0, 1000))));
        session.tick();
        assertTrue(session.view().hasLyric());

        // 数据源报告无会话
        source.emit(TrackIdentity.of("", "", 0L), 0L, false, 2L);
        session.tick();
        assertFalse(session.view().hasLyric());
        assertEquals(-1, session.view().lineIndex());
    }

    @Test
    @DisplayName("切歌后迟到的歌词必须被丢弃，不能串到新歌上")
    void lateLyricForPreviousTrackIsRejected() {
        FakeSource source = new FakeSource(SourceProfiles.SALT_PLAYER);
        LyricSession session = new LyricSession(source, null, new LyricCache());

        TrackIdentity first = TrackIdentity.of("First", "X", 1000L);
        source.emit(first, 0L, true, 1L);
        session.tick();
        assertTrue(session.isLyricPending());

        // 用户切歌
        TrackIdentity second = TrackIdentity.of("Second", "X", 1000L);
        source.emit(second, 0L, true, 2L);
        session.tick();

        // 上一首的结果此时才回来
        session.acceptLyric(first, new LyricTimeline(List.of(line("old", 0, 1000))));
        session.tick();

        assertEquals("", session.view().text(), "旧歌词不得显示");
        assertEquals(second, session.currentTrack());
    }

    @Test
    @DisplayName("换曲触发回调，供上层发起异步歌词查询")
    void trackChangeFiresListener() {
        FakeSource source = new FakeSource(SourceProfiles.SALT_PLAYER);
        LyricSession session = new LyricSession(source, null, new LyricCache());

        var seen = new java.util.ArrayList<TrackIdentity>();
        session.setTrackChangeListener(seen::add);

        source.emit(TrackIdentity.of("A", "B", 1000L), 0L, true, 1L);
        session.tick();
        source.emit(TrackIdentity.of("A", "B", 1000L), 500L, true, 2L);
        session.tick();
        source.emit(TrackIdentity.of("C", "D", 2000L), 0L, true, 3L);
        session.tick();

        assertEquals(2, seen.size(), "同一首不重复触发，换曲才触发");
        assertEquals("C", seen.get(1).title());
    }

    @Test
    @DisplayName("无进度能力时仍能跟随播放态，但接受漂移")
    void sourceWithoutPositionStillWorks() {
        CapabilitySet noPosition = CapabilitySet.of(
                Capability.TRACK_METADATA, Capability.TRANSPORT);
        FakeSource source = new FakeSource(noPosition);
        LyricSession session = new LyricSession(source, null, new LyricCache());

        source.emit(TrackIdentity.of("A", "B", 1000L), 0L, true, 1L);
        session.tick();
        session.acceptLyric(TrackIdentity.of("A", "B", 1000L),
                new LyricTimeline(List.of(line("x", 0, 60_000))));
        session.tick();

        assertTrue(session.view().playing());
        // 位置由墙钟推进，不因数据源不报进度而完全冻结
        assertTrue(session.view().positionMs() >= 0L);
    }

    @Test
    @DisplayName("乱序到达的旧快照被丢弃，指针不回跳")
    void outOfOrderSnapshotIgnored() {
        FakeSource source = new FakeSource(SourceProfiles.SALT_PLAYER);
        LyricSession session = new LyricSession(source, null, new LyricCache());

        source.emit(TrackIdentity.of("A", "B", 1000L), 10_000L, true, 10L);
        session.tick();
        long before = session.view().positionMs();

        source.emit(TrackIdentity.of("A", "B", 1000L), 2_000L, true, 5L);
        session.tick();

        assertTrue(session.view().positionMs() >= before,
                "旧序号不得覆盖已推进的位置");
    }

    // ---------------- 样式 ----------------

    @Test
    @DisplayName("样式预设满足正交维度约束")
    void stylePresets() {
        assertEquals(LyricStyle.Anchor.BOTTOM_CENTER, LyricStyle.defaultStyle().anchor());
        assertEquals(LyricStyle.Chrome.PROGRESS_BAR, LyricStyle.HOTBAR.chrome());
        assertFalse(LyricStyle.HOTBAR.perWordHighlight());
        assertEquals("island", LyricStyle.ISLAND.id());
        assertNotEquals(LyricStyle.ISLAND.anchor(), LyricStyle.SIDEBAR.anchor());
    }
}
