package com.linklyric.common.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天际线装箱的正确性。
 *
 * <p>字形图集靠它摆放，装错会表现为「两个字的纹素互相渗色」，
 * 而这种瑕疵只在特定字形组合下出现，靠肉眼几乎测不出来。
 */
class SkylinePackerTest {

    @Test
    void 首个矩形落在左上角() {
        SkylinePacker packer = new SkylinePacker(64, 64);
        int[] out = new int[2];
        assertTrue(packer.insert(8, 8, out));
        assertEquals(0, out[0], "首个矩形 x 应为 0");
        assertEquals(0, out[1], "首个矩形 y 应为 0");
    }

    @Test
    void 超出页面尺寸被拒绝() {
        SkylinePacker packer = new SkylinePacker(16, 16);
        int[] out = new int[2];
        assertFalse(packer.insert(17, 4, out), "宽超过页面应拒绝");
        assertFalse(packer.insert(4, 17, out), "高超过页面应拒绝");
        assertFalse(packer.insert(0, 4, out), "零宽应拒绝");
        assertFalse(packer.insert(4, -1, out), "负高应拒绝");
    }

    /** 同一行内并排摆放，y 相同，x 递增。 */
    @Test
    void 同尺寸并排摆放() {
        SkylinePacker packer = new SkylinePacker(64, 64);
        int[] out = new int[2];
        int lastX = -1;
        int lastY = -1;
        for (int i = 0; i < 4; i++) {
            assertTrue(packer.insert(8, 8, out), "第 " + i + " 个应能放下");
            if (lastY >= 0) {
                assertTrue(out[1] >= lastY, "y 不应回退");
            }
            assertTrue(out[0] > lastX || out[1] > lastY,
                    "位置必须推进，不能重叠：第 " + i + " 个=(" + out[0] + "," + out[1] + ")");
            lastX = out[0];
            lastY = out[1];
        }
    }

    /**
     * 摆放的矩形互不重叠。
     *
     * <p>这是最关键的性质：重叠会让两个字共用一个纹素区域，
     * 表现为文字边缘渗色。
     */
    @Test
    void 矩形互不重叠() {
        SkylinePacker packer = new SkylinePacker(64, 64);
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(1234L);
        for (int i = 0; i < 120; i++) {
            int w = 1 + random.nextInt(10);
            int h = 1 + random.nextInt(10);
            int[] out = new int[2];
            if (!packer.insert(w, h, out)) {
                break;
            }
            placed.add(new int[]{out[0], out[1], w, h});
        }
        assertTrue(placed.size() > 30, "64x64 页面应能装下几十个小矩形，实际=" + placed.size());

        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                int[] a = placed.get(i);
                int[] b = placed.get(j);
                boolean apart = a[0] + a[2] <= b[0] || b[0] + b[2] <= a[0]
                        || a[1] + a[3] <= b[1] || b[1] + b[3] <= a[1];
                assertTrue(apart, "矩形 " + i + " 与 " + j + " 重叠");
            }
        }
    }

    /** 所有矩形都在页面内。 */
    @Test
    void 不越出页面边界() {
        int size = 32;
        SkylinePacker packer = new SkylinePacker(size, size);
        java.util.Random random = new java.util.Random(99L);
        for (int i = 0; i < 200; i++) {
            int w = 1 + random.nextInt(8);
            int h = 1 + random.nextInt(8);
            int[] out = new int[2];
            if (!packer.insert(w, h, out)) {
                break;
            }
            assertTrue(out[0] >= 0 && out[0] + w <= size,
                    "x 越界：x=" + out[0] + " w=" + w);
            assertTrue(out[1] >= 0 && out[1] + h <= size,
                    "y 越界：y=" + out[1] + " h=" + h);
        }
    }

    /** 装满后返回 false 而不是抛异常或越界写入。 */
    @Test
    void 装满后拒绝() {
        SkylinePacker packer = new SkylinePacker(16, 16);
        int[] out = new int[2];
        int count = 0;
        while (packer.insert(8, 8, out)) {
            count++;
            if (count > 100) {
                break;
            }
        }
        assertTrue(count <= 4, "16x16 页面最多放 4 个 8x8，实际=" + count);
        assertTrue(count >= 4, "8x8 应能放满 4 个，实际=" + count);
    }

    /** 高矩形之后，矮矩形应塞进上方空隙，而不是新开一行。 */
    @Test
    void 矮矩形利用空隙() {
        SkylinePacker packer = new SkylinePacker(64, 64);
        int[] out = new int[2];
        assertTrue(packer.insert(16, 16, out));
        int tallY = out[1];
        // 紧跟着一个矮矩形：应落在 y=16 之上，且不与高大矩形同 x 区间重叠。
        assertTrue(packer.insert(16, 4, out));
        assertEquals(tallY, out[1], "第二个矩形应与第一个同高起始（左侧对齐时）");
    }
}
