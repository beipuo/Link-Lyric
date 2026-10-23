package com.linklyric.neoforge;

import com.linklyric.common.hud.LyricDragController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * NeoForge 侧的 {@code /linklyric} 指令。
 *
 * <p>三个子命令各管一件事：{@code on} 打开、{@code off} 关闭、
 * {@code location} 进入位置调整。状态由 {@link LyricDragController}
 * 统一维护并落盘，与 Fabric 侧共用同一份实现。
 *
 * <p>{@code RegisterClientCommandsEvent} 给出的命令源是
 * {@link CommandSourceStack}，因此这里用 {@link Commands} 构造。
 */
final class NeoForgeLyricCommand {

    private NeoForgeLyricCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         LyricDragController controller) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("linklyric")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "用法：/linklyric on | off | location [reset]"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    controller.enable();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("歌词已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    controller.disable();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("歌词已关闭"), false);
                    return 1;
                }))
                .then(Commands.literal("config").executes(ctx -> {
                    LinkLyricNeoForge.openConfigScreen();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("已打开歌词配置"), false);
                    return 1;
                }))
                .then(Commands.literal("location")
                        .executes(ctx -> {
                            // 打开专用界面：只有 GUI 打开时光标才可见、可拖。
                            LinkLyricNeoForge.openLocationScreen();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "按住歌词拖动，松手确定；右键或 Esc 取消"), false);
                            return 1;
                        })
                        .then(Commands.literal("reset").executes(ctx -> {
                            controller.reset();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("歌词位置已复位"), false);
                            return 1;
                        })));
        dispatcher.register(root);
    }
}
