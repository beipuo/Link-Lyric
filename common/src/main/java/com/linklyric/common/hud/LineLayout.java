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
        int[] heights = new int[lines];
        int total = 0;
        for (int i = 0; i < lines; i++) {
            heights[i] = i == currentIndex
                    ? lineHeight
                    : Math.max(1, Math.round(lineHeight * scaleRatio));
            total += heights[i];
        }
        total += gap * (lines - 1);

        int top = centerY - total / 2;
        int baseline = top;
        for (int i = 0; i < currentIndex; i++) {
            baseline += heights[i] + gap;
        }
        return new Block(top, total, baseline);
    }

    /** 第 i 行的顶部。 */
    public static int lineTop(Block block, int index, int current,
                              int lineHeight, int gap, float scaleRatio) {
        int y = block.top();
        for (int i = 0; i < index; i++) {
            int h = i == current ? lineHeight
                    : Math.max(1, Math.round(lineHeight * scaleRatio));
            y += h + gap;
        }
        return y;
    }
}
