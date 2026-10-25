package com.linklyric.common.hud.effect;

/**
 * 单个绘制单元的呈现变换。
 *
 * <p>是可复用的可变对象：每帧每个单元绘制前重置，特效依次叠加。
 * 特效约定为纯函数，只读 {@link EffectUnit} 与 {@link EffectParam}，只写本对象。
 */
public final class UnitTransform {

    /** 恒等变换，供没有特效时复用。 */
    public static final UnitTransform IDENTITY = new UnitTransform();

    /** 水平位移，像素，向右为正。 */
    public float dx;

    /** 垂直位移，像素，向上为正（与屏幕坐标相反）。 */
    public float dy;

    /** 透明度，1 为不透明。 */
    public float alpha = 1f;

    /** 缩放，围绕字形中心。 */
    public float scale = 1f;

    /**
     * 提亮强度，0..1。
     *
     * <p>强调动画的「发光」不用光晕实现：原版字体是位图图集，
     * 任何位移或缩放都会让字发糊，而 9px 字上几个像素的光晕本身也看不出来。
     * 改为把字形颜色朝白色提亮——像素位置不变，边缘仍与图集一致，
     * 观感是字被点亮，代价为零。
     */
    public float bloom;

    public void reset() {
        dx = 0f;
        dy = 0f;
        alpha = 1f;
        scale = 1f;
        bloom = 0f;
    }
}
