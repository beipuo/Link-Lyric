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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间重叠两行的端到端渲染验证。
 *
 * <p>只验证「另一句确实出现在屏幕上」：它是否被丢弃，
 * 从绘制记录里就能看出来，不需要看像素。
 */
class OverlapRenderTest {

    private static LyricLine line(String text, long start, long end) {
        List<LyricWord> words = new ArrayList<>();
        long step = Math.max(1L, (end - start) / text.length());
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(text.substring(i, i + 1),
                    start + step * i, start + step * (i + 1)));
        }
        return new LyricLine(text, "", start, end, words, "");
    }

    private static FloatingLyricRenderer rendererFor(List<LyricLine> lines, long positionMs) {
        return new FloatingLyricRenderer(new Feed(new LyricTimeline(lines), positionMs));
    }

    @Test
    void 重叠的第二句也会被绘制() {
        List<LyricLine> lines = List.of(
                line("主唱句子", 0, 4000),
                line("伴唱进来", 1000, 5000));

        Recorder p = new Recorder();
        assertTrue(rendererFor(lines, 2500L).render(p), "重叠期间应正常出画");

        // indexAt 取「最后开始的那一行」作为主行，所以此刻主行是伴唱，
        // 重叠的主唱句由副行槽承载。两者必须同时出现。
        String all = String.join("", p.drawn());
        assertTrue(all.contains("伴唱进来"), "主行应是最后进来的那句，实际=" + all);
        assertTrue(all.contains("主唱句子"), "重叠的另一句也应绘制，实际=" + all);
    }

    @Test
    void 无重叠时副行仍是下一句() {
        List<LyricLine> lines = List.of(
                line("第一句", 0, 1000),
                line("第二句", 2000, 3000));

        Recorder p = new Recorder();
        assertTrue(rendererFor(lines, 500L).render(p));

        String all = String.join("", p.drawn());
        assertTrue(all.contains("第一句"), "当前句应绘制，实际=" + all);
        assertTrue(all.contains("第二句"), "无重叠时副行应是下一句，实际=" + all);
    }

    /** 位置固定的数据源。 */
    private static final class Feed implements LyricFeed {
        private final LyricTimeline timeline;
        private final long positionMs;

        Feed(LyricTimeline timeline, long positionMs) {
            this.timeline = timeline;
            this.positionMs = positionMs;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                    positionMs, true, 1L, 0L, CapabilitySet.empty());
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

    /** 只记录画过哪些文本，其余能力取默认。 */
    private static final class Recorder implements FloatingLyricRenderer.Painter {
        private final List<String> texts = new ArrayList<>();

        List<String> drawn() {
            return texts;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            texts.add(text);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            texts.add(text);
        }

        @Override
        public int textWidth(String text) {
            return text.length() * 8;
        }

        @Override
        public int screenWidth() {
            return 320;
        }

        @Override
        public int screenHeight() {
            return 240;
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            // 底板不属于本测试的观察对象。
        }
    }
}
