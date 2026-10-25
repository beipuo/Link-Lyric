package com.linklyric.common.hud;

/**
 * 多行歌词的纵向堆叠几何。
 *
 * <p>布局是「整块居中」：所有行按总高算出一个统一的顶点，
 * 再逐行往下排。主行基线因此不随副行是否存在而浮动一行高，
 * 同一句歌词在有、无下一句之间不会跳动；行数变化时块心不动，
 * 视觉上是整体撑开而不是位移。
 *
 * <p>纯计算放在这里，是为了能直接验证「块居中」这条性质——
 * 它在界面里几乎看不出来，只能靠数算。
 */
public final class LineLayout {

    private LineLayout() {
    }

    /**
     * 一整块的纵向排布结果。
     *
     * @param top      第一行顶部
     * @param height   整块高度
     * @param baseline 正在演唱那行的顶部
     */
    public record Block(int top, int height, int baseline) {
    }

    /**
     * 一行的高度。
     *
     * <p>返回浮点而非取整：次行按 {@code 0.8} 缩放后是 7.2 像素，
     * 取整成 7 会少算 0.2，逐行累加后块高偏移，块心居中就偏了；
     * 向上取整成 8 又多算 0.8，两行歌词看起来比实际更松散。
     * 行高只在最终落位时取整一次，中间过程保持精确。
     */
    public static float heightOf(int index, int current, int lineHeight, float scaleRatio) {
        return index == current
                ? lineHeight
                : Math.max(1f, lineHeight * scaleRatio);
    }

    /**
     * 计算多行堆叠。
     *
     * @param centerY     块心的 y
     * @param lineCount   实际要画的行数（至少 1）
     * @param current     正在演唱那行的序号，从 0 开始
     * @param lineHeight  每行高（主行字号的行高）
     * @param gap         行间距
     * @param scaleRatio  已唱行之外的行字号比例（1 表示同样大小）
     */
    public static Block compute(int centerY, int lineCount, int current,
                                int lineHeight, int gap, float scaleRatio) {
        int lines = Math.max(1, lineCount);
        int currentIndex = Math.max(0, Math.min(lines - 1, current));

        // 先算每行的实际行高：非当前行按比例缩小。
        // 全程用浮点累加，只在落位时取整一次——逐行取整会让误差累积。
        float[] heights = new float[lines];
        float total = 0f;
        for (int i = 0; i < lines; i++) {
            heights[i] = heightOf(i, currentIndex, lineHeight, scaleRatio);
            total += heights[i];
        }
        total += gap * (lines - 1);

        int blockHeight = Math.round(total);
        int top = centerY - blockHeight / 2;
        float baseline = top;
        for (int i = 0; i < currentIndex; i++) {
            baseline += heights[i] + gap;
        }
        return new Block(top, blockHeight, Math.round(baseline));
    }

    /** 第 i 行的顶部。 */
    public static int lineTop(Block block, int index, int current,
                              int lineHeight, int gap, float scaleRatio) {
        float y = block.top();
        for (int i = 0; i < index; i++) {
            y += heightOf(i, current, lineHeight, scaleRatio) + gap;
        }
        return Math.round(y);
    }
}