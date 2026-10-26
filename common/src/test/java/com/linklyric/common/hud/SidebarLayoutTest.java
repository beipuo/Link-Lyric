package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「参数化是否真的够用」的判据：顶部窄列布局能被这组旋钮摆出来。
 *
 * <h2>为什么要写成几何断言，而不是「看起来对了」</h2>
 *
 * <p>本次改动的目标是补上「锚点位置」与「横向留白」两项能力，
 * 而能力够不够用，只能靠一个**具体布局**去试。README 里那个
 * 「灵动岛 / 侧边栏 / 物品栏」的待办若要开工，最需要的就是
 * 「贴顶 + 窄列 + 多行」这一组。因此这里不实现界面，
 * 只把那一组配置喂给默认策略，然后数出块顶、块宽与行数——
 * 三个量都能被数算，也都能被现有守卫的严格程度接受。
 *
 * <h2>为什么只用现成的旋钮，不加任何新参数</h2>
 *
 * <p>{@code anchorRatio = 1}（贴顶）+ 较小的 {@code maxWidthRatio} +
 * {@code wrap = true}（折行）。三者都是改动前就有的，两个是本次参数化的。
 * 若这个组合摆不出窄列，说明参数化还漏了东西；摆得出来，
 * 就说明「侧边栏」剩下的只是界面工作，而不是几何能力缺口。
 */
class SidebarLayoutTest {

    /** 屏幕 854×480：与其余几何测试同一口径（720p、GUI 缩放 1 档）。 */
    private static final int SCREEN_W = 854;
    private static final int SCREEN_H = 480;

    /** 窄列宽度：屏幕宽的 30%，也就是 {@code MIN_WIDTH_RATIO}。 */
    private static final float NARROW_RATIO = LyricStyleConfig.MIN_WIDTH_RATIO;

    private static final PlacementStrategy LINEAR = new LinearStackPlacement();

    /**
     * 贴顶 + 窄列 + 折行，块顶应靠近屏幕顶部。
     *
     * <h2>块顶与底板顶差一个 padY</h2>
     *
     * <p>锚点比例 1 把块的**上边界**放到 {@link LyricGeometry#TOP_MARGIN}，
     * 而 {@code LyricPlacement.blockTop()} 再往上让出 {@code padY}
     * （抬升与阴影的余量，3 像素）——那是底板的上边缘，不是字形。
     * 因此块顶实际落在 {@code TOP_MARGIN} 减去 {@code padY} 的位置。
     *
     * <p>这条不是瑕疵：抬起的字会往上走 2 像素、阴影还会再下偏，
     * 若把块顶钉死在上边距，抬到最高那一帧字形就顶出去了。
     * 断言因此在 {@code [TOP_MARGIN - padY, TOP_MARGIN + 行距]} 之间，
     * 既钉住「确实贴上去了」，又把「余量必须留出来」写清楚。
     */
    @Test
    void 贴顶窄列把块顶送到屏幕顶部() {
        LyricPlacement placed = placeSidebar(4, 4);

        int top = placed.blockTop();
        int lowest = LyricGeometry.TOP_MARGIN - placed.padY();
        assertTrue(top >= lowest,
                "块顶不应高过「上边距减抬升余量」：块顶=" + top
                        + " 下限=" + lowest + " TOP_MARGIN=" + LyricGeometry.TOP_MARGIN);
        assertTrue(top <= LyricGeometry.TOP_MARGIN + LyricGeometry.LINE_GAP,
                "块顶应贴着上边距（允许行距以内的整数余量）：块顶=" + top);
        // 与贴底档对照：两者必须差出大半个屏幕，否则「比例 1」根本没生效。
        LyricPlacement bottom = placeSidebar(4, 4, LyricStyleConfig.DEFAULT);
        assertTrue(bottom.blockTop() - top > SCREEN_H / 2,
                "贴顶与贴底的块顶应差出大半个屏幕：贴顶=" + top
                        + " 贴底=" + bottom.blockTop());
    }

    /**
     * 块宽受 {@code maxWidthRatio} 约束。
     *
     * <p>这里断言的是**视口宽度**不超过可用宽度。视口宽度决定
     * 逐字裁剪与滚动，而块的实际墨迹宽度取决于各行的文字宽度——
     * 后者是平台量出来的，测试里给的是固定值，因此拿它当判据
     * 等于在测测试自己的构造。
     *
     * <p>同时钉住「窄列确实窄」：854 的 30% 是 256，
     * 而默认 72% 是 614。若比例没接上，这里会算出 614。
     */
    @Test
    void 窄列的可用宽度被maxWidthRatio约束() {
        LyricPlacement placed = placeSidebar(4, 4);

        int expectedMaxWidth = Math.max(40, (int) (LyricGeometry.maxWidth(SCREEN_W)
                * NARROW_RATIO * 2f));
        assertEquals(expectedMaxWidth, placed.maxWidth(),
                "可用宽度应由 maxWidthRatio 算出");
        assertTrue(placed.viewWidth() <= placed.maxWidth(),
                "视口宽度不得超过可用宽度：视口=" + placed.viewWidth()
                        + " 可用=" + placed.maxWidth());
        // 窄列至少要比默认宽度窄一半以上，否则「窄」这个性质没成立。
        int defaultMaxWidth = Math.max(40, (int) (LyricGeometry.maxWidth(SCREEN_W)
                * LyricStyleConfig.DEFAULT.maxWidthRatio() * 2f));
        assertTrue(placed.maxWidth() * 2 < defaultMaxWidth,
                "窄列宽度应不足默认宽度的一半：窄列=" + placed.maxWidth()
                        + " 默认=" + defaultMaxWidth);
    }

    /**
     * 折行让同一句占多行，且各行都是全字号。
     *
     * <p>「多行」是侧边栏的必要条件：一列只有一行的东西是标题，不是歌词。
     * 而这几行必须**同字号**——它们属于同一句，若第二段缩到 0.8，
     * 看起来就是原文加译文。这一条由 {@code primarySpan} 与
     * {@link LineLayout} 共同保证，这里验它真的兑现了。
     */
    @Test
    void 折行的各段同字号且逐行下移() {
        LyricPlacement placed = placeSidebar(4, 4);

        assertEquals(4, placed.lines().size(), "四段都应出画");
        for (LyricPlacement.PlacedLine line : placed.lines()) {
            assertEquals(1.0f, line.scale(), 1e-6f,
                    "折行的每一段都应是全字号：第 " + line.index() + " 段 scale="
                            + line.scale());
            assertTrue(line.isPrimary(),
                    "折行的每一段都属于演唱中的那一句：第 " + line.index() + " 段");
        }
        // 纵向必须严格递增：同 y 的两段会叠印在一起。
        for (int i = 1; i < placed.lines().size(); i++) {
            float previous = placed.lines().get(i - 1).y();
            float current = placed.lines().get(i).y();
            assertTrue(current > previous,
                    "第 " + i + " 段应低于上一段：y=" + current + " 上一段 y=" + previous);
        }
    }

    /**
     * 每一段都不得超出窄列的横向范围。
     *
     * <p>这是「块宽受约束」的另一半：前一条验的是「视口不超」，
     * 这一条验的是「每一行的落笔点与右边界都在列内」。
     * 两者都成立，块才是真的窄。
     */
    @Test
    void 各段落在窄列范围内() {
        LyricPlacement placed = placeSidebar(4, 4);

        for (LyricPlacement.PlacedLine line : placed.lines()) {
            assertTrue(line.x() >= placed.viewportLeft(),
                    "第 " + line.index() + " 段左边界不应越过视口左界：x="
                            + line.x() + " 视口左=" + placed.viewportLeft());
            assertTrue(line.right() <= placed.viewportRight() + 1,
                    "第 " + line.index() + " 段右边界不应越过视口右界：右="
                            + line.right() + " 视口右=" + placed.viewportRight());
        }
    }

    /**
     * 整体：块高等于四行加行距，且块底远在屏幕中部之上。
     *
     * <p>「在屏幕上半部分」是侧边栏与默认横排最直观的区别，
     * 用一个不需要任何额外知识的不等式表达：块底小于屏幕高的三分之一。
     */
    @Test
    void 窄列整体位于屏幕上半部() {
        LyricPlacement placed = placeSidebar(4, 4);

        int expectedHeight = LineLayout.blockHeight(
                4, 4, LyricGeometry.LINE_HEIGHT,
                LyricStyleConfig.DEFAULT_LINE_GAP,
                LyricGeometry.SUB_TEXT_FONT_SCALE);
        assertEquals(expectedHeight, placed.blockHeight(),
                "四段全字号的块高应是 4×行高 + 3×行距");
        assertTrue(placed.blockBottom() < SCREEN_H / 3,
                "块底应明显高于屏幕三分之一：块底=" + placed.blockBottom()
                        + " 三分一=" + (SCREEN_H / 3));
    }

    /** 窄列配置本身：三项一起。 */
    private static LyricStyleConfig sidebarStyle() {
        return LyricStyleConfig.DEFAULT
                .withAnchorRatio(1f)
                .withMaxWidthRatio(NARROW_RATIO)
                .withWrap(true);
    }

    private static LyricPlacement placeSidebar(int lines, int span) {
        return placeSidebar(lines, span, sidebarStyle());
    }

    private static LyricPlacement placeSidebar(int lines, int span, LyricStyleConfig style) {
        return LINEAR.place(mk(lines, span, style));
    }

    /**
     * 造一份放置输入。
     *
     * <p>行宽按窄列上限定：4 段各 60 像素，合计远小于 256 的可用宽度，
     * 因此不会触发滚动（折行与滚动互斥，{@code scrolling} 传 false）。
     * 每一段的宽度都一样，于是「按各自宽度居中」全部落在同一条中线上——
     * 若它们各自不同，居中会把左右边界推出不同位置，
     * 让「是否在列内」这条断言多一层与主题无关的噪声。
     */
    private static PlacementInput mk(int lines, int span, LyricStyleConfig style) {
        List<String> texts = new ArrayList<>();
        int[] widths = new int[lines];
        int[] colors = new int[lines];
        float[] scales = new float[lines];
        for (int i = 0; i < lines; i++) {
            texts.add("第" + i + "段窄列歌词");
            widths[i] = 60;
            colors[i] = 0xFF00FF00 | i;
            scales[i] = 1f;
        }
        return new PlacementInput(SCREEN_W, SCREEN_H, null, 0, 0, style,
                texts, widths, colors, scales, widths[0],
                0f, false, 0, span);
    }
}
