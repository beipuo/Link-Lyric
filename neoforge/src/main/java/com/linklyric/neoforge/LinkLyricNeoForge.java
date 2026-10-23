package com.linklyric.neoforge;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.common.hud.FloatingLyricRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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

    public LinkLyricNeoForge(IEventBus modEventBus) {
        source = new BridgePlaybackSource("127.0.0.1", BRIDGE_PORT);
        source.start();
        renderer = new FloatingLyricRenderer(source);
        LOGGER.info("[Link Lyric] 数据源已启动，目标 127.0.0.1:{}", BRIDGE_PORT);

        // 渲染是游戏总线事件，不是 mod 总线
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);

        Runtime.getRuntime().addShutdownHook(new Thread(source::stop, "link-lyric-shutdown"));
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
