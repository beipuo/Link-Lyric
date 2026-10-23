package com.linklyric.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 客户端入口。
 *
 * <p>与 Fabric 侧保持同构：本类只做接线，业务逻辑全部落在 {@code :common}。
 */
@Mod(LinkLyricNeoForge.MOD_ID)
public final class LinkLyricNeoForge {

    public static final String MOD_ID = "linklyric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public LinkLyricNeoForge(IEventBus modEventBus) {
        LOGGER.info("[Link Lyric] 客户端入口初始化");
        // TODO 1: 按配置装配 PlaybackSource（salt-player / windows-smtc / mpd）
        // TODO 2: 按配置装配 LyricProvider（SPW 内嵌 / LRCLIB / 本地文件）
        // TODO 3: 创建 LyricSession 并按能力注册 HUD 组件
    }
}
