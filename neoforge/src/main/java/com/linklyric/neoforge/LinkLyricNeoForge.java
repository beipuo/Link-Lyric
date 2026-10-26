package com.linklyric.neoforge;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.neoforge.render.GradientPainter;
import com.linklyric.neoforge.render.sdf.SdfRenderType;
import com.linklyric.neoforge.render.sdf.SdfShaderRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.hud.LyricDragController;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 客户端入口。
 *
 * <p>与 Fabric 侧保持同构：本类只做接线，排版与状态计算全部落在 {@code :common}。
 * 两端共用 {@link FloatingLyricRenderer}，差异仅在于 HUD 事件来源。
 *
 * <p>1.21.1 使用 Mojang 官方映射，因此 {@code GuiGraphics} 与 {@code DeltaTracker}
 * 与 Fabric 端同名，{@link FloatingLyricRenderer.Painter} 的实现可以逐行对齐。
 */
@Mod(value = LinkLyricNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class LinkLyricNeoForge {

    public static final String MOD_ID = "linklyric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 桥接端口，需与 Salt Music 下 Link MC 插件的 BridgeConfig 一致。
     *
     * <p>取自 {@link com.linklyric.core.protocol.Protocol#DEFAULT_PORT}：
     * 此前两个平台入口各写一份字面量，与协议常量共三处，改端口极易漏改。
     */
    private static final int BRIDGE_PORT = BridgePlaybackSource.defaultPort();

    private final BridgePlaybackSource source;
    private final FloatingLyricRenderer renderer;
    private final LyricDragController dragController;

    public LinkLyricNeoForge(IEventBus modEventBus) {
        source = new BridgePlaybackSource("127.0.0.1", BRIDGE_PORT);
        source.start();
        renderer = new FloatingLyricRenderer(source);
        // 界面文案由游戏的语言文件提供，渲染器只持有键名。
        renderer.setTextResolver(key -> Component.translatable(key).getString());

        LayoutConfig layout = LayoutConfig.load(FMLPaths.CONFIGDIR.get());
        dragController = new LyricDragController(renderer, layout);
        LOGGER.info("[Link Lyric] 数据源已启动，目标 127.0.0.1:{}", BRIDGE_PORT);

        // 着色器必须在编译前登记，走 mod 总线
        modEventBus.addListener(SdfShaderRegistration::onRegisterShaders);

        // 渲染是游戏总线事件，不是 mod 总线
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        current = this;

        Runtime.getRuntime().addShutdownHook(new Thread(source::stop, "link-lyric-shutdown"));
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        NeoForgeLyricCommand.register(event.getDispatcher(), dragController);
    }

    /** 在 HUD 绘制完成后叠加歌词，确保覆盖在物品栏之上。 */
    private void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) {
            return;
        }
        // 待打开的界面必须在 hideGui 判断**之前**处理，理由见 flushPendingScreen。
        // 按着 F1 时发指令，界面照样要打开——它属于 UI 状态，不是 HUD 内容。
        flushPendingScreen(client);
        if (client.options != null && client.options.hideGui) {
            return;
        }
        renderer.render(new GuiPainter(event.getGuiGraphics(), client));
    }

    /**
     * 把 {@link GuiGraphics} 适配成渲染器需要的绘制接口。
     *
     * <p>HUD 与位置调整界面共用同一份适配，避免两处绘制参数走样。
     */
    static FloatingLyricRenderer.Painter painterFor(GuiGraphics graphics, Minecraft client) {
        return new GuiPainter(graphics, client);
    }

    /** 供配置界面取用控制器。 */
    static LyricDragController controller() {
        return current == null ? null : current.dragController;
    }

    /** 供配置界面取用渲染器。 */
    static FloatingLyricRenderer renderer() {
        return current == null ? null : current.renderer;
    }

    static void openConfigScreen() {
        Minecraft client = Minecraft.getInstance();
        if (current == null) {
            return;
        }
        // 配置期间强制预览，并确保总开关为开，否则调样式看不见效果。
        current.renderer.setPreviewForced(true);
        current.dragController.enable();
        openNextFrame(new LyricConfigScreen(
                current.renderer, current.dragController, null));
    }

    /** 打开位置调整界面；HUD 下光标不可见，必须开界面才能拖。 */
    static void openLocationScreen() {
        Minecraft client = Minecraft.getInstance();
        if (current == null) {
            return;
        }
        // 进入前先确保歌词可见，否则调位置时是一片空白。
        current.dragController.enable();
        openNextFrame(new LyricLocationScreen(
                current.renderer, current.dragController, null));
    }

    /**
     * 待打开的界面。下一帧的 HUD 回调里真正装上。
     *
     * <p>**不能立刻 {@code setScreen}。** 与 Fabric 端同理：
     * 指令是从聊天栏发出的，原版 {@code ChatScreen.keyPressed} 在回车分支里
     * 先执行 {@code handleChatInput}（指令在这里跑，我们会 setScreen），
     * 紧接着 {@code minecraft.setScreen(null)} 把聊天栏关掉——
     * 第二条会把刚装上的界面顶掉，表现为「提示出来了，界面却没出现」。
     *
     * <p>NeoForge 端曾经直接 setScreen，于是 {@code /linklyric config}
     * 与 {@code /linklyric location} 在这边只能靠运气生效。
     * 那条路径不会报错，只是界面偶尔不出现，最难归因。
     */
    private static Screen pendingScreen;

    /** 在下一帧打开界面；覆盖式赋值，连发两次只有最后一次生效。 */
    private static void openNextFrame(Screen screen) {
        pendingScreen = screen;
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

    /** 最近一次构造的实例，供静态入口取用。 */
    private static LinkLyricNeoForge current;

    /** {@link GuiGraphics} 到 {@link FloatingLyricRenderer.Painter} 的薄适配。 */
    private record GuiPainter(GuiGraphics graphics, Minecraft client)
            implements FloatingLyricRenderer.Painter {

        /** 每帧把字重与软阴影同步给 SDF 层，理由同 Fabric 端。 */
        private static void syncSdfStyle() {
            FloatingLyricRenderer r = renderer();
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

        /** 单字步进；与 Fabric 侧同义，供逐字排版增量累加。 */
        @Override
        public int charWidth(char c) {
            return client.font.width(String.valueOf(c));
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            drawText(text, x, y, color, true);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
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
            // 保留浮点抬升：抬升量只有约 1px，先取整会在 0 与 1 之间跳变。
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
         * <p>左右异色时，一个字形写成两条不同颜色的边，GPU 逐像素插值，
         * 染色前沿就能落在字内的任意亚像素位置。这是取代
         * 「裁剪切条带」的关键：裁剪只能整像素，9px 的字只有 9 个台阶。
         *
         * <p>平台能力不可用（字段改名、字形缺失）时退回单色，保住可读性。
         */
        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                    GlyphSpan[] spans, int originX, boolean shadow) {
            syncSdfStyle();
            // 清零：发光与描边都是全局状态，若上一个字设置过而不复位，
            // 这一个不需要特效的字会连带被点亮、或继承那圈轮廓。
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

        /** 不描边的发光版本；Fabric 侧有显式实现，此前这里靠接口默认实现退化成无发光。 */
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

        /**
         * 带外发光与描边的按段绘制。
         *
         * <p>此前这里直接转调不带特效的版本，把两个参数**静默丢弃**：
         * 接口的默认实现至少会退一步保住发光，而这里连发光都没了。
         * 结果是在 NeoForge 上描边与发光整体失效，且不会报错、
         * 也不会退回原版字形——只是那两项目特效凭空不出现。
         */
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

        /** 带外发光与描边的单字形绘制；理由同按段版本，此前参数被静默丢弃。 */
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

        /** 不描边的发光版本；此前同样缺实现，发光在 NeoForge 上整体失效。 */
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
            // 抬升量**不取整**，直接进矩阵平移。
            //
            // 曾经写成 Math.round(lift)：9px 字号下抬升幅度只有 0.9px，
            // 取整后变成 {0,1} 两档，一句 3 秒的歌有一半时间字完全不动。
            // 这是「上浮僵硬」的直接原因，与缓动曲线无关。
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

        /** 缩放绘制副行；以左上角为原点，保持调用方的锚点语义不变。 */
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