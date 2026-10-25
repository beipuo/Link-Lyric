/*
 * 派生自 Lyricon (https://github.com/tomakino/lyricon)
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * 上游声明其移植自 applemusic-like-lyrics (AGPL-3.0)
 *
 * 本项目修改：改为 Java record 风格；字符错峰位置改为按索引计算。
 */
package com.linklyric.common.hud.effect;

/**
 * 强调动画的预计算参数。
 *
 * <p>一组连续的字会作为一个整体做强调：先整体略微放大并上浮，同时组内各字
 * 按离组中心的距离向两侧展开，蓝色演到白色辉光。行末那一组幅度更大，
 * 让句子收尾有一个落点。
 *
 * <p>参数按组时长推导：时长越长幅度越大，但有上限。组时长下限 1 秒，
 * 短于这个时长的词不足以承载动画，直接不参与。
 */
public final class EmphasisGroup {

    /** 组动画时长，毫秒，至少 1000。 */
    final long durationMs;

    /** 组开始时间，毫秒。 */
    final long startMs;

    /** 放大系数上限 1.2。 */
    final float amount;

    /** 辉光强度系数上限 0.8。 */
    final float blur;

    /** 组内参与动画的字符数，决定错峰跨度。 */
    final int charCount;

    EmphasisGroup(long durationMs, long startMs, float amount, float blur, int charCount) {
        this.durationMs = durationMs;
        this.startMs = startMs;
        this.amount = amount;
        this.blur = blur;
        this.charCount = charCount;
    }

    /**
     * 组内第 {@code index} 个字的动画开始时间。
     *
     * <p>逐字错峰跨度是组时长的 {@code 1/2.5}，首字立即开始。
     */
    long charStartMs(int index) {
        if (charCount <= 0) {
            return startMs;
        }
        long stagger = (long) (durationMs / 2.5f * index / charCount);
        return startMs + stagger;
    }

    /** 组是空的：不产生任何变换。 */
    boolean isEmpty() {
        return charCount <= 0 || durationMs <= 0L;
    }
}
