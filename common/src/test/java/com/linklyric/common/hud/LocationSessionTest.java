package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位置调整的「先拖后确认」语义。
 *
 * <p>三条约束缺一不可：拖动期间不落盘、取消能整套回滚、保存才写盘。
 * 任何一条退化，表现都是「点取消也把位置改了」这类玩家会立刻察觉的问题。
 */
class LocationSessionTest {

    private static FloatingLyricRenderer renderer() {
        return new FloatingLyricRenderer(new TestFeeds.Simple());
    }

    /** 拖动过程中不能落盘，否则取消回不去。 */
    @Test
    void 拖动过程不落盘() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-session");
        LayoutConfig config = LayoutConfig.load(dir);
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.beginSession();
        c.begin(100, 100);
        c.drag(160, 140);
        c.end();

        // 屏幕上已移动
        assertEquals(60, renderer.offsetX());
        // 但磁盘上仍是旧值
        assertEquals(0, LayoutConfig.load(dir).offsetX(),
                "松手不应落盘，实际=" + LayoutConfig.load(dir).offsetX());
    }

    /** 取消必须回到进入会话时的位置。 */
    @Test
    void 取消回滚整套调整() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-revert");
        LayoutConfig config = LayoutConfig.load(dir);
        config.save(12, -8, true);
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.beginSession();
        // 分两次拖，模拟反复微调
        c.begin(100, 100);
        c.drag(150, 130);
        c.end();
        c.begin(150, 130);
        c.drag(200, 170);
        c.end();
        assertNotEquals(12, renderer.offsetX(), "应已偏离原位");

        c.revert();
        assertEquals(12, renderer.offsetX(), "取消应回到原位 X");
        assertEquals(-8, renderer.offsetY(), "取消应回到原位 Y");
        assertEquals(12, LayoutConfig.load(dir).offsetX(), "取消不应改磁盘");
    }

    /** 保存才写盘。 */
    @Test
    void 保存才落盘() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-commit");
        LayoutConfig config = LayoutConfig.load(dir);
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.beginSession();
        c.begin(0, 0);
        c.drag(45, -25);
        c.end();
        assertEquals(0, LayoutConfig.load(dir).offsetX(), "确认前不应写盘");

        c.commit();
        LayoutConfig saved = LayoutConfig.load(dir);
        assertEquals(45, saved.offsetX(), "保存后应写盘 X");
        assertEquals(-25, saved.offsetY(), "保存后应写盘 Y");
    }

    /** 会话是否被改动要能被界面正确反映。 */
    @Test
    void 会话改动状态可查询() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        c.beginSession();
        assertFalse(c.sessionDirty(), "刚进入会话时不应算改动");

        c.begin(0, 0);
        c.drag(30, 30);
        c.end();
        assertTrue(c.sessionDirty(), "拖动后应算改动");

        c.revert();
        assertFalse(c.sessionDirty(), "取消后应回到「未改动」");
    }

    /** 保存后继续调整，取消应回到上次保存的位置而不是最初位置。 */
    @Test
    void 保存后新会话的基线更新() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-rebase");
        LayoutConfig config = LayoutConfig.load(dir);
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, config);

        c.beginSession();
        c.begin(0, 0);
        c.drag(40, 0);
        c.end();
        c.commit();

        // 第二轮调整后取消，应回到 40 而不是 0
        c.beginSession();
        c.begin(0, 0);
        c.drag(90, 0);
        c.end();
        c.revert();

        assertEquals(40, renderer.offsetX(),
                "取消应回到上次保存的位置，实际=" + renderer.offsetX());
    }

    /** 拖动中关闭按钮不应把会话状态留在半途。 */
    @Test
    void 保存会停止拖动态() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        c.beginSession();
        assertTrue(renderer.isDragging(), "会话期间应显示底板");

        c.commit();
        assertFalse(renderer.isDragging(), "保存后应退出拖动态");
    }
}
