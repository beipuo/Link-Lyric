package com.linksaltmusic.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 客户端入口。
 *
 * <p>本类只做接线：注册 HUD 层、Screen、按键与链接状态机。
 * 所有不依赖加载器的逻辑必须放在 {@code :common}，禁止在此处沉淀业务代码。
 */
public final class LinkSaltMusicFabric implements ClientModInitializer {

    public static final String MOD_ID = "linksaltmusic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Link Salt Music] 客户端入口初始化");
        // TODO 1: 建立与 Salt Player 插件的本地连接（状态推送 + 命令回执）
        // TODO 2: 注册 HUD 歌词层（悬浮 / 灵动岛 / 侧边栏 / 物品栏）
        // TODO 3: 注册歌单 Screen 与快捷键
    }
}
