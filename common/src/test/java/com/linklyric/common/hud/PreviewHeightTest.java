package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览框高度必须随「行数」与「行距」变化。
 *
 * <h2>为什么要单独钉住</h2>
 *
 * <p>四个界面此前各写一个 {@code PREVIEW_HEIGHT = 56}，是按两行手算的。
 * 于是玩家把行数调到 3 以上，副行会被裁掉一半；调到 1 行，上下空一大截。
 * 更糟的是调那个滑条时预览毫无变化——正好把这个选项的效果藏住了。
 *
 * <p>现在高度由本方法实时算出，界面只负责把结果摆上去。
 * 这里钉住三件事：随行数增长、容纳得下实际内容、与渲染侧的排版同构。
 */
class PreviewHeightTest {

    private static final int LIFT = LyricGeometry.LIFT_HEADROOM;
    private static final int SHADOW = LyricGeometry.SHADOW_HEADROOM;

    /** 行数越多，预览框越高。 */
    @Test
    void 高度随行数单调增长() {
        int previous = -1;
        for (int lines = 1; lines <= 6; lines++) {
            int h = LyricGeometry.previewHeight(lines, 2, LIFT, SHADOW);
            assertTrue(h > previous,
                    "行数 " + lines + " 的高度应大于上一档：h=" + h + " 上一档=" + previous);
            previous = h;
        }
    }

    /** 行距变大时，多行预览也应变高；单行不受行距影响。 */
    @Test
    void 高度跟随行距() {
        int tight = LyricGeometry.previewHeight(3, 0, LIFT, SHADOW);
        int loose = LyricGeometry.previewHeight(3, 12, LIFT, SHADOW);
        assertTrue(loose > tight,
                "行距变大后三行预览应更高：0→" + tight + "，12→" + loose);

        assertEquals(LyricGeometry.previewHeight(1, 0, LIFT, SHADOW),
                LyricGeometry.previewHeight(1, 12, LIFT, SHADOW),
                "单行没有行间空隙，行距不应影响高度");
    }

    /**
     * 高度必须真正容纳得下渲染器要画的内容。
     *
     * <p>判据直接和 {@link LineLayout#compute} 对齐——那是渲染器实际
     * 用来排版同一个块的方法。若两者算出不同的块高，预览就会裁掉字。
     */
    @Test
    void 高度容纳实际排版结果() {
        for (int lines = 1; lines <= 6; lines++) {
            for (int gap = LyricStyleConfig.MIN_LINE_GAP;
                 gap <= LyricStyleConfig.MAX_LINE_GAP; gap++) {
                LineLayout.Block block = LineLayout.compute(
                        0, lines, 0, LyricGeometry.LINE_HEIGHT, gap,
                        LyricGeometry.SUB_TEXT_FONT_SCALE);
                int preview = LyricGeometry.previewHeight(lines, gap, LIFT, SHADOW);
                assertTrue(preview >= block.height(),
                        "预览高度应容纳块高：lines=" + lines + " gap=" + gap
                                + " 预览=" + preview + " 块高=" + block.height());
            }
        }
    }

    /** 行数下限按 1 行处理，不能出现 0 或负高度。 */
    @Test
    void 行数非正时按一行算() {
        assertEquals(LyricGeometry.previewHeight(1, 2, LIFT, SHADOW),
                LyricGeometry.previewHeight(0, 2, LIFT, SHADOW));
        assertEquals(LyricGeometry.previewHeight(1, 2, LIFT, SHADOW),
                LyricGeometry.previewHeight(-5, 2, LIFT, SHADOW));
        assertTrue(LyricGeometry.previewHeight(1, 0, LIFT, SHADOW) > 0);
    }
}
