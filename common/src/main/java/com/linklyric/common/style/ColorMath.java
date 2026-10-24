package com.linklyric.common.style;

/**
 * ARGB 颜色插值。
 *
 * <p>用于歌词羽化带：交界处要的是「已唱色 → 未唱色」的真正颜色过渡。
 *
 * <p>用「递减 alpha 叠已唱色」来近似时，字形的 alpha 只作用于背景，
 * 阴影会跟着变淡，在深色背景上就是一道竖向暗痕，而不是颜色过渡。
 * 直接对 RGB 插值再用不透明色绘制，结果与背景无关。
 */
public final class ColorMath {

    private ColorMath() {
    }

    /**
     * 按比例混合两个 ARGB 颜色。
     *
     * @param t 0 返回 {@code from}，1 返回 {@code to}，中间线性插值
     */
    public static int lerp(int from, int to, float t) {
        float k = clamp01(t);
        int a = mix((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, k);
        int r = mix((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, k);
        int g = mix((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, k);
        int b = mix(from & 0xFF, to & 0xFF, k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }
}
