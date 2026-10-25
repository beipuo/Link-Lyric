package com.linklyric.fabric.render.sdf;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderStateShard;

/**
 * 直接绑定一个已存在的 GL 纹理 id 的纹理状态。
 *
 * <h2>为什么不能用 {@code TextureStateShard}</h2>
 *
 * <p>原版那个只接受 {@code ResourceLocation}，setup 时走
 * {@code TextureManager.getTexture}，也就是「从资源包读文件」。
 * 但图集内容是运行时逐块上传进 GL 纹理的，磁盘上没有对应文件，
 * 也不该有——那是一张由字形拼出来的页。
 *
 * <p>强行按资源加载会抛 {@code AccessDeniedException}（路径指向资源目录本身），
 * 纹理绑不上，shader 采到空纹理，整行字全透明。这正是曾经的现象。
 *
 * <p>{@code EmptyTextureStateShard} 的构造只接受一对 Runnable，
 * 分别在 setup / clear 时执行。绑定动作放在 setup 里，
 * 因为别的绘制随时可能把纹理槽换成别的纹理。
 */
public final class BoundTextureState extends RenderStateShard.EmptyTextureStateShard {

    private final int textureId;

    public BoundTextureState(SdfAtlas.Page page) {
        super(() -> RenderSystem.setShaderTexture(0, page.getId()), () -> {});
        this.textureId = page.getId();
    }

    public int textureId() {
        return textureId;
    }

    @Override
    public String toString() {
        return "LinkLyricSdfTexture[" + textureId + "]";
    }
}
