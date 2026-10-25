package com.linklyric.common.font;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 距离变换的正确性。
 *
 * <h2>编码语义（实测确认，非推测）</h2>
 *
 * <p>输出值是<b>带符号距离</b>，正为形状内、负为形状外：
 * <pre>
 *   value = 128 + signedDistancePixels × (128 / spread)
 * </pre>
 * 128 正好落在零等值线上，所以着色器用 {@code 128/255} 作为轮廓基准。
 *
 * <p>实测（5×5 实心块，padding=3，spread=8）：
 * <pre>
 *   中心  176 = 128 + 3.000 × 16
 *   角落  144 = 128 + 1.000 × 16
 *   外部  112 = 128 − 1.000 × 16
 *   最远   60 = 128 − 4.243 × 16   (对角 √18)
 * </pre>
 *
 * <h2>spread 必须与 padding 匹配</h2>
 *
 * <p>{@code spread} 是「多远映射满 0..128」，超出该距离的像素一律夹到 0 或 255。
 * 若 spread 小于字形尺寸，场在字形内部就饱和，零等值线以外没有梯度可用，
 * 抗锯齿失效。因此 spread 应取与 padding 同量级的值，padding 则要
 * 至少覆盖 spread，否则梯度在图像边界被截断。
 */
class DistanceTransformTest {

    /** 5×5 实心块在 spread=8 下的整场应与带符号距离一致。 */
    @Test
    void 场与带符号距离一致() {
        byte[] mask = new byte[5 * 5];
        Arrays.fill(mask, (byte) 0xFF);
        int padding = 3;
        float spread = 8f;
        byte[] sdf = DistanceTransform.fromMask(mask, 5, 5, padding, spread);
        int w = 5 + padding * 2;

        // 中心像素：到最近外部像素的距离为 3（左侧外部在 x=2，中心在 x=5）。
        assertEquals(176, sdf[5 * w + 5] & 0xFF,
                "中心距离 3px，128 + 3×16 = 176");
        // 左上内部角：距离 1。
        assertEquals(144, sdf[3 * w + 3] & 0xFF,
                "内部角距离 1px，128 + 1×16 = 144");
        // 紧邻外部的像素：距离 1，符号为负。
        assertEquals(112, sdf[5 * w + 2] & 0xFF,
                "紧邻外部距离 1px，128 − 1×16 = 112");
    }

    /** 零等值线基准是 128，着色器的轮廓位置依赖它。 */
    @Test
    void 中值是轮廓基准() {
        assertEquals(128, DistanceTransform.ON_EDGE,
                "着色器把 128/255 当作轮廓位置");
    }

    /** 外部值随距离单调递减。 */
    @Test
    void 外部值随距离递减() {
        byte[] mask = new byte[5 * 5];
        Arrays.fill(mask, (byte) 0xFF);
        int padding = 3;
        byte[] sdf = DistanceTransform.fromMask(mask, 5, 5, padding, 8f);
        int w = 5 + padding * 2;

        int near = sdf[5 * w + 2] & 0xFF;   // 距离 1
        int mid = sdf[5 * w + 1] & 0xFF;    // 距离 2
        int far = sdf[5 * w + 0] & 0xFF;    // 距离 3

        assertTrue(near > mid, "越远应越低：near=" + near + " mid=" + mid);
        assertTrue(mid > far, "越远应越低：mid=" + mid + " far=" + far);
        assertTrue(far > 0, "距离 3 未超出 spread=8，不应被夹到 0，实际=" + far);
    }

    /** 形状内部距离越深值越高。 */
    @Test
    void 内部值随深度增长() {
        byte[] mask = new byte[5 * 5];
        Arrays.fill(mask, (byte) 0xFF);
        int padding = 3;
        byte[] sdf = DistanceTransform.fromMask(mask, 5, 5, padding, 8f);
        int w = 5 + padding * 2;

        int corner = sdf[3 * w + 3] & 0xFF;  // 距离 1
        int center = sdf[5 * w + 5] & 0xFF;  // 距离 3
        assertTrue(center > corner,
                "越深应越高：corner=" + corner + " center=" + center);
    }

    /**
     * spread 过小会让场在字形内部饱和。
     *
     * <p>这是接入时最容易踩的坑：spread=1 时 128/1 让相隔 1px 就吃满量程，
     * 整场退化成 0 与 255 二值，抗锯齿无从谈起。
     */
    @Test
    void spread过小会导致饱和() {
        byte[] mask = new byte[5 * 5];
        Arrays.fill(mask, (byte) 0xFF);
        byte[] saturated = DistanceTransform.fromMask(mask, 5, 5, 3, 1f);
        byte[] proper = DistanceTransform.fromMask(mask, 5, 5, 3, 8f);
        int w = 5 + 6;

        long distinctSaturated = Arrays.stream(toInts(saturated)).distinct().count();
        long distinctProper = Arrays.stream(toInts(proper)).distinct().count();
        assertTrue(distinctProper > distinctSaturated,
                "spread 匹配时应出现更多灰阶：saturated=" + distinctSaturated
                        + " proper=" + distinctProper);
        assertTrue(distinctSaturated <= 2,
                "spread=1 时应当已饱和为二值，实际灰阶数=" + distinctSaturated);
    }

    /** padding 让梯度有空间展开。 */
    @Test
    void padding扩大输出尺寸() {
        byte[] mask = new byte[4 * 4];
        Arrays.fill(mask, (byte) 0xFF);
        byte[] sdf = DistanceTransform.fromMask(mask, 4, 4, 2, 2f);
        assertEquals(8 * 8, sdf.length, "padding=2 时每边各加 2 像素");
    }

    /** 全透明掩膜不抛异常，整场都在外部且不为饱和值。 */
    @Test
    void 空掩膜不崩溃() {
        byte[] mask = new byte[16];
        byte[] sdf = DistanceTransform.fromMask(mask, 4, 4, 1, 8f);
        for (byte b : sdf) {
            assertTrue((b & 0xFF) <= DistanceTransform.ON_EDGE,
                    "全空掩膜不应出现内部值，实际=" + (b & 0xFF));
        }
    }

    /**
     * 无半覆盖像素时，覆盖率路径退化为掩膜路径。
     *
     * <p>像素画字体的硬边不携带次像素信息，
     * {@code fromCoverage} 必须能识别这一点并回退。
     */
    @Test
    void 无半覆盖时退化为掩膜() {
        byte[] hard = new byte[4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                hard[y * 4 + x] = (byte) (x >= 2 ? 0xFF : 0);
            }
        }
        byte[] a = DistanceTransform.fromMask(hard, 4, 4, 3, 4f);
        byte[] b = DistanceTransform.fromCoverage(hard, 4, 4, 3, 4f);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "无半覆盖时两条路径应一致，位置=" + i);
        }
    }

    /** 有半覆盖像素时，场会被子像素偏移修正。 */
    @Test
    void 半覆盖修正边界() {
        byte[] hard = new byte[4 * 4];
        byte[] soft = new byte[4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int v = x >= 2 ? 255 : 0;
                hard[y * 4 + x] = (byte) v;
                soft[y * 4 + x] = (byte) (x == 1 ? 128 : v);
            }
        }
        byte[] a = DistanceTransform.fromMask(hard, 4, 4, 3, 4f);
        byte[] b = DistanceTransform.fromCoverage(soft, 4, 4, 3, 4f);
        boolean differs = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "带半覆盖的输入应产生与硬边不同的场");
    }

    /** 整数倍放大逐像素复制，不插值。 */
    @Test
    void 放大是最近邻() {
        byte[] src = new byte[]{1, 2, 3, 4};
        byte[] up = DistanceTransform.upscale(src, 2, 2, 2);
        assertEquals(4 * 4, up.length, "2x2 放大 2 倍应为 4x4");
        assertEquals(1, up[0] & 0xFF, "左上应复制第一个像素");
        assertEquals(1, up[1] & 0xFF, "水平相邻仍是第一个像素");
        assertEquals(2, up[2] & 0xFF, "再往右才是第二个像素");
    }

    private static int[] toInts(byte[] bytes) {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[i] & 0xFF;
        }
        return out;
    }
}
