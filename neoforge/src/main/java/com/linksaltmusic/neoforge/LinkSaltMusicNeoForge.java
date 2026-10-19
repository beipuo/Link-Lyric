package com.linksaltmusic.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 客户端入口。
 *
 * <p>与 Fabric 侧保持同构：本类只做接线，业务逻辑全部落在 {@code :common}。
 */
@Mod(LinkSaltMusicNeoForge.MOD_ID)
public final class LinkSaltMusicNeoForge {

    public static final String MOD_ID = "linksaltmusic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public LinkSaltMusicNeoForge(IEventBus modEventBus) {
        LOGGER.info("[Link Salt Music] 客户端入口初始化");
        // TODO 1: 建立与 Salt Player 插件的本地连接（状态推送 + 命令回执）
        // TODO 2: 注册 HUD 歌词层（悬浮 / 灵动岛 / 侧边栏 / 物品栏）
        // TODO 3: 注册歌单 Screen 与快捷键
    }
}
