package com.linklyric.fabric.render.sdf;

import com.linklyric.common.style.GlyphSpan;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * 自己驱动 {@link RenderPass} 的 SDF 字形绘制器。
 *
 * <h2>为什么不走 {@code RenderType} + {@code MultiBufferSource}</h2>
 *
 * <p>1.21.5 把着色器 uniform 从 {@code ShaderInstance} 搬到了
 * {@code RenderPass} 上。{@code MultiBufferSource} 在内部自己创建并关闭
 * {@code RenderPass}，模组拿不到那个对象，也就没有公开入口去写
 * {@code Weight}、{@code GlowRadius} 这类每批要变的参数——
 * {@code RenderSystem} 的公开方法里一个设置 uniform 的都没有。
 *
 * <p>这些参数也不能烘进顶点属性：{@code COLOR} 已被渐变占用，
 * {@code UV2} 只是 16 位精度。所以只能自己建 {@code RenderPass}，
 * 在 {@code drawIndexed} 之前把 uniform 写进去。
 *
 * <p>代价是每次绘制自己上传顶点缓冲。一屏歌词只画一次阴影加一次正文，
 * 这点开销远小于丢掉 SDF 带来的画质损失。
 */
final class SdfPassDrawer {

    /** 顶点缓冲初始容量（字节）。一屏歌词的四边形远小于这个数。 */
    private static final int VERTEX_BYTES = 8192;

    private SdfPassDrawer() {
    }

    /**
     * 绘制一层字形。
     *
     * @param pipeline 已建好的管线
     * @param texture  图集页纹理
     * @param pose     当前变换矩阵
     * @param slot     图集区域
     * @param box      绘制盒 {@code [left, top, right, bottom, advance]}
     * @param x        字形原点横坐标
     * @param y        基线纵坐标
     * @param spans    颜色段
     * @param originX  段坐标锚点，必须是行首位置
     * @param light    打包光照坐标
     * @param uniforms 绘制前写入 uniform 的回调
     * @return false 表示没有写入任何顶点
     */
    static boolean draw(RenderPipeline pipeline, GpuTexture texture, Matrix4f pose,
                        SdfAtlas.Slot slot, float[] box, float x, float y,
                        GlyphSpan[] spans, float originX, int light,
                        Consumer<RenderPass> uniforms) {
        if (slot == null || box == null || spans == null || spans.length == 0) {
            return false;
        }
        // 顶点先攒进 BufferBuilder，再一次性交给 GPU。
        ByteBufferBuilder backing = new ByteBufferBuilder(VERTEX_BYTES);
        MeshData mesh = null;
        try {
            BufferBuilder builder = new BufferBuilder(backing,
                    VertexFormat.Mode.QUADS, pipeline.getVertexFormat());
            if (!SdfGlyphWriter.writeSpans(builder, pose, slot, box, x, y,
                    spans, originX, light)) {
                return false;
            }
            mesh = builder.build();
            if (mesh == null) {
                return false;
            }
            return submit(pipeline, texture, mesh, uniforms);
        } finally {
            if (mesh != null) {
                mesh.close();
            }
            backing.close();
        }
    }

    /** 上传顶点、开一个 RenderPass、写 uniform、画、关掉。 */
    private static boolean submit(RenderPipeline pipeline, GpuTexture texture,
                                  MeshData mesh, Consumer<RenderPass> uniforms) {
        RenderSystem.assertOnRenderThread();
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            return false;
        }
        GpuBuffer vertices = pipeline.getVertexFormat()
                .uploadImmediateVertexBuffer(mesh.vertexBuffer());
        RenderSystem.AutoStorageIndexBuffer indices =
                RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        int indexCount = mesh.drawState().indexCount();
        GpuBuffer indexBuffer = indices.getBuffer(indexCount);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
                target.getColorTexture(),
                OptionalInt.empty(),
                target.useDepth ? target.getDepthTexture() : null,
                OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indexBuffer, indices.type());
            // sampler 名字必须与管线里 withSampler("Sampler0") 一致。
            pass.bindSampler("Sampler0", texture);
            if (RenderSystem.SCISSOR_STATE.isEnabled()) {
                pass.enableScissor(RenderSystem.SCISSOR_STATE);
            }
            // 每批要变的参数在这里写：此刻 pass 已就绪，
            // 而顶点也已在缓冲里，uniform 对本层整体生效。
            uniforms.accept(pass);
            pass.drawIndexed(0, indexCount);
        }
        return true;
    }
}
