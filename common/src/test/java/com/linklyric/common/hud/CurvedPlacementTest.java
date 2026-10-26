package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弧形与环形排布的几何性质。
 *
 * <h2>这里验的是什么，不验什么</h2>
 *
 * <p>「好不好看」只能进游戏看，这里验的是**不变式**：
 * 主行在、行数对、朝向确实非零且各不相同、相邻行不重叠、整块不溢出。
 * 这些性质一旦破掉，画面就会以某种具体的坏法出现（叠字、出屏、歪得莫名），
 * 而它们全都能在无平台环境下判定。
 *
 * <p>刻意不验具体像素坐标：那些数字是参数调整的产物，
 * 钉死它们只会让每次调参都红一片，而红的原因与「排布对不对」无关。
 * 默认策略是例外——那里坐标就是判据本身，见 {@code PlacementGoldenTest}。
 */
class CurvedPlacementTest {

    private static final PlacementStrategy ARC = new ArcPlacement();
    private static final PlacementStrategy RING = new RingPlacement();

    @Test
    void 弧排主行在且行数正确() {
        LyricPlacement placed = ARC.place(input(3, LyricStyleConfig.DEFAULT));
        assertNotNull(placed.primary(), "弧排必须给出主行");
        assertEquals(0, placed.primary().index(), "第 0 项必须是主行");
        assertEquals(3, placed.lines().size(), "三个有内容的槽位应各出一行");
    }

    @Test
    void 环排主行在且行数正确() {
        LyricPlacement placed = RING.place(input(3, LyricStyleConfig.DEFAULT));
        assertNotNull(placed.primary(), "环排必须给出主行");
        assertEquals(0, placed.primary().index(), "第 0 项必须是主行");
        assertEquals(3, placed.lines().size());
    }

    /**
     * 朝向必须真的随位置变化。
     *
     * <p>这是整个改动的核心：若所有行的朝向都是 0，那「浮点化 + 接朝向」
     * 就只是白改一遍类型，画面与横排完全一样。因此这条验的是
     * 「**至少有一行**朝向非零」且「各行互不相同」——
     * 而不是「每行都非零」。
     *
     * <p>环排的主行落在正上方，它那一点的切线恰好水平（朝向 0），
     * 这是几何正确的结果，不是缺陷。若强行要求每行都非零，
     * 等于要求排布故意歪一点，那才是错的。
     */
    @Test
    void 弧排各行朝向非零且互不相同() {
        List<LyricPlacement.PlacedLine> lines =
                ARC.place(input(5, LyricStyleConfig.DEFAULT)).lines();
        assertSomeOriented(lines, "弧排");
        assertDistinctOrientations(lines, "弧排");
    }

    @Test
    void 环排各行朝向互不相同且有非零项() {
        List<LyricPlacement.PlacedLine> lines =
                RING.place(input(6, LyricStyleConfig.DEFAULT)).lines();
        assertSomeOriented(lines, "环排");
        assertDistinctOrientations(lines, "环排");
    }

    /** 至少要有一行真的转了，否则接朝向这件事等于没做。 */
    private static void assertSomeOriented(List<LyricPlacement.PlacedLine> lines, String what) {
        int oriented = 0;
        for (LyricPlacement.PlacedLine line : lines) {
            if (line.hasOrientation()) {
                oriented++;
            }
        }
        assertTrue(oriented >= lines.size() - 1,
                what + " 里带朝向的行太少：" + oriented + "/" + lines.size());
    }

    private static void assertDistinctOrientations(List<LyricPlacement.PlacedLine> lines,
                                                   String what) {
        for (int i = 1; i < lines.size(); i++) {
            assertNotEquals(lines.get(i - 1).orientationDegrees(),
                    lines.get(i).orientationDegrees(),
                    what + "相邻两行的朝向不该相同");
        }
    }

    /**
     * 环排的朝向必须覆盖四个象限附近。
     *
     * <p>只验「互不相同」还不够：一个把所有行都摆在顶部小弧上的实现
     * 也能给出互不相同的朝向。这条验的是「真的转了一整圈」，
     * 即朝向跨过 90 度——那是「钟面」与「稍微弯一下」的分界。
     */
    @Test
    void 环排朝向跨越九十度() {
        List<LyricPlacement.PlacedLine> lines =
                RING.place(input(8, LyricStyleConfig.DEFAULT)).lines();
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (LyricPlacement.PlacedLine line : lines) {
            min = Math.min(min, line.orientationDegrees());
            max = Math.max(max, line.orientationDegrees());
        }
        assertTrue(max - min >= 90f,
                "环排的朝向跨度应至少 90 度，实际 " + min + ".." + max);
    }

    @Test
    void 弧排相邻行不重叠() {
        assertNoOverlap(ARC.place(input(6, LyricStyleConfig.DEFAULT)), "弧排");
    }

    @Test
    void 环排相邻行不重叠() {
        assertNoOverlap(RING.place(input(6, LyricStyleConfig.DEFAULT)), "环排");
    }

    /**
     * 相邻两行不得叠印。
     *
     * <h2>判据为什么是「中心距」而不是「线段间距」</h2>
     *
     * <p>曲线排布里相邻两行**沿曲线**推进，中心连线大致垂直行方向。
     * 若拿两条线段求最近距离，当两行都倾斜且相隔一个行距时，
     * 线段端点可能彼此靠近，得到远小于实际间隙的数字——那是判据的错，
     * 不是排布的错。
     *
     * <p>正确的判据是：两行中心距不小于「两行半高之和」。
     * 两个矩形的中心距小于半高之和时，它们在行法向上必然重叠；
     * 反之必然分开。这与行是否倾斜无关，因为行高是法向的。
     */
    private static void assertNoOverlap(LyricPlacement placed, String what) {
        List<LyricPlacement.PlacedLine> lines = placed.lines();
        for (int i = 1; i < lines.size(); i++) {
            LyricPlacement.PlacedLine a = lines.get(i - 1);
            LyricPlacement.PlacedLine b = lines.get(i);
            float need = LyricGeometry.LINE_HEIGHT * (a.scale() + b.scale()) * 0.5f;
            float gap = (float) Math.hypot(centerX(b) - centerX(a), centerY(b) - centerY(a));
            assertTrue(gap >= need,
                    what + "第 " + (i - 1) + " 与 " + i + " 行中心距 " + gap
                            + " 小于所需 " + need);
        }
    }

    /** 该行中心点。 */
    private static float centerX(LyricPlacement.PlacedLine line) {
        return line.x() + line.width() * line.scale() * 0.5f;
    }

    private static float centerY(LyricPlacement.PlacedLine line) {
        return line.y() + LyricGeometry.LINE_HEIGHT * line.scale() * 0.5f;
    }

    /**
     * 整块不得超出屏幕。
     *
     * <p>弧形靠「半径由弦长反解」保证横向不溢出；环形的半径按屏幕高度收紧。
     * 这两条都是构造性保证，但构造写错时不会报错，只会让字跑出屏幕，
     * 因此仍然要验。
     *
     * <p>这里用 854×480（720p 缩放到 1 档的实际像素）：行数取到上限，
     * 是最容易溢出的情形。
     */
    @Test
    void 两种形态都不超出屏幕边界() {
        LyricStyleConfig maxLines = LyricStyleConfig.DEFAULT.withLineCount(5);
        for (PlacementStrategy strategy : List.of(ARC, RING)) {
            LyricPlacement placed = strategy.place(input(5, maxLines, 854, 480));
            FloatingLyricRenderer.Bounds box = placed.footprint();
            assertTrue(box.x() >= -LyricGeometry.PAD_X * 2,
                    strategy.getClass().getSimpleName() + " 左侧溢出：" + box.x());
            assertTrue(box.x() + box.width() <= 854 + LyricGeometry.PAD_X * 2,
                    strategy.getClass().getSimpleName() + " 右侧溢出："
                            + (box.x() + box.width()));
            assertTrue(box.y() >= -LyricGeometry.LINE_HEIGHT * 3,
                    strategy.getClass().getSimpleName() + " 顶部溢出：" + box.y());
            assertTrue(box.y() + box.height() <= 480 + LyricGeometry.LINE_HEIGHT * 3,
                    strategy.getClass().getSimpleName() + " 底部溢出："
                            + (box.y() + box.height()));
        }
    }

    /**
     * 形态必须否决滚动。
     *
     * <p>理由不是审美：逐字裁剪框是屏幕空间的轴对齐矩形，不跟随 pose 旋转。
     * 若弧形下仍然滚动，视口会比行窄，裁剪分支启用，斜排的字会被直边削掉。
     * 因此这条同时检验两个层面——枚举自报的 {@code scrolls()}，
     * 以及放置结果里的 {@code scroll} 与视口宽度。
     */
    @Test
    void 弧形与环形不滚动且视口覆盖整块() {
        assertFalse(LayoutMode.ARC.scrolls());
        assertFalse(LayoutMode.RING.scrolls());
        assertTrue(LayoutMode.LINEAR.scrolls(), "横排必须保留滚动");

        // 故意给一个会被判为滚动的输入：超长句 + 非预览 + 可裁剪。
        PlacementInput longLine = new PlacementInput(854, 480, null, 0, 0,
                LyricStyleConfig.DEFAULT,
                List.of("这是一句非常非常长的歌词用于触发滚动判定", "副行"),
                new int[]{900, 60}, new int[]{0xFF00FF00, 0xFF00FF00},
                new float[]{1f, 0.8f}, 900, 400f, true, 0, 1);
        for (PlacementStrategy strategy : List.of(ARC, RING)) {
            LyricPlacement placed = strategy.place(longLine);
            assertEquals(0, placed.scroll(),
                    strategy.getClass().getSimpleName() + " 不该有滚动偏移");
        }
    }

    /** 形态到策略的映射必须是纯映射，且默认是横排。 */
    @Test
    void 形态默认横排且能按标识反查() {
        assertEquals(LayoutMode.LINEAR, LyricStyleConfig.DEFAULT.layout(),
                "默认形态必须是横排，否则升级后画面会变");
        assertEquals(LayoutMode.LINEAR, LayoutMode.fromId(null));
        assertEquals(LayoutMode.LINEAR, LayoutMode.fromId("不存在的值"));
        assertEquals(LayoutMode.ARC, LayoutMode.fromId("ARC"));
        assertEquals(LayoutMode.RING, LayoutMode.fromId("ring"));
        assertTrue(LayoutMode.LINEAR.strategy() instanceof LinearStackPlacement);
        assertTrue(LayoutMode.ARC.strategy() instanceof ArcPlacement);
        assertTrue(LayoutMode.RING.strategy() instanceof RingPlacement);
    }

    /**
     * 空槽位占位但不出画。
     *
     * <p>与默认策略同一规则：某格暂时没有内容时它照样占一行的高度，
     * 否则块心固定而某格忽然不占高，整块会跳一下。
     * 曲线排布里这条表现为「空档沿曲线留出来」，而不是后面的行挤上来。
     */
    @Test
    void 空槽位推进位置但不出画() {
        PlacementInput gapped = new PlacementInput(854, 480, null, 0, 0,
                LyricStyleConfig.DEFAULT,
                List.of("主行", "", "第三行"),
                new int[]{60, 0, 70}, new int[]{0xFF00FF00, 0xFF00FF00, 0xFF00FF00},
                new float[]{1f, 0.8f, 0.8f}, 60, 0f, false, 0, 1);
        for (PlacementStrategy strategy : List.of(ARC, RING)) {
            List<LyricPlacement.PlacedLine> lines = strategy.place(gapped).lines();
            assertEquals(2, lines.size(),
                    strategy.getClass().getSimpleName() + " 空槽位不该出画");
            assertEquals(0, lines.get(0).index());
            assertEquals(2, lines.get(1).index(), "第三行仍应是槽位 2");
        }
    }

    /** 纯函数：同一输入两次调用必须逐字段一致。 */
    @Test
    void 两种形态都是纯函数() {
        PlacementInput in = input(4, LyricStyleConfig.DEFAULT);
        for (PlacementStrategy strategy : List.of(ARC, RING)) {
            LyricPlacement a = strategy.place(in);
            LyricPlacement b = strategy.place(in);
            assertEquals(a.blockTop(), b.blockTop());
            assertEquals(a.blockBottom(), b.blockBottom());
            assertEquals(a.viewportLeft(), b.viewportLeft());
            assertEquals(a.viewWidth(), b.viewWidth());
            assertEquals(a.lines().size(), b.lines().size());
            for (int i = 0; i < a.lines().size(); i++) {
                assertEquals(a.lines().get(i).x(), b.lines().get(i).x());
                assertEquals(a.lines().get(i).y(), b.lines().get(i).y());
                assertEquals(a.lines().get(i).orientationDegrees(),
                        b.lines().get(i).orientationDegrees());
            }
        }
    }

    private static PlacementInput input(int lines, LyricStyleConfig style) {
        return input(lines, style, 854, 480);
    }

    private static PlacementInput input(int lines, LyricStyleConfig style,
                                        int screenW, int screenH) {
        String[] texts = new String[lines];
        int[] widths = new int[lines];
        int[] colors = new int[lines];
        float[] scales = new float[lines];
        for (int i = 0; i < lines; i++) {
            texts[i] = i == 0 ? "主行" : "副行" + i;
            widths[i] = 60 + i * 4;
            colors[i] = 0xFF00FF00;
            scales[i] = i == 0 ? 1f : 0.8f;
        }
        return new PlacementInput(screenW, screenH, null, 0, 0, style,
                List.of(texts), widths, colors, scales, widths[0], 0f, false, 0, 1);
    }
}
