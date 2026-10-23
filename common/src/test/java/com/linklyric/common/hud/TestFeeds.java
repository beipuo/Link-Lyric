package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的数据源替身。
 *
 * <p>集中在一处，避免每个测试各自复制一份时间轴构造代码；
 * 那些复制品很容易在某一处被改坏而不自知。
 */
final class TestFeeds {

    private TestFeeds() {
    }

    /** 一行四字、每字 1 秒，位置固定在中段。 */
    static LyricTimeline timeline() {
        String text = "一二三四";
        long step = 1000L;
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    step * i, step * (i + 1)));
        }
        return new LyricTimeline(List.of(
                new LyricLine(text, "", 0, step * text.length(),
                        List.copyOf(words), "")));
    }

    static PlayerSnapshot snapshot(long positionMs) {
        return new PlayerSnapshot("test",
                TrackIdentity.of("曲目", "", 0L),
                positionMs, true, 1L, 0L,
                CapabilitySet.empty());
    }

    /** 最简单的一份：恒定中段位置。 */
    static final class Simple implements LyricFeed {
        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return snapshot(500L);
        }

        @Override
        public LyricTimeline lyric() {
            return timeline();
        }

        @Override
        public long lyricSignature() {
            return 1L;
        }
    }

    /** 可切换启用状态的一份，用于验证数据源开关。 */
    static final class Toggleable implements LyricFeed {
        private boolean enabled = true;

        @Override
        public boolean isAlive() {
            return enabled;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return snapshot(500L);
        }

        @Override
        public LyricTimeline lyric() {
            return timeline();
        }

        @Override
        public long lyricSignature() {
            return 1L;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean value) {
            this.enabled = value;
        }
    }
}
