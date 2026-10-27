package com.linklyric.fabric;

import com.linklyric.common.i18n.LangKeys;
import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.hud.LyricDragController;
import com.linklyric.common.style.ColorSquare;
import com.linklyric.common.style.HsvColor;
import com.linklyric.common.style.LyricStyleConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 字体颜色界面。
 *
 * <p>上：歌词预览，改色立刻反映。下左：四个按钮选择要调哪一项。
 * 下右：方形取色盘、色相条、十六进制输入框。
 *
 * <p>方盘用 {@code fillGradient} 直接画，不建纹理。
 * 原因是 SV 平面随色相变化：色相一动整块都要重算，
 * 每次重建 128x128 纹理纯属浪费，两次渐变叠加即可等效。
 */
final class ColorPickerScreen extends Screen {

    /** 调整目标。 */
    private enum Slot {
        SUNG(LangKeys.SLOT_SUNG),
        UNSUNG(LangKeys.SLOT_UNSUNG),
        TRANSLATION(LangKeys.SLOT_TRANSLATION),
        /** 描边颜色。描边另有浓度滑条，这里的 alpha 通道同样生效。 */
        STROKE(LangKeys.SLOT_STROKE),
        RESET(LangKeys.SLOT_RESET);

        final String labelKey;

        Slot(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private final Screen parent;
    private Slot slot = Slot.SUNG;

    private EditBox hexBox;

    private static final int PREVIEW_TOP = 26;
    /** 与配置界面的两列同宽，预览框才能和下方控件对齐。 */
    private static final int COLUMN_WIDTH = 150;
    private static final int COLUMN_GAP = 20;

    /**
     * 预览框高度：与配置界面共用同一条计算。
     *
     * <p>这里原本也是写死的 56，于是调色页与配置页看到的预览高度
     * 可能不一致——同一个渲染器被摆在两个不同尺寸的框里。
     * 高度取决于样式里的行数与行距，必须现算。
     */
    private int previewHeight() {
        var style = controller().style();
        return com.linklyric.common.hud.LyricGeometry.previewHeight(
                style.lineCount(), style.lineGapPx(),
                com.linklyric.common.hud.LyricGeometry.LIFT_HEADROOM,
                com.linklyric.common.hud.LyricGeometry.SHADOW_HEADROOM);
    }
    /** 取色区与色相条尺寸。 */
    private static final int SQUARE_SIZE = 96;
    private static final int HUE_HEIGHT = 12;
    private static final int GAP = 8;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 20;

    private int squareX;
    private int squareY;
    private int hueY;

    /** 自管按钮列表：不交给 {@code super.render} 画，否则遮罩会压在自绘内容之上。 */
    private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> buttons =
            new java.util.ArrayList<>();

    private boolean draggingSquare;
    private boolean draggingHue;

    ColorPickerScreen(Screen parent) {
        super(Component.translatable(LangKeys.COLOR_TITLE));
        this.parent = parent;
    }

    private LyricDragController controller() {
        return LinkLyricFabric.controller();
    }

    private int currentColor() {
        return switch (slot) {
            case SUNG -> controller().style().sungColor();
            case UNSUNG -> controller().style().unsungColor();
            case TRANSLATION -> controller().style().translationColor();
            case STROKE -> controller().style().strokeColor();
            case RESET -> controller().style().sungColor();
        };
    }

    private float currentHue() {
        return HsvColor.toHsv(currentColor())[0];
    }

    private void applyColor(int argb) {
        LyricStyleConfig s = controller().style();
        LyricStyleConfig next = switch (slot) {
            case SUNG -> s.withSungColor(argb);
            case UNSUNG -> s.withUnsungColor(argb);
            case TRANSLATION -> s.withTranslationColor(argb);
            case STROKE -> s.withStrokeColor(argb);
            case RESET -> s;
        };
        controller().setStyle(next);
        if (hexBox != null) {
            hexBox.setValue(HsvColor.format(currentColor()));
        }
    }

    @Override
    protected void init() {
        FloatingLyricRenderer renderer = LinkLyricFabric.renderer();
        renderer.setPreviewForced(true);
        // 宽度取「两列 + 间隙」，与配置界面的设置区同宽。
        int previewWidth = Math.min(width - 40, COLUMN_WIDTH * 2 + COLUMN_GAP);
        renderer.setPreviewArea(new FloatingLyricRenderer.Bounds(
                width / 2 - previewWidth / 2, PREVIEW_TOP, previewWidth, previewHeight()));

        buttons.clear();
        int leftX = Math.max(12, width / 2 - 190);
        int y = PREVIEW_TOP + previewHeight() + 16;
        for (Slot candidate : Slot.values()) {
            addOwned(Button.builder(Component.translatable(candidate.labelKey), btn -> {
                if (candidate == Slot.RESET) {
                    resetColors();
                } else {
                    slot = candidate;
                    refreshHexBox();
                }
            }).bounds(leftX, y, BUTTON_W, BUTTON_H).build());
            y += BUTTON_H + 4;
        }

        // 右侧：方形取色区在上，色相条在下，输入框再下。
        squareX = leftX + BUTTON_W + 28;
        squareY = PREVIEW_TOP + previewHeight() + 16;
        hueY = squareY + SQUARE_SIZE + GAP;

        hexBox = new EditBox(font, squareX, hueY + HUE_HEIGHT + GAP, 96, 18,
                Component.translatable(LangKeys.COLOR_CODE));
        hexBox.setMaxLength(9);
        hexBox.setValue(HsvColor.format(currentColor()));
        hexBox.setResponder(this::onHexTyped);
        addOwned(hexBox);

        addOwned(Button.builder(Component.translatable(LangKeys.DONE), b -> onClose())
                .bounds(width / 2 - 50, height - 32, 100, BUTTON_H).build());
    }

    /**
     * 注册控件并纳入自管列表。
     *
     * <p>用 {@code addWidget} 而不是 {@code addRenderableWidget}：
     * 后者交给 {@code Screen.render} 按顺序画，而 {@code Screen.render}
     * 一进来就先调 {@code renderBackground} 铺一层遮罩，
     * 结果自绘的色盘与预览会被压在遮罩下面，整个界面发暗、只有按钮亮着。
     */
    private void addOwned(net.minecraft.client.gui.components.AbstractWidget widget) {
        addWidget(widget);
        buttons.add(widget);
    }

    private void resetColors() {
        LyricStyleConfig s = controller().style();
        LyricStyleConfig d = LyricStyleConfig.DEFAULT;
        controller().setStyle(s.withSungColor(d.sungColor())
                .withUnsungColor(d.unsungColor())
                .withTranslationColor(d.translationColor()));
        refreshHexBox();
    }

    private void refreshHexBox() {
        if (hexBox != null) {
            hexBox.setValue(HsvColor.format(currentColor()));
        }
    }

    private void onHexTyped(String text) {
        int parsed = HsvColor.parse(text, currentColor());
        if (parsed != currentColor()) {
            applyColor(parsed);
        }
    }

    /** 关掉菜单模糊，否则预览里的歌词会被糊掉。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();

        // 先铺背景，再画自绘内容，最后画控件。
        // 顺序不能交给 super.render：它会把背景重画一遍压在最上层。
        extractBackground(graphics, mouseX, mouseY, partialTick);

        FloatingLyricRenderer renderer = LinkLyricFabric.renderer();
        FloatingLyricRenderer.Bounds area = renderer.previewArea();
        if (area != null && !area.isEmpty()) {
            graphics.fill(area.x(), area.y(),
                    area.x() + area.width(), area.y() + area.height(), 0x80000000);
            graphics.fill(area.x(), area.y(),
                    area.x() + area.width(), area.y() + 1, 0x88FFFFFF);
            graphics.fill(area.x(), area.y() + area.height() - 1,
                    area.x() + area.width(), area.y() + area.height(), 0x88FFFFFF);
        }
        renderer.render(LinkLyricFabric.painterFor(graphics, client));

        graphics.text(font, Component.translatable(LangKeys.COLOR_ADJUSTING,
                    Component.translatable(slot.labelKey)).getString(),
                squareX, squareY - 12, 0xFFFFFFFF, false);

        drawSquare(graphics);
        drawHueBar(graphics);

        // 编码框右侧标明格式，以及当前色块。
        graphics.text(font, "#AARRGGBB",
                squareX + 102, hueY + HUE_HEIGHT + GAP + 5, 0xFFAAAAAA, false);

        // 控件最后画，才不会被背景遮住。这里逐个画而不调 super.render。
        for (var widget : buttons) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * 画 SV 方盘。
     *
     * <p>两层渐变等效于完整的 HSV 平面：
     * 底层横向从白到纯色（饱和度），
     * 上层纵向从透明到黑（明度）。
     * 这样色相一变只换一个颜色参数，不必重算整块像素。
     */
    private void drawSquare(GuiGraphicsExtractor graphics) {
        int hue = HsvColor.toArgb(currentHue(), 1f, 1f, 0xFF);

        // 底层：左白右纯色。
        graphics.fillGradient(squareX, squareY,
                squareX + SQUARE_SIZE, squareY + SQUARE_SIZE,
                0xFFFFFFFF, hue);

        // 上层：上透明下黑，叠出明度。
        graphics.fillGradient(squareX, squareY,
                squareX + SQUARE_SIZE, squareY + SQUARE_SIZE,
                0x00000000, 0xFF000000);

        // 指示框：黑白双描边，落在任何底色上都看得见。
        int[] marker = ColorSquare.marker(currentColor(), SQUARE_SIZE, SQUARE_SIZE);
        int mx = squareX + marker[0];
        int my = squareY + marker[1];
        graphics.fill(mx - 3, my - 3, mx + 3, my + 3, 0xFF000000);
        graphics.fill(mx - 2, my - 2, mx + 2, my + 2, 0xFFFFFFFF);
    }

    /** 画色相条：六段渐变拼成完整色环。 */
    private void drawHueBar(GuiGraphicsExtractor graphics) {
        int segments = 6;
        int segWidth = SQUARE_SIZE / segments;
        for (int i = 0; i < segments; i++) {
            int from = HsvColor.toArgb(i * 60f, 1f, 1f, 0xFF);
            int to = HsvColor.toArgb((i + 1) * 60f, 1f, 1f, 0xFF);
            int x0 = squareX + i * segWidth;
            int x1 = (i == segments - 1) ? squareX + SQUARE_SIZE : x0 + segWidth;
            graphics.fillGradient(x0, hueY, x1, hueY + HUE_HEIGHT, from, to);
        }

        int hueMarkerX = squareX + ColorSquare.hueMarker(currentHue(), SQUARE_SIZE);
        graphics.fill(hueMarkerX - 1, hueY - 2, hueMarkerX + 2, hueY + HUE_HEIGHT + 2,
                0xFF000000);
        graphics.fill(hueMarkerX, hueY - 1, hueMarkerX + 1, hueY + HUE_HEIGHT + 1,
                0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 1.21.9 把「坐标 + 按键」收进事件对象，逐个方法不再散着传。
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (inSquare(mouseX, mouseY)) {
            draggingSquare = true;
            pickSquare(mouseX, mouseY);
            return true;
        }
        if (inHue(mouseX, mouseY)) {
            draggingHue = true;
            pickHue(mouseX, mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (draggingSquare) {
                pickSquare(mouseX, mouseY);
                return true;
            }
            if (draggingHue) {
                pickHue(mouseX, mouseY);
                return true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && (draggingSquare || draggingHue)) {
            draggingSquare = false;
            draggingHue = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private boolean inSquare(double mouseX, double mouseY) {
        return mouseX >= squareX && mouseX < squareX + SQUARE_SIZE
                && mouseY >= squareY && mouseY < squareY + SQUARE_SIZE;
    }

    private boolean inHue(double mouseX, double mouseY) {
        return mouseX >= squareX && mouseX < squareX + SQUARE_SIZE
                && mouseY >= hueY && mouseY < hueY + HUE_HEIGHT;
    }

    private void pickSquare(double mouseX, double mouseY) {
        int px = (int) Math.round(mouseX) - squareX;
        int py = (int) Math.round(mouseY) - squareY;
        int alpha = (currentColor() >>> 24) & 0xFF;
        applyColor(ColorSquare.pick(px, py, SQUARE_SIZE, SQUARE_SIZE,
                currentHue(), alpha, currentColor()));
    }

    private void pickHue(double mouseX, double mouseY) {
        int px = (int) Math.round(mouseX) - squareX;
        float hue = ColorSquare.hueAt(px, SQUARE_SIZE);
        float[] hsv = HsvColor.toHsv(currentColor());
        int alpha = (currentColor() >>> 24) & 0xFF;
        // 只换色相，饱和度与明度保持不变。
        applyColor(HsvColor.toArgb(hue, hsv[1], hsv[2], alpha));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }
}
