/*
 * 派生自 Lyricon (https://github.com/tomakino/lyricon)
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * 上游声明其移植自 applemusic-like-lyrics (AGPL-3.0)
 *
 * 本项目修改：改为 Java 实现；移除缩放与光晕改为原位提亮；按本项目逐字数据形态调整分组边界。
 */
package com.linklyric.common.hud.effect;

/**
 * 强调特效：够长的词组被唱到时上浮、向两侧展开，并泛起辉光。
 *
 * <p>不只做缩放：缩放会把字距一起放大，与「强调这一句」的语义无关，
 * 反而让整行抖动。位移只作用在字形上，字距保持不变。
 *
 * <p>组内逐字错峰：首字立即开始，其余按离组中心的距离依次跟上，
 * 于是观感是「一组字被逐个点亮」而不是整块齐动。
 *
 * <p>越靠组边缘的字位移越大，中心几乎不动，整组像是在中间被顶起来。
 */
public final class EmphasizeGlowEffect implements LyricUnitEffect {

    /** 提亮强度相对 blur 的比例。 */
    private static final float BLOOM_SCALE = 1.6f;

    /**
     * 光晕半径上限，相对字号。
     *
     * <p>与上游的 {@code GLOW_RADIUS_MAX} 一致（0.3 em）。
     * 这条上限是必需的：SDF 图集每边只留了 2 个设计像素的 padding，
     * 光晕超过这个范围会采到相邻字形，表现为字周围出现别人的笔画。
     * 9px 字号下 0.3 em 约 2.7px，仍在留白之内。
     */
    private static final float GLOW_RADIUS_MAX = 0.3f;

    /** 光晕半径相对 blur 的比例。与上游一致。 */
    private static final float GLOW_RADIUS_SCALE = 0.3f;

    /** 低于该强度的光晕肉眼不可见，跳过绘制。 */
    private static final float GLOW_ALPHA_THRESHOLD = 0.01f;

    /** 上浮系数，相对字号。 */
    private static final float LIFT_FACTOR = 0.025f;

    /**
     * 横向展开系数，相对字号。
     *
     * <p>比上浮略小：横向位移会改变字与字的间距，幅度过大会让整行
     * 看起来在抖动；纵向位移不影响相邻字，可以稍大。
     */
    private static final float SPREAD_FACTOR = 0.02f;

    /** 动画尚未开始时的第一帧进度，与上游的 fill:both 行为一致。 */
    private static final float FIRST_FRAME_X = 1f / 32f;

    @Override
    public String name() {
        return "emphasize_glow";
    }

    @Override
    public void apply(EffectUnit unit, EffectParam param, UnitTransform out) {
        EmphasisGroup group = unit.emphasisGroup;
        if (group == null || group.isEmpty()) {
            return;
        }

        long elapsed = param.positionMs - group.charStartMs(unit.emphasisCharIndex);
        float x;
        if (elapsed <= 0L) {
            x = FIRST_FRAME_X;
        } else if (elapsed >= group.durationMs) {
            x = 1f;
        } else {
            x = elapsed / (float) group.durationMs;
        }
        float eased = EmpEasing.at(x) * param.strength;

        // 仍不做缩放：缩放会把字距一起放大，
        // 与「强调这一句」的语义无关，反而让整行抖动。

        // 横向展开恢复。
        //
        // 上游依赖矢量字形，而本项目过去是 9px 位图字形、坐标必须取整，
        // 约 1px 的位移会被整成 0 或 1，动画中途整根笔画瞬移一格，
        // 观感就是字在乱跳，因此曾经整段移除。
        //
        // 改用 SDF 后字形在任意小数位置都锐利，绘制路径不再取整，
        // 位移可以真正连续推进，这点幅度才有意义。
        out.dx += eased * SPREAD_FACTOR * group.amount * param.textSize;

        out.dy += eased * LIFT_FACTOR * group.amount * param.textSize;

        // 提亮：像素不动，只把颜色推向白色。边缘仍是图集里的抗锯齿值，
        // 因此观感是字被点亮，而不是糊成一团光。
        out.bloom = Math.min(1f, out.bloom + eased * group.blur * BLOOM_SCALE);

        // 外发光：半径恒定、强度随动画推进。
        //
        // 上游用的是 Canvas 的 shadowLayer，那是把字形整体做一次高斯模糊，
        // 逐字时是主要的绘制开销。SDF 只需要在同一个片元里多算一次
        // 距离衰减，没有额外批次，也没有模糊采样。
        float glowLevel = eased * group.blur;
        if (glowLevel > GLOW_ALPHA_THRESHOLD) {
            out.glowRadius = Math.max(out.glowRadius,
                    Math.min(GLOW_RADIUS_MAX, group.blur * GLOW_RADIUS_SCALE)
                            * param.textSize);
            out.glowAlpha = Math.min(1f, out.glowAlpha + glowLevel);
        }
    }
}
