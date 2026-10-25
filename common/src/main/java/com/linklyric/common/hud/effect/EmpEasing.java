/*
 * 派生自 Lyricon (https://github.com/tomakino/lyricon)
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * 上游声明其移植自 applemusic-like-lyrics (AGPL-3.0)
 *
 * 本项目修改：抽出为独立类；查找表由 512 项保留。
 */
package com.linklyric.common.hud.effect;

import com.linklyric.common.style.CubicBezier;

/**
 * 强调动画的分段缓动。
 *
 * <p>前段取 {@code cubic-bezier(0.2, 0.4, 0.58, 1.0)}，后段取
 * {@code 1 - cubic-bezier(0.3, 0, 0.58, 1.0)}，中点 0.5 处分界。
 * 结果是先起后缓、尾段轻微回弹的双段曲线。
 *
 * <p>逐字特效每帧会被调用上百次，直接求解贝塞尔会成为 CPU 热路径，
 * 因此结果预计算为查找表，查表为 O(1) 并按索引线性插值。
 */
final class EmpEasing {

    private static final float MID = 0.5f;
    private static final int LUT_SIZE = 512;

    private static final float[] LUT = buildLut();

    private EmpEasing() {
    }

    private static float[] buildLut() {
        float[] table = new float[LUT_SIZE + 1];
        for (int i = 0; i <= LUT_SIZE; i++) {
            float x = i / (float) LUT_SIZE;
            table[i] = x < MID
                    ? CubicBezier.eval(x / MID, 0.2f, 0.4f, 0.58f, 1f)
                    : 1f - CubicBezier.eval((x - MID) / (1f - MID), 0.3f, 0f, 0.58f, 1f);
        }
        return table;
    }

    /**
     * 求值。
     *
     * @param x 进度，0..1；越界自动夹紧
     */
    static float at(float x) {
        if (x <= 0f) {
            return LUT[0];
        }
        if (x >= 1f) {
            return LUT[LUT_SIZE];
        }
        float pos = x * LUT_SIZE;
        int i = (int) pos;
        float frac = pos - i;
        return LUT[i] + (LUT[i + 1] - LUT[i]) * frac;
    }
}
