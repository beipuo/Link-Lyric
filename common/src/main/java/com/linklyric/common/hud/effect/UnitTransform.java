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
     * 旋转角度，单位为度，<b>顺时针为正</b>，围绕字形中心。
     *
     * <p>符号与 {@link com.linklyric.common.hud.Pose2D#rotate} 完全一致，
     * 绘制层原样传入、不做取反：两端平台层已各自把「正角 = 顺时针」
     * 钉在实现里（依据见 {@code Pose2D.rotate} 的注释——底层
     * {@code Axis.ZP.rotationDegrees} 把 +X 基推向 +Y，而 GUI 的正交投影
     * 里 +Y 指向屏幕下方）。若在这里取反，旋转方向就会与
     * {@code FloatingLyricRenderer} 已有的两处姿态变换相反，
     * 同一个角度在两处转出两个方向。
     *
     * <p>0 表示不旋转，也是 {@link #reset()} 恢复的值。
     */
    public float rotate;

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
        // 新字段必须在这里归零，否则会跨帧残留。
        //
        // 这个对象是复用的（{@code LyricEffectEngine} 只有一个实例，
        // 每帧每单元 reset 一次以避免分配），因此任何「忘了重置」的字段
        // 都会带着上一帧、上一个字的值继续被绘制层读取：
        // 表现为某个字突然保持旋转，且只在特定特效开启时才复现。
        rotate = 0f;
        bloom = 0f;
        glowRadius = 0f;
        glowAlpha = 0f;
    }

    /** 是否需要在绘制层开启外发光。 */
    public boolean hasGlow() {
        return glowRadius > 0f && glowAlpha > 0f;
    }
}
