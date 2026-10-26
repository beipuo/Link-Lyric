package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样式构造器必须夹住所有数值字段。
 *
 * <h2>为什么这件事不能靠 with* 方法</h2>
 *
 * <p>{@code LayoutConfig.read()} 是从配置文件恢复样式的唯一入口，
 * 它直接调 21 个参数的构造器，一次把全部字段填满——**不经过任何 with* 方法**。
 * 因此只要构造器少夹一项，手改配置文件就能把越界值直接送进渲染层。
 *
 * <p>已经踩过：{@code lineGapPx}、{@code strokePx}、{@code strokeAlphaPercent}
 * 三项只在 with* 里夹取，构造器没夹。把 {@code lineGap} 改成 999 会让每行
 * 相隔近千像素、整屏只剩一行；描边宽度 999 会让笔画糊成一团；
 * 描边不透明度 999% 则会经 {@code strokeArgb()} 乘以 alpha 后溢出。
 *
 * <p>这类错在正常使用中永远不会出现——界面滑条本身就有上下限——
 * 只会在玩家手改配置、或旧版本配置文件带到新版本时冒出来。
 */
class StyleRangeTest {

    /** 构造器必须把三个描边/行距字段夹到合法区间。 */
    @Test
    void 构造器夹住行距与描边范围() {
        LyricStyleConfig c = build(999, 999, 999);
        assertEquals(LyricStyleConfig.MAX_LINE_GAP, c.lineGapPx(), "行距应夹到上限");
        assertEquals(LyricStyleConfig.MAX_STROKE_PX, c.strokePx(), "描边宽度应夹到上限");
        assertEquals(LyricStyleConfig.MAX_STROKE_ALPHA, c.strokeAlphaPercent(),
                "描边不透明度应夹到上限");

        LyricStyleConfig low = build(-999, -999, -999);
        assertEquals(LyricStyleConfig.MIN_LINE_GAP, low.lineGapPx(), "行距应夹到下限");
        assertEquals(LyricStyleConfig.MIN_STROKE_PX, low.strokePx(), "描边宽度应夹到下限");
        assertEquals(LyricStyleConfig.MIN_STROKE_ALPHA, low.strokeAlphaPercent(),
                "描边不透明度应夹到下限");
    }

    /**
     * 描边不透明度越界不得让 alpha 溢出。
     *
     * <p>{@link LyricStyleConfig#strokeArgb()} 把百分比乘进颜色 alpha。
     * 百分比若是 999，乘出来是 254745，左移 24 位后会把高位写进 alpha 通道，
     * 得到 0xF3 这种既非全透明也非全不透明的怪值。
     */
    @Test
    void 描边透明度越界不溢出() {
        int argb = build(0, 1, 999).strokeArgb();
        int alpha = (argb >>> 24) & 0xFF;
        assertTrue(alpha >= 0 && alpha <= 255,
                "alpha 应在 0..255，实际=" + alpha + "，argb=0x" + Integer.toHexString(argb));
        assertEquals(255, alpha, "100% 不透明度应得到 255");
    }

    /** 其余字段的夹取行为一并钉住，防止以后新增字段时忘记。 */
    @Test
    void 构造器夹住其余数值范围() {
        LyricStyleConfig c = new LyricStyleConfig(
                0, 0, 0,
                99_999, 99_999, 99f, 99f,
                true, null, null,
                true, 999, null, 99f, null, 999, true,
                LyricStyleConfig.DEFAULT_LINE_GAP,
                false, LyricStyleConfig.DEFAULT_STROKE_PX,
                0, LyricStyleConfig.DEFAULT_STROKE_ALPHA, false,
                // 末位排布形态传 null，一并验证兜底。
                null,
                // 混排校正也传 null，一并验证兜底成关闭态。
                null);
        assertEquals(LyricStyleConfig.MAX_OFFSET_MS, c.offsetMs(), "时间偏移应夹住");
        assertEquals(LyricStyleConfig.MAX_TRANSITION_MS, c.lineTransitionMs(), "切换时长应夹住");
        assertEquals(LyricStyleConfig.MAX_LINE_SCALE, c.lineScale(), 1e-6f, "字号缩放应夹住");
        assertEquals(LyricStyleConfig.MAX_WIDTH_RATIO, c.maxWidthRatio(), 1e-6f, "宽度比例应夹住");
        assertEquals(LyricStyleConfig.MAX_LINES, c.lineCount(), "行数应夹住");
        assertEquals(1f, c.opacity(), 1e-6f, "不透明度上限为 1");
        assertEquals(LyricStyleConfig.MAX_WEIGHT_PERCENT, c.weightPercent(), "加粗应夹住");
        // 枚举为空时回退默认，不得留下 null
        assertEquals(LyricStyleConfig.Weight.REGULAR, c.weight());
        assertEquals(LyricStyleConfig.Align.CENTER, c.align());
        assertEquals(LyricStyleConfig.InterludeStyle.TEXT, c.interlude());
        assertEquals(LyricStyleConfig.LiftCurve.COSINE, c.liftCurve());
    }

    /** with* 与构造器必须给出同一个结果，否则两条路径会分叉。 */
    @Test
    void with方法与构造器结果一致() {
        LyricStyleConfig direct = build(999, 999, 999);
        LyricStyleConfig viaWith = LyricStyleConfig.DEFAULT
                .withLineGapPx(999)
                .withStrokePx(999)
                .withStrokeAlphaPercent(999);
        assertEquals(direct.lineGapPx(), viaWith.lineGapPx());
        assertEquals(direct.strokePx(), viaWith.strokePx());
        assertEquals(direct.strokeAlphaPercent(), viaWith.strokeAlphaPercent());
    }

    private static LyricStyleConfig build(int lineGap, int strokePx, int strokeAlpha) {
        return new LyricStyleConfig(
                0xFF7FD4FF, 0xFFD8D8D8, 0xFF9A9A9A, 0, 700, 1f, 0.72f,
                true, LyricStyleConfig.Weight.REGULAR, LyricStyleConfig.Align.CENTER,
                true, 2, LyricStyleConfig.InterludeStyle.TEXT, 1f,
                LyricStyleConfig.LiftCurve.COSINE, 0, true,
                lineGap, true, strokePx, 0xFF000000, strokeAlpha, false,
                // 末位排布形态：这里用默认横排，让本用例只测夹取本身。
                com.linklyric.common.hud.LayoutMode.LINEAR,
                com.linklyric.common.style.MixedScript.OFF);
    }
}
