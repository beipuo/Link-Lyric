package com.linklyric.neoforge;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.common.hud.FloatingLyricRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    /** 桥接端口，需与 Salt Music 下 Link MC 插件的 BridgeConfig 一致。 */
    private static final int BRIDGE_PORT = 35400;

    private final BridgePlaybackSource source;
    private final FloatingLyricRenderer renderer;
    private final LyricDragController dragController;

    public LinkLyricNeoForge(IEventBus modEventBus) {
        source = new BridgePlaybackSource("127.0.0.1", BRIDGE_PORT);
        source.start();
        renderer = new FloatingLyricRenderer(source);

        LayoutConfig layout = LayoutConfig.load(FMLPaths.CONFIGDIR.get());
        dragController = new LyricDragController(renderer, layout);
        LOGGER.info("[Link Lyric] 数据源已启动，目标 127.0.0.1:{}", BRIDGE_PORT);

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
        client.setScreen(new LyricConfigScreen(
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
        client.setScreen(new LyricLocationScreen(
                current.renderer, current.dragController, null));
    }

    /** 最近一次构造的实例，供静态入口取用。 */
    private static LinkLyricNeoForge current;

    /** {@link GuiGraphics} 到 {@link FloatingLyricRenderer.Painter} 的薄适配。 */
    private record GuiPainter(GuiGraphics graphics, Minecraft client)
            implements FloatingLyricRenderer.Painter {

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
            int dy = Math.round(lift);
            if (dy == 0) {
                drawText(text, x, y, color, shadow);
                return;
            }
            graphics.drawString(client.font, text, x, y - dy, color, shadow);
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
