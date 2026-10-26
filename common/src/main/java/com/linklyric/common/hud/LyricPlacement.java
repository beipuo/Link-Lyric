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
 * <h2>为什么块信息仍是 int，而逐行坐标是 float</h2>
 *
 * <p>这里要分两类量：
 *
 * <p>**块信息**（{@code blockTop / blockBottom / viewportLeft / viewWidth / textX}）
 * 是整数像素，而且必须继续是整数。它们同时喂给三处只认 int 的地方：
 * {@link FloatingLyricRenderer.Bounds}（命中检测，鼠标坐标是 int）、
 * 底板填充（{@code fillRect} 收 int）、逐字裁剪（{@code enableScissor} 收 int）。
 * 把这些换成 float，等于要求平台层每一个 {@code fillRect} 调用点都改名，
 * 而 {@code LyricDragController} 的 {@code bounds().contains(mouseX, mouseY)}
 * 会失去类型依据——那条路径是整块拖动的唯一入口，改错就是「歌词看得见、拖不动」。
 * 因此块信息维持 int，取整发生在放置结束那一刻，由策略自己负责。
 *
 * <p>**逐行坐标**（{@link PlacedLine#x()} / {@link PlacedLine#y()}）是 float。
 * 原因很直接：弧形排布上的位置是 {@code r·sin θ}，圆环上更是无理数；
 * 若在策略内部先取整再交给绘制，一个 30 行的圆环会累积出十几像素的错位，
 * 而且每行的误差方向不一致，看起来是「字歪着，但说不出哪歪」。
 * 浮点只保留到绘制前一步：{@code drawTextScaled} 与
 * {@link LyricPainter#karaoke} 都收 int，转换发生在**调用点**，
 * 而不是在策略里。
 *
 * <p>默认策略不受影响：{@link LinearStackPlacement} 内部仍用
 * {@link LineLayout} 的整数算式，把 int 赋给 float 字段是**构造上无损**的。
 * 「默认观感零变化」因此不依赖任何取整技巧，只依赖类型放宽的方向。
 * 这一条由 {@code PlacementGoldenTest} 的逐字段快照钉住。
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
     * @param x                  落笔横坐标；浮点，见 {@link LyricPlacement} 类注释
     * @param y                  落笔纵坐标（行顶，不是基线）；浮点
     * @param width              该行宽度，像素；主行在滚动时是整行宽度
     * @param text               该行要画的内容，已按可用宽度截断
     * @param color              该行颜色，ARGB
     * @param scale              字号比例：主行 1.0，副行 0.8
     * @param isPrimary          是否逐字染色那行；只有它参与进度与抬升
     * @param orientationDegrees 行朝向角，单位为度，顺时针为正，0 为水平
     */
    public record PlacedLine(
            int index,
            float x,
            float y,
            int width,
            String text,
            int color,
            float scale,
            boolean isPrimary,
            float orientationDegrees) {

        /**
         * 朝向恒为水平的构造函数。
         *
         * <p>眼下只有默认策略走这条入口，显式写 0.0f 会散落在每个调用点上，
         * 而其中任何一个手滑都不会有任何现象。留一个默认入口，
         * 让「当前恒为水平」这件事只写在类型里。
         *
         * <p>注意它收的是 {@code int x/y}：横排堆叠的坐标本来就是整数，
         * 让这条入口保留 int 形参，等于在类型上再声明一次「默认排布不引入小数」。
         * 需要小数的编排用全参构造。
         */
        public PlacedLine(int index, int x, int y, int width, String text,
                          int color, float scale, boolean isPrimary) {
            this(index, (float) x, (float) y, width, text, color, scale, isPrimary, 0.0f);
        }

        /**
         * 该行右边界。
         *
         * <p>返回 float：它唯一的消费者是逐字裁剪的右界（{@code segmentRight}），
         * 而那里算完立刻取 {@code Math.min} 交给 {@code karaoke} 的 int 形参。
         * 保持浮点直到那一步，斜排时段的右端才不会提前丢精度。
         */
        public float right() {
            return x + width;
        }

        /**
         * 该行是否带朝向。
         *
         * <p>阈值 0.01 度，与常量 {@code LyricPainter.ROTATE_EPSILON_DEGREES} 同源。
         * 9 像素的字转 0.01 度，最远角点位移约 {@code (9/2)·sin(0.01°) ≈ 0.0008}
         * 像素——比一个像素小三个数量级。用它当判据，横排堆叠永远走
         * 无变换分支，不会因为浮点残值多做一次矩阵压栈。
         *
         * <p>那个常量是私有的，因此这里另立一份而不是引用；两处数值必须一致，
         * 否则同一帧里会出现「行已转、字还没跟上」的边界帧。
         * {@code PoseIdentityTest} 会把两者钉在一起。
         */
        public boolean hasOrientation() {
            return Math.abs(orientationDegrees) > ORIENTATION_EPSILON_DEGREES;
        }
    }

    /**
     * 朝向生效阈值，单位度。
     *
     * <p>放在 {@link LyricPlacement} 里而不是 {@code PlacedLine} 里：
     * record 的嵌套 record 只能声明实例相关的东西，而这是个跨行共用的判据，
     * 由外层持有更合语义。数值与 {@code LyricPainter} 的旋转阈值一致，
     * 两处若各用一套，同一帧里会出现「行已转、字还没跟上」的边界帧。
     */
    public static final float ORIENTATION_EPSILON_DEGREES = 0.01f;
}
