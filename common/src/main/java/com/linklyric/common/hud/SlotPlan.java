package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@link PlacementInput} 里筛出真正要出画的行，以及各槽位的行高。
 *
 * <h2>为什么不直接遍历输入的所有行</h2>
 *
 * <p>「哪几行出画、各是什么字号、什么颜色」是一套**内容规则**，与位置无关：
 * 主行恒在、前 {@code primarySpan} 行属同一句（折行段）按全字号、
 * 其后的空槽位占高度但不出画、副行按 {@code scaleAt} 缩。
 * {@link LinearStackPlacement} 已经把它写在循环里了；弧形与圆环若各抄一遍，
 * 三份规则里任何一份漏一项，就会出现「切到弧形后，末句的翻译突然冒出来」
 * 这种只有对比两种排布才能发现的差异。
 *
 * <p>因此这里把它收成一份，三个策略共用。**位置算式仍然各不相同**——
 * 共享的只是「画什么」，不是「画在哪」。
 *
 * <h2>槽位与出画行是两回事</h2>
 *
 * <p>槽位决定**纵向占位**：某格暂时没有内容时它照样占一行的高度，
 * 否则块心固定而某一格忽然不占高，整块会跳一下（这条理由写在
 * {@link LinearStackPlacement} 的类注释里）。
 *
 * <p>但弧形与圆环是按「沿线距离」或「方位角」排的，空槽位若也落位，
 * 就会在曲线上留下一段可见的空弧——那正是占位的本意，所以这里的做法是：
 * **空槽位推进入线距离/角度，但不产出 {@link Entry}**。
 * 于是曲线上的空档自然出现在它该在的地方，而绘制端只看 {@code Entry}。
 */
final class SlotPlan {

    private SlotPlan() {
    }

    /**
     * 一个要出画的行。
     *
     * @param index   行序号，与服务端/时间轴的槽位对应
     * @param text    已截断的文本
     * @param width   该行宽度，像素（副行已是缩放后的宽度）
     * @param color   ARGB
     * @param scale   字号比例
     * @param primary 是否主行（含折行段）
     * @param slot    对应的槽位序号；{@code index} 与它在本类里恒等，
     *                分开写是为了让「槽位」这个概念在签名上可见
     * @param along   该槽位中心距曲线起点的沿线距离；空槽位也推进这个量
     */
    record Entry(int index, String text, int width, int color, float scale,
                 boolean primary, int slot, float along) {
    }

    /**
     * 按内容规则摊平输入。
     *
     * @return 出画行，按槽位升序；至少一项（主行恒在）
     */
    static List<Entry> entries(PlacementInput in, LyricStyleConfig style) {
        int slots = Math.max(1, in.lineCount());
        int span = in.primarySpan();
        float[] steps = slotSteps(in, style, slots, span);
        float[] along = slotCenters(steps, style.lineGapPx());

        List<Entry> out = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            String text = in.textAt(i);
            if (i == 0) {
                // 主行宽度取 primaryWidth：滚动时它是**未截断**的整行宽度，
                // 与放置结果的 viewWidth 同源，两处不能各说各话。
                out.add(new Entry(0, text, in.primaryWidth(),
                        style.sungColor(), 1.0f, true, i, along[i]));
                continue;
            }
            if (i < span) {
                // 折行的其余段：同一句的一部分，全字号，宽度按自己量。
                out.add(new Entry(i, text, in.widthAt(i),
                        style.sungColor(), 1.0f, true, i, along[i]));
                continue;
            }
            if (text.isEmpty()) {
                // 空槽位只占长度，不出画。与默认策略的 continue 等价。
                continue;
            }
            out.add(new Entry(i, text, in.widthAt(i),
                    in.colorAt(i), in.scaleAt(i), false, i, along[i]));
        }
        return out;
    }

    /**
     * 每个槽位的高度。
     *
     * <p>直接借 {@link LineLayout#heightOf}：它与默认策略读同一份
     * 「前 N 行全字号、其余按比例缩」的规则，也共享「只在最终落位时取整」
     * 的做法。曲线排布用浮点行高，因此这里不取整。
     */
    static float[] slotSteps(PlacementInput in, LyricStyleConfig style,
                             int slots, int span) {
        float[] steps = new float[slots];
        for (int i = 0; i < slots; i++) {
            steps[i] = LineLayout.heightOf(i, span,
                    LyricGeometry.LINE_HEIGHT, LyricGeometry.SUB_TEXT_FONT_SCALE);
        }
        return steps;
    }

    /**
     * 各槽位中心距首槽位中心的沿线距离。
     *
     * <p>相邻两槽的步长是「两行各半高 + 行距」：这样第 0 槽中心到第 1 槽中心
     * 的距离恰好等于两者不重叠所需的法向距离**再加上用户设的行距**。
     *
     * <p>行距必须算进来。这一条曾经漏过：{@link #totalLength} 把行距加进了
     * 总长，而这里只累加了两个半高，于是「槽位位置」与「曲线总长」不成比例——
     * 墨迹刚好贴在一起（中心距恰好等于半高之和，一点余量都没有），
     * 而最后一行也落不到曲线末端，整块看着像被压短了一截。
     */
    static float[] slotCenters(float[] steps, int lineGapPx) {
        float[] along = new float[steps.length];
        float cursor = steps.length == 0 ? 0f : steps[0] / 2f;
        along[0] = cursor;
        for (int i = 1; i < steps.length; i++) {
            cursor += steps[i - 1] / 2f + steps[i] / 2f + (float) lineGapPx;
            along[i] = cursor;
        }
        return along;
    }

    /**
     * 曲线的总沿线长度：首槽上缘到末槽下缘，含槽间行距。
     *
     * <p>必须与 {@link #slotCenters} 用的是同一份行距，否则槽位位置与总长
     * 不成比例：两端的分段被拉长或压短，最后一行落不到曲线末端。
     */
    static float totalLength(float[] steps, int lineGapPx) {
        if (steps.length == 0) {
            return 0f;
        }
        float total = 0f;
        for (float h : steps) {
            total += h;
        }
        return total + (float) lineGapPx * (steps.length - 1);
    }
}
