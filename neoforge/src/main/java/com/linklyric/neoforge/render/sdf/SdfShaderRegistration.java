package com.linklyric.neoforge.render.sdf;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * NeoForge 侧的 SDF 着色器注册。
 *
 * <p>与 Fabric 侧的差别只在于装载时机：Fabric 挂在资源重载监听上，
 * 这里必须用 {@link RegisterShadersEvent}——NeoForge 在着色器编译前
 * 才允许把实例登记进去，早了拿不到已加载的资源。
 *
 * <p>事件可能被调用多次（资源重载），因此每次都要替换掉旧实例，
 * 否则旧着色器程序不会被释放。
 */
public final class SdfShaderRegistration {

    private SdfShaderRegistration() {
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        ShaderInstance old = SdfRenderType.current();
        try {
            // 顺序有讲究：先释放图集（它绑着旧纹理 id），再贴新着色器。
            SdfGlyphProvider.reset();
            ShaderInstance shader = new ShaderInstance(event.getResourceProvider(),
                    "linklyric_sdf_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            SdfRenderType.install(shader);
            event.registerShader(shader, s -> SdfRenderType.install(s));
            if (old != null) {
                old.close();
            }
        } catch (IOException | RuntimeException e) {
            SdfRenderType.install(null);
            Minecraft.getInstance();
            org.slf4j.LoggerFactory.getLogger("linklyric")
                    .warn("[Link Lyric] SDF 着色器装载失败，退回原版字形", e);
        }
    }
}
