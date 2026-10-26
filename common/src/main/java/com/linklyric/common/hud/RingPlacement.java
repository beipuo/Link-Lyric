package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 环形排布：各行的中心均布在圆周上，朝向随方位角连续变化。
 *
 * <h2>视觉意图</h2>
 *
 * <p>弧排仍是「一句在上、下一句在下」的线性次序，只是被弯了一下。
 * 环排改变的是次序的形态：每行占据表盘上的一个方位，字头朝向圆心、
 * 字尾向外（或反过来）。读一行要转一次头，于是它天生不适合长句，
 * 而适合「短句 + 少行」的情形——这一点必须写进选项说明，
 * 否则用户切过去只会看到一团挤在圆周上的字。
 *
 * <h2>参数</h2>
 *
 * <p>三个量决定全部几何：
 *
 * <p>**起始方位角** {@code startDegrees}：第一行（主行）落在表盘的哪个方向。
 * 取 {@code -90} 度即正上方，于是主行在顶部、后续行沿顺时针依次排开——
 * 与阅读习惯（从上往下）最接近。
 *
 * <p>**张角** {@code sweepDegrees}：整块占多少度。行数少时不必转满一圈，
 * 否则两行字会隔着半圈相望，中间空得莫名。这里取
 * {@code min(360, 每行角度 × 行数)}，每行角度随行数收缩。
 *
 * <p>**半径** {@code radius}：由张角与行高反解，保证相邻两行不叠：
 * 圆周上相邻两行中心的弧长必须不小于行高加行距，于是
 * {@code R ≥ (行高 + 行距) / (2 sin(每行角度 / 2))}。
 * 用「相邻中心弧长」而不是「弦长」做判据是保守的——弧长恒大于弦长，
 * 因此按弧长算出的半径一定足够。反过来按弦长算，行数多时相邻行
 * 会挤在弧的内侧。
 *
 * <h2>朝向</h2>
 *
 * <p>朝向导数恒等于「该行的方位角」：位于正上方的行朝向 0（水平），
 * 位于右侧的行朝向 +90（顺时针转四分之一圈，于是字沿半径方向躺着）。
 * 这与弧排的切线角是同一族公式，但参数不同——弧排朝向是
 * {@code -span/2 + ...}（切线随位置线性变化），环排朝向是
 * {@code 方位角}（整圈都有）。
 *
 * <h2>同样绕开逐字裁剪</h2>
 *
 * <p>与 {@link ArcPlacement} 同一个理由：裁剪框是屏幕空间轴对齐的，
 * 不跟随 pose 旋转。环排同样让视口完整覆盖整块、不进入滚动，
 * 于是裁剪分支不会启用。代价是长句不滚动、超出屏幕的部分不显示。
 */
public final class RingPlacement implements PlacementStrategy {

    /** 主行方位：正上方。屏幕 y 向下，因此上方是 -90 度。 */
    private static final float START_DEGREES = -90f;

    /** 每行最少占多少度；再小两行字形会压在一起。 */
    private static final float MIN_STEP_DEGREES = 14f;

    /** 每行最多占多少度；行数很少时也别把字摊得太开。 */
    private static final float MAX_STEP_DEGREES = 42f;

    /** 底板上下留白的最小值，与默认策略同一个理由。 */
    private static final int MIN_PAD_Y = 3;

    @Override
    public LyricPlacement place(PlacementInput in) {
        LyricStyleConfig style = in.style();

        int maxWidth = LyricGeometry.availableWidth(
                style.maxWidthRatio(), in.previewArea(), in.screenWidth());

        int slots = Math.max(1, in.lineCount());
        int span = in.primarySpan();
        float[] steps = SlotPlan.slotSteps(in, style, slots, span);
        // 块高先于锚点。环排的真实块高是所有行外接矩形的并集，
        // 而那要等半径、圆心都定下来——而圆心又依赖锚点。循环如下：
        // 锚点 → 圆心 → 行位置 → 并集 → 块高 → 锚点。
        //
        // 与 ArcPlacement 同一个破法：贴顶那一档只需要「块的纵向跨度大约是
        // 多少」，用 SlotPlan 的总沿线长当代理值即可。环的并集高度也总是
        // 不小于它（旋转让每个外接矩形都变大），代理值偏小只会让块顶
        // 略高于 TOP_MARGIN，不会压低到血条下面。比例 0 时它不被读取。
        int approxHeight = Math.round(
                SlotPlan.totalLength(steps, style.lineGapPx()));

        int anchorX = LyricGeometry.anchorX(
                in.screenWidth(), in.offsetX(), in.previewArea());
        int anchorY = LyricGeometry.anchorY(
                in.screenHeight(), in.offsetY(), in.previewArea(),
                style.anchorRatio(), approxHeight);

        int padX = style.padXPx();
        int padY = Math.max(MIN_PAD_Y, LyricGeometry.LIFT_HEADROOM + 1);

        List<SlotPlan.Entry> entries = SlotPlan.entries(in, style);

        // 每行占的角度：行多则收，行少则放，夹在上下限之间。
        float stepDegrees = Math.min(MAX_STEP_DEGREES, MAX_STEP_DEGREES * 3f / (float) slots);
        stepDegrees = Math.max(MIN_STEP_DEGREES, Math.min(MAX_STEP_DEGREES, stepDegrees));

        // 半径：让相邻两行中心的**弧长**不小于「行高 + 行距」。
        //
        // 弧长 = R × 每行角度（弧度），于是 R = 所需弧长 / 每行角度（弧度）。
        // 这是唯一正确的不变式：弧长等于行间距，行才刚好排开。
        // 若按弦长算（2R sin(θ/2)），弦长恒小于弧长，半径会偏小、
        // 相邻行中心距不足，字直接压上——实测第 0、1 行只有 2.9 像素。
        float needed = LyricGeometry.LINE_HEIGHT + (float) style.lineGapPx();
        double stepRad = Math.toRadians(stepDegrees);
        float radius = (float) (needed / stepRad);
        // 再按屏幕高度收一次：环的直径不能超过可用高度的一定比例，
        // 否则上下两端的行会被屏幕边缘切掉。这一步会牺牲「完全不重叠」，
        // 因此只在收得动的时候收——下限保住行距所需的最小半径。
        float maxRadius = in.previewArea() != null && LyricGeometry.isUsablePreview(in.previewArea())
                ? in.previewArea().height() * 0.34f
                : in.screenHeight() * 0.20f;
        radius = Math.max(radius, LyricGeometry.LINE_HEIGHT * 2f);
        if (maxRadius >= radius) {
            // 屏幕放得下所需半径：用屏幕允许的最大值，环更舒展但不再缩小间距。
            radius = Math.min(maxRadius, radius * 1.35f);
        }

        // 圆心：抬到锚点上方，使最低那一行的底部正好落在锚点行附近。
        // 直接以锚点为圆心时，整个环的重心会明显高于横排，切换时跳一下。
        float circleCenterX = anchorX;
        float circleCenterY = anchorY - radius + LyricGeometry.LINE_HEIGHT * 0.5f;

        List<LyricPlacement.PlacedLine> lines = new ArrayList<>(entries.size());
        float[] union = OrientedBox.empty();
        // 空槽位也要推角度，否则「末句还没到」时后面几行会挤上来。
        float[] alongDegrees = new float[slots];
        for (int i = 0; i < slots; i++) {
            alongDegrees[i] = START_DEGREES + stepDegrees * i;
        }
        for (SlotPlan.Entry e : entries) {
            float degrees = alongDegrees[Math.min(e.slot(), slots - 1)];
            double radians = Math.toRadians(degrees);
            float lineCenterX = circleCenterX + radius * (float) Math.cos(radians);
            float lineCenterY = circleCenterY + radius * (float) Math.sin(radians);

            float drawWidth = e.width() * e.scale();
            float drawHeight = LyricGeometry.LINE_HEIGHT * e.scale();
            // 落笔点由中心反推；绘制原语收左上角。
            float x = lineCenterX - drawWidth * 0.5f;
            float y = lineCenterY - drawHeight * 0.5f;

            // 朝向 = 该点处圆的切线方向。
            //
            // 圆上一点 (cos θ, sin θ) 的切向量是 (-sin θ, cos θ)，
            // 它与 +X 轴的夹角正是 θ + 90 度。屏幕 y 向下、rotate 顺时针为正，
            // 与极角同向，因此直接相加即可，不用取负。
            //
            // 用切线而不是法线（= 方位角本身）是有区别的：法线朝向会让字
            // 「沿半径躺着」，看起来像一个人面朝圆心席地而坐；
            // 切线朝向则让字沿圆周排列，像钟面刻度。后者才是钟面语义，
            // 也让多行之间的间距与弧长成正比，不会在中段挤成一团。
            float orientation = degrees + 90f;

            lines.add(new LyricPlacement.PlacedLine(e.index(), x, y, e.width(),
                    e.text(), e.color(), e.scale(), e.primary(), orientation));

            OrientedBox.merge(union, OrientedBox.of(
                    lineCenterX, lineCenterY, drawWidth, drawHeight, orientation));
        }

        int blockTop = Math.round(union[OrientedBox.TOP]);
        int blockBottom = Math.round(union[OrientedBox.BOTTOM]);
        int blockLeft = Math.round(union[OrientedBox.LEFT]);
        int blockRight = Math.round(union[OrientedBox.RIGHT]);
        int viewWidth = Math.max(1, blockRight - blockLeft);
        int textX = lines.isEmpty() ? blockLeft : Math.round(lines.get(0).x());

        return new LyricPlacement(blockTop, Math.max(1, blockBottom - blockTop),
                blockBottom, anchorX, anchorY,
                blockLeft, blockLeft + viewWidth, viewWidth,
                maxWidth, padX, padY, 0, textX, lines);
    }
}
