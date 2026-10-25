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
     * <p>只把字形颜色朝白色推，不改像素位置。与 {@link #glowAlpha} 分工不同：
     * 提亮作用于<b>字形本身</b>（笔画变白），光晕作用于<b>字形之外</b>
     * （轮廓周围散开的一圈）。两者叠加才是完整的「被点亮」。
     */
    public float bloom;

    /**
     * 外发光半径，屏幕像素。
     *
     * <p>由 SDF 的距离场直接算出：轮廓外侧的场值就是到边缘的距离，
     * 因此这是一圈按像素精确衰减的光晕，不需要额外的模糊批次。
     *
     * <p>0 表示不发光。
     */
    public float glowRadius;

    /**
     * 外发光强度，0..1。
     *
     * <p>与 {@link #glowRadius} 分开：半径是形状，强度是亮度。
     * 上游把两者绑在一起（半径恒定、强度渐变），这里同样如此，
     * 但拆成两个量才能在动画里只淡出亮度而不改变光晕形状——
     * 形状突变会看到光晕「缩一下」，只淡亮度则是自然地散掉。
     */
    public float glowAlpha;

    public void reset() {
        dx = 0f;
        dy = 0f;
        alpha = 1f;
        scale = 1f;
        bloom = 0f;
        glowRadius = 0f;
        glowAlpha = 0f;
    }

    /** 是否需要在绘制层开启外发光。 */
    public boolean hasGlow() {
        return glowRadius > 0f && glowAlpha > 0f;
    }
}
