package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方形取色盘。
 *
 * <p>SV 平面最容易出的错是方向反了：横向从彩色到白色（不是反过来），
 * 纵向从亮到暗。方向一错，盘面看着仍然「像那么回事」，
 * 但拖到右下角会得到白色而不是黑色。只能靠数算。
 */
class ColorSquareTest {

    private static final float EPS = 0.02f;
    private static final int W = 100;
    private static final int H = 100;

    /** 左上角：低饱和、高明度，接近白。 */
    @Test
    void 左上角接近白色() {
        int c = ColorSquare.pick(0, 0, W, H, 200f, 0xFF, 0);
        float[] hsv = HsvColor.toHsv(c);
        assertTrue(hsv[1] < 0.05f, "左上角饱和度应接近 0，实际=" + hsv[1]);
        assertTrue(hsv[2] > 0.95f, "左上角明度应接近 1，实际=" + hsv[2]);
    }

    /** 右上角：高饱和、高明度，是纯色相。 */
    @Test
    void 右上角为纯色() {
        int c = ColorSquare.pick(W - 1, 0, W, H, 200f, 0xFF, 0);
        float[] hsv = HsvColor.toHsv(c);
        assertTrue(hsv[1] > 0.95f, "右上角饱和度应接近 1，实际=" + hsv[1]);
        assertTrue(hsv[2] > 0.95f, "右上角明度应接近 1，实际=" + hsv[2]);
    }

    /** 底部：明度必须接近 0，否则方盘等于不能用。 */
    @Test
    void 底部接近黑色() {
        int c = ColorSquare.pick(W / 2, H - 1, W, H, 200f, 0xFF, 0);
        float[] hsv = HsvColor.toHsv(c);
        assertTrue(hsv[2] < 0.05f, "底部明度应接近 0，实际=" + hsv[2]);
    }

    /** 右侧中段应是「彩色但不满亮」。 */
    @Test
    void 中段明度居中() {
        int c = ColorSquare.pick(W - 1, H / 2, W, H, 200f, 0xFF, 0);
        float[] hsv = HsvColor.toHsv(c);
        assertTrue(Math.abs(hsv[2] - 0.5f) < 0.05f, "纵向中点明度应约 0.5，实际=" + hsv[2]);
    }

    /** 区域外必须回退。 */
    @Test
    void 区域外回退() {
        int fallback = 0xFF123456;
        assertEquals(fallback, ColorSquare.pick(-1, 0, W, H, 0f, 0xFF, fallback));
        assertEquals(fallback, ColorSquare.pick(0, H, W, H, 0f, 0xFF, fallback));
        assertEquals(fallback, ColorSquare.pick(0, 0, 0, H, 0f, 0xFF, fallback));
    }

    /** 指示框必须与取色互逆：按指示框位置取色应回到原色。 */
    @Test
    void 指示框与取色互逆() {
        int[] samples = {0xFF0000FF, 0xFF7FD4FF, 0xFF336633, 0xFFAAAAAA};
        for (int argb : samples) {
            int[] m = ColorSquare.marker(argb, W, H);
            int back = ColorSquare.pick(m[0], m[1], W, H,
                    HsvColor.toHsv(argb)[0], (argb >>> 24) & 0xFF, 0);
            // 允许每通道 6 的误差：100 格离散化必然有量化损失。
            for (int shift = 0; shift <= 16; shift += 8) {
                int a = (argb >>> shift) & 0xFF;
                int b = (back >>> shift) & 0xFF;
                assertTrue(Math.abs(a - b) <= 6,
                        "取色与指示框不互逆: " + HsvColor.format(argb)
                                + " -> " + HsvColor.format(back));
            }
        }
    }

    /** 指示框不能跑出区域。 */
    @Test
    void 指示框不越界() {
        int[] white = ColorSquare.marker(0xFFFFFFFF, W, H);
        assertEquals(0, white[0], 1, "纯白饱和度 0，应在最左");
        assertEquals(0, white[1], 1, "纯白明度 1，应在最上");

        int[] black = ColorSquare.marker(0xFF000000, W, H);
        assertEquals(H - 1, black[1], 1, "纯黑应贴最下");

        for (int argb : new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF808080}) {
            int[] m = ColorSquare.marker(argb, W, H);
            assertTrue(m[0] >= 0 && m[0] < W, "x 越界: " + m[0]);
            assertTrue(m[1] >= 0 && m[1] < H, "y 越界: " + m[1]);
        }
    }

    /** 色相条取色与指示位置互逆。 */
    @Test
    void 色相条互逆() {
        int w = 96;
        for (float hue : new float[]{0f, 60f, 120f, 250f, 359f}) {
            int x = ColorSquare.hueMarker(hue, w);
            float back = ColorSquare.hueAt(x, w);
            float diff = Math.abs(back - hue);
            diff = Math.min(diff, 360f - diff);
            assertTrue(diff < 6f, "色相往返误差过大: " + hue + " -> " + back);
        }
    }

    /** 色相条两端不能越界。 */
    @Test
    void 色相条不越界() {
        assertEquals(0, ColorSquare.hueMarker(0f, 96));
        assertEquals(95, ColorSquare.hueMarker(359.9f, 96), "最大色相应贴右端内侧");
        assertEquals(0f, ColorSquare.hueAt(-10, 96), EPS, "负坐标夹到 0");
        // 夹紧发生在算比例之前，因此右端正好得到 360，而不是 360 减去半格。
        assertEquals(360f, ColorSquare.hueAt(999, 96), EPS, "超界夹到右端");
    }
}
