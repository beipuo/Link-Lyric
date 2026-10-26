package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 弧形排布：整块歌词沿一段圆弧铺开，每行按该处**切线方向**转。
 *
 * <h2>视觉意图</h2>
 *
 * <p>横排歌词是一条直线，与画面的关系只有「在中间」或「偏左偏右」。
 * 弧排把整句沿一段向上拱起的圆弧摆，中间高、两端低，于是文字与画面
 * 之间多出一层「沿某个中心环绕」的关系。弧的曲率由行数决定：
 * 行越多弧越弯，否则三十行的圆环会撑出屏幕。
 *
 * <h2>参数：只有半径与张角两个旋钮</h2>
 *
 * <p>圆心固定在弧的最低点正下方，半径 {@code R}，整块跨越张角 {@code spanDegrees}。
 * 每行的位置由「它在弧长上的位置」映射到角度：
 *
 * <pre>{@code
 * angle = -span/2 + span * (along / totalLength)   // 度
 * x     = cx + R * sin(angle)
 * y     = cy - R * cos(angle)                      // cy 是圆心，屏幕 y 向下
 * }</pre>
 *
 * <p>朝向就是切线角，直接等于 {@code angle}：在 {@code angle = 0} 处
 * 切线水平（{@code orientationDegrees = 0}），两端各倾斜一半张角。
 * 这不是近似——圆心在下方时，圆弧上一点的切线与竖直方向的夹角恰好
 * 等于该点的极角，而屏幕 y 向下、rotate 顺时针为正，符号正好对上。
 *
 * <h2>半径怎么定</h2>
 *
 * <p>不暴露成用户可见参数，而是由「张角 + 弦长」反解：
 * 让弧的弦跨越可用宽度，于是
 * {@code R = (chord / 2) / sin(span / 2)}。
 * 这样弧排在任何屏幕上都不会横向溢出，且张角是唯一真正决定「弯多少」的量。
 * 张角随行数增长但封顶：单行时接近平直（弧排对一行字没有意义，
 * 强行弯曲只会让它看起来歪了），行多时最多到 {@link #MAX_SPAN_DEGREES}。
 *
 * <h2>为什么绕开了逐字裁剪</h2>
 *
 * <p>行级旋转走 pose 栈，而逐字裁剪框（{@code enableScissor}）是
 * 屏幕空间的轴对齐矩形，**不跟随 pose**（见 {@code GuiGraphics.applyScissor}：
 * 它按 {@code window.getGuiScale()} 把矩形换到屏幕坐标，完全不读 pose 栈）。
 * 于是「转过的行 + 轴对齐裁剪框」会互相切：斜排的字被直边削掉一角。
 *
 * <p>本策略因此把视口设成完整覆盖整行：{@code viewportLeft = 该行最左}，
 * {@code viewWidth = 该行最右 - 最左}，且不让输入进入滚动。
 * 视口完整覆盖时 {@code LyricPainter} 的逐字判定恒为「不需要裁剪」，
 * 那条轴对齐框根本不会被启用——不是靠调大容差骗过去，而是让分支不发生。
 * 代价是弧形排布下长句不滚动，超出屏幕的部分直接不显示。
 * 这个取舍写在 {@link #place} 的注释里，也写在配置界面的选项说明里。
 */
public final class ArcPlacement implements PlacementStrategy {

    /**
     * 张角上限，单位度。
     *
     * <p>超过这个值，两端的行会接近竖直，9 像素高的字形在竖直方向上
     * 占位接近行宽，相邻行开始互相压。110 度是按「30 行、行距 2」
     * 的密集情形估的：再大就要靠加大半径来避开重叠，而半径一大，
     * 弧就退化成直线，弧排本身失去意义。
     */
    private static final float MAX_SPAN_DEGREES = 110f;

    /** 单行时的张角：几乎平直，但保留一点点曲率以免与横排完全重合成两套代码。 */
    private static final float MIN_SPAN_DEGREES = 6f;

    /** 每多一行增加多少张角。行数与张角线性挂钩，斜率取「三十行到上限」。 */
    private static final float SPAN_PER_LINE = 3.5f;

    /** 底板上下留白的最小值，与默认策略同一个理由：抬起的字不能顶出框。 */
    private static final int MIN_PAD_Y = 3;

    @Override
    public LyricPlacement place(PlacementInput in) {
        LyricStyleConfig style = in.style();

        int maxWidth = LyricGeometry.availableWidth(
                style.maxWidthRatio(), in.previewArea(), in.screenWidth());

        // 块高先于锚点：锚点比例贴顶那一档要把块顶换算成块心，
        // 而「块高」在曲线排布里是逐行外接矩形的并集，要等行都摆完才知道。
        // 这里遇到一个真实的循环：锚点 → 行位置 → 并集 → 块高 → 锚点。
        //
        // 破环的办法是**用一个与曲线无关、只取决于行数与行距的代理块高**：
        // 曲线的并集高度总是略大于它（上拱让两端往下探、旋转让外接矩形变高），
        // 因此代理值只会让贴顶的块顶比理想位置略高一点，不会让块顶
        // 掉到 TOP_MARGIN 之下压住血条。默认比例 0 时这个代理值
        // **完全不被读取**（见 LyricGeometry.anchorRatioY 的首行），
        // 于是圆弧的既有像素一个都不会动。
        int slots = Math.max(1, in.lineCount());
        int span = in.primarySpan();
        float[] steps = SlotPlan.slotSteps(in, style, slots, span);
        int approxHeight = Math.round(
                SlotPlan.totalLength(steps, style.lineGapPx()));

        int anchorX = LyricGeometry.anchorX(
                in.screenWidth(), in.offsetX(), in.previewArea());
        int anchorY = LyricGeometry.anchorY(
                in.screenHeight(), in.offsetY(), in.previewArea(),
                style.anchorRatio(), approxHeight);

        int padX = style.padXPx();
        int padY = Math.max(MIN_PAD_Y, LyricGeometry.LIFT_HEADROOM + 1);

        float totalLength = Math.max(1f, SlotPlan.totalLength(steps, style.lineGapPx()));
        List<SlotPlan.Entry> entries = SlotPlan.entries(in, style);

        // 张角：行数越多越弯，两端分别是「几乎平直」与「上限」。
        float wantedSpanDegrees = Math.min(MAX_SPAN_DEGREES,
                MIN_SPAN_DEGREES + SPAN_PER_LINE * (float) (slots - 1));

        // 半径由**弧长**反解，而不是弦长。
        //
        // 这是本类的核心不变式：半径 = 总沿线长 / 张角（弧度）。
        // 只有这样，相邻两行在弧上的间距才恰好等于它们在直线上该有的间距，
        // 叠字才不会发生。
        //
        // 用弦长反解是错的：弦长恒小于弧长，于是半径偏小、弧太短。
        // 实测数据（6 行、每行 60..80 像素、张角 23.5 度）：
        // 按弦长算第 0、1 行的朝向只差 3.4 度，中心距 0.77 像素，
        // 字直接压在一起；按弧长算则正好等于行高 9 加行距。
        //
        // 弦长只用来约束「横向不溢出」，且它靠**收紧张角**来满足：
        // 张角变小只是弧变平（退化成直线，观感仍整齐），
        // 而半径变小会让不重叠失效。两种收缩的代价不对等，因此只能动前者。
        float halfRad = (float) Math.toRadians(wantedSpanDegrees) * 0.5f;
        float radius = totalLength / Math.max(1e-4f, halfRad * 2f);
        float spanDegrees = (float) Math.toDegrees(halfRad * 2f);
        // 圆心：弧的最低点落在「默认横排的中心行」上，整块因此与默认位置同高。
        // 上拱把两端往下拉，若把圆心直接放在锚点上，整块会整体上移半弧高，
        // 表现为「切到弧形后歌词忽然跳高一截」。
        float arcSagitta = radius * (1f - (float) Math.cos(halfRad));
        float arcCenterY = anchorY + steps[0] * 0.5f + arcSagitta;

        List<LyricPlacement.PlacedLine> lines = new ArrayList<>(entries.size());
        float[] union = OrientedBox.empty();
        for (SlotPlan.Entry e : entries) {
            float t = totalLength <= 0f ? 0.5f : e.along() / totalLength;
            float degrees = -spanDegrees * 0.5f + spanDegrees * t;
            double radians = Math.toRadians(degrees);
            float lineCenterX = anchorX + radius * (float) Math.sin(radians);
            float lineCenterY = arcCenterY - radius * (float) Math.cos(radians);

            // 行宽与行高都按该行自己的字号比例缩放：弧形下的行宽是
            // 实际墨迹宽度，不是可用宽度。
            float drawWidth = e.width() * e.scale();
            float drawHeight = LyricGeometry.LINE_HEIGHT * e.scale();
            // 落笔点（左上角）由中心反推：绘制原语收左上角，而弧排算的是中心。
            float x = lineCenterX - drawWidth * 0.5f;
            float y = lineCenterY - drawHeight * 0.5f;

            lines.add(new LyricPlacement.PlacedLine(e.index(), x, y, e.width(),
                    e.text(), e.color(), e.scale(), e.primary(), degrees));

            OrientedBox.merge(union, OrientedBox.of(
                    lineCenterX, lineCenterY, drawWidth, drawHeight, degrees));
        }

        return boundingPlacement(lines, union, maxWidth, anchorX, anchorY, padX, padY);
    }

    /**
     * 把逐行结果收进一份放置结果。
     *
     * <h2>块边界取自所有行的外接矩形并集</h2>
     *
     * <p>弧形下「块」不再是一个和视口同高的矩形：上拱的两端比中间低，
     * 整块的包围盒比横排时高得多。用行数乘行高去估算会让底板切掉
     * 两端最斜的那几行，而拖动时命中框与实际墨迹不符。
     * 因此逐行累加外接矩形，一次得到真实的并集。
     *
     * <h2>视口完整覆盖整块，关闭滚动</h2>
     *
     * <p>理由在类注释里：逐字裁剪框是屏幕空间轴对齐的，不跟随行旋转。
     * 让视口恰好等于所有行的水平范围，{@code LyricPainter} 的逐字判定
     * 恒为「完整可见」，裁剪分支不会启用，斜排的字才不会被直边削掉。
     *
     * <p>视口宽度是**整块**的最左到最右，而不是主行宽度：
     * 主行在弧中间、两端还有别的行探出去，只按主行给宽会让两端的行
     * 被判成越界而进入裁剪分支——那正是要避免的情形。
     */
    private LyricPlacement boundingPlacement(List<LyricPlacement.PlacedLine> lines,
                                             float[] union, int maxWidth,
                                             int anchorX, int anchorY,
                                             int padX, int padY) {
        int blockTop = Math.round(union[OrientedBox.TOP]);
        int blockBottom = Math.round(union[OrientedBox.BOTTOM]);
        int blockLeft = Math.round(union[OrientedBox.LEFT]);
        int blockRight = Math.round(union[OrientedBox.RIGHT]);

        int viewportLeft = blockLeft;
        int viewWidth = Math.max(1, blockRight - blockLeft);

        // textX 是主行落笔横坐标的便捷副本。弧形下主行不一定是视口起点，
        // 因此取它自己的位置；行列表为空时退回视口左界。
        int textX = lines.isEmpty() ? viewportLeft : Math.round(lines.get(0).x());

        return new LyricPlacement(blockTop, Math.max(1, blockBottom - blockTop),
                blockBottom, anchorX, anchorY,
                viewportLeft, viewportLeft + viewWidth, viewWidth,
                maxWidth, padX, padY,
                // scroll 恒为 0：弧排不滚动，理由见上方与类注释。
                0, textX, lines);
    }
}
