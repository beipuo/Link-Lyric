package com.linklyric.common.hud;

import java.util.List;

/**
 * 一帧内「每一行画在哪」的完整结果。
 *
 * <h2>为什么要把结果摊成不可变对象</h2>
 *
 * <p>此前这些量散在 {@code render()} 的一段流程里：块顶、块高、锚点、视口、
 * 留白、滚动偏移、落笔位置，都是顺次算出的局部变量。它们**彼此依赖且有顺序**
 * （块顶要先有块高，滚动要先有视口宽），但读者看不出这层依赖：任意一行都能
 * 引用十来行之前的值，改一处动全身。更糟的是包围盒与底板在同一个方法里
 * 各自重算了一遍 {@code blockTop / blockBottom / viewWidth}——两份公式一旦
 * 不同步，就是「框画在这儿、命中判在那儿」这种只靠手感才能发现的错。
 *
 * <p>摊成 record 之后有两件事同时成立：编排者（{@link PlacementStrategy}）
 * 只负责**算出**这些数，绘制者只负责**读**这些数；块信息只算一次，
 * 包围盒与底板都从同一个 {@code blockTop} 派生。
 *
 * <h2>为什么坐标全是 int</h2>
 *
 * <p>{@code common} 的排版下沿至今是整数像素：{@link LineLayout} 与
 * {@link LyricGeometry} 都在落位时取整一次，绘制原语也收 int。
 * 把这里换成 float 会顺手改掉取整时机，逐像素快照立刻不一致。
 * 精度提升是一次独立改动，不该混进「结构不变」的重构里——
 * 唯一例外是 {@link PlacedLine#orientationDegrees()}，见其注释。
 *
 * @param blockTop     歌词块上边界（不含留白），像素
 * @param blockHeight  整块高度，像素；至少容纳一行
 * @param blockBottom  歌词块下边界（不含留白），即 {@code blockTop + blockHeight}
 * @param centerX      本帧的横向锚点：预览区内居中，否则全屏居中加用户偏移
 * @param centerY      本帧的纵向锚点：预览区内居中，否则贴底部加偏移
 * @param viewportLeft 视口左边界；滚动与逐字裁剪都以它为原点
 * @param viewportRight 视口右边界，等于 {@code viewportLeft + viewWidth}
 * @param viewWidth    视口宽度：滚动时是可用宽度，否则是主行宽度
 * @param maxWidth     单行可用宽度，已乘 {@code maxWidthRatio}
 * @param padX         左右留白，底板与包围盒共用
 * @param padY         上下留白，必须容得下抬升，否则抬起的字顶出底板
 * @param scroll       横向滚动偏移，非滚动时恒为 0
 * @param textX        主行落笔横坐标，等于 {@code viewportLeft + scroll}
 * @param lines        逐行放置结果，第 0 项恒为主行
 */
public record LyricPlacement(
        int blockTop,
        int blockHeight,
        int blockBottom,
        int centerX,
        int centerY,
        int viewportLeft,
        int viewportRight,
        int viewWidth,
        int maxWidth,
        int padX,
        int padY,
        int scroll,
        int textX,
        List<PlacedLine> lines) {

    /**
     * 规整成不可变列表。
     *
     * <p>放置结果每帧都要投递给绘制层与测试，若保留可变引用，
     * 调用方一次 {@code add} 就能让「本帧结果」在投递后悄悄多出一行。
     */
    public LyricPlacement {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * 主行；没有行时返回 null。
     *
     * <p>正常路径一定有主行——没有可画的内容时连放置都不会发生。
     * 返回 null 而不是抛异常，是因为「本帧无内容」在运行时是常态
     * （关闭、间奏、时间轴为空），不该让诊断代码因此炸掉。
     */
    public PlacedLine primary() {
        for (PlacedLine line : lines) {
            if (line.isPrimary()) {
                return line;
            }
        }
        return lines.isEmpty() ? null : lines.get(0);
    }

    /**
     * 按行序号取放置结果；越界返回 null。
     *
     * <p>折行时同一句会占前若干格，逐段绘制要按段序号取，
     * 而不是按 {@link #primary()} 只拿第一段。返回 null 而非抛异常，
     * 理由与 {@link #primary()} 相同：诊断代码不该因为本帧少一行就炸。
     */
    public PlacedLine line(int index) {
        return index >= 0 && index < lines.size() ? lines.get(index) : null;
    }

    /**
     * 包围盒要覆盖的矩形（**不含**预览裁切）。
     *
     * <p>横向覆盖整个视口：滚动时可见的正是这一整段，只按文字宽度算会让
     * 边上的字命中不到。纵向按块的**稳定**位置算，不含行切换偏移——
     * 否则换行那几帧命中框会跟着位移，鼠标悬停会抖。
     *
     * <p>它与底板的绘制矩形是同一个矩形。此前两处各写一遍
     * {@code viewportLeft - padX, blockTop, viewWidth + padX * 2, ...}：
     * 抄错一个符号的后果是「看见的框」与「能拖的框」错位，
     * 而这种错只在拖动时用手感才察觉得到。
     */
    public FloatingLyricRenderer.Bounds footprint() {
        return new FloatingLyricRenderer.Bounds(viewportLeft - padX, blockTop,
                viewWidth + padX * 2, blockBottom - blockTop);
    }

    /**
     * 每一行的放置结果。
     *
     * @param index              行序号，0 恒为主行
     * @param x                  落笔横坐标
     * @param y                  落笔纵坐标（行顶，不是基线）
     * @param width              该行宽度，像素；主行在滚动时是整行宽度
     * @param text               该行要画的内容，已按可用宽度截断
     * @param color              该行颜色，ARGB
     * @param scale              字号比例：主行 1.0，副行 0.8
     * @param isPrimary          是否逐字染色那行；只有它参与进度与抬升
     * @param orientationDegrees 行朝向角，单位为度
     */
    public record PlacedLine(
            int index,
            int x,
            int y,
            int width,
            String text,
            int color,
            float scale,
            boolean isPrimary,
            float orientationDegrees) {

        /**
         * 朝向恒为水平的构造函数。
         *
         * <p>眼下所有编排都是横排，显式写 0.0f 会散落在每个调用点上，
         * 而其中任何一个手滑都不会有任何现象。留一个默认入口，
         * 让「当前恒为水平」这件事只写在类型里。
         */
        public PlacedLine(int index, int x, int y, int width, String text,
                          int color, float scale, boolean isPrimary) {
            this(index, x, y, width, text, color, scale, isPrimary, 0.0f);
        }

        /** 该行右边界。半开区间，与裁剪语义一致。 */
        public int right() {
            return x + width;
        }
    }
}
