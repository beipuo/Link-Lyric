package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纵向锚点比例：贴底端点与历史输出逐字节相同，贴顶端点落在上边距处。
 *
 * <h2>这份测试要证明的到底是哪件事</h2>
 *
 * <p>本次改动消掉了 {@code anchorY} 里一段 {@code - LINE_HEIGHT + LINE_HEIGHT}
 * 的抵消往返（推导见 {@code LyricGeometry.anchorY} 的注释）。「抵消」这件事
 * 在整数加法上显然成立，而**显然成立的事正是最容易不验就写进提交说明的**：
 * 一旦某个符号抄错、或者将来有人把 {@code LINE_HEIGHT} 挪进浮点算式，
 * 「显然」就不再成立，而症状只是整块歌词偏 9 像素——只有把新旧截图
 * 叠起来才看得出来。
 *
 * <p>因此这里做的不是「断言某个具体数字」，而是**断言两式恒等**：
 * 测试里保留一份逐字抄自改造前的 {@code legacyAnchorY}，
 * 然后对新式在 {@code screenHeight}、{@code offsetY} 的大范围组合上
 * 逐个比对。断言的对象是「两个函数在所有输入上给出同一个 int」，
 * 因此它不会因为换了一组样本就失效。
 *
 * <p>另一半是比例本身：0 与 1 两个端点、中间插值的单调性、
 * 以及「比例 0 时块高怎么变都不影响结果」——最后这条是
 * 「默认值零变化」的结构性判据。
 */
class AnchorRatioTest {

    /**
     * 改造前的实现，逐字抄自 {@code c1b92db} 的 {@code LyricGeometry}。
     *
     * <p>两段都要抄全：{@code centerY} 与 {@code anchorY}。
     * 只抄展开后的形式就失去了「验抵消」的意义——
     * 那等于假设抵消成立，再拿它去证明自己。
     */
    private static int legacyCenterY(int screenHeight) {
        return screenHeight - LyricGeometry.BOTTOM_MARGIN - LyricGeometry.LINE_HEIGHT;
    }

    private static int legacyAnchorY(int screenHeight, int offsetY) {
        return legacyCenterY(screenHeight) + LyricGeometry.LINE_HEIGHT + offsetY;
    }

    /**
     * 贴底端点与历史实现逐字节相同。
     *
     * <p>扫描范围覆盖从极小窗口到 4K（取到 4000），偏移覆盖 ±500——
     * 后者远大于界面上能拖到的量，因为配置文件可以手改。
     */
    @Test
    void 比例为零时与历史锚点逐字节相同() {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (int screenHeight = 100; screenHeight <= 4000; screenHeight++) {
            for (int offsetY = -500; offsetY <= 500; offsetY += 7) {
                // 块高刻意取几个互不相同的值：贴底端点**不读**它，
                // 因此三个值必须得到同一个结果。若某一版有人把块高
                // 混进了贴底分支，多行歌词的默认位置就会整体平移。
                for (int blockHeight : new int[]{9, 27, 86}) {
                    int actual = LyricGeometry.anchorRatioY(
                            screenHeight, offsetY, 0f, blockHeight);
                    int expected = legacyAnchorY(screenHeight, offsetY);
                    checked++;
                    if (actual != expected && mismatches.size() < 5) {
                        mismatches.add("screenHeight=" + screenHeight
                                + " offsetY=" + offsetY + " blockHeight=" + blockHeight
                                + " 实际=" + actual + " 期望=" + expected);
                    }
                }
            }
        }
        assertTrue(checked > 1_000_000, "扫描组合数应过百万，实际=" + checked);
        assertTrue(mismatches.isEmpty(),
                "贴底端点必须与历史实现逐字节相同，不等例：" + mismatches);
    }

    /**
     * 抵消那条恒等式本身，用整数边界钉住。
     *
     * <p>{@code (h - BOTTOM_MARGIN - LINE_HEIGHT) + LINE_HEIGHT == h - BOTTOM_MARGIN}
     * 成立的前提是三个量都在 int 范围内且 {@code h - BOTTOM_MARGIN - LINE_HEIGHT}
     * 不溢出。这里把 {@code h} 取到 int 的两端，证明「不会溢出到另一个方向」。
     *
     * <p>上界取 {@code Integer.MAX_VALUE} 时中间值仍为正（减 67 不会跨零），
     * 下界取 {@code Integer.MIN_VALUE} 时中间值会下溢——因此下界只取
     * 到 {@code MIN_VALUE + BOTTOM_MARGIN + LINE_HEIGHT}。真实屏幕高远在
     * 这个区间内部，这条断言的作用是标出「恒等式在哪失效」，
     * 而不是假装它处处成立。
     */
    @Test
    void 抵消恒等式在int范围内成立() {
        int margin = LyricGeometry.BOTTOM_MARGIN + LyricGeometry.LINE_HEIGHT;
        int[] heights = {
                0, 1, margin, margin + 1,
                Integer.MAX_VALUE, Integer.MAX_VALUE - 1,
                Integer.MIN_VALUE + margin, Integer.MIN_VALUE + margin + 1,
        };
        for (int h : heights) {
            int withRoundTrip = (h - LyricGeometry.BOTTOM_MARGIN
                    - LyricGeometry.LINE_HEIGHT) + LyricGeometry.LINE_HEIGHT;
            int expanded = h - LyricGeometry.BOTTOM_MARGIN;
            assertEquals(expanded, withRoundTrip,
                    "抵消恒等式在 screenHeight=" + h + " 处不成立");
        }
    }

    /**
     * 比例 1 时块顶贴上边距（允许一像素的整数除法余量）。
     *
     * <p>块心 = {@code TOP_MARGIN + blockHeight / 2}，块顶 = 块心 - 块高 / 2。
     * 奇数块高时 {@code blockHeight / 2} 向下取整两次不相等，
     * 因此块顶会落在 {@code TOP_MARGIN} 或 {@code TOP_MARGIN + 1}。
     * 这个「或 1」不是缺陷，是整数除法的必然结果——断言把它写清楚，
     * 好让以后有人想「优化」成 {@code Math.round} 时先看到代价：
     * 那样块顶会变成 {@code TOP_MARGIN - 1}，块会顶出上边距一像素。
     */
    @Test
    void 比例为一的块顶落在上边距内() {
        for (int screenHeight : new int[]{240, 480, 720, 1080, 2160}) {
            for (int blockHeight = 1; blockHeight <= 120; blockHeight++) {
                int centerY = LyricGeometry.anchorRatioY(
                        screenHeight, 0, 1f, blockHeight);
                int blockTop = centerY - blockHeight / 2;
                int delta = blockTop - LyricGeometry.TOP_MARGIN;
                assertTrue(delta >= 0 && delta <= 1,
                        "块顶应落在 TOP_MARGIN 或其次一行：blockHeight=" + blockHeight
                                + " 块顶=" + blockTop + " TOP_MARGIN="
                                + LyricGeometry.TOP_MARGIN);
            }
        }
    }

    /** 比例在 0..1 之间时锚点单调下降（屏幕 y 向下，所以数值变小）。 */
    @Test
    void 中间比例为单调插值() {
        int screenHeight = 480;
        int blockHeight = 27;
        int previous = LyricGeometry.anchorRatioY(screenHeight, 0, 0f, blockHeight);
        for (int percent = 1; percent <= 100; percent++) {
            int current = LyricGeometry.anchorRatioY(
                    screenHeight, 0, percent / 100f, blockHeight);
            assertTrue(current <= previous,
                    "比例增大时锚点必须向上（数值变小）：比例=" + percent
                            + "% 当前=" + current + " 上一个=" + previous);
            previous = current;
        }
        assertEquals(LyricGeometry.TOP_MARGIN + blockHeight / 2, previous,
                "比例 100% 应恰好落在贴顶端点");
    }

    /**
     * 用户偏移在两个端点上都是同样的像素位移。
     *
     * <p>若只在贴底端点加 {@code offsetY}，比例调到 1 之后拖动就完全失灵，
     * 玩家会以为拖动坏了。这条断言把「拖 10 像素就动 10 像素」
     * 在所有比例上钉住。
     */
    @Test
    void 用户偏移在所有比例上等量作用() {
        int screenHeight = 480;
        int blockHeight = 27;
        for (int percent = 0; percent <= 100; percent += 5) {
            float ratio = percent / 100f;
            int base = LyricGeometry.anchorRatioY(screenHeight, 0, ratio, blockHeight);
            for (int delta : new int[]{-37, -1, 1, 37, 200}) {
                int moved = LyricGeometry.anchorRatioY(
                        screenHeight, delta, ratio, blockHeight);
                assertEquals(base + delta, moved,
                        "偏移应等量作用：比例=" + percent + "% 偏移=" + delta);
            }
        }
    }

    /**
     * NaN 比例必须落回贴底，而不是插值出垃圾。
     *
     * <p>{@code Float.parseFloat("NaN")} 不抛异常，因此配置文件里写
     * {@code anchorRatio=NaN} 就能把一个 NaN 送进来。构造器会把它夹成 0，
     * 但这条方法本身不能依赖「上游一定夹过」——它也被测试与诊断直接调用。
     */
    @Test
    void 非数值比例落回贴底() {
        int actual = LyricGeometry.anchorRatioY(480, 0, Float.NaN, 27);
        assertEquals(480 - LyricGeometry.BOTTOM_MARGIN, actual,
                "NaN 比例应落回贴底端点");
    }

    /**
     * 预览区忽略比例：比例拉到 1 也不能让预览框里的歌词跳位置。
     *
     * <p>预览框里没有物品栏、没有 Boss 血条，也没有「屏幕底部」这个概念，
     * 因此比例在全屏上表达的东西在这里没有对应物。若预览也照比例摆，
     * 用户拖滑条时预览里的歌词会撞到框顶被裁掉一半——
     * 而他真正想看的字号、宽度、行距全被这个位移盖住了。
     */
    @Test
    void 预览区忽略锚点比例() {
        FloatingLyricRenderer.Bounds preview =
                new FloatingLyricRenderer.Bounds(100, 40, 240, 90);
        int bottom = LyricGeometry.anchorY(480, 0, preview, 0f, 27);
        int top = LyricGeometry.anchorY(480, 0, preview, 1f, 27);
        int middle = LyricGeometry.anchorY(480, 0, preview, 0.5f, 27);

        assertEquals(preview.y() + preview.height() / 2, bottom,
                "预览内的锚点应是框的纵向中心");
        assertEquals(bottom, top, "预览内比例不应改变锚点");
        assertEquals(bottom, middle, "预览内比例不应改变锚点");
    }

    /**
     * 上边距常量必须避开单条 Boss 血条。
     *
     * <p>1.21.1 的 {@code BossHealthOverlay.render} 首条血条从 y=12 起、
     * 高 19 像素，底边到 31（反汇编确认，见 {@link LyricGeometry#TOP_MARGIN}
     * 的注释）。这条断言把那个外部事实变成可执行的判据：
     * 若有人把 {@code TOP_MARGIN} 调到 16 这种「看着更贴顶」的值，
     * 测试会指出它压住了血条。
     */
    @Test
    void 上边距避开单条Boss血条() {
        int bossBarBottom = 12 + 19;
        assertTrue(LyricGeometry.TOP_MARGIN >= bossBarBottom,
                "上边距 " + LyricGeometry.TOP_MARGIN
                        + " 应不低于单条 Boss 血条的底边 " + bossBarBottom);
        // 但也不能太大：超过两条血条的底边就是在浪费屏幕上部。
        assertTrue(LyricGeometry.TOP_MARGIN < 12 + 19 * 2,
                "上边距不应超过两条 Boss 血条的高度，否则贴顶档会凭空空出一大截");
    }

    /** 默认样式的两个新字段就是历史行为，一字不差。 */
    @Test
    void 默认样式等于历史行为() {
        LyricStyleConfig d = LyricStyleConfig.DEFAULT;
        assertEquals(0f, d.anchorRatio(), 1e-6f, "默认锚点比例必须是 0（贴底）");
        assertEquals(6, d.padXPx(), "默认留白必须是 6，与改造前三个策略的硬编码值相同");
        assertEquals(LyricGeometry.PAD_X, d.padXPx(),
                "样式默认值必须直接引用几何常量，不能另写一个字面量");
    }
}
