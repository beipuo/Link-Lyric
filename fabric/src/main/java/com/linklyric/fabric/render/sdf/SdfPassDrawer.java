package com.linklyric.fabric.render.sdf;

import com.linklyric.common.style.GlyphSpan;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * 自己驱动 {@link RenderPass} 的 SDF 字形绘制器。
 *
 * <h2>为什么不走 {@code RenderType} + {@code MultiBufferSource}</h2>
 *
 * <p>1.21.5 把着色器参数从 {@code ShaderInstance} 搬到了 {@code RenderPass} 上，
 * 1.21.6 又进一步改成只认 std140 的 uniform 缓冲。{@code MultiBufferSource}
 * 在内部自己创建并关闭 {@code RenderPass}，模组拿不到那个对象，
 * 也就没有公开入口去写 {@code Weight}、{@code GlowRadius} 这类
 * 每批要变的参数——{@code RenderSystem} 的公开方法里一个都没有。
 *
 * <p>这些参数也不能烘进顶点属性：{@code COLOR} 已被渐变占用，
 * {@code UV2} 只是 16 位精度。所以只能自己建 {@code RenderPass}，
 * 在 {@code drawIndexed} 之前把参数块写进去。
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
     * @param view     图集页纹理视图
     * @param pose     当前变换矩阵
     * @param slot     图集区域
     * @param box      绘制盒 {@code [left, top, right, bottom, advance]}
     * @param x        字形原点横坐标
     * @param y        基线纵坐标
     * @param spans    颜色段
     * @param originX  段坐标锚点，必须是行首位置
     * @param light    打包光照坐标
     * @param params   参数块取值，顺序见 {@link SdfRenderType}
     * @return false 表示没有写入任何顶点
     */
    static boolean draw(RenderPipeline pipeline, SdfAtlas.Page page, Matrix4f pose,
                        SdfAtlas.Slot slot, float[] box, float x, float y,
                        GlyphSpan[] spans, float originX, int light, float[] params) {
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
            return submit(pipeline, page, mesh, params);
        } finally {
            if (mesh != null) {
                mesh.close();
            }
            backing.close();
        }
    }

    /** 上传顶点、开一个 RenderPass、写参数块、画、关掉。 */
    private static boolean submit(RenderPipeline pipeline, SdfAtlas.Page page,
                                  MeshData mesh, float[] params) {
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
        // 参数缓冲每帧新建：内容随字重、光晕、描边变化，
        // 复用还得自己比对脏值，不如直接重传这几十字节。
        GpuBuffer paramsBuffer = uploadParams(encoder, params);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "LinkLyricSdf",
                target.getColorTextureView(),
                OptionalInt.empty(),
                target.useDepth ? target.getDepthTextureView() : null,
                OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indexBuffer, indices.type());
            // 1.21.11 绑定时要连采样器一起给：寻址与过滤已经从纹理
            // 搬到独立的 GpuSampler 上，光有视图不够。
            // 名字必须与管线里 withSampler("Sampler0") 一致。
            pass.bindTexture("Sampler0", page.view(), page.sampler());
            // 1.21.6 把 SCISSOR_STATE 常量换成访问器，且 enableScissor
            // 直接收矩形、不再收 ScissorState。
            ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissor.enabled()) {
                pass.enableScissor(scissor.x(), scissor.y(),
                        scissor.width(), scissor.height());
            }
            // 参数块整块上传，名字与管线里 withUniform("SdfParams") 一致。
            pass.setUniform("SdfParams",
                    new GpuBufferSlice(paramsBuffer, 0, paramsBuffer.size()));
            pass.drawIndexed(0, indexCount, 0, 1);
        } finally {
            paramsBuffer.close();
        }
        return true;
    }

    /**
     * 按 std140 打包参数块。
     *
     * <p>布局必须与着色器里 {@code SdfParams} 的字段声明顺序逐字对应：
     * 七个 float 之后是一个 vec4。vec4 需要 16 字节对齐，
     * 而七个 float 只占 28 字节，因此中间必须先 {@code align(4)}，
     * 否则 {@code StrokeColor} 会整体错位、读成垃圾值。
     */
    private static GpuBuffer uploadParams(CommandEncoder encoder, float[] params) {
        int size = new Std140SizeCalculator()
                .putFloat().putFloat().putFloat().putFloat()
                .putFloat().putFloat().putFloat()
                .putVec4()
                .get();
        ByteBuffer packed = ByteBuffer.allocateDirect(size);
        Std140Builder builder = Std140Builder.intoBuffer(packed);
        builder.putFloat(params[0])   // Sharpness
                .putFloat(params[1])  // Weight
                .putFloat(params[2])  // Softness
                .putFloat(params[3])  // GlowRadius
                .putFloat(params[4])  // GlowAlpha
                .putFloat(params[5])  // GlowLimit
                .putFloat(params[6])  // StrokeWidth
                .putVec4(params[7], params[8], params[9], params[10]);
        packed.flip();
        return RenderSystem.getDevice().createBuffer(
                () -> "LinkLyricSdfParams",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                packed);
    }
}
