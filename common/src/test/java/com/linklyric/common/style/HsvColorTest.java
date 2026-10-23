package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 颜色转换与圆形调色盘的几何。
 *
 * <p>调色盘的错法肉眼几乎看不出来：色相反了、饱和度反了、圆心偏一格，
 * 看上去都还是「一个彩色圆盘」。只能靠数算。
 */
class HsvColorTest {

    private static final float EPS = 0.01f;

    /** 纯色的 HSV 应精确落在对应点上。 */
    @Test
    void 纯色转换正确() {
        float[] red = HsvColor.toHsv(0xFFFF0000);
        assertEquals(0f, red[0], EPS, "红色的色相应为 0");
        assertEquals(1f, red[1], EPS, "红色的饱和度为 1");
        assertEquals(1f, red[2], EPS, "红色的明度为 1");

        float[] green = HsvColor.toHsv(0xFF00FF00);
        assertEquals(120f, green[0], EPS, "绿色色相应为 120");

        float[] blue = HsvColor.toHsv(0xFF0000FF);
        assertEquals(240f, blue[0], EPS, "蓝色色相应为 240");
    }

    /** 灰色的饱和度必须为 0，否则盘上取色会偏向某个色相。 */
    @Test
    void 灰色饱和度为0() {
        float[] gray = HsvColor.toHsv(0xFF808080);
        assertEquals(0f, gray[1], EPS);
    }

    /** 往返转换不能漂移，否则在盘上点一下再读值会变。 */
    @Test
    void 往返转换稳定() {
        int[] samples = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
                0xFF7FD4FF, 0xFF123456, 0xFFFFFFFF, 0xFF000000};
        for (int argb : samples) {
            float[] hsv = HsvColor.toHsv(argb);
            int back = HsvColor.toArgb(hsv[0], hsv[1], hsv[2], (argb >>> 24) & 0xFF);
            // 允许每通道 1 的舍入误差
            for (int shift = 0; shift <= 16; shift += 8) {
                int a = (argb >>> shift) & 0xFF;
                int b = (back >>> shift) & 0xFF;
                assertTrue(Math.abs(a - b) <= 1,
                        "往返漂移过大: " + HsvColor.format(argb) + " -> " + HsvColor.format(back));
            }
        }
    }

    /** 透明度必须被保留，改色相不该把不透明度清零。 */
    @Test
    void 保留透明度() {
        int argb = HsvColor.toArgb(200f, 0.5f, 0.8f, 0x66);
        assertEquals(0x66, (argb >>> 24) & 0xFF);
    }

    /** 六位写法的解析沿用原透明度。 */
    @Test
    void 六位写法沿用原透明度() {
        assertEquals(0x80FF0000, HsvColor.parse("#FF0000", 0x80000000));
    }

    /** 八位写法直接用给定透明度。 */
    @Test
    void 八位写法直接采用() {
        assertEquals(0x12345678, HsvColor.parse("#12345678", 0xFFFFFFFF));
    }

    /** 非法输入必须回退，不能让输入框把颜色打成透明。 */
    @Test
    void 非法输入回退() {
        int fallback = 0xFF7FD4FF;
        assertEquals(fallback, HsvColor.parse("zzz", fallback));
        assertEquals(fallback, HsvColor.parse("#12345", fallback));
        assertEquals(fallback, HsvColor.parse(null, fallback));
    }
}
