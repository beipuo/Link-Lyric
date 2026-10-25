package com.linklyric.fabric;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.fabric.render.GradientPainter;
import com.linklyric.fabric.render.sdf.SdfGlyphProvider;
import com.linklyric.fabric.render.sdf.SdfRenderType;
import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.hud.LyricDragController;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Fabric 客户端入口。
 *
 * <p>只做三件事：启动数据源、注册 HUD 回调、把 {@link GuiGraphics} 适配成
 * {@link FloatingLyricRenderer.Painter}。排版与状态计算全在 {@code :common}，
 * NeoForge 端复用同一份渲染器。
 */
public final class LinkLyricFabric implements ClientModInitializer {

    public static final String MOD_ID = "linklyric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 桥接端口，需与 Salt Music 下 Link MC 插件的 BridgeConfig 一致。 */
    private static final int BRIDGE_PORT = 35400;

    private static BridgePlaybackSource source;
    private static FloatingLyricRenderer renderer;
    private static LyricDragController dragController;

    @Override
    public void onInitializeClient() {
        source = new BridgePlaybackSource("127.0.0.1", BRIDGE_PORT);
        source.start();
        renderer = new FloatingLyricRenderer(source);
        // 界面文案由游戏的语言文件提供，渲染器只持有键名。
        renderer.setTextResolver(key -> Component.translatable(key).getString());

        // 位置持久化到 config 目录：换存档、换分辨率都跟着玩家走。
        LayoutConfig layout = LayoutConfig.load(
                FabricLoader.getInstance().getConfigDir());
        dragController = new LyricDragController(renderer, layout);
        LOGGER.info("[Link Lyric] 数据源已启动，目标 127.0.0.1:{}", BRIDGE_PORT);

        HudRenderCallback.EVENT.register(LinkLyricFabric::onHudRender);

        // 指令需要客户端命令源：位置是本机显示偏好，服务端无对应状态。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                FabricLyricCommand.register(dispatcher, dragController));

        // SDF 着色器必须等资源加载完才能编译，而资源可以在游戏内重载。
        // 挂在资源重载完成事件上，顺便覆盖第一次加载。
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(MOD_ID, "sdf_shader");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        loadSdfShader(manager);
                    }
                });

        // 关闭游戏时停掉轮询线程，避免守护线程持有连接
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (source != null) {
                source.stop();
            }
        }, "link-lyric-shutdown"));
    }

    /**
     * 装载 SDF 文字着色器。
     *
     * <p>失败不致命：{@code SdfRenderType} 保持不可用，
     * 绘制自动退回原版字形，只是没有高清边缘。
     *
     * <p>重载时先释放旧实例再接新的，否则会漏掉一个着色器程序。
     */
    private static void loadSdfShader(ResourceManager manager) {
        // 顺序有讲究：先释放图集（它绑着旧纹理 id），再贴新着色器。
        // 反过来的话，install 清空了 RenderType 缓存，但旧图集还在，
        // 下一次绘制会重新建 RenderType 指向仍存活的旧页，
        // 之后 reset 才关掉它——留下悬空的纹理引用。
        SdfGlyphProvider.reset();
        ShaderInstance old = SdfRenderType.current();
        try {
            ShaderInstance shader = new ShaderInstance(manager, "linklyric_sdf_text",
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            SdfRenderType.install(shader);
            if (old != null) {
                old.close();
            }
        } catch (IOException | RuntimeException e) {
            SdfRenderType.install(null);
            LOGGER.warn("[Link Lyric] SDF 着色器装载失败，退回原版字形", e);
        }
    }

    private static void onHudRender(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) {
            return;
        }
        // F1 隐藏界面、或打开了 GUI 时不绘制
        if (client.options != null && client.options.hideGui) {
            return;
        }
        // 先处理待打开的界面：这一步必须在渲染之前，
        // 否则新界面装上的那一帧还会多画一次旧 HUD。
        flushPendingScreen(client);

        if (renderer == null) {
            return;
        }
        renderer.render(new GuiPainter(graphics, client));
    }

    /**
     * 把 {@link GuiGraphics} 适配成渲染器需要的绘制接口。
     *
     * <p>HUD 与位置调整界面共用同一份适配，避免两处绘制参数走样。
     */
    static FloatingLyricRenderer.Painter painterFor(GuiGraphics graphics, Minecraft client) {
        return new GuiPainter(graphics, client);
    }

    /**
     * 打开位置调整界面。
     *
     * <p>必须开界面而不是在 HUD 上直接操作：HUD 状态下光标被锁在窗口中心，
     * 玩家既看不见指针也无法按住拖动。
     */
    /** 供配置界面取用控制器。 */
    static LyricDragController controller() {
        return dragController;
    }

    /** 供配置界面取用渲染器。 */
    static FloatingLyricRenderer renderer() {
        return renderer;
    }

    /**
     * 待打开的界面。下一帧的 HUD 回调里真正装上。
     *
     * <p>**不能立刻 {@code setScreen}。** 指令是从聊天栏发出的，
     * 而原版 {@code ChatScreen.keyPressed} 在回车分支里的顺序是：
     * <pre>
     *   handleChatInput(...)          // 指令在这里执行，我们会 setScreen
     *   minecraft.setScreen(null)     // 把聊天栏关掉
     * </pre>
     * 第二条会把刚装上的界面顶掉，表现为「指令生效了、提示也出来了，
     * 但界面一闪而过或者根本没出现」。整个聊天栏的执行期间都没机会插进去，
     * 只能推迟到下一帧。
     */
    private static Screen pendingScreen;

    /**
     * 在下一帧打开界面。
     *
     * <p>覆盖式赋值：连续发两次指令时只有最后一次生效，
     * 不会留下一个永远打不开的残留请求。
     */
    private static void openNextFrame(Screen screen) {
        pendingScreen = screen;
    }

    static void openConfigScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || renderer == null || dragController == null) {
            return;
        }
        renderer.setPreviewForced(true);
        dragController.enable();
        openNextFrame(new LyricConfigScreen(renderer, dragController, null));
    }

    static void openLocationScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || renderer == null || dragController == null) {
            return;
        }
        // 进入前先确保歌词可见，否则调位置时是一片空白。
        dragController.enable();
        openNextFrame(new LyricLocationScreen(renderer, dragController, null));
    }

    /** 装上待打开的界面。在游戏线程上执行，因此可以安全 setScreen。 */
    private static void flushPendingScreen(Minecraft client) {
        if (pendingScreen == null) {
            return;
        }
        Screen next = pendingScreen;
        pendingScreen = null;
        client.setScreen(next);
    }

    /** {@link GuiGraphics} 到 {@link FloatingLyricRenderer.Painter} 的薄适配。 */
    private record GuiPainter(GuiGraphics graphics, Minecraft client)
            implements FloatingLyricRenderer.Painter {

        /**
         * 把当前样式里的字重与软阴影同步给 SDF 渲染层。
         *
         * <p>uniform 在每批 setup 时读取，而 setup 发生在攒批之后的刷批
         * 时刻，不在配置回调里。因此这里每帧同步一次，而不是只在
         * 配置变更时写一次——否则刷新界面后要等下一帧才生效，
         * 拖动滑条时还会看到一两帧的滞后。
         */
        private static void syncSdfStyle() {
            FloatingLyricRenderer r = renderer;
            if (r == null) {
                return;
            }
            SdfRenderType.setWeightPercent(r.style().weightPercent());
            SdfRenderType.setSoftShadow(r.style().softShadow());
        }

        @Override
        public int screenWidth() {
            return graphics.guiWidth();
        }

        @Override
        public int screenHeight() {
            return graphics.guiHeight();
        }

        @Override
        public int textWidth(String text) {
            return client.font.width(text);
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            drawText(text, x, y, color, true);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            // 末两个参数：是否投影、是否应用背景色。投影提升亮场景下的可读性。
            graphics.drawString(client.font, text, x, y, color, shadow);
        }

        /**
         * 亚像素抬升。
         *
         * <p>位移取整到整像素，**不能**用浮点平移。
         *
         * <p>MC 的字体是位图图集，按整像素对齐取样。若把字形平移到半像素上，
         * 采样点落在纹素之间，会触发线性过滤，笔画边缘出现颗粒与锯齿，
         * 细笔画还会因为两侧都采到半透明值而黏连变糊——
         * 看起来像低分辨率放大。取整后每个字形都落在整数格点上，
         * 边缘与图集原样一致。
         *
         * <p>代价是抬升在 0/1 像素之间跳变。但抬升幅度本身就只有 1px，
         * 且由时间驱动的连续宽度算出，观感上仍是平滑推进，
         * 清晰度比那半个像素的平滑更值得。
         */
        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            drawTextLifted(text, x, y, lift, color, shadow, false);
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow, boolean ignoredBold) {
            // 加粗已移除：§l 与它带来的伪粗体副本会让 9px 字形出现重影。
            // 参数保留只为实现接口签名。
            int dy = Math.round(lift);
            if (dy == 0) {
                drawText(text, x, y, color, shadow);
                return;
            }
            graphics.drawString(client.font, text, x, y - dy, color, shadow);
        }

        /**
         * 逐顶点着色的单字绘制。
         *
         * <p>左右异色时，一个字形会写成两条不同颜色的边，GPU 逐像素插值，
         * 染色前沿就能落在字内的任意亚像素位置。这是取代
         * 「裁剪切条带」的关键：裁剪只能整像素，9px 的字只有 9 个台阶。
         *
         * <p>平台能力不可用（字段改名、字形缺失）时退回单色，保住可读性。
         */
        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                    GlyphSpan[] spans, int originX, boolean shadow) {
            syncSdfStyle();
            // 清零：发光是全局状态，若上一个字设置过而不复位，
            // 这一个不需要发光的字会连带被点亮。
            // 描边同样是全局状态，必须一起清：
            // 上一个字描过边而这里不复位，这个字会继承那圈轮廓。
            SdfRenderType.setGlow(0f, 0f);
            SdfRenderType.clearStroke();
            if (!GradientPainter.usable()) {
                drawTextLifted(glyph, Math.round(x), y, lift,
                        spans.length == 0 ? 0xFFFFFFFF : spans[0].startColor(), shadow);
                return;
            }
            if (lift != 0f) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(0f, -lift, 0f);
                GradientPainter.drawSpans(graphics, client.font, glyph, x, y,
                        spans, originX, shadow);
                pose.popPose();
                return;
            }
            GradientPainter.drawSpans(graphics, client.font, glyph, x, y,
                    spans, originX, shadow);
        }

        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                    GlyphSpan[] spans, int originX, boolean shadow,
                                    float glowRadius, float glowAlpha) {
            syncSdfStyle();
            SdfRenderType.setGlow(glowRadius, glowAlpha);
            if (!GradientPainter.usable()) {
                drawTextLifted(glyph, Math.round(x), y, lift,
                        spans.length == 0 ? 0xFFFFFFFF : spans[0].startColor(), shadow);
                return;
            }
            if (lift != 0f) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(0f, -lift, 0f);
                GradientPainter.drawSpans(graphics, client.font, glyph, x, y,
                        spans, originX, shadow, glowRadius, glowAlpha);
                pose.popPose();
                return;
            }
            GradientPainter.drawSpans(graphics, client.font, glyph, x, y,
                    spans, originX, shadow, glowRadius, glowAlpha);
        }

        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                    GlyphSpan[] spans, int originX, boolean shadow,
                                    float glowRadius, float glowAlpha,
                                    float strokeWidth, int strokeArgb) {
            syncSdfStyle();
            SdfRenderType.setGlow(glowRadius, glowAlpha);
            if (!GradientPainter.usable()) {
                drawTextLifted(glyph, Math.round(x), y, lift,
                        spans.length == 0 ? 0xFFFFFFFF : spans[0].startColor(), shadow);
                return;
            }
            if (lift != 0f) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(0f, -lift, 0f);
                GradientPainter.drawSpans(graphics, client.font, glyph, x, y,
                        spans, originX, shadow, glowRadius, glowAlpha,
                        strokeWidth, strokeArgb);
                pose.popPose();
                return;
            }
            GradientPainter.drawSpans(graphics, client.font, glyph, x, y,
                    spans, originX, shadow, glowRadius, glowAlpha,
                    strokeWidth, strokeArgb);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       GlyphColors colors, boolean shadow,
                                       float glowRadius, float glowAlpha,
                                       float strokeWidth, int strokeArgb) {
            syncSdfStyle();
            SdfRenderType.setGlow(glowRadius, glowAlpha);
            if (!GradientPainter.usable()) {
                drawTextLifted(glyph, Math.round(x), y, lift, colors.left(), shadow);
                return;
            }
            if (lift != 0f) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(0f, -lift, 0f);
                GradientPainter.draw(graphics, client.font, glyph, x, y,
                        colors, shadow, glowRadius, glowAlpha, strokeWidth, strokeArgb);
                pose.popPose();
                return;
            }
            GradientPainter.draw(graphics, client.font, glyph, x, y,
                    colors, shadow, glowRadius, glowAlpha, strokeWidth, strokeArgb);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       GlyphColors colors, boolean shadow,
                                       float glowRadius, float glowAlpha) {
            syncSdfStyle();
            SdfRenderType.setGlow(glowRadius, glowAlpha);
            if (!GradientPainter.usable()) {
                drawTextLifted(glyph, Math.round(x), y, lift, colors.left(), shadow);
                return;
            }
            if (lift != 0f) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(0f, -lift, 0f);
                GradientPainter.draw(graphics, client.font, glyph, x, y,
                        colors, shadow, glowRadius, glowAlpha);
                pose.popPose();
                return;
            }
            GradientPainter.draw(graphics, client.font, glyph, x, y,
                    colors, shadow, glowRadius, glowAlpha);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       GlyphColors colors, boolean shadow) {
            syncSdfStyle();
            SdfRenderType.setGlow(0f, 0f);
            SdfRenderType.clearStroke();
            if (!GradientPainter.usable()) {
                drawTextLifted(glyph, Math.round(x), y, lift, colors.left(), shadow);
                return;
            }
            // 抬升量**不取整**，直接进顶点坐标。
            //
            // 曾经写成 Math.round(lift)：9px 字号下抬升幅度只有 0.9px，
            // 取整后变成 {0,1} 两档，一句 3 秒的歌有一半时间字完全不动。
            // 这是「上浮僵硬」的直接原因，与缓动曲线无关。
            //
            // 顶点写入接收的是 float（BakedGlyph 的 left/up 也是 float），
            // 整条链路本来就能表达亚像素位置，是这里自己把精度丢掉的。
            //
            // 代价是字形在半像素位置会被纹理线性过滤采样。这一点与
            // Salt 用翻译矩阵平移已光栅化图层在物理上等价：
            // 它的 translationY 同样让 GPU 采样落在纹素之间。
            if (lift != 0f) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(0f, -lift, 0f);
                GradientPainter.draw(graphics, client.font, glyph, x, y,
                        colors, shadow);
                pose.popPose();
                return;
            }
            GradientPainter.draw(graphics, client.font, glyph, x, y,
                    colors, shadow);
        }

        /**
         * 缩放绘制副行。
         *
         * <p>以左上角为原点缩放，再把坐标乘回去，使 {@code x}/{@code y}
         * 仍然表示未缩放时的锚点，调用方不必做逆变换。
         */
        @Override
        public void drawTextScaled(String text, int x, int y, float scale,
                                   int color, boolean shadow) {
            if (scale == 1f) {
                drawText(text, x, y, color, shadow);
                return;
            }
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, y, 0f);
            pose.scale(scale, scale, 1f);
            pose.translate(-x, -y, 0f);
            graphics.drawString(client.font, text, x, y, color, shadow);
            pose.popPose();
        }

        @Override
        public int textWidthScaled(String text, float scale) {
            return Math.round(client.font.width(text) * scale);
        }

        /**
         * 真实行高，供裁剪区留足空间。
         *
         * <p>不能用渲染器的常量：中文走 fallback 字体，字形比拉丁字母高，
         * 按写死的行高裁剪会把中文的下缘（尤其是阴影）切掉。
         * 这正是「英文有阴影、中文没有」的来源。
         */
        @Override
        public int lineHeight() {
            // 加 2 像素容纳阴影偏移与字形下缘的留白。
            return client.font.lineHeight + 2;
        }

        /** 把原版 PoseStack 适配成 common 的极简变换栈。 */
        @Override
        public FloatingLyricRenderer.Pose2D pose() {
            var stack = graphics.pose();
            return new FloatingLyricRenderer.Pose2D() {
                @Override
                public void pushPose() {
                    stack.pushPose();
                }

                @Override
                public void popPose() {
                    stack.popPose();
                }

                @Override
                public void translate(float x, float y) {
                    stack.translate(x, y, 0f);
                }

                @Override
                public void scale(float x, float y) {
                    stack.scale(x, y, 1f);
                }
            };
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            graphics.fill(x, y, x + width, y + height, color);
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            graphics.enableScissor(x, y, x + width, y + height);
        }

        @Override
        public void disableScissor() {
            graphics.disableScissor();
        }
    }
}