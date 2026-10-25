package com.linklyric.common.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 烘焙产出的可用留白（{@code glowSpace}）。
 *
 * <h2>为什么这条约束必须有测试</h2>
 *
 * <p>光晕按距离衰减，但距离场只在图集留白范围内有效。越过留白的纹理
 * 是 {@code CLAMP_TO_EDGE} 的边界像素，场值不变，衰减被硬生生截断——
 * 视觉上是给字描了一圈边，而不是散开。这个错误不会崩、不会报错，
 * 只在特定字号下露出来，靠肉眼极难定位。
 *
 * <p>因此这里钉住两件事：留白落在设计空间的实际大小，以及
 * {@code padding >= spread} 这条烘焙前提。
 */
class GlowSpaceTest {

    /** 位图分支：padding 8 / upscale 4，scale 1 → 2 个设计像素留白。 */
    @Test
    void 位图留白为两个设计像素() {
        byte[] coverage = new byte[8 * 8];
        // 中间画一格，保证有墨迹。
        coverage[3 * 8 + 3] = (byte) 0xFF;

        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7f, 6f,
                4, 8, 8f);

        assertEquals(2f, glyph.glowSpace(), 1e-5f,
                "padding 8 / upscale 4 / scale 1 应得到 2 设计像素");
    }

    /** unifont 分支：scale 0.5，padding 4 / upscale 2 → 1 个设计像素。 */
    @Test
    void unifont留白为一个设计像素() {
        byte[] coverage = new byte[8 * 16];
        coverage[8 * 8] = (byte) 0xFF;

        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 16, 0.5f, 16f, 5f,
                2, 4, 4f);

        // min(4*0.5/2, 4*0.5/2) = min(1, 1) = 1
        assertEquals(1f, glyph.glowSpace(), 1e-5f,
                "scale 0.5 / padding 4 / upscale 2 应得到 1 设计像素");
    }

    /**
     * {@code spread} 小于留白时，以 spread 为准。
     *
     * <p>spread 描述「多远映射满量程」。超出 spread 的留白里场值已经
     * 饱和到端点，不再有梯度可用，因此真正可用的距离受 spread 约束。
     */
    @Test
    void spread更小时以spread为准() {
        byte[] coverage = new byte[8 * 8];
        coverage[4 * 8 + 4] = (byte) 0xFF;

        // padding 16 / upscale 4 → 4 设计像素；spread 4 / upscale 4 → 1 设计像素。
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7f, 6f,
                4, 16, 4f);

        assertEquals(1f, glyph.glowSpace(), 1e-5f,
                "spread 只支撑 1 设计像素，留白再多也无效");
    }

    /**
     * 留白非负，且不超过四边形本身的外扩量。
     *
     * <p>光晕半径由这个值封顶。若它算出负数，绘制层会静默跳过光晕，
     * 看起来就是「特效完全没生效」。
     */
    @Test
    void 留白恒非负() {
        byte[] coverage = new byte[8 * 8];
        coverage[0] = (byte) 0xFF;
        RawGlyph glyph = SdfGlyphBaker.bake(coverage, 8, 8, 1f, 7f, 6f);
        assertTrue(glyph.glowSpace() >= 0f, "留白不应为负");
    }

    /**
     * 空白字形没有留白。
     *
     * <p>空格不占图集位置，也就没有可采样的距离场；
     * 给它光晕只会在不该出现的地方画出一圈白边。
     */
    @Test
    void 空白字形无留白() {
        assertEquals(0f, RawGlyph.space(4f).glowSpace(), 1e-5f);
    }
}
