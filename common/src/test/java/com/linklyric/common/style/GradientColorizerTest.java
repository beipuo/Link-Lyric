package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字内渐变的颜色分配。
 *
 * <p>核心判据是「前沿落在字内时，该字必须是左右异色」——
 * 否则就退化成整字跳变，与旧的裁剪方案没有区别。
 */
class GradientColorizerTest {

    private static final int SUNG = 0xFF3AA0FF;
    private static final int UNSUNG = 0xFFFFFFFF;

    /** 前沿在字之前：整字未唱，两侧同色。 */
    @Test
    void 未唱到的字两侧同色() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 5f, SUNG, UNSUNG);
        assertEquals(UNSUNG, c.left());
        assertEquals(UNSUNG, c.right());
        assertFalse(c.isGradient());
    }

    /** 前沿在字之后：整字已唱，两侧同色。 */
    @Test
    void 已唱完的字两侧同色() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 25f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(SUNG, c.right());
        assertFalse(c.isGradient());
    }

    /**
     * 关掉羽化后前沿必须是硬切。
     *
     * <p>这条分支本来就在 {@code spansForCharWithFeather} 里，但此前没有任何
     * 调用方能到达它：样式里的 {@code feather} 开关能改、能存盘，渲染侧却
     * 始终按算出的非零宽度画渐变。用户关掉开关后画面毫无变化。
     */
    @Test
    void 关闭羽化后前沿硬切() {
        GlyphSpan[] spans = GradientColorizer.spansForChar(
                10f, 19f, 14f, SUNG, UNSUNG, 300f, false);
        assertEquals(2, spans.length, "硬切只应有已唱与未唱两段");
        assertEquals(SUNG, spans[0].startColor());
        assertEquals(SUNG, spans[0].endColor());
        assertEquals(UNSUNG, spans[1].startColor());
        assertEquals(UNSUNG, spans[1].endColor());
        assertEquals(14f, spans[0].endX(), 0.001f, "切点必须落在前沿上");
    }

    /** 开启羽化时必须存在一段两端异色的过渡带，否则等于没开。 */
    @Test
    void 开启羽化后存在过渡带() {
        GlyphSpan[] spans = GradientColorizer.spansForChar(
                10f, 19f, 14f, SUNG, UNSUNG, 300f, true);
        assertTrue(spans.length >= 2, "羽化开启时应有过渡段");
        boolean gradient = false;
        for (GlyphSpan span : spans) {
            if (span.startColor() != span.endColor()) {
                gradient = true;
                break;
            }
        }
        assertTrue(gradient, "羽化开启时至少有一段两端异色，否则与硬切无异");
    }

    /** 前沿穿过字内：必须左右异色，这样顶点插值才能画出字内渐变。 */
    @Test
    void 前沿穿过时左右异色() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 14f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(UNSUNG, c.right());
        assertTrue(c.isGradient(), "前沿在字内时必须异色，否则就是整字跳变");
    }

    /** 前沿极其接近左边缘时，仍判为字内渐变。 */
    @Test
    void 前沿贴左边缘仍是渐变() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 10.001f, SUNG, UNSUNG);
        assertTrue(c.isGradient());
    }

    /** 前沿接近右边缘时，右端只是被染了一点，仍算渐变。 */
    @Test
    void 前沿接近右边缘仍是渐变() {
        // 过渡带宽 1.5px（行宽未知时的下限），前沿在 18 时右端仍在过渡带内。
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 18f, SUNG, UNSUNG);
        assertTrue(c.isGradient(), "前沿在字内偏右时两端不应同色");
    }

    /**
     * 前沿到达右边缘时，该字整体已唱。
     *
     * <p>过渡带位于前沿右侧，因此前沿与右顶点重合时右顶点正好是纯已唱色。
     * 这正是「整行唱完不留残影」的保证。
     */
    @Test
    void 前沿到达右边缘时整字已唱() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 19f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(SUNG, c.right());
        assertFalse(c.isGradient());
    }

    /** 边界处不应出现「整字已唱」的误判。 */
    @Test
    void 右边缘处应判为已唱完() {
        GlyphColors c = GradientColorizer.forChar(10f, 19f, 19f, SUNG, UNSUNG);
        assertEquals(SUNG, c.left());
        assertEquals(SUNG, c.right());
    }

    /** 整行：只有跨前沿的那个字是渐变，其余同色。 */
    @Test
    void 整行只有跨前沿的字渐变() {
        float[] lefts = {0f, 9f, 18f, 27f};
        float[] rights = {9f, 18f, 27f, 36f};
        GlyphColors[] out = GradientColorizer.forLine(lefts, rights, 4, 14f,
                SUNG, UNSUNG);

        assertEquals(4, out.length);
        assertFalse(out[0].isGradient(), "第 0 字已唱完");
        assertTrue(out[1].isGradient(), "第 1 字跨越前沿 14");
        assertFalse(out[2].isGradient(), "第 2 字未唱到");
        assertFalse(out[3].isGradient(), "第 3 字未唱到");

        assertEquals(SUNG, out[0].left());
        assertEquals(UNSUNG, out[2].left());
    }

    /** 前沿在行首之前：整行同色未唱。 */
    @Test
    void 前沿在行首前整行未唱() {
        float[] lefts = {0f, 9f};
        float[] rights = {9f, 18f};
        // 前沿远在行首之前：过渡带也不该伸到行首。
        GlyphColors[] out = GradientColorizer.forLine(lefts, rights, 2, -10f,
                SUNG, UNSUNG);
        for (GlyphColors c : out) {
            assertFalse(c.isGradient());
            assertEquals(UNSUNG, c.left());
        }
    }

    /** 前沿在行尾之后：整行同色已唱。 */
    @Test
    void 前沿在行尾后整行已唱() {
        float[] lefts = {0f, 9f};
        float[] rights = {9f, 18f};
        GlyphColors[] out = GradientColorizer.forLine(lefts, rights, 2, 100f,
                SUNG, UNSUNG);
        for (GlyphColors c : out) {
            assertFalse(c.isGradient());
            assertEquals(SUNG, c.left());
        }
    }

    /** 同色值对象不应被判定为渐变，这样能跳过自定义顶点。 */
    @Test
    void 同色不算渐变() {
        assertFalse(GlyphColors.solid(SUNG).isGradient());
        assertTrue(new GlyphColors(SUNG, UNSUNG).isGradient());
    }

    /**
     * 相邻字的接缝处必须同色。
     *
     * <p>第 i 字的右顶点与第 i+1 字的左顶点落在同一个横坐标上，
     * 取到的颜色必须完全相同。否则字与字之间出现色阶，
     * 观感就是一跳一跳。
     */
    @Test
    void 相邻字接缝处同色() {
        float width = 90f;
        float edge = 41.7f;
        float[] lefts = new float[10];
        float[] rights = new float[10];
        for (int i = 0; i < 10; i++) {
            lefts[i] = i * 9f;
            rights[i] = lefts[i] + 9f;
        }
        GlyphColors[] out = GradientColorizer.forLine(
                lefts, rights, 10, edge, SUNG, UNSUNG, width);
        for (int i = 0; i < 9; i++) {
            assertEquals(out[i].right(), out[i + 1].left(),
                    "第 " + i + " 字与第 " + (i + 1) + " 字接缝处颜色不一致");
        }
    }

    /**
     * 前沿在字内推进时，端点色必须跟着变。
     *
     * <p>若不变，说明前沿位置没有参与计算，字内仍是整字跳变。
     */
    @Test
    void 前沿在字内推进时端点色变化() {
        float width = 90f;
        // 过渡带位于前沿右侧，因此前沿进入字内后左顶点已是纯已唱色，
        // 变化全部体现在右顶点上。只要右端有多次不同取值，就说明
        // 前沿位置参与了计算。
        int prev = -1;
        int distinct = 0;
        for (float edge = 10f; edge <= 19f; edge += 0.5f) {
            GlyphColors c = GradientColorizer.forChar(
                    10f, 19f, edge, SUNG, UNSUNG, width);
            assertEquals(SUNG, c.left(), "前沿已越过的顶点应为纯已唱色");
            if (c.right() != prev) {
                distinct++;
                prev = c.right();
            }
        }
        assertTrue(distinct >= 4,
                "前沿在 9px 字内推进时右端点色应有多次变化，实际=" + distinct);
    }

    /**
     * 前沿在字内时，必须在折点处切出多段，而不是整字一段渐变。
     *
     * <p>这是「字内变色看不出边界」的直接回归测试。真实的前沿函数是
     * 「平-斜-平」三段折线，两个顶点只能画直线，拟合不了。
     * 判据：跨前沿的字必须产出至少两段，且首段是纯已唱、末段是纯未唱。
     */
    @Test
    void 跨前沿的字切成多段() {
        GlyphSpan[] spans = GradientColorizer.spansForChar(
                100f, 109f, 105f, SUNG, UNSUNG, 40f);
        assertTrue(spans.length >= 2,
                "跨前沿的字必须切成多段，实际=" + spans.length);
        assertEquals(SUNG, spans[0].startColor(), "首段左端应为纯已唱色");
        assertEquals(UNSUNG, spans[spans.length - 1].endColor(),
                "末段右端应为纯未唱色");
        // 相邻段首尾相接，不能有缝隙。
        for (int i = 0; i < spans.length - 1; i++) {
            assertEquals(spans[i].endX(), spans[i + 1].startX(), 0.0001f,
                    "第 " + i + " 段与下一段之间不应有缝隙");
            assertEquals(spans[i].endColor(), spans[i + 1].startColor(),
                    "接缝处颜色必须一致");
        }
    }

    /**
     * 切段后，前沿位置必须精确。
     *
     * <p>前沿在 105、羽化带覆盖 {@code [105, 109]}，因此：
     * 102~104 应完全已唱，109 之后应完全未唱，106 落在羽化带内是中间色。
     *
     * <p>若整字只有一段渐变，x=102 会拿到约 78%、x=104 约 56%，
     * 这正是「字内看不出边界扫过」的表现。
     */
    @Test
    void 切段后前沿位置精确() {
        // 最后一个参数是行宽，羽化宽度由 featherFor 推算。
        // 行宽 40 → 羽化 4.0，带覆盖 [105, 109]。
        GlyphSpan[] spans = GradientColorizer.spansForChar(
                100f, 109f, 105f, SUNG, UNSUNG, 40f);
        assertEquals(SUNG, colorAt(spans, 102f), "前沿左侧应完全已唱");
        assertEquals(SUNG, colorAt(spans, 104f), "前沿左侧应完全已唱");
        // 羽化带内是中间色：既不是纯已唱也不是纯未唱。
        int mid = colorAt(spans, 107f);
        assertNotEquals(SUNG, mid, "羽化带内不应是纯已唱色");
        assertNotEquals(UNSUNG, mid, "羽化带内不应是纯未唱色");
    }

    /** 羽化带右侧回到纯未唱。 */
    @Test
    void 羽化带右侧纯未唱() {
        // 行宽 40 → 羽化 4.0，前沿 100 时带覆盖 [100, 104]，
        // 字 100..109 的右半应为纯未唱。
        GlyphSpan[] spans = GradientColorizer.spansForChar(
                100f, 109f, 100f, SUNG, UNSUNG, 40f);
        assertEquals(UNSUNG, colorAt(spans, 106f), "羽化带右侧应完全未唱");
        assertEquals(UNSUNG, colorAt(spans, 108f), "羽化带右侧应完全未唱");
    }

    /** 取某横坐标处的颜色；落在段内则按段两端插值。 */
    private static int colorAt(GlyphSpan[] spans, float x) {
        for (GlyphSpan s : spans) {
            if (x >= s.startX() - 0.0001f && x <= s.endX() + 0.0001f) {
                float t = (x - s.startX()) / (s.endX() - s.startX());
                return GradientColorizer.lerpArgb(
                        s.startColor(), s.endColor(), Math.max(0f, Math.min(1f, t)));
            }
        }
        throw new AssertionError("x=" + x + " 不在任何段内");
    }

    /** 完全在前沿之后的字只有一段，不该白白增加顶点。 */
    @Test
    void 未开始的字不切段() {
        GlyphSpan[] spans = GradientColorizer.spansForChar(
                100f, 109f, 50f, SUNG, UNSUNG, 4f);
        assertEquals(1, spans.length, "未开始的字应只有一段");
        assertEquals(UNSUNG, spans[0].startColor());
        assertEquals(UNSUNG, spans[0].endColor());
    }

    /** 过渡宽度按行宽比例推算，并夹在合理区间内。 */
    @Test
    void 过渡宽度随行宽变化且有上下限() {
        assertEquals(1.5f, GradientColorizer.featherFor(5f), 0.001f);
        assertEquals(2.0f, GradientColorizer.featherFor(20f), 0.001f);
        assertEquals(4.0f, GradientColorizer.featherFor(100f), 0.001f);
        assertEquals(4.0f, GradientColorizer.featherFor(1000f), 0.001f);
    }
}
