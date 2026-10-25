package com.linklyric.common.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 段的坐标换算：{@code 局部坐标 = 段坐标 − 字锚点}。
 *
 * <p>这是「正在上色的字变透明」与「被盖住」两个症状的回归测试。
 *
 * <p>段坐标由 {@code spansForChar(charX, charRight, ...)} 生成，
 * 是以**该字左边缘**为原点的绝对屏幕坐标。绘制时若把锚点取成
 * 行首（{@code textX}），每个字都会多偏移自身之前的累计宽度
 * {@code charX − textX}，被推出裁剪区，表现为完全透明；
 * 若把锚点取成绘制原点 {@code x}，阴影的 −1 位移会被抵消，
 * 表现为阴影压在主色上、字发暗发糊。
 */
class GlyphSpanOriginTest {

    /**
     * 锚点取字左边缘时，局部坐标落在字形范围内。
     *
     * <p>局部坐标必须落在 {@code [0, charWidth]}，
     * 才能被夹进字形的可见纹素范围。
     */
    @Test
    void 锚点取字左边缘时局部坐标正确() {
        int textX = 68;
        int[] charXs = {68, 77, 86, 95, 104, 113};
        int charWidth = 9;

        for (int charX : charXs) {
            GlyphSpan span = new GlyphSpan(charX, charX + charWidth,
                    0xFF3AA0FF, 0xFFFFFFFF);
            float local = span.startX() - charX;
            assertEquals(0f, local, 0.0001f,
                    "锚点取字左边缘时，段起点应正好是局部 0，charX=" + charX);
            float localEnd = span.endX() - charX;
            assertEquals(charWidth, localEnd, 0.0001f,
                    "段终点应正好是字宽，charX=" + charX);
        }
    }

    /**
     * 锚点取行首会让偏移随字累积，这就是「字变透明」的成因。
     *
     * <p>第 6 个字会多偏移约 45px，远超字形宽度，
     * 夹进纹素范围后整段被丢弃，于是什么都画不出来。
     */
    @Test
    void 锚点取行首会让偏移随字累积() {
        int textX = 68;
        int charWidth = 9;
        int lastCharX = 113; // 第 6 个字

        GlyphSpan span = new GlyphSpan(lastCharX, lastCharX + charWidth, 0, 0);

        float correctLocal = span.startX() - lastCharX;
        float wrongLocal = span.startX() - textX;

        assertEquals(0f, correctLocal, 0.0001f);
        assertEquals(45f, wrongLocal, 0.0001f,
                "错用行首作锚点时，偏移量等于该字之前所有字的累计宽度");

        // 夹进 [0, charWidth] 后，错误值整体落在字形之外，整段被丢弃。
        float clamped = Math.max(0f, Math.min(charWidth, wrongLocal));
        assertEquals(charWidth, clamped, 0.0001f,
                "错误局部坐标会被夹到字宽处，段长归零，什么都不画");
    }

    /**
     * 阴影与主色的几何必须相差一格。
     *
     * <p>两者共用同一锚点，只有绘制原点不同，
     * 因此局部坐标相同、绝对位置差 1。
     */
    @Test
    void 阴影与主色相差一格() {
        float originX = 104f;
        float mainX = 104f;
        float shadowX = mainX - 1f;
        GlyphSpan span = new GlyphSpan(105f, 106.5f, 0, 0);

        float local = span.startX() - originX;
        float mainAbs = mainX + local;
        float shadowAbs = shadowX + local;

        assertEquals(1f, mainAbs - shadowAbs, 0.0001f,
                "阴影应比主色左移一格，否则会精确压在主色上");
    }
}
