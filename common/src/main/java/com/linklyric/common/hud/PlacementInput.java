package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;

/**
 * 放置策略的只读输入。
 *
 * <h2>为什么要把输入也摊开</h2>
 *
 * <p>策略要算的只有「位置」，但它需要的原料横跨三处：平台给的屏幕尺寸、
 * 渲染器的状态（偏移、预览区）、用户的样式配置。若这些量以「渲染器 + 绘制器」
 * 的形式传进去，策略就能顺手做任何事——读数据源、写包围盒、改样式，
 * 于是「换一个策略」从「换一段几何」变成「换一段行为」，接缝等于没有。
 *
 * <p>摊成不可变输入之后，策略的权限被限死在纯计算上：它拿不到渲染器，
 * 也拿不到绘制器，因此它算不出字宽——字宽必须由调用方量好放进来。
 * 这是刻意的：{@code common} 的核心几何必须可脱离平台单独验证，
 * 而「量字宽」恰恰是唯一必须问平台的事。
 *
 * <h2>为什么行内容在这里就截断好</h2>
 *
 * <p>截断要量字宽，量字宽要平台。若把「原文 + 可用宽度」交给策略，
 * 每个策略都得自己实现一份 {@code clip}，而截断规则（加省略号、
 * 按码点累加）与排版无关，不该跟着策略重复。调用方用同一条规则截好，
 * 策略只负责摆位置——将来加弧形排布时，它改的是 {@code x/y}，
 * 不需要重新定义「多长算超宽」。
 *
 * <p>折行同理：切分点由 {@link LyricWrap} 在调用方算好，
 * 策略只看到若干已经切好的行。但折行让**行数与「哪几行属于同一句」**
 * 不再是固定关系，所以多出一个 {@code primarySpan}。
 *
 * @param screenWidth   屏幕宽，像素
 * @param screenHeight  屏幕高，像素
 * @param previewArea   预览区；null 表示按全屏布局
 * @param offsetX       用户水平偏移，像素，以屏幕中线为原点向右为正
 * @param offsetY       用户垂直偏移，像素，相对默认底部位置向下为正
 * @param style         用户可调样式；只读，策略不得替换
 * @param lineTexts     各行要画的文本，前 {@code primarySpan} 行属于演唱中的那一句
 * @param lineWidths    与 {@code lineTexts} 等长的行宽，像素，副行按缩放过
 * @param lineColors    与 {@code lineTexts} 等长的行色，ARGB
 * @param lineScales    与 {@code lineTexts} 等长的字号比例，主行段 1.0、副行 0.8
 * @param primaryWidth  主行**未截断**的原始宽度；滚动时整行要按它推
 * @param sungPx        本帧已唱宽度（已夹在 {@code [0, primaryWidth]} 之内）
 * @param scrolling     本帧是否横向滚动
 * @param blockShift    行切换的整块上移量，像素；0 表示没有过渡
 * @param primarySpan   前几行属于演唱中的那一句；不折行时恒为 1。
 *                      它决定行槽位高度：这些行与主行同字号，其余副行按比例缩
 */
public record PlacementInput(
        int screenWidth,
        int screenHeight,
        FloatingLyricRenderer.Bounds previewArea,
        int offsetX,
        int offsetY,
        LyricStyleConfig style,
        java.util.List<String> lineTexts,
        int[] lineWidths,
        int[] lineColors,
        float[] lineScales,
        int primaryWidth,
        float sungPx,
        boolean scrolling,
        int blockShift,
        int primarySpan) {

    /**
     * 只读化数组。
     *
     * <p>record 的 {@code int[]}/{@code float[]} 分量是按引用比较与传递的，
     * 调用方在投递后改一下数组，策略正在读的那份就变了——同一帧里
     * 出现两种行宽，而且只在高并发或测试复用同一个输入时才复现。
     * 复制一次的成本是每帧几个小数组，换的是「输入真的只读」。
     */
    public PlacementInput {
        lineWidths = lineWidths == null ? new int[0] : lineWidths.clone();
        lineColors = lineColors == null ? new int[0] : lineColors.clone();
        lineScales = lineScales == null ? new float[0] : lineScales.clone();
    }

    /** 前几行与主行同字号；至少 1，且不超过实际行数。 */
    @Override
    public int primarySpan() {
        return Math.max(1, Math.min(Math.max(1, lineCount()), primarySpan));
    }

    /** 要画的行数。以文本数量为准，其余数组按同一长度填。 */
    public int lineCount() {
        return lineTexts == null ? 0 : lineTexts.size();
    }

    /** 第 {@code index} 行的文本；越界返回空串（空串表示该槽位不出画）。 */
    public String textAt(int index) {
        return index >= 0 && index < lineCount() ? lineTexts.get(index) : "";
    }

    /** 第 {@code index} 行的宽度；越界返回 0。 */
    public int widthAt(int index) {
        return index >= 0 && index < lineWidths.length ? lineWidths[index] : 0;
    }

    /** 第 {@code index} 行的颜色；越界返回透明。 */
    public int colorAt(int index) {
        return index >= 0 && index < lineColors.length ? lineColors[index] : 0;
    }

    /** 第 {@code index} 行的字号比例；越界返回 1.0（不缩放）。 */
    public float scaleAt(int index) {
        return index >= 0 && index < lineScales.length ? lineScales[index] : 1f;
    }

    /** 是否在预览区内布局。 */
    public boolean inPreview() {
        return LyricGeometry.isUsablePreview(previewArea);
    }
}
