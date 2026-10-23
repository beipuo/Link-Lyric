package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.hud.FloatingLyricRenderer.Bounds;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词位置与拖动交互的回归测试。
 *
 * <p>三个都不能退化：平时不画底板、拖动时画底板、位移与偏移严格一致。
 */
class DragAndOffsetTest {

    private static LyricTimeline timeline() {
        List<LyricWord> words = new ArrayList<>();
        String text = "一二三四";
        long step = 1000L;
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    step * i, step * (i + 1)));
        }
        return new LyricTimeline(List.of(
                new LyricLine(text, "", 0, step * text.length(),
                        List.copyOf(words), "")));
    }

    private static FloatingLyricRenderer renderer() {
        return new FloatingLyricRenderer(new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("t",
                        com.linklyric.core.sync.TrackIdentity.of("曲目", "", 0L),
                        500L, true, 1L, 0L,
                        com.linklyric.core.capability.CapabilitySet.empty());
            }

            @Override
            public LyricTimeline lyric() {
                return timeline();
            }

            @Override
            public long lyricSignature() {
                return 1L;
            }
        });
    }

    /** 平时不画底板：歌词直接浮在画面上。 */
    @Test
    void 默认不绘制底板() {
        RecordingPainter painter = new RecordingPainter();
        renderer().render(painter);

        assertEquals(0, painter.backdropCount,
                "默认不应绘制底板，实际=" + painter.backdropCount);
    }

    /** 拖动时必须画底板，否则用户看不出可拖区域。 */
    @Test
    void 拖动时绘制底板() {
        FloatingLyricRenderer renderer = renderer();
        renderer.setDragging(true);

        RecordingPainter painter = new RecordingPainter();
        renderer.render(painter);

        assertTrue(painter.backdropCount >= 1,
                "拖动时应绘制底板，实际=" + painter.backdropCount);
    }

    /** 偏移必须真的改变绘制位置。 */
    @Test
    void 偏移改变绘制位置() {
        RecordingPainter base = new RecordingPainter();
        renderer().render(base);

        FloatingLyricRenderer shifted = renderer();
        shifted.setOffset(-40, 25);
        RecordingPainter moved = new RecordingPainter();
        shifted.render(moved);

        assertEquals(base.textX + -40, moved.textX,
                "水平偏移应原样作用到绘制坐标");
        assertEquals(base.textY + 25, moved.textY,
                "垂直偏移应原样作用到绘制坐标");
    }

    /** 位移小于阈值视为手抖，不该挪动位置。 */
    @Test
    void 微小位移被忽略() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        c.begin(100, 100);
        c.drag(101, 100);
        c.end();

        assertEquals(0, renderer.offsetX(),
                "1 像素位移应视为手抖，实际=" + renderer.offsetX());
    }

    /** 拖动过程按「按下点 + 累计位移」计算，不累积每帧增量。 */
    @Test
    void 拖动按累计位移计算() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        c.begin(100, 100);
        c.drag(140, 130);
        c.drag(160, 150);
        c.end();

        assertEquals(60, renderer.offsetX(), "水平偏移应等于总位移");
        assertEquals(50, renderer.offsetY(), "垂直偏移应等于总位移");
    }

    /** 指令入口不能因为第一次移动就把歌词瞬移到光标处。 */
    @Test
    void 指令入口首帧不跳变() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        assertTrue(c.beginAtCenter());
        // 第一次移动只是确定基准点，位置不变
        c.drag(500, 400);
        assertEquals(0, renderer.offsetX(), "首帧不应移动");

        // 之后的位移才生效
        c.drag(520, 410);
        assertEquals(20, renderer.offsetX());
        assertEquals(10, renderer.offsetY());
    }

    /** 取消必须回到按下前的位置，不能留下半路的位置。 */
    @Test
    void 取消回到原位() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        c.begin(0, 0);
        c.drag(80, 80);
        assertNotEquals(0, renderer.offsetX());
        c.cancel();

        assertEquals(0, renderer.offsetX(), "取消后应回到原位");
        assertFalse(renderer.isDragging(), "取消后应退出拖动模式");
    }

    /** 显式落盘的入口（不经调整界面）应写入磁盘。 */
    @Test
    void 拖动结果持久化() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-layout");
        LayoutConfig config = LayoutConfig.load(dir);

        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.begin(0, 0);
        c.drag(30, -20);
        // 这条路径是「直接拖动、无需确认」，与调整界面的先拖后确认不同。
        c.endAndPersist();

        LayoutConfig reloaded = LayoutConfig.load(dir);
        assertEquals(30, reloaded.offsetX(), "水平偏移应持久化");
        assertEquals(-20, reloaded.offsetY(), "垂直偏移应持久化");
    }

    /** 复位要把内存与磁盘一起清掉。 */
    @Test
    void 复位清除偏移() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-reset");
        LayoutConfig config = LayoutConfig.load(dir);
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.setOffset(70, 40);
        c.reset();

        assertEquals(0, renderer.offsetX());
        assertEquals(0, LayoutConfig.load(dir).offsetX(),
                "复位也应写入磁盘");
    }

    /** 关闭后完全不出画。 */
    @Test
    void 关闭后不绘制() {
        FloatingLyricRenderer renderer = renderer();
        renderer.setEnabled(false);

        RecordingPainter painter = new RecordingPainter();
        boolean drawn = renderer.render(painter);

        assertFalse(drawn, "关闭后不应有任何绘制");
        assertEquals(Integer.MIN_VALUE, painter.textX, "关闭后不应画文字");
    }

    /** 拖动时必须无视关闭状态，否则调位置时看不到歌词。 */
    @Test
    void 关闭状态下拖动仍可见() {
        FloatingLyricRenderer renderer = renderer();
        renderer.setEnabled(false);
        renderer.setDragging(true);

        RecordingPainter painter = new RecordingPainter();
        assertTrue(renderer.render(painter), "拖动时应出画");
        assertNotEquals(Integer.MIN_VALUE, painter.textX, "拖动时应画文字");
    }

    /** on/off 必须落盘，重启后保持。 */
    @Test
    void 开关持久化() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-enabled");
        LayoutConfig config = LayoutConfig.load(dir);
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.disable();
        assertFalse(LayoutConfig.load(dir).enabled(), "关闭状态应落盘");

        c.enable();
        assertTrue(LayoutConfig.load(dir).enabled(), "开启状态应落盘");
    }

    /** 旧配置文件没有 enabled 键时，必须默认开启。 */
    @Test
    void 旧配置默认开启() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-legacy");
        Files.writeString(dir.resolve("linklyric-layout.properties"),
                "offsetX=12\noffsetY=-8\n");

        LayoutConfig config = LayoutConfig.load(dir);
        assertTrue(config.enabled(), "旧配置缺少 enabled 时应默认开启");
        assertEquals(12, config.offsetX(), "位置仍应读出");
    }

    /** 控制器构造时应把配置里的开关应用到渲染器。 */
    @Test
    void 启动时应用已存开关() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-apply");
        LayoutConfig config = LayoutConfig.load(dir);
        config.save(0, 0, false);

        FloatingLyricRenderer renderer = renderer();
        new LyricDragController(renderer, LayoutConfig.load(dir));

        assertFalse(renderer.isEnabled(), "构造时应应用已存的关闭状态");
    }

    /** 渲染后必须留下非空的包围盒。 */
    @Test
    void 渲染后产生包围盒() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());

        Bounds b = renderer.bounds();
        assertFalse(b.isEmpty(), "出画后包围盒不应为空");
        assertTrue(b.width() > 0 && b.height() > 0, "宽高应为正");
    }

    /** 关闭后包围盒必须清空，否则会命中看不见的歌词。 */
    @Test
    void 关闭后包围盒为空() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());
        assertFalse(renderer.bounds().isEmpty());

        renderer.setEnabled(false);
        renderer.render(new RecordingPainter());

        assertTrue(renderer.bounds().isEmpty(),
                "关闭后包围盒应收起，实际=" + renderer.bounds());
    }

    /** 偏移必须让包围盒同步移动。 */
    @Test
    void 偏移移动包围盒() {
        FloatingLyricRenderer base = renderer();
        base.render(new RecordingPainter());
        Bounds a = base.bounds();

        FloatingLyricRenderer moved = renderer();
        moved.setOffset(-30, 20);
        moved.render(new RecordingPainter());
        Bounds b = moved.bounds();

        assertEquals(a.x() - 30, b.x(), "水平偏移应同步到包围盒");
        assertEquals(a.y() + 20, b.y(), "垂直偏移应同步到包围盒");
        assertEquals(a.width(), b.width(), "平移不应改变宽度");
        assertEquals(a.height(), b.height(), "平移不应改变高度");
    }

    /** 包围盒中心必须落在自身内部，这是悬停能命中的前提。 */
    @Test
    void 包围盒中心可命中() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());
        Bounds b = renderer.bounds();

        assertTrue(b.contains(b.x() + b.width() / 2, b.y() + b.height() / 2),
                "中心点应命中");
    }

    /** 光标在歌词上时按下即进入拖动，无需先打指令。 */
    @Test
    void 悬停按下直接拖动() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());
        Bounds b = renderer.bounds();

        LyricDragController c = new LyricDragController(renderer, null);
        int cx = b.x() + b.width() / 2;
        int cy = b.y() + b.height() / 2;

        assertTrue(c.beginAtCursor(cx, cy), "落在歌词上时应进入拖动");
        assertTrue(renderer.isDragging(), "应显示底板");
    }

    /** 光标在歌词外时按下不该抢事件，否则界面按钮会被吞掉。 */
    @Test
    void 歌词外按下不拦截() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());

        LyricDragController c = new LyricDragController(renderer, null);
        assertFalse(c.beginAtCursor(0, 0), "歌词之外不应开始拖动");
        assertFalse(renderer.isDragging());
    }

    /** 关闭状态下即使坐标在原位置也不该命中。 */
    @Test
    void 关闭状态不响应悬停() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());
        Bounds b = renderer.bounds();
        renderer.setEnabled(false);
        renderer.render(new RecordingPainter());

        LyricDragController c = new LyricDragController(renderer, null);
        assertFalse(c.isCursorOverLyric(b.x() + 1, b.y() + 1),
                "关闭后不应命中");
    }

    /** 松手确定：位置应落盘，且退出拖动状态。 */
    @Test
    void 松手确定并落盘() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-confirm");
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, LayoutConfig.load(dir));

        renderer.render(new RecordingPainter());
        Bounds b = renderer.bounds();
        assertTrue(c.beginAtCursor(b.x() + 2, b.y() + 2), "按下应命中歌词");

        c.drag(b.x() + 42, b.y() + 22);
        c.endAndPersist();

        assertFalse(renderer.isDragging(), "松手后应退出拖动");
        LayoutConfig saved = LayoutConfig.load(dir);
        assertEquals(40, saved.offsetX(), "应落盘水平位移");
        assertEquals(20, saved.offsetY(), "应落盘垂直位移");
    }

    /** 空白处按下不该被当成拖动起点：界面据此把这次操作视为放弃。 */
    @Test
    void 空白处按下不开始拖动() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());
        Bounds b = renderer.bounds();
        LyricDragController c = new LyricDragController(renderer, null);

        // 取歌词上方的空白处
        assertFalse(c.beginAtCursor(b.x() + 1, b.y() - 10),
                "歌词之外按下不应进入拖动");
        assertFalse(c.isActive(), "不应处于拖动中");
    }

    /** 拖动中再次按下不该重置基准点，否则会丢掉已累计的位移。 */
    @Test
    void 拖动中重复按下被忽略() {
        FloatingLyricRenderer renderer = renderer();
        renderer.render(new RecordingPainter());
        Bounds b = renderer.bounds();
        LyricDragController c = new LyricDragController(renderer, null);

        int cx = b.x() + 5;
        int cy = b.y() + 5;
        assertTrue(c.beginAtCursor(cx, cy));
        c.drag(cx + 30, cy);

        assertFalse(c.beginAtCursor(cx + 10, cy), "拖动中不应再次进入");
        assertEquals(30, renderer.offsetX(), "已累计的位移不应被重置");
    }

    /** 记录绘制坐标与底板次数的最小替身。 */
    private static final class RecordingPainter
            implements FloatingLyricRenderer.Painter {
        int textX = Integer.MIN_VALUE;
        int textY = Integer.MIN_VALUE;
        int backdropCount;

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
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (textX == Integer.MIN_VALUE) {
                textX = x;
                textY = y;
            }
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            if (color == FloatingLyricRenderer.COLOR_BACKDROP) {
                backdropCount++;
            }
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
