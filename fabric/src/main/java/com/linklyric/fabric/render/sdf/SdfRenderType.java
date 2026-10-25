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
 * <h2>顶点色仍然逐顶点插值</h2>
 *
 * <p>着色器里的 {@code vertexColor} 就是顶点属性，GPU 按像素线性插值。
 * 因此既有的字内渐变（{@code GlyphSpan} → 逐顶点写入）不需要任何改动，
 * 换成 SDF 后依然生效。
 */
public final class SdfRenderType {

    /** 着色器实例。首次使用时装载，失败后保持 null 由调用方降级。 */
    private static ShaderInstance shader;

    /** 按图集页缓存 RenderType。 */
    private static final Map<SdfAtlas.Page, RenderType> CACHE = new HashMap<>();

    private SdfRenderType() {
    }

    /** 由外部（客户端初始化）注入已装载的着色器实例；null 表示不可用。 */
    public static void install(ShaderInstance instance) {
        shader = instance;
        CACHE.clear();
    }

    /** 当前已装载的着色器实例，供重载时释放。 */
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
     * 取某张图集页对应的 RenderType。
     *
     * <p>每页一个 RenderType 是不可避免的：一张 RenderType 绑定一张纹理。
     * 页数在设计上被压到最少（1024×1024 一页装得下一屏的字），
     * 因此通常只有一次绘制批次。
     */
    public static RenderType get(SdfAtlas.Page page) {
        return CACHE.computeIfAbsent(page, SdfRenderType::create);
    }

    private static RenderType create(SdfAtlas.Page page) {
        Supplier<ShaderInstance> supplier = () -> shader;
        // 顶点格式含 LIGHTMAP 属性，就必须配 setLightmapState：
        // 状态不全的 RenderType 在 prepare 阶段拿不到完整绑定，
        // 顶点会被整批丢弃——表现就是「字完全不显示」。
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(supplier))
                .setTextureState(new BoundTextureState(page))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                .createCompositeState(false);
        return RenderType.create("linklyric_sdf_text",
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                state);
    }
}
