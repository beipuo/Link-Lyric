package com.linklyric.fabric;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.common.hud.FloatingLyricRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public void onInitializeClient() {
        source = new BridgePlaybackSource("127.0.0.1", BRIDGE_PORT);
        source.start();
        renderer = new FloatingLyricRenderer(source);
        LOGGER.info("[Link Lyric] 数据源已启动，目标 127.0.0.1:{}", BRIDGE_PORT);

        HudRenderCallback.EVENT.register(LinkLyricFabric::onHudRender);

        // 关闭游戏时停掉轮询线程，避免守护线程持有连接
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (source != null) {
                source.stop();
            }
        }, "link-lyric-shutdown"));
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
        if (renderer == null) {
            return;
        }
        renderer.render(new GuiPainter(graphics, client));
    }

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
            // 末尾两个参数：是否投影、是否应用背景色。投影可提升在亮场景下的可读性。
            graphics.drawString(client.font, text, x, y, color, true);
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
