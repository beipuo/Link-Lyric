package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多行堆叠与间奏样式。
 *
 * <p>这两个特性的错法都不显眼：块心偏一行、行数增加后整体上移、
 * 间奏空白档仍占包围盒。只能靠算。
 */
class LineLayoutTest {

    private static final int LH = 10;
    private static final int GAP = 2;

    /** 无论几行，块心都必须落在 centerY 上。 */
    @Test
    void 块心居中() {
        for (int lines = 1; lines <= 6; lines++) {
            LineLayout.Block b = LineLayout.compute(100, lines, 0, LH, GAP, 1.0f);
            int actualCenter = b.top() + b.height() / 2;
            assertTrue(Math.abs(actualCenter - 100) <= 1,
                    lines + " 行时块心应约为 100，实际=" + actualCenter);
        }
    }

    /** 行数增加时，块应变高且顶点上移，而不是整体往下长。 */
    @Test
    void 行数增加向上下均分() {
        LineLayout.Block two = LineLayout.compute(100, 2, 0, LH, GAP, 1.0f);
        LineLayout.Block four = LineLayout.compute(100, 4, 0, LH, GAP, 1.0f);

        assertTrue(four.height() > two.height(), "4 行应比 2 行高");
        assertTrue(four.top() < two.top(), "4 行应向上扩展，实际 " + four.top());
    }

    /** 当前行是第一行时，基线就是块顶。 */
    @Test
    void 首行基线等于块顶() {
        LineLayout.Block b = LineLayout.compute(100, 3, 0, LH, GAP, 1.0f);
        assertEquals(b.top(), b.baseline());
    }

    /** 单行时块高等于行高，不能多算行距。 */
    @Test
    void 单行不计行距() {
        LineLayout.Block b = LineLayout.compute(100, 1, 0, LH, GAP, 1.0f);
        assertEquals(LH, b.height(), "单行块高应等于行高");
    }

    /** 行距只在行之间计入，共 (n-1) 段。 */
    @Test
    void 行距为n减1段() {
        int lines = 4;
        LineLayout.Block b = LineLayout.compute(100, lines, 0, LH, GAP, 1.0f);
        assertEquals(LH * lines + GAP * (lines - 1), b.height());
    }

    /** 非当前行按比例缩小时，行高必须跟着变小。 */
    @Test
    void 非当前行按比例缩小() {
        float ratio = 0.8f;
        LineLayout.Block b = LineLayout.compute(100, 2, 0, LH, GAP, ratio);
        int scaled = Math.max(1, Math.round(LH * ratio));
        assertEquals(LH + scaled + GAP, b.height());
    }

    /** 逐行顶部必须递增且不重叠。 */
    @Test
    void 逐行顶部递增() {
        LineLayout.Block b = LineLayout.compute(100, 5, 0, LH, GAP, 1.0f);
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < 5; i++) {
            int top = LineLayout.lineTop(b, i, 0, LH, GAP, 1.0f);
            assertTrue(top > prev, "第 " + i + " 行顶部应递增，实际=" + top);
            prev = top;
        }
        assertEquals(b.top(), LineLayout.lineTop(b, 0, 0, LH, GAP, 1.0f),
                "第 0 行顶部应等于块顶");
    }

    /** 行数越界必须夹紧，不能返回空块。 */
    @Test
    void 行数越界夹紧() {
        LineLayout.Block zero = LineLayout.compute(100, 0, 0, LH, GAP, 1.0f);
        assertEquals(LH, zero.height(), "0 行应退化为 1 行");

        LineLayout.Block negative = LineLayout.compute(100, -5, 0, LH, GAP, 1.0f);
        assertEquals(LH, negative.height());
    }

    /** 样式里的行数必须被夹在 1..10。 */
    @Test
    void 样式行数夹紧() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT.withLineCount(99);
        assertEquals(LyricStyleConfig.MAX_LINES, s.lineCount());

        assertEquals(LyricStyleConfig.MIN_LINES,
                LyricStyleConfig.DEFAULT.withLineCount(0).lineCount());
    }

    /** 间奏三档必须有正确的绘制内容。 */
    @Test
    void 间奏样式内容() {
        assertEquals("", LyricStyleConfig.InterludeStyle.BLANK.text,
                "空白档不应绘制任何文字");
        assertEquals("···", LyricStyleConfig.InterludeStyle.DOTS.text);
        assertEquals("间奏", LyricStyleConfig.InterludeStyle.TEXT.text);
    }

    /** 间奏默认应保留可见提示，不能无意中变成空白。 */
    @Test
    void 间奏默认可见() {
        assertEquals(LyricStyleConfig.InterludeStyle.TEXT,
                LyricStyleConfig.DEFAULT.interlude());
    }

    /** 显示行数默认 2，与旧版双行观感一致。 */
    @Test
    void 默认行数为2() {
        assertEquals(2, LyricStyleConfig.DEFAULT.lineCount());
    }
}
