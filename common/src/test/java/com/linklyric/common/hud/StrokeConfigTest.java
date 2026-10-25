package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描边：配置模型与接线。
 *
 * <h2>描边为什么容易出错</h2>
 *
 * <p>描边与正文、发光都作用在同一个字形上，三者的像素范围必须互不重叠：
 * 正文在笔画内、描边贴着轮廓外一圈、发光再往外散。任何一处画多了，
 * 表现都不是崩溃，而是「字变粗了」「边缘发虚」这种说不清的现象。
 *
 * <p>着色器里的距离场划分由 {@code linklyric_sdf_text.fsh} 负责，
 * 这里验证的是配置层与接线层：开关、宽度、浓度、颜色各自的边界，
 * 以及它们到最终 ARGB 的换算。
 */
class StrokeConfigTest {

    /** 默认关闭：描边会改变观感，不能替用户做主打开。 */
    @Test
    void 默认不描边() {
        assertFalse(LyricStyleConfig.DEFAULT.stroke(),
                "描边默认关闭，避免升级后观感突变");
        assertEquals(0, LyricStyleConfig.DEFAULT
                .withStroke(false).strokePx() == LyricStyleConfig.DEFAULT.strokePx()
                ? 0 : 1,
                "关闭时宽度仍保留原值，供下次打开使用");
    }

    /** 宽度越界被夹紧。 */
    @Test
    void 宽度越界被夹紧() {
        assertEquals(LyricStyleConfig.MIN_STROKE_PX,
                LyricStyleConfig.DEFAULT.withStrokePx(0).strokePx());
        assertEquals(LyricStyleConfig.MIN_STROKE_PX,
                LyricStyleConfig.DEFAULT.withStrokePx(-9).strokePx());
        assertEquals(LyricStyleConfig.MAX_STROKE_PX,
                LyricStyleConfig.DEFAULT.withStrokePx(99).strokePx());
    }

    /** 浓度越界被夹紧。 */
    @Test
    void 浓度越界被夹紧() {
        assertEquals(LyricStyleConfig.MIN_STROKE_ALPHA,
                LyricStyleConfig.DEFAULT.withStrokeAlphaPercent(-1).strokeAlphaPercent());
        assertEquals(LyricStyleConfig.MAX_STROKE_ALPHA,
                LyricStyleConfig.DEFAULT.withStrokeAlphaPercent(101).strokeAlphaPercent());
    }

    /**
     * 最终 ARGB 的 alpha 由浓度决定，RGB 取自颜色。
     *
     * <p>颜色与浓度分开存的意义就在这里：改浓度不该动颜色。
     */
    @Test
    void 浓度只影响alpha不动颜色() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT
                .withStrokeColor(0xFF3366CC)
                .withStrokeAlphaPercent(50);
        int argb = s.strokeArgb();
        assertEquals(0x33, ((argb >>> 16) & 0xFF), "红通道应保持");
        assertEquals(0x66, ((argb >>> 8) & 0xFF), "绿通道应保持");
        assertEquals(0xCC, (argb & 0xFF), "蓝通道应保持");
        assertEquals(Math.round(255 * 0.5f), (argb >>> 24) & 0xFF,
                "alpha 应为原 alpha × 浓度");
    }

    /** 浓度 0 时完全透明，但颜色信息不丢。 */
    @Test
    void 浓度为零时alpha为零() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT
                .withStrokeColor(0xFFFF0000)
                .withStrokeAlphaPercent(0);
        assertEquals(0, (s.strokeArgb() >>> 24) & 0xFF, "alpha 应为 0");
        assertEquals(0xFF, ((s.strokeArgb() >>> 16) & 0xFF), "红色仍应保留");
    }

    /** 颜色本身带 alpha 时，浓度在其基础上再乘一次。 */
    @Test
    void 颜色自带alpha与浓度相乘() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT
                .withStrokeColor(0x80FFFFFF)
                .withStrokeAlphaPercent(50);
        // 0x80 = 128，乘 0.5 得 64
        assertEquals(64, (s.strokeArgb() >>> 24) & 0xFF,
                "两层透明度应相乘，而不是互相覆盖");
    }

    /** 写盘再读回来保持。 */
    @Test
    void 描边能存能读(@TempDir Path dir) throws IOException {
        LyricStyleConfig before = LyricStyleConfig.DEFAULT
                .withStroke(true)
                .withStrokePx(3)
                .withStrokeColor(0xFF204080)
                .withStrokeAlphaPercent(65);
        com.linklyric.common.config.LayoutConfig config =
                com.linklyric.common.config.LayoutConfig.load(dir);
        config.saveStyle(before);

        LyricStyleConfig after =
                com.linklyric.common.config.LayoutConfig.load(dir).style();
        assertTrue(after.stroke(), "开关应保持");
        assertEquals(3, after.strokePx(), "宽度应保持");
        assertEquals(0xFF204080, after.strokeColor(), "颜色应保持");
        assertEquals(65, after.strokeAlphaPercent(), "浓度应保持");
    }

    /** 旧配置没有描边键时回退默认，不影响启动。 */
    @Test
    void 缺失键回退默认(@TempDir Path dir) throws IOException {
        java.nio.file.Files.writeString(
                dir.resolve("linklyric-layout.properties"),
                "offsetX=0\noffsetY=0\n");
        LyricStyleConfig s = com.linklyric.common.config.LayoutConfig
                .load(dir).style();
        assertFalse(s.stroke(), "旧配置应回退为不描边");
        assertEquals(LyricStyleConfig.DEFAULT_STROKE_PX, s.strokePx());
        assertEquals(LyricStyleConfig.DEFAULT_STROKE_ALPHA, s.strokeAlphaPercent());
    }

    /** 开关与其它设置互不影响。 */
    @Test
    void 开关不冲掉其它设置() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT
                .withStrokePx(4)
                .withStrokeAlphaPercent(30)
                .withStroke(true)
                .withStroke(false);
        assertEquals(4, s.strokePx(), "关掉后宽度仍应在");
        assertEquals(30, s.strokeAlphaPercent(), "关掉后浓度仍应在");
    }

    /** 描边与发光是两回事，各自独立保存。 */
    @Test
    void 描边与软阴影互不影响() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT
                .withStroke(true)
                .withSoftShadow(false)
                .withShadow(false);
        assertTrue(s.stroke(), "软阴影与阴影的开关不应影响描边");
        assertEquals(LyricStyleConfig.DEFAULT.strokePx(), s.strokePx());
    }
}
