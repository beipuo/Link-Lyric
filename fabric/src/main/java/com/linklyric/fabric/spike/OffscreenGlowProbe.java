package com.linklyric.fabric.spike;

import com.linklyric.fabric.LinkLyricFabric;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 离屏 framebuffer 可行性探针。
 *
 * <h2>只验证一件事</h2>
 *
 * <p>「HUD 阶段换渲染目标 → 画进离屏 → 换回来 → 当纹理采样」这条链路通不通。
 * 通了，整屏弥漫光晕才有实现基础；不通，那条路线就得降级。
 *
 * <h2>为什么用 {@link RenderTarget} 而不是裸 {@code glGenFramebuffers}</h2>
 *
 * <p>两条路在 1.21.1 上都编译得过：{@code GlStateManager} 的
 * {@code _glBindFramebuffer} / {@code _glFramebufferTexture2D} / {@code _genTexture}
 * 全都是 public。选前者的理由是**它替我们兜住了三件最容易埋雷的事**：
 *
 * <ol>
 *   <li><b>视口。</b>{@code bindWrite(true)} 内部会连带设置
 *       {@code _viewport(0, 0, viewWidth, viewHeight)}。裸 GL 忘了设视口
 *       会照样画、照样不报错，只是画在错误的像素区域上——
 *       这种 bug 只能靠肉眼看出来。</li>
 *   <li><b>尺寸校验。</b>{@code createBuffers} 拿 {@code maxSupportedTextureSize}
 *       卡住非法尺寸并直接抛，而不是留一个永远 incomplete 的 FBO
 *       让你在别处追查黑屏。</li>
 *   <li><b>线程转发。</b>{@code resize}/{@code bindWrite} 在非渲染线程调用时
 *       会转成 {@code recordRenderCall} 排队，而不是当场打 GL 调用炸掉。
 *       本探针全程在渲染线程，用不上这层，但它让调用点不必自己判断线程。</li>
 * </ol>
 *
 * <p>它<strong>没有</strong>替我们做的是清屏语义上的自由度：
 * {@link RenderTarget#clear} 的实现是 {@code bindWrite → glClear → unbindWrite}，
 * 它顺手把绑定解掉了。所以本类不用 {@code clear()}，而是先 {@code bindWrite}
 * 再 {@code RenderSystem.clear}，把「绑定」与「清屏」两件事分开控制。
 * 这个细节很关键：用 {@code clear()} 再画，内容会画回主屏上去。
 */
public final class OffscreenGlowProbe {

    /**
     * 离屏缓冲尺寸。
     *
     * <p>刻意取一个明显小于屏幕的固定值。除了开销小，还有个更有用的副作用：
     * 它逼着合成阶段真的做一次**缩放采样**，而不是 1:1 拷贝。
     * 后面做多级降采样模糊时，每一级正是这种带缩放的采样——
     * 若这里只能 1:1 才通，那条路线要到更后期才暴露问题。
     */
    private static final int OFFSCREEN_WIDTH = 256;
    private static final int OFFSCREEN_HEIGHT = 256;

    /** 关闭态：什么都不做。探针必须能完全隐身。 */
    private static final HudHook NOOP = (graphics, partialTick) -> {
    };

    private static final HudHook DRAW = (graphics, partialTick) ->
            drawFrame(graphics, partialTick);

    /**
     * 当前挂着的回调。
     *
     * <p>volatile：指令回调读渲染线程状态，允许跨线程读到完整对象。
     */
    private static volatile HudHook current = NOOP;

    /** 离屏缓冲。null 表示尚未创建。 */
    private static RenderTarget target;

    /**
     * 失败即熔断。
     *
     * <p>没有这道闸门，一次 FBO 创建失败会在每个 HUD 帧重试一次，
     * 日志刷屏到看不出别的问题。探针的职责是「报告不可行」，不是「反复摔倒」。
     */
    private static boolean failed;

    /** 首帧日志只打一次，避免把日志变成每帧噪音。 */
    private static boolean loggedFirstFrame;

    private OffscreenGlowProbe() {
    }

    /** 供 HUD 回调读取当前钩子。 */
    public static HudHook current() {
        return current;
    }

    /** 探针是否已启用。 */
    public static boolean enabled() {
        return current != NOOP;
    }

    /**
     * 开关探针。
     *
     * <p>关闭时**不释放**离屏缓冲：反复开关会反复创建/销毁 FBO 与纹理，
     * 每次都留下一串要单独排查的 GL 生命周期问题。探针这种一次性代码
     * 不值得为它做对象池，缓冲随游戏退出一起走。
     *
     * @return 切换后是否处于开启状态
     */
    public static boolean toggle() {
        if (enabled()) {
            current = NOOP;
            LinkLyricFabric.LOGGER.info("[Link Lyric] 离屏探针已关闭");
            return false;
        }
        failed = false;
        loggedFirstFrame = false;
        current = DRAW;
        LinkLyricFabric.LOGGER.info("[Link Lyric] 离屏探针已开启");
        return true;
    }

    /** 释放离屏缓冲。仅在确认要长期关闭时调用。 */
    public static void dispose() {
        current = NOOP;
        if (target == null) {
            return;
        }
        if (target.frameBufferId >= 0) {
            try {
                target.destroyBuffers();
            } catch (RuntimeException e) {
                LinkLyricFabric.LOGGER.warn("[Link Lyric] 离屏缓冲释放失败", e);
            }
        }
        target = null;
    }

    /**
     * 一帧的完整链路：离屏绘制 → 切回主屏 → 采样合成。
     *
     * <p>整段包在 try/catch 里。探针是**允许坏掉**的东西，
     * 它坏掉时不能顺手把同帧的歌词一起带走——所以失败即自我关闭，
     * 让下一帧回到与未装探针时完全一致的路径。
     */
    private static void drawFrame(GuiGraphics graphics, float partialTick) {
        if (failed) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) {
            return;
        }
        try {
            renderOffscreen(client);
            composite(client);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            current = NOOP;
            LinkLyricFabric.LOGGER.error(
                    "[Link Lyric] 离屏探针失败，已自动关闭以免影响正常渲染", e);
        }
    }

    /**
     * 第一步：把内容画进离屏缓冲。
     *
     * <p>每一步的顺序都不是随手写的：
     *
     * <ol>
     *   <li><b>先 endBatch。</b>此刻 {@code GuiGraphics} 里还攒着本帧
     *       GUI 元素（血量、物品栏、聊天……）的顶点，它们的目标是<strong>主屏</strong>。
     *       不先刷掉就换 FBO，那些顶点会被画进离屏纹理里，
     *       表现为「屏幕上的 HUD 少了一块，而离屏纹理里凭空多了一块」。
     *       这是本探针最容易踩、也最容易误判成「渲染管线不可用」的坑。</li>
     *   <li><b>再 bindWrite(true)。</b>true 表示同时把视口设成离屏尺寸。</li>
     *   <li><b>清屏。</b>不清则离屏内容会叠加上一帧的残留。</li>
     *   <li><b>画。</b></li>
     *   <li><b>finally 恢复。</b>切回主屏、恢复视口、恢复投影矩阵。
     *       异常路径也必须走到，否则一次失败会把后续所有帧的视口带偏。</li>
     * </ol>
     */
    private static void renderOffscreen(Minecraft client) {
        RenderTarget rt = ensureTarget(client);
        if (rt == null) {
            return;
        }

        // 1) 本帧已攒下的 GUI 顶点必须先落到主屏，否则会被我们画进离屏。
        client.renderBuffers().bufferSource().endBatch();

        // 进入前的状态快照。视口不靠 GL 查询——1.21.1 的 GlStateManager
        // 没有暴露视口读取接口，只能按窗口尺寸与缩放等级反推。
        var window = client.getWindow();
        int windowWidth = Math.max(1, window.getWidth());
        int windowHeight = Math.max(1, window.getHeight());
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var savedSorting = RenderSystem.getVertexSorting();

        try {
            // 2) 切到离屏并同步视口。
            rt.bindWrite(true);

            // 3) 清屏。不用 rt.clear()：它内部会 unbindWrite，
            //    把上一步刚设好的目标撤掉，后面的绘制就落回主屏了。
            RenderSystem.clearColor(0.07f, 0.02f, 0.12f, 1.0f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false);

            // 4) 画测试图案。
            drawSolidRect(rt.viewWidth, rt.viewHeight);
        } finally {
            rt.unbindWrite();
            RenderSystem.viewport(0, 0, windowWidth, windowHeight);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
    }

    /**
     * 第二步：把离屏内容当纹理画到主屏。
     *
     * <p>用一次独立的手动 draw call，而不是往 {@code GuiGraphics} 的批次里塞顶点。
     * 原因是探针要**确定性地**控制这一笔的顺序：塞进批次的话，
     * 它什么时候落屏取决于后面还有谁攒批，「离屏内容是否真的进了屏幕」
     * 这件事就不再可信。
     *
     * <p>之所以可以立刻发 draw call，是因为调用前 {@link #renderOffscreen}
     * 已经 {@code endBatch} 过一次。
     */
    private static void composite(Minecraft client) {
        RenderTarget rt = target;
        if (rt == null) {
            return;
        }
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) {
            // 着色器尚未装载（启动早期或资源重载中）。静默跳过，下一帧再来。
            return;
        }

        var window = client.getWindow();
        double guiScale = window.getGuiScale();
        int guiWidth = window.getGuiScaledWidth();
        int guiHeight = window.getGuiScaledHeight();
        int windowWidth = Math.max(1, window.getWidth());
        int windowHeight = Math.max(1, window.getHeight());

        // 合成位置放在右侧、纵向居中，避免正好压住歌词主体。
        int boxWidth = Math.max(1, Math.min(96, guiWidth / 3));

        // 主屏的视口是物理像素尺寸（FBO 缩放后），不是逻辑分辨率。
        RenderSystem.viewport(0, 0, windowWidth, windowHeight);

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var savedSorting = RenderSystem.getVertexSorting();

        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            // 离屏缓冲默认是最近邻，缩小时会跳采样，边缘出现明显阶梯。
            rt.setFilterMode(GL11.GL_LINEAR);

            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, rt.getColorTextureId());

            // 用 GUI 正交投影：与 HUD 播放阶段用的那一套一致，
            // 这样探针框的位置能直接和歌词坐标对照。
            float logicalWidth = (float) (windowWidth / guiScale);
            float logicalHeight = (float) (windowHeight / guiScale);
            Matrix4f projection = new Matrix4f()
                    .setOrtho(0f, logicalWidth, logicalHeight, 0f, 1000f, 21000f);
            RenderSystem.setProjectionMatrix(projection,
                    com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);

            drawTexturedRect(shader,
                    guiWidth - boxWidth - 8, (guiHeight - boxWidth) / 2,
                    boxWidth, boxWidth);
        } finally {
            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            RenderSystem.viewport(0, 0, windowWidth, windowHeight);
        }

        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            LinkLyricFabric.LOGGER.info(
                    "[Link Lyric] 离屏探针首帧完成：FBO={} 颜色纹理={} 离屏尺寸={}x{} 视口={}x{} GUI缩放={}",
                    rt.frameBufferId, rt.getColorTextureId(),
                    rt.width, rt.height, windowWidth, windowHeight, guiScale);
        }
    }

    /**
     * 在离屏缓冲里画一块纯色矩形。
     *
     * <p>不用 {@code GuiGraphics.fill}：它写入的是主屏的 {@code BufferSource}，
     * 而那套批次的落屏时机由别人决定，探针没法保证它在我们切回去之前落屏。
     * {@code Tesselator.begin → buildOrThrow → BufferUploader.drawWithShader}
     * 是「立刻画」的语义，正合需要。
     *
     * <p>坐标用**离屏像素空间**并配一套自己的正交投影，而不是沿用 GUI 的
     * 1000..21000 深度区间——那是给主屏 HUD 准备的。
     *
     * <p>图案刻意只占中间一半、四周留边框：合成到屏幕上时可以直接用肉眼判断
     * 「上下是否颠倒」「边缘是否被裁」「采样是否越界」。纯满屏色块什么都看不出来。
     */
    private static void drawSolidRect(int width, int height) {
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            return;
        }

        // 离屏自己的正交投影：左上角原点，y 向下，与 GUI 坐标系一致。
        Matrix4f projection = new Matrix4f()
                .setOrtho(0f, width, height, 0f, 1000f, 21000f);
        RenderSystem.setProjectionMatrix(projection,
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.setShader(() -> shader);
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();

        float x0 = width * 0.25f;
        float y0 = height * 0.25f;
        float x1 = width * 0.75f;
        float y1 = height * 0.75f;
        // z 落在 1000..21000 中间，避开两个裁剪面。
        float z = 11000f;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(x0, y0, z).setColor(0xFF4D9BFF);
        buffer.addVertex(x0, y1, z).setColor(0xFF4D9BFF);
        buffer.addVertex(x1, y1, z).setColor(0xFF4D9BFF);
        buffer.addVertex(x1, y0, z).setColor(0xFF4D9BFF);
        MeshData mesh = buffer.buildOrThrow();
        // upload 内部会 close 掉 MeshData，这里不重复关。
        BufferUploader.drawWithShader(mesh);
    }

    /**
     * 在主屏上画带纹理的矩形。
     *
     * <p>UV 的 v 轴**要翻**：离屏 FBO 的原点在左下（GL 约定），
     * 而我们按 GUI 习惯把 v=0 当作上边缘。不翻的话内容上下颠倒——
     * 这种错误在纯色测试图案上极难发现，所以图案刻意留了上下不对称的边距。
     */
    private static void drawTexturedRect(ShaderInstance shader,
                                         int x, int y, int width, int height) {
        float x0 = x;
        float y0 = y;
        float x1 = x + width;
        float y1 = y + height;
        // 与 HUD 同层：GUI 的 z 在 1000..21000 区间里按层递减堆叠，
        // 取 0 会落在裁剪面外，必须留在这个区间内。
        float z = 11000f;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(x0, y0, z).setUv(0f, 1f).setColor(0xFFFFFFFF);
        buffer.addVertex(x0, y1, z).setUv(0f, 0f).setColor(0xFFFFFFFF);
        buffer.addVertex(x1, y1, z).setUv(1f, 0f).setColor(0xFFFFFFFF);
        buffer.addVertex(x1, y0, z).setUv(1f, 1f).setColor(0xFFFFFFFF);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /**
     * 懒创建离屏缓冲。
     *
     * <p>创建动作发生在**渲染线程的 HUD 回调里**，这是安全时机：
     * {@code RenderTarget} 的所有 GL 操作都要求渲染线程或初始化阶段。
     * 若放到 mod 初始化里，反而要处理「GL 上下文可能还没建好」的时序问题。
     *
     * <p>构造参数 {@code (w, h, useDepth, clear)}：原版 {@code PostChain}
     * 与 {@code MainTarget} 都是 {@code (w, h, true, ON_OSX)} 这个形状。
     * 探针不需要深度（只画 2D 色块），但保留深度附件能在后续实验里
     * 直接复用同一套缓冲去测「带深度的 HUD 内容」，代价只是一张附件。
     */
    private static RenderTarget ensureTarget(Minecraft client) {
        if (target != null && target.frameBufferId >= 0) {
            return target;
        }
        try {
            RenderTarget rt = new TextureTarget(OFFSCREEN_WIDTH, OFFSCREEN_HEIGHT,
                    true, Minecraft.ON_OSX);
            rt.setClearColor(0f, 0f, 0f, 0f);
            target = rt;
            LinkLyricFabric.LOGGER.info(
                    "[Link Lyric] 离屏缓冲已创建：{}x{}，FBO={}，颜色纹理={}",
                    OFFSCREEN_WIDTH, OFFSCREEN_HEIGHT,
                    rt.frameBufferId, rt.getColorTextureId());
            return rt;
        } catch (RuntimeException e) {
            failed = true;
            current = NOOP;
            LinkLyricFabric.LOGGER.error("[Link Lyric] 离屏缓冲创建失败，探针关闭", e);
            return null;
        }
    }

    /**
     * HUD 回调形状。
     *
     * <p>与 {@code HudRenderCallback#onHudRender} 的参数一致，
     * 这样接入点只需要一行转发。
     */
    public interface HudHook {
        void render(GuiGraphics graphics, float partialTick);
    }
}
