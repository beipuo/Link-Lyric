package com.linklyric.common.style;

/**
 * HSV 与 ARGB 的转换。
 *
 * <p>圆形调色盘用 HSV 而不是 RGB：色相沿环走、饱和度沿半径走，
 * 这与人的直觉一致。若用 RGB，盘上相邻的两点颜色并不相近，
 * 取色就成了碰运气。
 *
 * <p>纯计算、无副作用，因此放进 {@code :common} 以便单测覆盖。
 */
public final class HsvColor {

    private HsvColor() {
    }

    /**
     * 把 ARGB 拆成 HSV。
     *
     * @return {@code [h, s, v]}，h 在 {@code [0,360)}，s 与 v 在 {@code [0,1]}
     */
    public static float[] toHsv(int argb) {
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float h;
        if (delta == 0f) {
            h = 0f;
        } else if (max == r) {
            h = 60f * (((g - b) / delta) % 6f);
        } else if (max == g) {
            h = 60f * (((b - r) / delta) + 2f);
        } else {
            h = 60f * (((r - g) / delta) + 4f);
        }
        if (h < 0f) {
            h += 360f;
        }

        float s = max == 0f ? 0f : delta / max;
        return new float[]{h, s, max};
    }

    /** 把 HSV 与指定透明度合回 ARGB。 */
    public static int toArgb(float hue, float saturation, float value, int alpha) {
        float h = ((hue % 360f) + 360f) % 360f;
        float s = clamp01(saturation);
        float v = clamp01(value);

        float c = v * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = v - c;

        float r;
        float g;
        float b;
        if (h < 60f) {
            r = c; g = x; b = 0f;
        } else if (h < 120f) {
            r = x; g = c; b = 0f;
        } else if (h < 180f) {
            r = 0f; g = c; b = x;
        } else if (h < 240f) {
            r = 0f; g = x; b = c;
        } else if (h < 300f) {
            r = x; g = 0f; b = c;
        } else {
            r = c; g = 0f; b = x;
        }

        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return ((alpha & 0xFF) << 24) | (clamp8(ri) << 16) | (clamp8(gi) << 8) | clamp8(bi);
    }

    /**
     * 解析 {@code #AARRGGBB} 或 {@code #RRGGBB}。
     *
     * <p>六位写法沿用原透明度，这样玩家改色相不会意外把不透明度清零。
     *
     * @return 解析失败时返回 {@code fallback}
     */
    public static int parse(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6 && s.length() != 8) {
            return fallback;
        }
        try {
            long raw = Long.parseLong(s, 16);
            if (s.length() == 6) {
                return (fallback & 0xFF000000) | (int) raw;
            }
            return (int) raw;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 格式化为 {@code #AARRGGBB}，供界面显示。 */
    public static String format(int argb) {
        return String.format("#%08X", argb);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp8(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
