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
 * 强调特效：够长的词组被唱到时整体放大、上浮、向两侧展开，并泛起辉光。
 *
 * <p>组内逐字错峰：首字立即开始，其余按离组中心的距离依次跟上，
 * 于是观感是「一组字被逐个点亮」而不是整块齐动。
 *
 * <p>越靠组边缘的字位移越大，中心几乎不动，整组像是在中间被顶起来。
 */
public final class EmphasizeGlowEffect implements LyricUnitEffect {

    /** 提亮强度相对 blur 的比例。 */
    private static final float BLOOM_SCALE = 1.6f;

    /** 上浮系数，相对字号。 */
    private static final float LIFT_FACTOR = 0.025f;

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

        // 原版字体是位图图集，缩放会让字形按非整数倍采样而发糊，
        // 9px 的字尤其明显。因此这里不做缩放，改用上浮与提亮表达强调。

        // 不再做左右展开。
        //
        // 上游的横向展开依赖矢量字形：位移由平滑值驱动，渲染器按浮点
        // 定位，1px 级差异在几十像素的字号上只是细微的疏密变化。
        // 本项目是 9px 位图字形，坐标必须取整，位移幅度也只有约 1px
        // （0.03 × amount 1.92 × spread 2 × 9px），
        // 于是取整结果在动画中途整根笔画瞬移一格，观感就是字在乱跳；
        // 而这点幅度又不足以让人看出「展开」。
        // 付出 1px 抖动、换不到任何可感知效果，故整段移除。
        out.dy += eased * LIFT_FACTOR * group.amount * param.textSize;

        // 提亮：像素不动，只把颜色推向白色。边缘仍是图集里的抗锯齿值，
        // 因此观感是字被点亮，而不是糊成一团光。
        out.bloom = Math.min(1f, out.bloom + eased * group.blur * BLOOM_SCALE);
    }
}
