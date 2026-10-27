package com.linklyric.neoforge.render.sdf;

import com.linklyric.common.style.GlyphSpan;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * SDF 文字专用的 {@link RenderType}。
 *
 * <h2>为什么不能复用原版的 {@code RenderType.text}</h2>
 *
 * <p>原版 {@code rendertype_text.fsh} 把纹理值当作颜色乘数：
 * {@code texture(Sampler0, uv) * vertexColor}。位图字体的纹理就是
 * 覆盖率本身，这样乘正好。但距离场的纹理值是「到轮廓的带符号距离」，
 * 直接当成颜色乘出来是一片灰块。
 *
 * <p>必须走自定义着色器，把距离值经梯度换算成覆盖率，
 * 这才能得到任意缩放都锐利的边缘。
 *
 * <h2>纹理绑定</h2>
 *
 * <p>图集是运行时上传的 GL 纹理，磁盘上没有文件，
 * 因此不能按 {@code ResourceLocation} 走资源加载——
 * 那是 {@code TextureStateShard} 的行为，会去读一个不存在的文件。
 * 这里由 {@link SdfPassDrawer} 在 {@code RenderPass} 上直接绑 sampler。
 *
 * <h2>为什么正文与阴影是两个 RenderType</h2>
 *
 * <p>顶点是攒批的：{@code MultiBufferSource} 在遇到不同的 RenderType
 * 时才把上一批刷出去。字重与柔化是着色器 uniform，属于全局状态，
 * 若正文与阴影共用一个 RenderType，两层的顶点会落进同一批，
 * 只能有一份 uniform 生效——结果是阴影把正文一起糊掉，或正文把
 * 阴影一起变锐利。
 *
 * <p>所以按「页 + 是否正文」四个组合各建一个 RenderType。
 * 每个 RenderType 有自己的 ShaderStateShard，在 setup 时设置对应的
 * uniform，批处理自然就分成两批。代价是绘制调用翻倍，
 * 但一屏歌词只有一次阴影、一次正文，实际影响可以忽略。
 */
public final class SdfRenderType {

    /**
     * 着色器声明。1.21.2 起着色器改为「声明 + 配置」：
     * {@link ShaderProgram} 只是 configId 与顶点格式的载体，
     * 真正的编译产物由 {@code ShaderManager.getProgram} 按它取回。
     * 因此这里持有声明，程序在 setup 时现取，避免缓存到过期的程序。
     */
    private static volatile boolean ready;

    /**
     * 当前描边宽度与颜色。
     *
     * <p>和发光一样是每批 setup 时读取的全局状态。**必须参与分批**：
     * 若描边与不描边的顶点落进同一批，那一批只会有一次 setup，
     * 要么全都描边、要么全都不描边，取决于最后的赋值顺序。
     */
    private static volatile float strokeWidth;
    private static volatile float strokeRed;
    private static volatile float strokeGreen;
    private static volatile float strokeBlue;
    private static volatile float strokeAlpha;

    /**
     * 设置当前描边。
     *
     * @param width 宽度，屏幕像素；0 表示不描边
     * @param argb  颜色，含 alpha
     */
    public static void setStroke(float width, int argb) {
        strokeWidth = Math.max(0f, width);
        strokeAlpha = ((argb >>> 24) & 0xFF) / 255f;
        strokeRed = ((argb >>> 16) & 0xFF) / 255f;
        strokeGreen = ((argb >>> 8) & 0xFF) / 255f;
        strokeBlue = (argb & 0xFF) / 255f;
    }

    /** 当前是否在发光；描边据此让位。 */
    public static boolean glowing() {
        return glowRadius > 0f && glowAlpha > 0f;
    }

    /** 关闭描边。 */
    public static void clearStroke() {
        setStroke(0f, 0);
    }

    /** 当前是否有描边，供分批决策。 */
    private static boolean strokeEnabled() {
        return strokeWidth > 0f && strokeAlpha > 0f;
    }

    /**
     * 管线缓存，键是正文/阴影 × 发光 × 描边的组合名。
     *
     * <p>管线不含纹理，资源重载后仍然有效，因此这张表不随
     * {@link #invalidate()} 清空。
     */
    private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();

    /**
     * 笔画加粗的取值范围。
     *
     * <p>距离场被归一化成 0..1，0.15 约等于一个半像素的加厚，
     * 肉眼能看出笔画变实而字距不变。上限压在 0.15：再往上一是
     * 笔画粘连，二是小字号会糊成一团。
     */
    private static final float MAX_WEIGHT = 0.15f;

    /**
     * 阴影的柔化倍数。
     *
     * <p>柔化靠放大 {@code aa}，也就是把边缘过渡拉宽。
     * 2.5 倍已能把一格像素的硬边化成柔和投影，
     * 再大就散成一团雾、失去「落在纸面上的影子」的感觉。
     */
    private static final float SHADOW_SOFTNESS = 2.5f;
    private static final float BODY_SOFTNESS = 1.0f;

    /** 阴影相对正文的偏移（像素）。原版也是右下方一格。 */
    private static final float SHADOW_OFFSET = 1.0f;

    /**
     * 当前字重，由渲染器每帧写入。
     *
     * <p>uniform 在每批 setup 时读取，而 setup 发生在攒批之后的
     * 刷批时刻，因此这里必须是「绘制时最新」的值，不能只在
     * 配置变化时更新一次。
     */
    private static volatile float weight;

    /**
     * 阴影是否柔和。
     *
     * <p>关闭时阴影层的柔化倍数回到 1.0，即与正文同样锐利，
     * 效果接近原版的硬边投影。留着这个开关是因为柔和阴影
     * 在深色背景上会比原版更"脏"，有人就是喜欢硬边。
     */
    private static volatile boolean softShadow = true;

    /** 设置阴影是否柔和。 */
    public static void setSoftShadow(boolean value) {
        softShadow = value;
    }

    /**
     * 当前帧的外发光半径与强度。
     *
     * <p>和字重一样是每批 setup 时读取的全局状态。半径参与分批决策，
     * 强度只是在 setup 时设进 uniform。
     */
    private static volatile float glowRadius;
    private static volatile float glowAlpha;

    /**
     * 当前字形可用的光晕距离（设计空间）。
     *
     * <p>由烘焙留白算出，绘制层在设置光晕半径时一并给出。
     * 着色器据此封顶，避免光晕在纹理边界被截断成描边。
     */
    private static volatile float glowLimit = 2f;

    /** 设置当前帧的外发光参数。 */
    public static void setGlow(float radius, float alpha) {
        glowRadius = Math.max(0f, radius);
        glowAlpha = Math.max(0f, Math.min(1f, alpha));
    }

    /** 设置当前字形可用的光晕距离（设计空间，非屏幕像素）。 */
    public static void setGlowLimit(float distance) {
        glowLimit = Math.max(0f, distance);
    }

    private SdfRenderType() {
    }

    /**
     * 标记 SDF 路径可用。
     *
     * <p>1.21.5 里着色器由 {@link RenderPipeline} 描述，首次使用时
     * 由原版编译，模组侧没有「注册」这一步；调用方在确认资源就绪后
     * 调这里一次即可。
     */
    public static void install() {
        ready = true;
    }

    /** 图集重建后无需丢弃任何缓存：纹理从 {@code slot.page()} 现取。 */
    public static void invalidate() {
        // 管线不含纹理，图集换页不影响它们。
    }

    public static boolean usable() {
        return ready;
    }

    /**
     * 把界面上的字重百分比映射成着色器的加粗量。
     *
     * <p>界面用 0..100 表达，0 表示不加粗。映射到 0..MAX_WEIGHT 而不是
     * 直接当像素用：一个像素在 9px 的字上已占十分之一高度，
     * 直接加会引起笔画粘连。
     */
    public static float weightFor(int percent) {
        if (percent <= 0) {
            return 0f;
        }
        int clamped = Math.min(100, percent);
        return MAX_WEIGHT * (clamped / 100f);
    }

    /** 设置当前字重（百分比）。渲染器每帧调用一次即可。 */
    public static void setWeightPercent(int percent) {
        weight = weightFor(percent);
    }

    /** 阴影相对正文的偏移量（像素）。 */
    public static float shadowOffset() {
        return SHADOW_OFFSET;
    }

    /**
     * 绘制正文层。
     *
     * <p>1.21.5 起不再产出 {@code RenderType}：uniform 只能在
     * {@code RenderPass} 上写，而 {@code MultiBufferSource} 不给模组
     * 那个对象。因此改成「取管线 + 自己开 pass」，入口见
     * {@link SdfPassDrawer}。
     */
    public static boolean drawBody(SdfAtlas.Slot slot, Matrix4f pose, float[] box,
                                   float x, float y, GlyphSpan[] spans, float originX,
                                   int light) {
        boolean glowOn = glowing();
        boolean strokeOn = strokeEnabled();
        return SdfPassDrawer.draw(
                pipelineFor(true, glowOn, strokeOn), slot.page().texture(), pose,
                slot, box, x, y, spans, originX, light,
                pass -> applyUniforms(pass, true, glowOn, strokeOn));
    }

    /**
     * 取阴影层的 RenderType。
     *
     * <p>阴影层不描边：阴影本身就是一圈糊掉的暗色轮廓，
     * 再给它描边会变成两圈套在一起，边界彻底糊掉。
     * 因此这里的 Key 恒为 {@code glow=false}，与描边状态无关。
     */
    public static boolean drawShadow(SdfAtlas.Slot slot, Matrix4f pose, float[] box,
                                    float x, float y, GlyphSpan[] spans, float originX,
                                    int light) {
        // 阴影层的发光与描边两位恒为 false，与当前状态无关。
        return SdfPassDrawer.draw(
                pipelineFor(false, false, false), slot.page().texture(), pose,
                slot, box, x, y, spans, originX, light,
                pass -> applyUniforms(pass, false, false, false));
    }

    /**
     * 按当前状态取对应的 {@link RenderPipeline}。
     *
     * <p>正文与阴影必须分开：字重、柔化、发光、描边都是管线级的 uniform，
     * 两支顶点若共用一个管线，只会有一份 uniform 生效，
     * 结果是阴影把正文一起糊掉，或正文把阴影一起变锐利。
     */
    private static RenderPipeline pipelineFor(boolean body, boolean glowEnabled,
                                              boolean strokeEnabled) {
        String suffix = (body ? "body" : "shadow")
                + (glowEnabled ? "_glow" : "")
                + (strokeEnabled ? "_stroke" : "");
        return PIPELINES.computeIfAbsent(suffix, key -> RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        "linklyric", "pipeline/sdf_text_" + key))
                // 着色器路径沿用 assets/linklyric/shaders/core/linklyric_sdf_text。
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                        "linklyric", "core/linklyric_sdf_text"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                        "linklyric", "core/linklyric_sdf_text"))
                .withSampler("Sampler0")
                .withUniform("ModelViewMat", UniformType.MATRIX4X4)
                .withUniform("ProjMat", UniformType.MATRIX4X4)
                .withUniform("ColorModulator", UniformType.VEC4)
                .withUniform("Sharpness", UniformType.FLOAT)
                .withUniform("Weight", UniformType.FLOAT)
                .withUniform("Softness", UniformType.FLOAT)
                .withUniform("GlowRadius", UniformType.FLOAT)
                .withUniform("GlowAlpha", UniformType.FLOAT)
                .withUniform("GlowLimit", UniformType.FLOAT)
                .withUniform("StrokeWidth", UniformType.FLOAT)
                .withUniform("StrokeColor", UniformType.VEC4)
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                        VertexFormat.Mode.QUADS)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withCull(false)
                .build());
    }

    /**
     * 在绘制前写入这一层的 uniform。
     *
     * <p>矩阵用当前 GUI 阶段的值：GUI 顶点已经在 CPU 侧乘过 pose，
     * 这里给单位矩阵即可，与原版 {@code RenderType.text} 的约定一致。
     */
    private static void applyUniforms(RenderPass pass, boolean body,
                                      boolean glowEnabled, boolean strokeEnabled) {
        pass.setUniform("ModelViewMat", new Matrix4f());
        pass.setUniform("ProjMat", RenderSystem.getProjectionMatrix());
        pass.setUniform("ColorModulator", 1f, 1f, 1f, 1f);
        pass.setUniform("Sharpness", 1f);
        pass.setUniform("Weight", body ? weight : 0f);
        pass.setUniform("Softness", body ? BODY_SOFTNESS
                : (softShadow ? SHADOW_SOFTNESS : BODY_SOFTNESS));
        // 光晕只给正文层：阴影层本就是一圈糊掉的影子，
        // 再叠发光会变成两团混在一起，边界全失。
        boolean glowOn = body && glowEnabled;
        pass.setUniform("GlowRadius", glowOn ? glowRadius : 0f);
        pass.setUniform("GlowAlpha", glowOn ? glowAlpha : 0f);
        pass.setUniform("GlowLimit", glowLimit);
        // 描边只给正文层，理由同发光：阴影层不描边。
        boolean strokeOn = body && strokeEnabled;
        pass.setUniform("StrokeWidth", strokeOn ? strokeWidth : 0f);
        pass.setUniform("StrokeColor",
                strokeOn ? strokeRed : 0f,
                strokeOn ? strokeGreen : 0f,
                strokeOn ? strokeBlue : 0f,
                strokeOn ? strokeAlpha : 0f);
    }
}