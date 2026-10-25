package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主行与次行的行间距。
 *
 * <h2>为什么行距要单独钉住</h2>
 *
 * <p>行距是"多算出来的空白"最容易藏身的地方。字形在纵向只占
 * {@code font.lineHeight}（9 像素），若布局按更大的数算行高，
 * 差额既不会被字形填满，也不会报错，只是安静地变成两行之间的空隙。
 *
 * <p>这里曾经写 {@code LINE_HEIGHT = 10}，比真实字形高 1 像素，
 * 再叠上 2 像素行距，主行与次行之间出现 3 像素空白；
 * 而次行按 {@code round(10 * 0.8) = 8} 算高度，实际字形只有 7.2，
 * 又少算了 0.8——两个方向都有误差，块心跟着偏移。
 */
class LineGapTest {

    /** 行高必须等于 MC 默认字体的行高。 */
    @Test
    void 行高等于字体行高() {
        assertEquals(9, LyricGeometry.LINE_HEIGHT,
                "行高应等于 font.lineHeight，多算的像素会变成行间空隙");
    }

    /** 默认行距与样式默认值一致。 */
    @Test
    void 默认行距与几何常量一致() {
        assertEquals(LyricGeometry.LINE_GAP,
                LyricStyleConfig.DEFAULT.lineGapPx(),
                "样式的默认行距应与几何常量同源");
    }

    /**
     * 两行的视觉空隙就是行距本身。
     *
     * <p>这是行距的定义：不是基线差，而是<b>上一行字形底部</b>到
     * <b>下一行字形顶部</b>之间的空白。按基线差衡量会把字形自身的
     * 高度混进去，看到的数与画面上看到的空白对不上。
     */
    @Test
    void 两行视觉空隙等于行距() {
        int gap = 2;
        int lineHeight = LyricGeometry.LINE_HEIGHT;

        LineLayout.Block block = LineLayout.compute(0, 2, 0,
                lineHeight, gap, 0.8f);
        int mainTop = LineLayout.lineTop(block, 0, 0, lineHeight, gap, 0.8f);
        int subTop = LineLayout.lineTop(block, 1, 0, lineHeight, gap, 0.8f);

        int mainBottom = mainTop + lineHeight;
        assertEquals(gap, subTop - mainBottom,
                "主行底到次行顶的空白应正好是行距 " + gap
                        + "，实际 主底=" + mainBottom + " 次顶=" + subTop);
    }

    /**
     * 次行高度按比例精确计算，不取整。
     *
     * <p>0.8 倍于 9 是 7.2。取整成 7 会少算 0.2、成 8 会多算 0.8，
     * 两种都会让块高偏移，块心居中随之偏掉。
     */
    @Test
    void 次行高度保持精确() {
        assertEquals(7.2f, LineLayout.heightOf(1, 0, 9, 0.8f), 1e-5f,
                "次行高度应为 9 * 0.8 = 7.2，不应取整");
        assertEquals(9f, LineLayout.heightOf(0, 0, 9, 0.8f), 1e-5f,
                "当前行不缩放");
    }

    /** 行距为 0 时两行紧贴，但不重叠。 */
    @Test
    void 行距为零时两行紧贴() {
        int lineHeight = LyricGeometry.LINE_HEIGHT;
        LineLayout.Block block = LineLayout.compute(0, 2, 0, lineHeight, 0, 0.8f);
        int mainTop = LineLayout.lineTop(block, 0, 0, lineHeight, 0, 0.8f);
        int subTop = LineLayout.lineTop(block, 1, 0, lineHeight, 0, 0.8f);

        assertEquals(lineHeight, subTop - mainTop,
                "行距为 0 时，次行顶部应正好接在主行底部");
        assertTrue(subTop >= mainTop + lineHeight, "两行不应重叠");
    }

    /** 行距只影响纵向，不改变块心的横向。 */
    @Test
    void 加大行距后仍然块心居中() {
        int lineHeight = LyricGeometry.LINE_HEIGHT;
        for (int gap : new int[]{0, 2, 6, 12}) {
            LineLayout.Block block = LineLayout.compute(100, 2, 0,
                    lineHeight, gap, 0.8f);
            int centerOfBlock = block.top() + block.height() / 2;
            assertEquals(100, centerOfBlock, 0,
                    "行距 " + gap + " 时块心应仍在 100，实际 " + centerOfBlock);
        }
    }

    /** 行距写盘再读回来保持。 */
    @Test
    void 行距能存能读(@TempDir Path dir) throws IOException {
        LayoutConfig config = LayoutConfig.load(dir);
        config.saveStyle(config.style().withLineGapPx(7));

        assertEquals(7, LayoutConfig.load(dir).style().lineGapPx(),
                "行距应保持为 7");
    }

    /** 越界行距被夹紧，不会把行压成负间距。 */
    @Test
    void 行距越界被夹紧() {
        assertEquals(LyricStyleConfig.MIN_LINE_GAP,
                LyricStyleConfig.DEFAULT.withLineGapPx(-5).lineGapPx());
        assertEquals(LyricStyleConfig.MAX_LINE_GAP,
                LyricStyleConfig.DEFAULT.withLineGapPx(999).lineGapPx());
    }

    /** 旧配置没有行距键时回退到默认，不影响启动。 */
    @Test
    void 缺失配置回退默认(@TempDir Path dir) throws IOException {
        java.nio.file.Files.writeString(
                dir.resolve("linklyric-layout.properties"),
                "offsetX=0\noffsetY=0\n");
        assertEquals(LyricStyleConfig.DEFAULT_LINE_GAP,
                LayoutConfig.load(dir).style().lineGapPx(),
                "旧配置应回退到默认行距");
    }

    /** 行距是真实空白：主行字形不会被次行盖住。 */
    @Test
    void 行距保证不重叠() {
        int lineHeight = LyricGeometry.LINE_HEIGHT;
        for (int gap = 0; gap <= LyricStyleConfig.MAX_LINE_GAP; gap++) {
            LineLayout.Block block = LineLayout.compute(0, 3, 0, lineHeight, gap, 0.8f);
            int a = LineLayout.lineTop(block, 0, 0, lineHeight, gap, 0.8f);
            int b = LineLayout.lineTop(block, 1, 0, lineHeight, gap, 0.8f);
            int c = LineLayout.lineTop(block, 2, 0, lineHeight, gap, 0.8f);
            assertTrue(b >= a + lineHeight * 0.8f - 0.001f,
                    "行距 " + gap + " 时次行不应压到主行");
            assertFalse(c < b, "第三行应在第二行之下");
        }
    }
}
