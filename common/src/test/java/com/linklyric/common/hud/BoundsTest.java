package com.linklyric.common.hud;

import com.linklyric.common.hud.FloatingLyricRenderer.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词屏幕包围盒的几何与命中语义。
 *
 * <p>包围盒是悬停拖动与外部布局的依据，错一个像素就表现为
 * 「明明点到了歌词却拖不动」，或反过来「点到旁边的按钮被吞掉」。
 */
class BoundsTest {

    /** 右边界与下边界不包含，与屏幕裁剪语义一致。 */
    @Test
    void 边界判定遵循半开区间() {
        Bounds b = new Bounds(10, 20, 30, 40);

        assertTrue(b.contains(10, 20), "左上角属于区域内部");
        assertTrue(b.contains(39, 59), "右下角内侧仍算命中");
        assertFalse(b.contains(40, 30), "右边界不属于区域");
        assertFalse(b.contains(20, 60), "下边界不属于区域");
        assertFalse(b.contains(9, 30), "左外侧不命中");
        assertFalse(b.contains(20, 19), "上外侧不命中");
    }

    /** 空区域不命中任何点，避免关闭状态下抢鼠标。 */
    @Test
    void 空区域永不命中() {
        assertTrue(Bounds.EMPTY.isEmpty());
        assertFalse(Bounds.EMPTY.contains(0, 0));
        assertFalse(new Bounds(0, 0, 0, 10).contains(0, 5));
        assertFalse(new Bounds(0, 0, 10, 0).contains(5, 0));
    }
}
