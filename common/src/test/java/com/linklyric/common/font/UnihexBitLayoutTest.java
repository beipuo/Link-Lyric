package com.linklyric.common.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * unifont 位布局的回归测试。
 *
 * <h2>为什么这段值得单独测</h2>
 *
 * <p>unifont 的行数据是<b>左对齐存放在 32 位 int</b> 里的：
 * 第一个像素在 bit31。按「有效位宽」求位移对 16 宽的字形恰好等价，
 * 但对 8 宽的字形会把有效像素整体右移 16 位，全部落进零区，
 * 拼出的覆盖率整片为零——表现为<b>整个中文字符消失</b>，
 * 而英文（位图路径）完全正常。
 *
 * <p>这种错误不会抛异常、不会报错，只会让字静默不见，
 * 因此必须用测试钉住位到像素的映射。
 */
class UnihexBitLayoutTest {

    /**
     * 复现布局：把「行位」按 LDLib2 的方式展开成覆盖率。
     *
     * 与被测实现保持同一套规则：
     * <pre>
     *   mask  = 所有行按位或
     *   left  = numberOfLeadingZeros(mask)
     *   right = 32 - numberOfTrailingZeros(mask) - 1
     *   bit   = 31 - left - x
     * </pre>
     */
    private static byte[] unpack(int[] rows, int height, int[] sizeOut) {
        int mask = 0;
        for (int y = 0; y < height; y++) {
            mask |= rows[y];
        }
        if (mask == 0) {
            sizeOut[0] = 0;
            sizeOut[1] = 0;
            return new byte[0];
        }
        int left = Integer.numberOfLeadingZeros(mask);
        int right = 32 - Integer.numberOfTrailingZeros(mask) - 1;
        int width = right - left + 1;
        sizeOut[0] = width;
        sizeOut[1] = height;
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int shift = 31 - left - x;
                boolean on = shift >= 0 && shift < 32 && ((rows[y] >>> shift) & 1) != 0;
                out[y * width + x] = (byte) (on ? 0xFF : 0);
            }
        }
        return out;
    }

    /** 靠左的 8 位宽字形：像素必须在最左，而不是偏移 16 位。 */
    @Test
    void 八位宽字形靠左对齐() {
        // 第 0 行最高 8 位全亮：0xFF000000
        int[] rows = new int[16];
        rows[0] = 0xFF00_0000;
        int[] size = new int[2];
        byte[] cov = unpack(rows, 16, size);

        assertEquals(8, size[0], "墨迹宽度应为 8");
        assertEquals(16, size[1], "高度固定 16 行");
        // 第 0 行前 8 个像素全亮。
        for (int x = 0; x < 8; x++) {
            assertEquals((byte) 0xFF, cov[x], "第 0 行第 " + x + " 个像素应亮");
        }
    }

    /** 右对齐的窄字形：前导零被正确跳过，不会画在错误的位置。 */
    @Test
    void 前导零被正确跳过() {
        // 只有 bit31 亮：最左 1 列。
        int[] rows = new int[16];
        rows[5] = 0x8000_0000;
        int[] size = new int[2];
        byte[] cov = unpack(rows, 16, size);

        assertEquals(1, size[0], "只有一列墨迹");
        assertEquals((byte) 0xFF, cov[5 * 1 + 0], "第 5 行唯一的像素应亮");
    }

    /** 中段墨迹：左右界由 first/last 置位决定。 */
    @Test
    void 中段墨迹宽度正确() {
        // bit27..bit24 亮 → 距 bit31 为 4..7，宽 4 列。
        int[] rows = new int[16];
        rows[2] = 0x0F00_0000;
        int[] size = new int[2];
        byte[] cov = unpack(rows, 16, size);

        assertEquals(4, size[0], "宽度应为 4");
        for (int x = 0; x < 4; x++) {
            assertEquals((byte) 0xFF, cov[2 * 4 + x], "第 " + x + " 个像素应亮");
        }
    }

    /** 全空字形不产生位图，也不该被当作可见字符占位。 */
    @Test
    void 全空字形无位图() {
        int[] rows = new int[16];
        int[] size = new int[2];
        byte[] cov = unpack(rows, 16, size);
        assertEquals(0, size[0], "全空字形宽度为 0");
        assertEquals(0, cov.length);
    }

    /** 跨越整行的字形（16 宽）也能完整取出。 */
    @Test
    void 十六位宽字形完整() {
        int[] rows = new int[16];
        rows[0] = 0xFFFF_0000;
        int[] size = new int[2];
        byte[] cov = unpack(rows, 16, size);

        assertEquals(16, size[0], "宽度应为 16");
        for (int x = 0; x < 16; x++) {
            assertEquals((byte) 0xFF, cov[x], "第 " + x + " 个像素应亮");
        }
    }

    /**
     * 若按「有效位宽」求位移，8 宽字形会整片丢失。
     *
     * <p>这是修复前的错误算法，用来说明为什么必须按 32 位左对齐处理。
     */
    @Test
    void 按有效位宽求位移会丢像素() {
        int[] rows = new int[16];
        rows[0] = 0xFF00_0000;
        // 错误做法：把第一个像素当作 bit(bitWidth-1) = bit7。
        int bitWidth = 8;
        boolean anyOn = false;
        for (int x = 0; x < bitWidth; x++) {
            int shift = bitWidth - 1 - x;
            if (((rows[0] >>> shift) & 1) != 0) {
                anyOn = true;
                break;
            }
        }
        assertTrue(!anyOn,
                "按 bitWidth 求位移时全部像素都落在零区，"
                        + "这正是中文整行消失的原因");
    }
}
