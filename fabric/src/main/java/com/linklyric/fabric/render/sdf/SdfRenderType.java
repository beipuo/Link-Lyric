package com.linklyric.fabric.render.sdf;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

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
 * 这里用 {@link BoundTextureState} 直接绑纹理 id。
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

    /** 着色器实例。首次使用时装载，失败后保持 null 由调用方降级。 */
    private static ShaderInstance shader;

    /**
     * 缓存键：图集页 + 是否正文层。
     *
     * @param body  true 为正文，false 为阴影
     */
    private record Key(SdfAtlas.Page page, boolean body) {
    }

    private static final Map<Key, RenderType> CACHE = new HashMap<>();

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

    private SdfRenderType() {
    }

    public static void install(ShaderInstance instance) {
        shader = instance;
        CACHE.clear();
    }

    public static ShaderInstance current() {
        return shader;
    }

    /**
     * 丢弃所有缓存的 RenderType。
     *
     * <p>每个 RenderType 都绑定一张具体图集纹理的 id。图集一旦关闭，
     * 这些 id 会被 OpenGL 回收再由别处复用，旧 RenderType 就会绑到
     * 完全无关的纹理——表现为整行字透明或花屏。
     */
    public static void invalidate() {
        CACHE.clear();
    }

    public static boolean usable() {
        return shader != null;
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

    /** 取正文层的 RenderType。 */
    public static RenderType body(SdfAtlas.Page page) {
        return CACHE.computeIfAbsent(new Key(page, true), k -> create(k.page(), true));
    }

    /** 取阴影层的 RenderType。 */
    public static RenderType shadow(SdfAtlas.Page page) {
        return CACHE.computeIfAbsent(new Key(page, false), k -> create(k.page(), false));
    }

    private static RenderType create(SdfAtlas.Page page, boolean body) {
        Supplier<ShaderInstance> supplier = () -> shader;
        // 顶点格式含 LIGHTMAP 属性，就必须配 setLightmapState：
        // 状态不全的 RenderType 在 prepare 阶段拿不到完整绑定，
        // 顶点会被整批丢弃——表现就是「字完全不显示」。
        //
        // ShaderStateShard 的 setup 里设 uniform，正好是刷批的时刻，
        // 此时该层所有顶点都已就绪，uniform 对该层整体生效。
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(supplier) {
                    @Override
                    public void setupRenderState() {
                        super.setupRenderState();
                        ShaderInstance instance = shader;
                        if (instance == null) {
                            return;
                        }
                        // safeGetUniform 在 uniform 缺失时返回空实现，
                        // 不会抛异常，因此旧着色器也能继续工作。
                        instance.safeGetUniform("Sharpness").set(1.0f);
                        instance.safeGetUniform("Weight").set(body ? weight : 0f);
                        instance.safeGetUniform("Softness")
                                .set(body ? BODY_SOFTNESS
                                        : (softShadow ? SHADOW_SOFTNESS : BODY_SOFTNESS));
                    }
                })
                .setTextureState(new BoundTextureState(page))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                .createCompositeState(false);
        return RenderType.create(
                "linklyric_sdf_text_" + (body ? "body" : "shadow"),
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                state);
    }
}