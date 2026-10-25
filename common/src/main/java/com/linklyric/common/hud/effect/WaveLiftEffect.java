/*
 * 派生自 Lyricon (https://github.com/tomakino/lyricon)
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * 上游声明其移植自 applemusic-like-lyrics (AGPL-3.0)
 *
 * 本项目修改：改为 Java 实现；浮位与脉冲时长参数保持与上游一致。
 */
package com.linklyric.common.hud.effect;

/**
 * 浮动上浮特效：唱到即停在浮位，另叠一次柔和的正弦脉冲。
 *
 * <p>唱过之后保持一个固定的上浮位，不回落到基线——回落会让已经唱完的字
 * 重新沉下去，与「已经唱过」这个语义相悖。脉冲在词开始前 400 毫秒就已经
 * 起跳，因此字被染到时正好在上升途中，而不是在染到之后才起步。
 *
 * <p>幅度都在字号的百分之几量级，是柔和的起伏而不是大幅跳动。
 */
public final class WaveLiftEffect implements LyricUnitEffect {

    /** 唱到后的固定浮位，相对字号。 */
    private static final float LIFT_EM = 0.05f;

    /** 脉冲提前量，毫秒。 */
    private static final long LEAD_MS = 400L;

    /** 脉冲时长相对单元时长的倍数。 */
    private static final float DURATION_SCALE = 1.4f;

    /** 脉冲时长下限，毫秒。 */
    private static final long MIN_PULSE_MS = 1000L;

    @Override
    public String name() {
        return "wave_lift";
    }

    @Override
    public void apply(EffectUnit unit, EffectParam param, UnitTransform out) {
        long elapsed = param.positionMs - unit.beginMs;

        // 唱到后保持浮位。
        if (elapsed >= 0L) {
            out.dy += param.textSize * LIFT_EM * param.strength;
        }

        // 正弦脉冲：提前量让它在染色到达时正处于上升段。
        long pulseMs = Math.max(MIN_PULSE_MS, unit.durationMs());
        long floatDuration = (long) (pulseMs * DURATION_SCALE);
        long floatTime = elapsed + LEAD_MS;
        if (floatTime >= 0L && floatTime <= floatDuration && floatDuration > 0L) {
            float x = floatTime / (float) floatDuration;
            out.dy += param.textSize * LIFT_EM * (float) Math.sin(x * Math.PI) * param.strength;
        }
    }
}
