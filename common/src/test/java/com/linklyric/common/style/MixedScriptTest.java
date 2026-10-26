package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混排校正参数本身的行为。
 *
 * <h2>这里测的是"开关的语义边界"，不是"观感"</h2>
 *
 * <p>观感只能进游戏看。测试能做的是把三件事钉死：
 * 默认必须是关闭；夹取必须生效（配置文件和滑条都会给出越界值）；
 * 以及最关键的一条——{@link MixedScript#active()} 必须在
 * 「开关关着」与「两个刻度都在中性位置」两种情况下**都**返回 false。
 *
 * <p>最后那条是"默认零视觉变化"的机械保证。若它退化成
 * 「只要 enabled 为真就去缩放」，用户把刻度拖回 100%/0 时就仍会压姿态栈、
 * 仍会走按外接矩形放宽裁剪框的分支，而那条分支的公式与未变换分支不同
 * （见 {@code LyricPainter.glyph}），边界像素上会差一个像素。
 */
class MixedScriptTest {

    /** 默认构造出来的必须是关闭态。 */
    @Test
    void 默认关闭() {
        MixedScript off = MixedScript.OFF;
        assertFalse(off.enabled(), "默认必须关闭");
        assertFalse(off.active(), "关闭态必须 inactive");
    }

    /**
     * 默认关闭时两个刻度仍是"建议起点"，而不是中性值。
     *
     * <p>这是刻意的：用户打开开关的那一刻就该看到效果，
     * 而不是看到一个"看起来什么都没变"的中性值然后以为功能坏了。
     */
    @Test
    void 关闭态仍保留建议起点() {
        assertEquals(MixedScript.DEFAULT_SCALE_PERCENT, MixedScript.OFF.cjkScalePercent());
        assertEquals(MixedScript.DEFAULT_BASELINE_SHIFT_PX,
                MixedScript.OFF.baselineShiftPx());
        assertEquals(96, MixedScript.DEFAULT_SCALE_PERCENT, "起点值应锁定为 96");
        assertEquals(0, MixedScript.DEFAULT_BASELINE_SHIFT_PX, "默认不做基线校正");
    }

    /** 打开且刻度偏离中性时，必须 active。 */
    @Test
    void 开启且偏离中性则生效() {
        assertTrue(new MixedScript(true, 96, 0).active());
        assertTrue(new MixedScript(true, 100, 1).active());
        assertTrue(new MixedScript(true, 100, -1).active());
        assertTrue(new MixedScript(true, 85, 0).active());
    }

    /**
     * 打开但两个刻度都在中性位置时，必须**不**生效。
     *
     * <p>这条是"结构性零变化"的核心判据。用户可以把滑条拖到
     * 100% / 0 来验证"开启前后有没有差别"：此时绘制路径必须与从未开启
     * 完全一致——不是"参数恰好等于 1 所以结果相同"，而是代码根本不进那条分支。
     */
    @Test
    void 中性刻度等同关闭() {
        MixedScript neutral = new MixedScript(true, 100, 0);
        assertTrue(neutral.enabled(), "开关本身是开的");
        assertFalse(neutral.active(), "但两个刻度都在中性位，必须不生效");
        assertEquals(1f, neutral.scale(), 0f,
                "中性刻度的 scale 必须恰好是 1.0f（用 0 容差，"
                        + "因为 active() 的判据依赖这个精确值）");
    }

    /** 关闭时即便刻度很激进，也不得生效。 */
    @Test
    void 关闭时忽略刻度() {
        MixedScript aggressive = new MixedScript(false, 85, 3);
        assertFalse(aggressive.active(), "开关关着时刻度再激进也不得生效");
    }

    /**
     * 百分比到系数的换算必须精确。
     *
     * <p>{@code 100 / 100f} 恰好等于 {@code 1.0f}，这是
     * {@link MixedScript#active()} 能用 {@code !=} 直接比较的前提。
     * 若某天有人改成 {@code 1f - (100 - p) / 100f}，浮点误差会让
     * "中性刻度"判不出来。
     */
    @Test
    void 百分比换算精确() {
        assertEquals(1f, new MixedScript(true, 100, 0).scale(), 0f);
        assertEquals(0.96f, new MixedScript(true, 96, 0).scale(), 1e-6f);
        assertEquals(0.85f, new MixedScript(true, 85, 0).scale(), 1e-6f);
    }

    /** 越界百分比必须夹紧，而不是原样保留。 */
    @Test
    void 缩放百分比被夹紧() {
        assertEquals(MixedScript.MAX_SCALE_PERCENT,
                new MixedScript(true, 999, 0).cjkScalePercent());
        assertEquals(MixedScript.MIN_SCALE_PERCENT,
                new MixedScript(true, -999, 0).cjkScalePercent());
        assertEquals(MixedScript.MIN_SCALE_PERCENT,
                new MixedScript(true, 0, 0).cjkScalePercent(),
                "0 低于下界，应夹到 85 而不是上界");
    }

    /** 基线位移同样夹紧到对称区间。 */
    @Test
    void 基线位移被夹紧() {
        assertEquals(MixedScript.MAX_BASELINE_SHIFT_PX,
                new MixedScript(true, 96, 999).baselineShiftPx());
        assertEquals(MixedScript.MIN_BASELINE_SHIFT_PX,
                new MixedScript(true, 96, -999).baselineShiftPx());
        assertEquals(0, new MixedScript(true, 96, 0).baselineShiftPx());
    }

    /**
     * 夹取之后，原本越界的值必须变成合法值。
     *
     * <p>这条防的是"夹取写反了符号"或"上下界交换"这类错法：
     * 只断言 {@code == MAX} 在把 {@code Math.max} 写成 {@code Math.min} 时
     * 也可能通过，因此这里同时验证夹取结果的**方向**。
     */
    @Test
    void 夹取方向正确() {
        // 上界夹紧后应 >= 原默认值，下界夹紧后应 <= 原默认值
        assertTrue(new MixedScript(true, 999, 0).cjkScalePercent()
                        >= MixedScript.DEFAULT_SCALE_PERCENT,
                "上界夹紧应向大走");
        assertTrue(new MixedScript(true, 0, 0).cjkScalePercent()
                        <= MixedScript.DEFAULT_SCALE_PERCENT,
                "下界夹紧应向小走");
        assertEquals(MixedScript.MIN_SCALE_PERCENT,
                new MixedScript(true, 0, 0).cjkScalePercent(),
                "0 必须夹到下界 85，而不是保持 0");
    }

    /** with* 方法必须各改一项、其余不变。 */
    @Test
    void with方法只改一项() {
        MixedScript base = new MixedScript(true, 96, 1);

        MixedScript noEnable = base.withEnabled(false);
        assertFalse(noEnable.enabled());
        assertEquals(base.cjkScalePercent(), noEnable.cjkScalePercent());
        assertEquals(base.baselineShiftPx(), noEnable.baselineShiftPx());

        MixedScript rescaled = base.withCjkScalePercent(90);
        assertTrue(rescaled.enabled());
        assertEquals(90, rescaled.cjkScalePercent());
        assertEquals(base.baselineShiftPx(), rescaled.baselineShiftPx());

        MixedScript rebased = base.withBaselineShiftPx(-2);
        assertTrue(rebased.enabled());
        assertEquals(base.cjkScalePercent(), rebased.cjkScalePercent());
        assertEquals(-2, rebased.baselineShiftPx());
    }

    /** with* 也必须过夹取。 */
    @Test
    void with方法同样夹取() {
        MixedScript s = MixedScript.OFF.withCjkScalePercent(999).withBaselineShiftPx(-999);
        assertEquals(MixedScript.MAX_SCALE_PERCENT, s.cjkScalePercent());
        assertEquals(MixedScript.MIN_BASELINE_SHIFT_PX, s.baselineShiftPx());
    }

    /**
     * 基线校正的符号语义：正数上抬。
     *
     * <p>这个符号必须在这里定死。{@code LyricCanvas.drawTextLifted} 的语义是
     * {@code y - lift}，所以"正数上抬"直接对应正的 lift。
     * 若这里返回负数，绘制时中文会整体下沉，与"校正"的意图相反。
     */
    @Test
    void 基线正数表示上抬() {
        MixedScript up = new MixedScript(true, 96, 2);
        assertEquals(2f, up.baselineLift(), 1e-6f, "正数必须原样当作上抬量");
        MixedScript down = new MixedScript(true, 96, -2);
        assertEquals(-2f, down.baselineLift(), 1e-6f, "负数必须原样当作下压量");
        MixedScript none = new MixedScript(true, 96, 0);
        assertEquals(0f, none.baselineLift(), 1e-6f);
    }

    /**
     * 常量必须落在可用区间内。
     *
     * <p>区间本身是硬约束：缩放上限不能超过 100（超过就是在放大中文，
     * 那不再是校正），下限不能低到 80 以下（9px 缩到 7px 以下会糊成一团）。
     */
    @Test
    void 常量区间合理() {
        assertTrue(MixedScript.MAX_SCALE_PERCENT <= 100,
                "缩放上限不应超过 100，否则是在放大中文而非校正");
        assertTrue(MixedScript.MIN_SCALE_PERCENT >= 80,
                "缩放下限不应低于 80，否则 9px 字形糊成一团");
        assertTrue(MixedScript.DEFAULT_SCALE_PERCENT
                        >= MixedScript.MIN_SCALE_PERCENT
                        && MixedScript.DEFAULT_SCALE_PERCENT
                        <= MixedScript.MAX_SCALE_PERCENT,
                "默认值必须落在区间内");
        assertTrue(MixedScript.DEFAULT_BASELINE_SHIFT_PX
                        >= MixedScript.MIN_BASELINE_SHIFT_PX
                        && MixedScript.DEFAULT_BASELINE_SHIFT_PX
                        <= MixedScript.MAX_BASELINE_SHIFT_PX);
        assertNotNull(MixedScript.OFF);
    }
}
