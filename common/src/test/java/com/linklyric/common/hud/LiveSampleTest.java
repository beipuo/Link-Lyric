package com.linklyric.common.hud;

import com.linklyric.common.bridge.SnapshotParser;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Link MC 插件的**真实响应**做端到端验证。
 *
 * <p>{@code live-sample.json} 直接抓自 {@code http://127.0.0.1:35400/lyric}，
 * 未经任何改写。这是唯一能证明「解析器与真实输出对齐」的证据——
 * 手写样例永远会漏掉真实数据里的格式细节（缩进、转义、字段顺序）。
 */
class LiveSampleTest {

    private static String load() {
        try (InputStream in = LiveSampleTest.class.getResourceAsStream("/live-sample.json")) {
            assertNotNull(in, "缺少 live-sample.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 真实响应可解析出曲目() {
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(load(), "salt-player", 0L);

        assertTrue(snapshot.hasTrack(), "真实响应必须能解析出曲目，否则 MC 端无法匹配歌词");
        assertFalse(snapshot.track().title().isEmpty());
        assertTrue(snapshot.positionMs() > 0L);
    }

    @Test
    void 真实响应可解析出完整歌词时间轴() {
        LyricTimeline timeline = SnapshotParser.parseLyric(load());

        assertFalse(timeline.isEmpty());
        assertTrue(timeline.size() > 10, "真实歌词应有较多行，实际 " + timeline.size());

        // 时间轴必须严格递增，二分查找依赖该性质
        long previousStart = -1L;
        for (LyricLine line : timeline.lines()) {
            assertFalse(line.text().isEmpty(), "不应出现空文本行");
            assertTrue(line.startMs() >= previousStart, "行起始时间必须单调不减");
            assertTrue(line.endMs() >= line.startMs(), "行结束时间不得早于起始");
            assertFalse(line.words().isEmpty(), "每行至少应有一个字级单元");
            previousStart = line.startMs();
        }
    }

    @Test
    void 真实响应的逐字时间落在行范围内() {
        LyricTimeline timeline = SnapshotParser.parseLyric(load());
        LyricLine line = timeline.line(0);

        assertFalse(line.words().isEmpty());
        for (var word : line.words()) {
            assertTrue(word.startMs() >= line.startMs(), "字级起点不应早于行起点");
            assertTrue(word.endMs() <= line.endMs(), "字级终点不应晚于行终点");
        }
    }

    @Test
    void 真实响应能被渲染器绘制() {
        String body = load();
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(body, "salt-player", 0L);
        LyricTimeline timeline = SnapshotParser.parseLyric(body);
        LyricFeed feed = new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return snapshot;
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

        RecordingPainter painter = new RecordingPainter();
        boolean drawn = new FloatingLyricRenderer(feed).render(painter);

        assertTrue(drawn, "当前位置应落在某一行上并绘制出来");
        assertFalse(painter.texts.isEmpty());
        assertFalse(painter.texts.get(0).isEmpty());
    }

    /** 按字符数估算宽度，足够验证排版路径不会崩。 */
    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {
        final List<String> texts = new ArrayList<>();
        int fills;

        @Override
        public int screenWidth() {
            return 1920;
        }

        @Override
        public int screenHeight() {
            return 1080;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            texts.add(text);
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            fills++;
        }
    }
}
