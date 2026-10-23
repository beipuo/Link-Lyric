package com.linklyric.common.style;

/**
 * 方形取色盘的几何。
 *
 * <p>横轴饱和度、纵轴明度（上明下暗），色相由旁边的色相条决定。
 * 这是 Photoshop / GIMP / 各类专业软件里最常见的取色盘布局，
 * 与圆盘相比有个实质优点：**明度是独立的一维**，
 * 想调暗时纵向拉到底即可，不必同时追着半径跑。
 *
 * <p>纯计算放在 {@code :common}，平台侧只负责画渐变与响应鼠标。
 */
public final class ColorSquare {

    private ColorSquare() {
    }

    /**
     * 方形区域某点的颜色。
     *
     * @param px       相对区域左上角的 x
     * @param py       相对区域左上角的 y
     * @param width    区域宽
     * @param height   区域高
     * @param hue      当前色相（0..360）
     * @param alpha    保留的透明度
     * @param fallback 点在区域外时的返回值
     */
    public static int pick(int px, int py, int width, int height,
                           float hue, int alpha, int fallback) {
        if (px < 0 || py < 0 || px >= width || py >= height || width <= 0 || height <= 0) {
            return fallback;
        }
        // 取格心，避免边界像素被算到半格外。
        float saturation = (px + 0.5f) / width;
        // 顶部明度为 1，底部为 0。
        float value = 1f - (py + 0.5f) / height;
        return HsvColor.toArgb(hue, saturation, value, alpha);
    }

    /**
     * 由颜色反推色相、饱和度、明度，用于放置指示框。
     *
     * @return {@code [hue, saturation, value]}
     */
    public static float[] split(int argb) {
        return HsvColor.toHsv(argb);
    }

    /**
     * 指示框左上角，相对区域左上角。
     *
     * @return {@code [x, y]}
     */
    public static int[] marker(int argb, int width, int height) {
        float[] hsv = split(argb);
        int x = Math.round(hsv[1] * width);
        int y = Math.round((1f - hsv[2]) * height);
        // 夹进区域内，避免在边缘时指示框跑出去。
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        return new int[]{x, y};
    }

    /** 色相条的 x 坐标反推色相。 */
    public static float hueAt(int px, int width) {
        if (width <= 0) {
            return 0f;
        }
        float ratio = Math.max(0f, Math.min(1f, (px + 0.5f) / width));
        return ratio * 360f;
    }

    /** 色相条上色相位对应的 x 坐标。 */
    public static int hueMarker(float hue, int width) {
        if (width <= 0) {
            return 0;
        }
        float normalized = ((hue % 360f) + 360f) % 360f / 360f;
        int x = Math.round(normalized * width);
        return Math.max(0, Math.min(width - 1, x));
    }
}
