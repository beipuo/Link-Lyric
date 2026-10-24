package com.linklyric.fabric;

import com.linklyric.common.i18n.LangKeys;
import com.linklyric.common.hud.LyricDragController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Fabric 侧的 {@code /linklyric} 指令。
 *
 * <p>三个子命令各管一件事：{@code on} 打开、{@code off} 关闭、
 * {@code location} 进入位置调整。状态由 {@link LyricDragController}
 * 统一维护并落盘，NeoForge 侧复用同一份。
 *
 * <p>使用客户端命令源：显示开关与位置都是本机偏好，
 * 服务端没有对应状态。
 */
final class FabricLyricCommand {

    private FabricLyricCommand() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                         LyricDragController controller) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("linklyric")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(
                            "用法：/linklyric on | off | location [reset]"));
                    return 1;
                })
                .then(literal("on").executes(ctx -> {
                    controller.enable();
                    ctx.getSource().sendFeedback(
                            Component.translatable(LangKeys.COMMAND_ON));
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    controller.disable();
                    ctx.getSource().sendFeedback(
                            Component.translatable(LangKeys.COMMAND_OFF));
                    return 1;
                }))
                .then(literal("config").executes(ctx -> {
                    // 配置界面用原版控件，改动即时生效。
                    LinkLyricFabric.openConfigScreen();
                    ctx.getSource().sendFeedback(Component.translatable(LangKeys.COMMAND_OPEN_CONFIG));
                    return 1;
                }))
                .then(literal("location")
                        .executes(ctx -> {
                            // 打开专用界面：只有 GUI 打开时光标才可见、可拖。
                            LinkLyricFabric.openLocationScreen();
                            ctx.getSource().sendFeedback(Component.literal(
                                    "按住歌词拖动，松手确定；右键或 Esc 取消"));
                            return 1;
                        })
                        .then(literal("reset").executes(ctx -> {
                            controller.reset();
                            ctx.getSource().sendFeedback(
                                    Component.translatable(LangKeys.COMMAND_RESET));
                            return 1;
                        })));
        dispatcher.register(root);
    }
}
