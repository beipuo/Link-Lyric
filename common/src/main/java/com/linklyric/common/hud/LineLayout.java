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
     * @param top           第一行顶部
     * @param height        整块高度
     * @param baseline      正在演唱那行的顶部
     * @param fullSizeLines 前几行按主行全字号计，见 {@link #compute} 的同名参数
     */
    public record Block(int top, int height, int baseline, int fullSizeLines) {

        /**
         * 全部副行都缩放的兼容构造。
         *
         * <p>折行之前「全字号行」只有主行一行，这个三参形态就是那时的语义。
         * 保留它是因为块信息也会被诊断代码与测试直接构造，
         * 而那两处不关心折行——让它们继续用短构造，比强迫每处都写 1 干净。
         */
        public Block(int top, int height, int baseline) {
            this(top, height, baseline, 1);
        }
    }

    /**
     * 一行的高度。
     *
     * <p>返回浮点而非取整：次行按 {@code 0.8} 缩放后是 7.2 像素，
     * 取整成 7 会少算 0.2，逐行累加后块高偏移，块心居中就偏了；
     * 向上取整成 8 又多算 0.8，两行歌词看起来比实际更松散。
     * 行高只在最终落位时取整一次，中间过程保持精确。
     */
    public static float heightOf(int index, int fullSizeLines, int lineHeight, float scaleRatio) {
        return index < Math.max(1, fullSizeLines)
                ? lineHeight
                : Math.max(1f, lineHeight * scaleRatio);
    }

    /**
     * 计算多行堆叠。
     *
     * <h2>为什么是「前 N 行全字号」而不是「只有第 0 行全字号」</h2>
     *
     * <p>折行把一句长词拆成连续几段，这几段**都属于同一句**，
     * 字号必须一致——第二段缩到 0.8 会让一句话看起来像原文加译文。
     * 而副行（下一句预览、翻译）该缩。两者的区别只是「在不在前 N 行」，
     * 所以槽位高度由 {@code fullSizeLines} 统一决定，不再特判某一行。
     *
     * <p>不折行时 {@code fullSizeLines = 1}，与旧公式逐项相同：
     * 旧实现判的是 {@code index == current}，而 {@code current} 恒为 0
     * （逐字染色的永远是第一行）。把 {@code 1} 代进去，
     * {@code index < 1} 与 {@code index == 0} 是同一个集合，
     * 因此关闭折行时块高、块顶、基线全部逐像素不变。
     *
     * @param fullSizeLines 前几行按主行全字号计；至少 1
     */
    public static Block compute(int centerY, int lineCount, int fullSizeLines,
                                int lineHeight, int gap, float scaleRatio) {
        int height = blockHeight(lineCount, fullSizeLines, lineHeight, gap, scaleRatio);
        int top = centerY - height / 2;
        // 基线恒等于块顶：正在演唱的永远是第 0 行（逐字染色只发生在它上面），
        // 它前面没有别的行，累加和为空。折行时后面几段同字号，
        // 但它们在第 0 行之下，不影响基线。
        float baseline = top;
        int lines = Math.max(1, lineCount);
        int full = Math.max(1, Math.min(lines, fullSizeLines));
        return new Block(top, height, Math.round(baseline), full);
    }

    /**
     * 整块的高度，像素。
     *
     * <h2>为什么它必须能单独算</h2>
     *
     * <p>锚点比例（{@code LyricStyleConfig.anchorRatio()}）描述的是
     * 「块心落在屏幕纵向的哪个位置」，而块心一旦依赖块高，
     * 就出现了「算块心要先知道块高、算块高又要先知道块心」的循环。
     * 本方法把半边拆出来：块高只取决于行数、字号与行距，与位置无关。
     *
     * <p>抽出的另一个后果是**两份块高不可能不一致**：
     * {@link #compute} 直接调这里，放置策略也在同一个输入上调这里。
     * 若策略自己另抄一份算式，哪怕只差在 {@code Math.round} 的位置，
     * 贴顶那一档的块顶就会偏一个像素——而贴底档因为不读块高，
     * 完全看不出问题，于是这种偏差只在新功能上显形。
     *
     * <p>累加顺序与 {@link #compute} 原先的内联版本**逐项相同**：
     * 逐行浮点累加、行距用 {@code int * int} 一次加完、最后只取整一次。
     * 这是「默认值零变化」的算术前提，不是近似。
     */
    public static int blockHeight(int lineCount, int fullSizeLines,
                                  int lineHeight, int gap, float scaleRatio) {
        int lines = Math.max(1, lineCount);
        int full = Math.max(1, Math.min(lines, fullSizeLines));
        // 全程用浮点累加，只在落位时取整一次——逐行取整会让误差累积。
        float total = 0f;
        for (int i = 0; i < lines; i++) {
            total += heightOf(i, full, lineHeight, scaleRatio);
        }
        total += gap * (lines - 1);
        return Math.round(total);
    }

    /** 第 i 行的顶部。 */
    public static int lineTop(Block block, int index, int fullSizeLines,
                              int lineHeight, int gap, float scaleRatio) {
        float y = block.top();
        int full = Math.max(1, fullSizeLines);
        for (int i = 0; i < index; i++) {
            y += heightOf(i, full, lineHeight, scaleRatio) + gap;
        }
        return Math.round(y);
    }
}