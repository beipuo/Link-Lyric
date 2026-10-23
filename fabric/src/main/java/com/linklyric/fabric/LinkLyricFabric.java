package com.linklyric.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 客户端入口。
 *
 * <p>本类只做接线：注册 HUD 层、Screen、按键与数据源选择。
 * 所有不依赖加载器的逻辑必须放在 {@code :common}，禁止在此处沉淀业务代码。
 */
public final class LinkLyricFabric implements ClientModInitializer {

    public static final String MOD_ID = "linklyric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Link Lyric] 客户端入口初始化");
        // TODO 1: 按配置装配 PlaybackSource（salt-player / windows-smtc / mpd）
        // TODO 2: 按配置装配 LyricProvider（SPW 内嵌 / LRCLIB / 本地文件）
        // TODO 3: 创建 LyricSession 并按能力注册 HUD 组件
    }
}
