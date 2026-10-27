package com.linklyric.neoforge;

import com.linklyric.common.i18n.LangKeys;
import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.hud.LyricDragController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 歌词位置调整界面。
 *
 * <p>为什么必须开界面：只有 GUI 打开时游戏才释放光标，
 * 玩家才看得见并操作鼠标。HUD 状态下光标被锁定在窗口中心，
 * 「按住拖动」无从谈起。
 *
 * <p>语义是「先拖、后确认」：拖动只改屏幕上的位置，松手不落盘也不退出；
 * 点「保存」才写盘并关闭，点「取消」回到进入时的位置。
 * 这样一次调整可以分多次拖、反复微调，不满意还能整套撤销。
 *
 * <p>界面全透明，只画歌词与可拖区域的高亮；按下必须落在歌词上才开始拖动，
 * 因此在空白处按住不会误拖，也不会挡住下方的界面。
 */
final class LyricLocationScreen extends Screen {

    private final FloatingLyricRenderer renderer;
    private final LyricDragController controller;
    private final Screen parent;
    /** 本次按下是否落在歌词上，决定松手时是否结束拖动。 */
    private boolean pressedOnLyric;

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    LyricLocationScreen(FloatingLyricRenderer renderer,
                        LyricDragController controller, Screen parent) {
        super(Component.literal("Link Lyric 位置调整"));
        this.renderer = renderer;
        this.controller = controller;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 清除配置界面留下的预览区。
        //
        // 配置页把歌词限制在顶部预览框里绘制；从那里跳进来时若不解除，
        // 歌词仍然只画在预览框内，拖动时看不出任何移动，
        // 看起来就像「位置调不了」。
        renderer.setPreviewArea(null);
        // 但仍要强制出画：歌词可能处于关闭状态，而调位置必须看得见。
        renderer.setPreviewForced(true);

        // 进入即开启会话：记下当前位置作为取消回滚点。
        controller.beginSession();

        int centerX = width / 2;
        int y = height - 34;
        addOwned(Button.builder(
                        Component.translatable(LangKeys.SAVE),
                        b -> {
                            controller.commit();
                            close();
                        })
                .bounds(centerX - BUTTON_WIDTH - BUTTON_GAP / 2, y,
                        BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        addOwned(Button.builder(
                        Component.translatable(LangKeys.CANCEL),
                        b -> {
                            controller.revert();
                            close();
                        })
                .bounds(centerX + BUTTON_GAP / 2, y,
                        BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    /**
     * 完全透明背景。
     *
     * <p>两件事都必须避开：父类的 {@code renderBackground} 会铺一层不透明黑，
     * 更会让整屏套上高斯模糊，导致歌词既看不清、也像「和背景糊在一起」。
     * 位置调整要能直接看见游戏画面与歌词本身，因此这里什么都不画。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 故意留空。
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        // 不画父类背景：位置调整要能看见下面的游戏画面。
        renderer.render(LinkLyricNeoForge.painterFor(graphics, client));

        // 提示放在顶部，避开被拖的歌词与底部按钮。
        graphics.centeredText(client.font,
                "按住歌词拖动，松手后可继续微调；点保存生效",
                width / 2, 12, 0xFFFFFFFF);
        if (controller.sessionDirty()) {
            graphics.centeredText(client.font,
                    "位置已改动，尚未保存", width / 2, 26, 0xFFFFD166);
        }

        // 显式顺序而不是 super.render：后者会先调 renderBackground，
        // 一旦将来有人给本界面加了背景，歌词就会被压在遮罩下。
        extractBackground(graphics, mouseX, mouseY, partialTick);
        for (var widget : ownedWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 1.21.9 把「坐标 + 按键」收进事件对象。
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // 先让按钮处理：否则拖过歌词的手感会把按钮点击也吞掉。
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressedOnLyric = controller.beginAtCursor(
                    (int) Math.round(mouseX), (int) Math.round(mouseY));
            // 按下落在歌词外：放行为 false，不启动拖动。
            return pressedOnLyric;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && pressedOnLyric) {
            // 松手只停止跟随，不落盘、不关闭：位置留在屏幕上继续微调。
            controller.end();
            pressedOnLyric = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && pressedOnLyric) {
            // 用当前光标而不是 dragX/dragY：后者是帧间增量，
            // 掉帧时会丢位移，累计位移的方式更稳。
            controller.drag((int) Math.round(mouseX), (int) Math.round(mouseY));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Esc 等于取消：回到进入时的位置。
            controller.revert();
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            controller.commit();
            close();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 关闭界面并回到来源界面。 */
    private void close() {
        pressedOnLyric = false;
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /** 自管控件列表：显式控制绘制顺序，不交给 {@code super.render}。 */
    private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> ownedWidgets =
            new java.util.ArrayList<>();

    private void addOwned(net.minecraft.client.gui.components.AbstractWidget widget) {
        addWidget(widget);
        ownedWidgets.add(widget);
    }

    @Override
    public void onClose() {
        // 只有从指令直接进来时才由这里收尾强制预览。
        // 从配置页进来时 parent 不为空，配置页自己管理预览状态，
        // 这里若也清掉，返回后配置页的预览就失效了。
        if (parent == null) {
            renderer.setPreviewForced(false);
        }
        close();
    }

    @Override
    public boolean isPauseScreen() {
        // 单人游戏下不要因为调位置而暂停世界，音乐还要继续放。
        return false;
    }
}
