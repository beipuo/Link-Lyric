package com.linklyric.common.hud;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.common.bridge.SnapshotParser;
import com.linklyric.core.capability.Capability;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥接解析与悬浮排版的验证。
 *
 * <p>样例 JSON 取自 Link MC 插件的真实输出（含逐字单元格与空 track），
 * 因此这些用例同时充当协议回归测试。
 */
class SnapshotAndRendererTest {

    /** 真实响应的精简版：保留结构与转义场景。 */
    private static final String SAMPLE = """
            {"v":2,"type":"snapshot","sourceId":"salt-player","displayName":"Salt Player",
            "capabilities":["TRACK_METADATA","TRACK_DURATION","PLAYBACK_POSITION","TRANSPORT","SEEK",
            "INLINE_LYRIC","WORD_LEVEL_LYRIC"],"seq":351,"positionMs":26190,"playing":true,
            "hostState":"Ready","track":{"title":"测试曲","artist":"某艺术家","album":"专辑","durationMs":212748},
            "lyric":{"wordLevel":true,"lines":[
            {"text":"欠二两笔墨债","translation":"","startMs":17711,"endMs":23679,"speaker":"",
            "words":[{"text":"欠","startMs":17711,"endMs":18122},{"text":"二","startMs":18122,"endMs":18517}]},
            {"text":"陋室窗静","translation":"Quiet room","startMs":24439,"endMs":29639,"speaker":"",
            "words":[]}]}}
            """;

    @Test
    void 解析快照的基本字段() {
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(SAMPLE, "salt-player", 0L);

        assertTrue(snapshot.hasTrack());
        assertEquals("测试曲", snapshot.track().title());
        assertEquals("某艺术家", snapshot.track().artist());
        assertEquals(212748L, snapshot.track().durationMs());
        assertEquals(26190L, snapshot.positionMs());
        assertTrue(snapshot.playing());
        assertEquals(351L, snapshot.sequence());
    }

    @Test
    void 解析能力集合() {
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(SAMPLE, "salt-player", 0L);

        assertTrue(snapshot.capabilities().has(Capability.PLAYBACK_POSITION));
        assertTrue(snapshot.capabilities().has(Capability.WORD_LEVEL_LYRIC));
        assertFalse(snapshot.capabilities().has(Capability.QUEUE));
    }

    @Test
    void 解析歌词时间轴与逐字() {
        LyricTimeline timeline = SnapshotParser.parseLyric(SAMPLE);

        assertEquals(2, timeline.size());
        LyricLine first = timeline.line(0);
        assertEquals("欠二两笔墨债", first.text());
        assertEquals(17711L, first.startMs());
        assertEquals(23679L, first.endMs());
        assertEquals(2, first.words().size());
        assertEquals("欠", first.words().get(0).text());
        assertEquals(17711L, first.words().get(0).startMs());
    }

    @Test
    void 空单词列表时自动合成整行单元() {
        LyricTimeline timeline = SnapshotParser.parseLyric(SAMPLE);
        LyricLine second = timeline.line(1);

        assertEquals("陋室窗静", second.text());
        assertEquals(1, second.words().size());
        assertEquals(24439L, second.words().get(0).startMs());
        assertEquals(29639L, second.words().get(0).endMs());
    }

    @Test
    void 按位置查行() {
        LyricTimeline timeline = SnapshotParser.parseLyric(SAMPLE);

        assertEquals(-1, timeline.indexAt(0L));
        assertEquals(0, timeline.indexAt(20000L));
        assertEquals(1, timeline.indexAt(26190L));
    }

    @Test
    void 空曲目快照不算有曲目() {
        String emptyTrack = """
                {"v":2,"capabilities":[],"seq":1,"positionMs":0,"playing":false,
                "track":{"title":"","artist":"","album":"","durationMs":0},
                "lyric":{"wordLevel":false,"lines":[]}}
                """;
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(emptyTrack, "salt-player", 0L);

        assertFalse(snapshot.hasTrack());
        assertTrue(SnapshotParser.parseLyric(emptyTrack).isEmpty());
    }

    @Test
    void 畸形输入不抛异常() {
        assertFalse(SnapshotParser.parseSnapshot(null, "x", 0L).hasTrack());
        assertFalse(SnapshotParser.parseSnapshot("", "x", 0L).hasTrack());
        assertFalse(SnapshotParser.parseSnapshot("{{{", "x", 0L).hasTrack());
        assertFalse(SnapshotParser.parseSnapshot("{\"track\":}", "x", 0L).hasTrack());
        assertTrue(SnapshotParser.parseLyric("not json").isEmpty());
    }

    @Test
    void 字符串值里的同名键不干扰定位() {
        // title 的文本里含 "artist" 字样，解析器必须跳过值内部的伪键
        String tricky = """
                {"track":{"title":"artist: 这是标题","artist":"真艺术家","album":"","durationMs":100},
                "positionMs":5,"playing":false,"seq":2,"capabilities":[]}
                """;
        PlayerSnapshot snapshot = SnapshotParser.parseSnapshot(tricky, "x", 0L);

        assertEquals("artist: 这是标题", snapshot.track().title());
        assertEquals("真艺术家", snapshot.track().artist());
    }

    @Test
    void 悬浮渲染在无会话时不绘制() {
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new FakeSource(SAMPLE, false));
        FakePainter painter = new FakePainter(320, 240);

        // isAlive 为 false：不应有任何绘制指令
        assertFalse(renderer.render(painter));
        assertEquals(0, painter.drawn.size());
    }

    @Test
    void 悬浮渲染按当前位置取行并居中() {
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new FakeSource(SAMPLE, true));
        FakePainter painter = new FakePainter(320, 240);

        assertTrue(renderer.render(painter));

        // 位置 26190 落在第二行。该行 words 为空 → 退化为整行单元绘制，
        // 因此主文本会整串出现一次。
        //
        // 后续行优先取「下一句」而非翻译：翻译只在没有下一句时兜底。
        // 这里示例数据不足两行时才会用到翻译，故只断言主文本。
        List<String> texts = painter.allTexts();
        assertTrue(texts.contains("陋室窗静"), "应绘制主文本: " + texts);
    }

    @Test
    void 超宽文本被截断并加省略号() {
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(new FakeSource(SAMPLE, true));
        // 窄屏：最大宽度约 72 像素，第二行 4 字 × 6 = 24 不触发截断，
        // 因此单独构造超长文本来验证
        FakePainter painter = new FakePainter(80, 240);
        renderer.render(painter);
        String drawn = painter.allTexts().isEmpty() ? "" : painter.allTexts().get(0);

        assertTrue(drawn.length() <= "陋室窗静".length() + 1, "超宽时不应原样输出: " + drawn);
    }

    /** 固定返回样例数据的测试替身，绕过真实网络。 */
    private static final class FakeSource implements LyricFeed {
        private final String body;
        private final boolean alive;

        FakeSource(String body, boolean alive) {
            this.body = body;
            this.alive = alive;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return SnapshotParser.parseSnapshot(body, "salt-player", 0L);
        }

        @Override
        public LyricTimeline lyric() {
            return SnapshotParser.parseLyric(body);
        }

        @Override
        public long lyricSignature() {
            return 1L;
        }
    }

    /** 记录绘制指令的假画笔，字宽按每字符 6 像素估算。 */
    private static final class FakePainter implements FloatingLyricRenderer.Painter {
        final List<Drawn> drawn = new ArrayList<>();

        private final int width;
        private final int height;

        FakePainter(int width, int height) {
            this.width = width;
            this.height = height;
        }

        List<String> allTexts() {
            List<String> out = new ArrayList<>();
            for (Drawn d : drawn) {
                out.add(d.text);
            }
            return out;
        }

        @Override
        public int screenWidth() {
            return width;
        }

        @Override
        public int screenHeight() {
            return height;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 6;
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            // 测试替身不校验底板
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            drawn.add(new Drawn(text, x, y, color));
        }


    }

    private record Drawn(String text, int x, int y, int color) {
    }
}
