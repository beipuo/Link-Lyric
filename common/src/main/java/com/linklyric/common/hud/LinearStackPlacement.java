package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认放置策略：整块居中、纵向堆叠、按对齐换算左边界、叠上滚动偏移。
 *
 * <h2>这是「当前行为」的逐字照搬</h2>
 *
 * <p>本类的唯一目的是把原先散在 {@code FloatingLyricRenderer.render()} 里
 * 的布局算式原样搬到一个可替换的位置，**不顺手改任何取整与次序**。
 * 算式里看着可疑的地方（例如全屏可用宽度的 {@code * 2f}）也照抄，
 * 因为改动会平移既有像素，而「零变化」是这次抽取的唯一判据。
 * 想改公式，等接缝稳定之后单独提一次带快照对比的改动。
 *
 * <h2>三个次序不能换</h2>
 *
 * <p>一、**可用宽度**先于视口。视口宽度滚动时取可用宽度、否则取行宽，
 * 两个分支都依赖 {@code maxWidth}。
 *
 * <p>二、**视口**先于滚动。滚动量夹在 {@code [-(行宽-视口宽), 0]} 之间，
 * 视口宽算错就直接决定滚不滚得动。
 *
 * <p>三、**块高**与**行落位**必须用同一个行距。此前换行位移的步长写死成
 * 常量，用户把行距调大后，收起的那一像素会以跳格收尾——两处读同一个
 * {@code style.lineGapPx()} 才是唯一稳的做法。
 *
 * <h2>块高按「槽位数」算，不按「已填内容」算</h2>
 *
 * <p>行槽位由时间轴剩余行数决定，某一格暂时没有内容（末句没有下一句、
 * 后续行缺失）时它仍然占一行高度。这不是浪费：块心固定，若某一格
 * 突然不占高度，整块会跳一下。因此输入里空串的槽位照样参与
 * {@link LineLayout#compute}，只是不出画。
 *
 * <h2>折行让「全字号行」不再只有一行</h2>
 *
 * <p>不折行时 {@code current = 0}——逐字染色的永远是第一行，
 * 其余都是副行，按 {@code SUB_SCALE} 缩。折行把一句拆成连续几段，
 * 这几段是**同一句**，字号必须一致；只有后面的真副行才缩。
 * 因此行槽位改由 {@link PlacementInput#primarySpan()} 决定，
 * 而该值不折行时恒为 1，把 {@code 1} 代进 {@code index < 1}
 * 与旧的 {@code index == 0} 是同一个集合——旧画面因此逐像素不变。
 */
public final class LinearStackPlacement implements PlacementStrategy {

    /** 副行字号比例，与 {@link LyricGeometry#SUB_TEXT_FONT_SCALE} 同源。 */
    private static final float SUB_SCALE = LyricGeometry.SUB_TEXT_FONT_SCALE;

    /** 底板上下留白的最小值：再小的话拖动底板的上下边会贴住字形。 */
    private static final int MIN_PAD_Y = 3;

    @Override
    public LyricPlacement place(PlacementInput in) {
        LyricStyleConfig style = in.style();

        // 可用宽度：预览与全屏同一条公式，只换「屏幕宽度」为「框宽度」。
        // 公式住在 LyricGeometry，避免预览分支又一次漏乘 maxWidthRatio。
        int maxWidth = LyricGeometry.availableWidth(
                style.maxWidthRatio(), in.previewArea(), in.screenWidth());

        int centerX = LyricGeometry.anchorX(
                in.screenWidth(), in.offsetX(), in.previewArea());
        int centerY = LyricGeometry.anchorY(
                in.screenHeight(), in.offsetY(), in.previewArea());

        int padX = LyricGeometry.PAD_X;
        // 上下留白必须容纳最大抬升，否则抬起的字顶部会顶出底板，
        // 画面上就是「文字浮在框外」。+1 是抗锯齿的那一行。
        int padY = Math.max(MIN_PAD_Y, LyricGeometry.LIFT_HEADROOM + 1);

        // 多行堆叠：把当前行与后续若干行当作一整块，块心固定在 centerY。
        // 前 primarySpan 行是同一句（折行段），与主行同字号；其余副行缩一档。
        int slots = Math.max(1, in.lineCount());
        int span = in.primarySpan();
        LineLayout.Block block = LineLayout.compute(centerY, slots, span,
                LyricGeometry.LINE_HEIGHT, style.lineGapPx(), SUB_SCALE);

        // 视口宽度：滚动时铺满可用宽度，否则就是主行自身宽度。
        // 不滚动时若按可用宽度算视口，逐字裁剪会把整行两侧留出一大段空白，
        // 强调辉光的展开也会被误判成「还没到边界」。
        int viewWidth = in.scrolling() ? maxWidth : in.primaryWidth();
        // 视口跟随对齐方式：左对齐贴左端、右对齐贴右端，
        // 否则滚动起点会偏，正在唱的字会跑出可视区。
        int viewportLeft = LyricGeometry.left(
                style.align(), centerX, maxWidth, viewWidth);
        int viewportRight = viewportLeft + viewWidth;

        // 滚动偏移由已唱宽度直接决定，不额外维护动画状态：
        // 传进来的已唱宽度本身已经过跨帧平滑，偏移自然跟着平滑。
        int scroll = in.scrolling()
                ? Math.round(LyricGeometry.scrollOffset(
                        in.sungPx(), viewWidth, in.primaryWidth()))
                : 0;
        int textX = viewportLeft + scroll;

        // 块上下边界取**稳定基线**，不含行切换偏移。
        // 包围盒与底板都由它派生：若把切换偏移算进来，换行那几帧
        // 命中框会跟着位移，鼠标悬停就会抖。
        int stableBaseline = block.baseline();
        int blockTop = stableBaseline - padY;
        int blockBottom = stableBaseline + block.height() + padY;

        List<LyricPlacement.PlacedLine> lines = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            String text = in.textAt(i);
            if (i == 0) {
                // 主行：逐字染色的那一行，落笔点在滚动后的视口起点。
                // 颜色取已唱色作为「这一行的代表色」，实际绘制是
                // 已唱/未唱双层，本字段只用于诊断与外部布局。
                lines.add(new LyricPlacement.PlacedLine(0, textX,
                        stableBaseline + in.blockShift(), in.primaryWidth(),
                        text, style.sungColor(), 1.0f, true));
                continue;
            }
            if (i < span) {
                // 折行的其余段：同样是这句的一部分，因此 isPrimary 也为真，
                // 全字号、按自身宽度居中。落笔点不能用 textX——那是为
                // 滚动准备的视口起点；折行不滚动，各段按自己的宽度各自居中。
                int width = in.widthAt(i);
                int x = LyricGeometry.left(style.align(), centerX, maxWidth, width);
                int y = LineLayout.lineTop(block, i, span, LyricGeometry.LINE_HEIGHT,
                        style.lineGapPx(), SUB_SCALE) + in.blockShift();
                lines.add(new LyricPlacement.PlacedLine(i, x, y, width, text,
                        style.sungColor(), 1.0f, true));
                continue;
            }
            if (text.isEmpty()) {
                // 空槽位只占高度，不出画。与原先循环里的 continue 等价。
                continue;
            }
            // 左边界按**缩放后宽度**算：副行以 (x, y) 为原点缩放，
            // 实际占据 [x, x + width*scale]。用未缩放宽度居中会算出偏左的
            // 左边，副行中心比主行偏左 (width - width*0.8)/2 像素，
            // 行数越多越明显，看起来就是「多行歌词不居中」。
            int width = in.widthAt(i);
            int x = LyricGeometry.left(style.align(), centerX, maxWidth, width);
            int y = LineLayout.lineTop(block, i, span, LyricGeometry.LINE_HEIGHT,
                    style.lineGapPx(), SUB_SCALE) + in.blockShift();
            lines.add(new LyricPlacement.PlacedLine(i, x, y, width, text,
                    in.colorAt(i), in.scaleAt(i), false));
        }

        return new LyricPlacement(blockTop, block.height(), blockBottom,
                centerX, centerY, viewportLeft, viewportRight, viewWidth,
                maxWidth, padX, padY, scroll, textX, lines);
    }
}
