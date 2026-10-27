package com.linklyric.neoforge.render.sdf;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/**
 * NeoForge 侧的 SDF 着色器注册。
 *
 * <p>与 Fabric 侧的差别只在于登记时机：Fabric 在资源重载监听里登记，
 * 这里必须走 {@link RegisterShadersEvent}，NeoForge 才会在启动时预编译。
 *
 * <p>1.21.2 起着色器是声明式的，登记的是 {@link ShaderProgram} 而非
 * 已编译的实例，因此不再需要释放旧的程序。
 */
public final class SdfShaderRegistration {

    private SdfShaderRegistration() {
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        // 顺序有讲究：先释放图集（它绑着旧纹理 id），再贴新着色器。
        SdfGlyphProvider.reset();
        // 1.21.2 起着色器改为声明式：登记 configId 与顶点格式即可，
        // 编译由 ShaderManager 在首次 getProgram 时完成并缓存。
        ShaderProgram program = new ShaderProgram(
                ResourceLocation.fromNamespaceAndPath("linklyric", "linklyric_sdf_text"),
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                ShaderDefines.EMPTY);
        // 必须交给事件登记，NeoForge 会在启动时预编译它；
        // 不登记的话首次 getProgram 发生在渲染线程，编译会失败。
        event.registerShader(program);
        SdfRenderType.install(program);
    }
}
