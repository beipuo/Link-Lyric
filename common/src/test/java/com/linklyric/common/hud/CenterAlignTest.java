package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多行歌词的**水平**居中。
 *
 * <p>副行按 0.8 倍字号绘制，是整串一次画出；主行是逐字绘制。
 * 两者若按不同基准算左边界，中心就会错开，
 * 看起来是「多行歌词不居中」，但纵向其实没问题。
 */
class CenterAlignTest {

    private static final int SCREEN_W = 480;
    private static final String[] TEXTS = {
            "春江潮水连海平", "海上明月共潮生", "滟滟随波千万里"};

    @Test
    void 副行中心与主行一致() {
        RecordingPainter painter = new RecordingPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(
                feed(timeline()));
        renderer.setStyle(LyricStyleConfig.DEFAULT.withLineCount(3));

        for (int i = 0; i < 5; i++) {
            painter.reset();
            renderer.render(painter);
        }

        // 主行：逐字绘制，取首字起点到末字终点的中心。
        int mainLeft = Integer.MAX_VALUE;
        int mainRight = Integer.MIN_VALUE;
        for (var e : painter.singleChars) {
            mainLeft = Math.min(mainLeft, e.x);
            mainRight = Math.max(mainRight, e.x + e.width);
        }
        assertTrue(mainLeft != Integer.MAX_VALUE, "应采到主行逐字绘制");
        int mainCenter = (mainLeft + mainRight) / 2;

        // 副行：整串绘制，中心由记录直接得出。
        assertTrue(painter.wholeCenters.size() >= 2,
                "应采到两条副行，实际=" + painter.wholeCenters);

        for (int center : painter.wholeCenters) {
            assertTrue(Math.abs(center - mainCenter) <= 1,
                    "副行中心应与主行一致：主=" + mainCenter
                            + " 副=" + center);
        }
    }

    private static LyricTimeline timeline() {
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < TEXTS.length; i++) {
            lines.add(line(TEXTS[i], i * 5_000L, (i + 1) * 5_000L));
        }
        return new LyricTimeline(lines);
    }

    private static LyricFeed feed(LyricTimeline timeline) {
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("test",
                        TrackIdentity.of("曲目", "", 0L),
                        1_000L, true, 0L, 0L, CapabilitySet.empty());
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

    private static LyricLine line(String text, long start, long end) {
        List<LyricWord> words = new ArrayList<>();
        long step = Math.max(1L, (end - start) / text.length());
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    start + step * i, start + step * (i + 1)));
        }
        return new LyricLine(text, "", start, end, List.copyOf(words), "");
    }

    private record Entry(int x, int width) {
    }

    private static final class RecordingPainter
            implements FloatingLyricRenderer.Painter {

        final List<Entry> singleChars = new ArrayList<>();
        final List<Integer> wholeCenters = new ArrayList<>();

        void reset() {
            singleChars.clear();
            wholeCenters.clear();
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (text.length() == 1) {
                singleChars.add(new Entry(x, 9));
            } else {
                wholeCenters.add(x + text.length() * 9 / 2);
            }
        }

        @Override
        public void drawTextScaled(String text, int x, int y, float scale,
                                   int color, boolean shadow) {
            if (text == null || text.isEmpty()) {
                return;
            }
            wholeCenters.add(x + Math.round(text.length() * 9 * scale) / 2);
        }

        @Override
        public int screenWidth() {
            return SCREEN_W;
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
