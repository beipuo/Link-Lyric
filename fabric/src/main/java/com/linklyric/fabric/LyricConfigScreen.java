package com.linklyric.fabric;

import com.linklyric.common.config.LyricOptions;
import com.linklyric.common.config.LyricOptions.Option;
import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.hud.LyricDragController;
import com.linklyric.common.i18n.LangKeys;
import com.linklyric.common.style.LyricStyleConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词个性化配置界面，全部使用原版控件。
 *
 * <p>布局自上而下：顶部歌词预览框（固定不滚）、中间可滚动的设置区、底部操作按钮。
 * 预览框固定是为了让玩家一边滚动一边看到改动效果；
 * 设置区放得下更多项，窗口再小也能滚到。
 *
 * <p>所有改动即时生效——不需要「应用」按钮，改一下歌词就变一下，
 * 因为渲染器每帧直接读 {@link LyricStyleConfig}。
 */
final class LyricConfigScreen extends Screen {

    private final FloatingLyricRenderer renderer;
    private final LyricDragController controller;
    private final Screen parent;

    private static final int COLUMN_WIDTH = 150;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;

    /** 预览框高度：容纳两行歌词加抬升余量。 */
    private static final int PREVIEW_HEIGHT = 56;
    private static final int PREVIEW_TOP = 26;

    /** 底部按钮区高度。 */
    private static final int FOOTER_HEIGHT = 34;

    /** 滚动内容区里的控件；绘制时按内容坐标加滚动量摆放。 */
    private final List<AbstractWidget> scrollWidgets = new ArrayList<>();
    /** 滚动状态；内容高度在 init 里确定。 */
    private LyricOptions.ScrollState scrollState = new LyricOptions.ScrollState(0, 0);

    LyricConfigScreen(FloatingLyricRenderer renderer,
                      LyricDragController controller, Screen parent) {
        super(Component.literal("Link Lyric 配置"));
        this.renderer = renderer;
        this.controller = controller;
        this.parent = parent;
    }

    /** 滚动区上边界：紧贴预览框下方。 */
    private int viewTop() {
        return PREVIEW_TOP + PREVIEW_HEIGHT + 10;
    }

    /** 滚动区下边界：让开底部按钮。 */
    private int viewBottom() {
        return height - FOOTER_HEIGHT - 4;
    }

    private int viewHeight() {
        return Math.max(0, viewBottom() - viewTop());
    }

    /** 内容总高度，由实际摆放决定。 */
    private int contentHeight;

    /** 内容区总高度，供滚动量夹紧；占位以保持字段声明顺序清晰。 */
    @Override
    protected void init() {
        // 配置期间强制预览：即使歌词被关闭也要看得见改动。
        renderer.setPreviewForced(true);

        int previewWidth = Math.min(width - 40, 360);
        int previewX = width / 2 - previewWidth / 2;
        renderer.setPreviewArea(new FloatingLyricRenderer.Bounds(
                previewX, PREVIEW_TOP, previewWidth, PREVIEW_HEIGHT));

        scrollWidgets.clear();
        footerWidgets.clear();
        // 重建界面会创建全新控件，旧的坐标记录必须丢弃，
        // 否则 IdentityHashMap 会随每次 rebuild 无限增长。
        contentY.clear();

        int left = width / 2 - COLUMN_WIDTH - 10;
        int right = width / 2 + 10;
        // 内容坐标从 0 开始，绘制时统一加 viewTop() 与滚动量。
        int y = 0;

        for (Option option : Option.values()) {
            addScrollWidget(new OptionSlider(left, y, COLUMN_WIDTH, option));
            y += ROW_HEIGHT + ROW_GAP;
        }

        addScrollWidget(CycleButton.booleanBuilder(
                        Component.translatable(LangKeys.FEATHER_ON), Component.translatable(LangKeys.FEATHER_OFF))
                .withInitialValue(controller.style().feather())
                .create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                        Component.translatable(LangKeys.TRANSITION),
                        (button, value) -> {
                            apply(style -> style.withFeather(value));
                            // 重建控件：滑条与开关的显示值必须同步。
                            rebuildWidgets();
                        }));
        y += ROW_HEIGHT + ROW_GAP;

        // 字重：三档循环。原版没有粗体字库，靠叠加描边模拟。
        addScrollWidget(cycleEnum(LyricStyleConfig.Weight.class, controller.style().weight(),
                left, y, LangKeys.OPTION_WEIGHT, 0, (style, value) -> style.withWeight(value)));
        y += ROW_HEIGHT + ROW_GAP;

        // 上浮曲线：两档切换。余弦是默认，形状来自 Salt Player。
        addScrollWidget(cycleEnum(LyricStyleConfig.LiftCurve.class, controller.style().liftCurve(),
                left, y, LangKeys.LIFT_CURVE, 0, (style, value) -> style.withLiftCurve(value)));
        y += ROW_HEIGHT + ROW_GAP;

        // 对齐：影响主行与副行的水平锚点。
        addScrollWidget(cycleEnum(LyricStyleConfig.Align.class, controller.style().align(),
                left, y, LangKeys.OPTION_ALIGN, 0, (style, value) -> style.withAlign(value)));
        y += ROW_HEIGHT + ROW_GAP;

        addScrollWidget(CycleButton.booleanBuilder(
                        Component.translatable(LangKeys.SHADOW_ON), Component.translatable(LangKeys.SHADOW_OFF))
                .withInitialValue(controller.style().shadow())
                .create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                        Component.translatable(LangKeys.SHADOW),
                        (button, value) -> {
                            apply(style -> style.withShadow(value));
                            rebuildWidgets();
                        }));
        y += ROW_HEIGHT + ROW_GAP;

        // 整体不透明度：10% 到 100%。
        addScrollWidget(new OptionSlider(left, y, COLUMN_WIDTH,
                LyricOptions.Option.OPACITY));
        y += ROW_HEIGHT + ROW_GAP;

        // 显示行数：1 到 10。
        addScrollWidget(Button.builder(
                        Component.translatable(LangKeys.LINE_COUNT, controller.style().lineCount()),
                        b -> {
                            int raw = controller.style().lineCount() + 1;
                            // 循环回到最小值，而不是夹在上限——
                            // 夹住的话连点就永远停在上限，回不去。
                            final int next = raw > LyricStyleConfig.MAX_LINES
                                    ? LyricStyleConfig.MIN_LINES
                                    : raw;
                            apply(style -> style.withLineCount(next));
                            rebuildWidgets();
                        })
                .bounds(left, y, COLUMN_WIDTH, ROW_HEIGHT)
                .build());
        y += ROW_HEIGHT + ROW_GAP;

        // 间奏提示：空白 / ··· / 间奏。
        addScrollWidget(cycleEnum(LyricStyleConfig.InterludeStyle.class,
                controller.style().interlude(), left, y, "间奏", 0,
                (style, value) -> style.withInterlude(value)));
        y += ROW_HEIGHT + ROW_GAP;

        // 颜色只留一个入口：三项颜色在调色页里切换，避免主界面被按钮占满。
        addScrollWidget(Button.builder(
                        Component.translatable(LangKeys.FONT_COLOR),
                        b -> minecraft.setScreen(new ColorPickerScreen(this)))
                .bounds(left, y, COLUMN_WIDTH, ROW_HEIGHT)
                .build());
        y += ROW_HEIGHT + ROW_GAP;

        int ry = 0;
        addScrollWidget(CycleButton.booleanBuilder(
                        Component.translatable(LangKeys.LYRICS_ON), Component.translatable(LangKeys.LYRICS_OFF))
                .withInitialValue(controller.isEnabled())
                .create(right, ry, COLUMN_WIDTH, ROW_HEIGHT,
                        Component.translatable(LangKeys.MASTER_SWITCH),
                        (button, value) -> {
                            if (value) {
                                controller.enable();
                            } else {
                                controller.disable();
                            }
                        }));
        ry += ROW_HEIGHT + ROW_GAP;

        addScrollWidget(CycleButton.booleanBuilder(
                        Component.translatable(LangKeys.SOURCE_ENABLED), Component.translatable(LangKeys.SOURCE_DISABLED))
                .withInitialValue(controller.isSourceEnabled())
                .create(right, ry, COLUMN_WIDTH, ROW_HEIGHT,
                        Component.literal("Salt Player"),
                        (button, value) -> controller.setSourceEnabled(value)));
        ry += ROW_HEIGHT + ROW_GAP;

        addScrollWidget(Button.builder(
                        Component.translatable(LangKeys.ADJUST_POSITION),
                        b -> {
                            // 保留父界面引用，保存/取消后回到本页。
                            if (minecraft != null) {
                                minecraft.setScreen(new LyricLocationScreen(
                                        renderer, controller, this));
                            }
                        })
                .bounds(right, ry, COLUMN_WIDTH, ROW_HEIGHT)
                .build());
        ry += ROW_HEIGHT + ROW_GAP;

        addScrollWidget(Button.builder(Component.translatable(LangKeys.RESET_DEFAULT),
                        b -> restoreDefaults())
                .bounds(right, ry, COLUMN_WIDTH, ROW_HEIGHT)
                .build());
        ry += ROW_HEIGHT + ROW_GAP;

        // 两栏取较高者作为内容高度。
        contentHeight = Math.max(y, ry);
        scrollState = new LyricOptions.ScrollState(contentHeight, viewHeight());

        // 底部按钮固定在滚动区之外。
        int bottom = height - FOOTER_HEIGHT + 6;
        addFooterWidget(Button.builder(Component.translatable(LangKeys.DONE), b -> onClose())
                .bounds(width / 2 - 102, bottom, 100, ROW_HEIGHT)
                .build());
        addFooterWidget(Button.builder(Component.translatable(LangKeys.CANCEL), b -> cancel())
                .bounds(width / 2 + 2, bottom, 100, ROW_HEIGHT)
                .build());
    }

    /**
     * 注册一个随滚动移动的控件。
     *
     * <p>用 {@code addWidget} 而不是 {@code addRenderableWidget}：
     * 后者会被 {@code super.render} 直接按自身坐标画出来，无法裁剪，
     * 滚动时控件会盖到预览框上。这里改为自己画，绘制前设好裁剪区。
     */
    private void addScrollWidget(AbstractWidget widget) {
        // 注册时记下内容坐标，之后所有滚动重排都以它为基准。
        // 若只存「当前屏幕 y」，反复滚动会把偏移量一层层累加进去。
        contentY.put(widget, widget.getY());
        widget.setY(viewTop() + widget.getY() - scrollState.offset());
        addWidget(widget);
        scrollWidgets.add(widget);
    }

    /** 底部固定按钮：不随滚动移动，也不参与裁剪。 */
    private final List<AbstractWidget> footerWidgets = new ArrayList<>();

    private void addFooterWidget(AbstractWidget widget) {
        addWidget(widget);
        footerWidgets.add(widget);
    }

    /**
     * 枚举循环按钮。
     *
     * <p>点击后在枚举值之间循环切换。比下拉框轻，也符合原版控件的观感。
     *
     * @param offset 显示文案在枚举名之前追加的字符数，用于对齐不同长度的标签
     */
    private <E extends Enum<E>> AbstractWidget cycleEnum(Class<E> type, E initial,
                                                         int x, int y, String label,
                                                         int offset,
                                                         java.util.function.BiFunction<
                                                                 LyricStyleConfig, E,
                                                                 LyricStyleConfig> writer) {
        E[] values = type.getEnumConstants();
        return Button.builder(Component.translatable(LangKeys.OPTION_ENUM_FORMAT,
                        Component.translatable(label), displayOf(initial)),
                b -> {
                    // 基准取当前样式里的值，而不是创建按钮时的快照：
                    // 按钮可能活过多轮点击，用快照会导致第一次之后永远只在
                    // 两个值之间来回跳。
                    E current = currentOf(type);
                    E next = values[(current.ordinal() + 1) % values.length];
                    apply(style -> writer.apply(style, next));
                    rebuildWidgets();
                })
                .bounds(x, y, COLUMN_WIDTH, ROW_HEIGHT)
                .build();
    }

    /** 从当前样式取该枚举的现值。 */
    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> E currentOf(Class<E> type) {
        LyricStyleConfig s = controller.style();
        if (type == LyricStyleConfig.Weight.class) {
            return (E) s.weight();
        }
        if (type == LyricStyleConfig.Align.class) {
            return (E) s.align();
        }
        if (type == LyricStyleConfig.InterludeStyle.class) {
            return (E) s.interlude();
        }
        return type.getEnumConstants()[0];
    }

    /**
     * 取枚举的显示名。
     *
     * <p>样式枚举各自持有语言键字段 {@code labelKey}，界面文本由此翻译得到。
     */
    private static Component displayOf(Enum<?> value) {
        try {
            Object key = value.getClass().getField("labelKey").get(value);
            if (key instanceof String s && !s.isEmpty()) {
                return Component.translatable(s);
            }
        } catch (ReflectiveOperationException ignored) {
            // 无键的枚举退回常量名
        }
        return Component.literal(value.name());
    }

    /** 应用样式变更：立刻写进渲染器，预览即时生效。 */
    private void apply(java.util.function.UnaryOperator<LyricStyleConfig> change) {
        controller.setStyle(change.apply(controller.style()));
    }

    private void restoreDefaults() {
        controller.setStyle(LyricStyleConfig.DEFAULT);
        rebuildWidgets();
    }

    private void cancel() {
        onClose();
    }

    @Override
    public void onClose() {
        renderer.setPreviewForced(false);
        renderer.setPreviewArea(null);
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /** 关掉菜单模糊背景，否则预览框里的歌词会被糊掉。 */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xD0000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        // 显式控制顺序：背景在最底层，自绘内容与控件依次覆盖。
        // 本类不调 super.render，否则背景会被重画一遍压在最上层。
        renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(client.font, title, width / 2, 12, 0xFFFFFFFF);

        // 预览框：深色底与上下边框，让歌词有明确的展示区域。
        FloatingLyricRenderer.Bounds area = renderer.previewArea();
        if (area != null && !area.isEmpty()) {
            graphics.fill(area.x(), area.y(),
                    area.x() + area.width(), area.y() + area.height(), 0x80000000);
            graphics.fill(area.x(), area.y(),
                    area.x() + area.width(), area.y() + 1, 0x88FFFFFF);
            graphics.fill(area.x(), area.y() + area.height() - 1,
                    area.x() + area.width(), area.y() + area.height(), 0x88FFFFFF);
        }

        // 歌词画进预览框。
        renderer.render(LinkLyricFabric.painterFor(graphics, client));

        // 滚动区：裁剪后逐个画控件，滚动才不会溢出到预览框与按钮上。
        int top = viewTop();
        int bottom = viewBottom();
        graphics.enableScissor(0, top, width, bottom);
        for (AbstractWidget widget : scrollWidgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();

        drawScrollbar(graphics, top, bottom);

        // 底部按钮固定在滚动区外，不参与裁剪。
        for (AbstractWidget widget : footerWidgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** 画右侧滚动条；内容装得下时不画。 */
    private void drawScrollbar(GuiGraphics graphics, int top, int bottom) {
        if (!scrollState.canScroll()) {
            return;
        }
        int trackHeight = bottom - top;
        int[] thumb = LyricOptions.ScrollState.thumb(
                top, trackHeight, viewHeight(), contentHeight, scrollState.offset());

        // 贴着右边缘，不会与两栏控件重叠。
        int barX = width - 7;
        graphics.fill(barX, top, barX + 3, bottom, 0x44FFFFFF);
        graphics.fill(barX, thumb[0], barX + 3, thumb[0] + thumb[1], 0xCCFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!scrollState.canScroll()) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        // 一档滚一行，手感与原版列表一致。向上为正，与滚动量方向相反。
        scrollState.scrollBy((int) Math.round(-vertical * (ROW_HEIGHT + ROW_GAP)));
        layoutScrollWidgets();
        return true;
    }

    /** 按当前滚动量重新摆放控件。 */
    private void layoutScrollWidgets() {
        for (AbstractWidget widget : scrollWidgets) {
            widget.setY(viewTop() + widgetContentY(widget) - scrollState.offset());
        }
    }

    /** 控件在内容坐标里的 y，注册时记录。 */
    private final java.util.Map<AbstractWidget, Integer> contentY =
            new java.util.IdentityHashMap<>();

    private int widgetContentY(AbstractWidget widget) {
        Integer y = contentY.get(widget);
        return y == null ? widget.getY() : y;
    }

    /** 单个可调项的滑条。 */
    private final class OptionSlider extends AbstractSliderButton {

        private final Option option;

        OptionSlider(int x, int y, int width, Option option) {
            super(x, y, width, ROW_HEIGHT,
                    Component.literal(option.displayValue(controller.style())
                        + " " + Component.translatable(option.unitKey).getString()),
                    option.read(controller.style()));
            this.option = option;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(LangKeys.OPTION_VALUE_FORMAT,
                    Component.translatable(option.labelKey),
                    option.displayValue(controller.style()),
                    Component.translatable(option.unitKey)));
        }

        @Override
        protected void applyValue() {
            apply(style -> option.write(style, value));
        }
    }
}
