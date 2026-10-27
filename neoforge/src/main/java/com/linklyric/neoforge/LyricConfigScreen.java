package com.linklyric.neoforge;

import com.linklyric.common.hud.FloatingLyricRenderer;
import com.linklyric.common.hud.LyricDragController;
import com.linklyric.common.hud.LayoutMode;
import com.linklyric.common.i18n.LangKeys;
import com.linklyric.common.style.LyricStyleConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import com.linklyric.common.hud.LyricGeometry;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * 歌词配置界面。
 *
 * <h2>为什么继承 {@link OptionsSubScreen}</h2>
 *
 * <p>原版设置页已经处理好滚动、标题、页脚、控件对齐、键盘导航与朗读。
 * 自己搭一套意味着这些全要重写，而且每一项都容易与原版不一致——
 * 滚动条位置、行高、Tab 顺序、窗口过小时的裁剪，做错了也不会报错，
 * 只会看起来"怪怪的"。
 *
 * <p>继承之后要写的只有 {@link #addOptions()} 与预览那一行。
 *
 * <h2>预览为什么单独占一行控件</h2>
 *
 * <p>{@code OptionsList} 的行高固定 20 像素，由 {@code Entry.render}
 * 里写死，改不了。所以预览不能用"高一点的控件"来做——那样会溢出
 * 盖住下一行。这里的办法是塞一个隐藏的占位控件占住一整行的位置，
 * 再在自己的 {@code render} 里按这一行的实际坐标画预览，
 * 高度只借这一行的 20 像素，不越界。
 */
final class LyricConfigScreen extends OptionsSubScreen {

    private final FloatingLyricRenderer renderer;
    private final LyricDragController controller;

    /**
     * 描边预设色。
     *
     * <p>描边不需要整块调色板：它的取值空间很小，现实里几乎只用
     * 黑、白与几个深色。给一排预设比塞进调色页更省事，也不用
     * 再为它开一个二级界面。要精确颜色仍可去调色页——那里管的是
     * 歌词本体。
     */
    private record StrokePreset(String nameKey, int argb) {
    }

    private static final StrokePreset[] STROKE_PRESETS = {
            new StrokePreset(LangKeys.PRESET_BLACK, 0xFF000000),
            new StrokePreset(LangKeys.PRESET_WHITE, 0xFFFFFFFF),
            new StrokePreset(LangKeys.PRESET_DARK_GRAY, 0xFF404040),
            new StrokePreset(LangKeys.PRESET_DARK_BLUE, 0xFF102040),
            new StrokePreset(LangKeys.PRESET_DARK_BROWN, 0xFF3A2410),
    };

    /** 当前颜色对应哪个预设；不在预设里时退回第一个。 */
    private int strokePresetIndex() {
        int current = controller.style().strokeColor();
        for (int i = 0; i < STROKE_PRESETS.length; i++) {
            if (STROKE_PRESETS[i].argb == current) {
                return i;
            }
        }
        return 0;
    }

    /** 预览占位：只为占住若干行，本身不画任何东西。 */
    private AbstractWidget previewAnchor;

    /**
     * 除首行外，预览还需要额外占几行。
     *
     * <p>由渲染侧同一个 {@link LyricGeometry#previewHeight} 算出目标高度，
     * 再折算成 OptionsList 的整数行数。写死 56 的做法在行数被调大后
     * 会让副行被裁掉，调小时又会空出一大截。
     */
    private int previewExtraRows() {
        int rowStride = 20 + 4;
        int want = LyricGeometry.previewHeight(
                controller.style().lineCount(),
                controller.style().lineGapPx(),
                LyricGeometry.LIFT_HEADROOM,
                LyricGeometry.SHADOW_HEADROOM);
        int rows = (want + 4 + rowStride - 1) / rowStride;
        return Math.max(0, rows - 1);
    }

    LyricConfigScreen(FloatingLyricRenderer renderer,
                      LyricDragController controller, Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable(LangKeys.CONFIG_TITLE));
        this.renderer = renderer;
        this.controller = controller;
    }

    @Override
    protected void init() {
        // 配置期间强制预览：歌词关掉也要看得见改动。
        renderer.setPreviewForced(true);
        super.init();
    }

    @Override
    protected void addOptions() {
        if (list == null) {
            return;
        }

        // 预览占位：按当前行数算出需要多高，再折算成 OptionsList 的行数。
        //
        // OptionsList 的行高由 Entry.render 写死 20 像素，无法给单个控件
        // 更高空间。这里的办法是让占位控件连续占 N 行，列表自然把后续
        // 控件整体下推——不会盖住任何东西，也不需要自绘一套滚动。
        // 高度随「行数」与「行距」实时变化，滑条一动预览就跟着长高。
        previewAnchor = hidden();
        list.addSmall(previewAnchor, null);
        int extraRows = previewExtraRows();
        for (int i = 0; i < extraRows; i++) {
            list.addSmall(hidden(), null);
        }

        section(LangKeys.GROUP_DISPLAY);

        pair(CycleButton.booleanBuilder(Component.translatable(LangKeys.LYRICS_ON), Component.translatable(LangKeys.LYRICS_OFF), controller.isEnabled())
                        .create(0, 0, 150, 20,
                                Component.translatable(LangKeys.MASTER_SWITCH),
                                (b, v) -> {
                                    if (v) {
                                        controller.enable();
                                    } else {
                                        controller.disable();
                                    }
                                }),
                CycleButton.booleanBuilder(Component.translatable(LangKeys.SOURCE_ENABLED), Component.translatable(LangKeys.SOURCE_DISABLED), controller.isSourceEnabled())
                        .create(0, 0, 150, 20,
                                Component.literal("Salt Player"),
                                (b, v) -> controller.setSourceEnabled(v)));

        single(integer(LangKeys.OPTION_LINE_COUNT,
                LyricStyleConfig.MIN_LINES, LyricStyleConfig.MAX_LINES,
                LyricStyleConfig::lineCount,
                LyricStyleConfig::withLineCount,
                LangKeys.TIP_LINE_COUNT));

        single(integer(LangKeys.OPTION_LINE_GAP,
                LyricStyleConfig.MIN_LINE_GAP, LyricStyleConfig.MAX_LINE_GAP,
                LyricStyleConfig::lineGapPx,
                LyricStyleConfig::withLineGapPx,
                LangKeys.TIP_LINE_GAP));

        single(integer(LangKeys.OPTION_OPACITY,
                Math.round(LyricStyleConfig.MIN_OPACITY * 100f), 100,
                s -> Math.round(s.opacity() * 100f),
                (s, v) -> s.withOpacity(v / 100f),
                LangKeys.TIP_OPACITY));

        section(LangKeys.GROUP_LAYOUT);

        // 纵向位置：0% 贴底（默认）、100% 贴顶。
        //
        // 放在排版分组的最前面，因为它决定「整块落在屏幕的哪个高度」，
        // 是比字号、宽度更上位的约束。滑条是连续的百分比而不是三档枚举：
        // 贴底/居中/贴顶都是它的特例，而「灵动岛」「侧边栏」这类布局
        // 需要的正是中间那几格。
        //
        // 提示里明确写了贴顶会被 Boss 血条与计分板遮挡：这一点无法在界面上
        // 用预览框体现（预览框里没有那些元素），只能靠文字告知。
        single(integer(LangKeys.OPTION_ANCHOR_RATIO,
                0, 100,
                s -> Math.round(s.anchorRatio() * 100f),
                (s, v) -> s.withAnchorRatio(v / 100f),
                LangKeys.TIP_ANCHOR_RATIO));

        // 左右留白：只影响底板与可拖范围，不改变文字落笔位置。
        single(integer(LangKeys.OPTION_PAD_X,
                LyricStyleConfig.MIN_PAD_X, LyricStyleConfig.MAX_PAD_X,
                LyricStyleConfig::padXPx,
                LyricStyleConfig::withPadXPx,
                LangKeys.TIP_PAD_X));

        single(integer(LangKeys.OPTION_LINE_SCALE,
                100, 200,
                s -> Math.round(s.lineScale() * 100f),
                (s, v) -> s.withLineScale(v / 100f),
                LangKeys.TIP_LINE_SCALE));

        single(integer(LangKeys.OPTION_MAX_WIDTH,
                30, 100,
                s -> Math.round(s.maxWidthRatio() * 100f),
                (s, v) -> s.withMaxWidthRatio(v / 100f),
                LangKeys.TIP_MAX_WIDTH));

        single(integer(LangKeys.OPTION_TRANSITION_MS,
                LyricStyleConfig.MIN_TRANSITION_MS, LyricStyleConfig.MAX_TRANSITION_MS,
                LyricStyleConfig::lineTransitionMs,
                LyricStyleConfig::withLineTransitionMs,
                LangKeys.TIP_TRANSITION));

        single(integer(LangKeys.OPTION_OFFSET,
                LyricStyleConfig.MIN_OFFSET_MS, LyricStyleConfig.MAX_OFFSET_MS,
                LyricStyleConfig::offsetMs,
                LyricStyleConfig::withOffsetMs,
                LangKeys.TIP_OFFSET));

        // 对齐是枚举，用原版 CycleButton 的原生多值切换。
        single(CycleButton.builder(
                        (LyricStyleConfig.Align a) -> Component.translatable(a.labelKey),
                        controller.style().align())
                .withValues(LyricStyleConfig.Align.values())
                .create(0, 0, 310, 20,
                        Component.translatable(LangKeys.OPTION_ALIGN),
                        (b, v) -> apply(s -> s.withAlign(v))));

        section(LangKeys.GROUP_APPEARANCE);

        single(integer(LangKeys.OPTION_WEIGHT,
                LyricStyleConfig.MIN_WEIGHT_PERCENT, LyricStyleConfig.MAX_WEIGHT_PERCENT,
                LyricStyleConfig::weightPercent,
                LyricStyleConfig::withWeightPercent,
                LangKeys.TIP_WEIGHT));

        pair(onOff(LangKeys.SHADOW, controller.style().shadow(),
                        (s, v) -> s.withShadow(v)),
                onOff(LangKeys.SHADOW_SOFT, controller.style().softShadow(),
                        (s, v) -> s.withSoftShadow(v)));

        pair(onOff(LangKeys.FEATHER, controller.style().feather(),
                        (s, v) -> s.withFeather(v)),
                CycleButton.builder(
                                (LyricStyleConfig.LiftCurve c) ->
                                        Component.translatable(c.labelKey),
                                controller.style().liftCurve())
                        .withValues(LyricStyleConfig.LiftCurve.values())
                        .create(0, 0, 150, 20,
                                Component.translatable(LangKeys.LIFT_CURVE),
                                (b, v) -> apply(s -> s.withLiftCurve(v))));

        // 超长行折行：默认关。开启后超宽不再横向滚动，而是换成多行。
        // 单独占一行而不是与上面的开关配对，因为它会改变整块的高度，
        // 旁边再挤一个控件容易让人误以为是同一组。
        single(onOff(LangKeys.WRAP, controller.style().wrap(),
                (s, v) -> s.withWrap(v)));

        // 排布形态：横排、弧形、环形。
        //
        // 放在折行之后单独一行，因为它的影响面比上面任何一项都大：
        // 它决定每一行落在哪、以什么角度画。切成弧形后折行开关仍然有效
        // （折行决定「有哪几行」），但滚动会被形态否决，超长句直接截断。
        // 这一点写进提示里，避免用户以为切过去之后长句还能滚。
        AbstractWidget layoutButton = CycleButton.builder(
                        (LayoutMode mode) -> Component.translatable(mode.labelKey),
                        controller.style().layout())
                .withValues(LayoutMode.values())
                .create(0, 0, 310, 20,
                        Component.translatable(LangKeys.LAYOUT),
                        (b, v) -> apply(s -> s.withLayout(v)));
        // 提示挂在控件上而不是加一行说明文字：后者会占掉一整行高度，
        // 而这个界面已经排满。悬停即见，也符合原版设置页的做法。
        layoutButton.setTooltip(Tooltip.create(
                Component.translatable(LangKeys.LAYOUT_TOOLTIP)));
        single(layoutButton);

        // ——— 中英混排校正 ———
        //
        // 三项一组：开关 + 两个刻度。开关放在最前，因为它决定后两项
        // 此刻是否生效——但后两项**不联动禁用**，理由与描边那组相同：
        // 用户会先调好刻度再打开开关，若关着就调不了，只能反过来操作，
        // 那是个死角。
        //
        // 默认关闭。开启会改变所有含中日韩字符的歌词的像素位置，
        // 因此这一项必须由用户明确选择，不能替他们决定。
        AbstractWidget mixedButton = CycleButton.booleanBuilder(Component.translatable(LangKeys.GENERIC_ON), Component.translatable(LangKeys.GENERIC_OFF), controller.style().mixed().enabled())
                .create(0, 0, 310, 20,
                        Component.translatable(LangKeys.MIXED_SCRIPT),
                        (b, v) -> apply(s -> s.withMixed(s.mixed().withEnabled(v))));
        // 提示必须挂上：这一项的效果依赖字体资源，无法在界面上说明白，
        // 只能告诉用户「它做什么、怎么调」。
        mixedButton.setTooltip(Tooltip.create(
                Component.translatable(LangKeys.MIXED_SCRIPT_TOOLTIP)));
        single(mixedButton);

        single(integer(LangKeys.OPTION_MIXED_SCALE,
                com.linklyric.common.style.MixedScript.MIN_SCALE_PERCENT,
                com.linklyric.common.style.MixedScript.MAX_SCALE_PERCENT,
                s -> s.mixed().cjkScalePercent(),
                (s, v) -> s.withMixed(s.mixed().withCjkScalePercent(v)),
                LangKeys.TIP_MIXED_SCALE));

        single(integer(LangKeys.OPTION_MIXED_BASELINE,
                com.linklyric.common.style.MixedScript.MIN_BASELINE_SHIFT_PX,
                com.linklyric.common.style.MixedScript.MAX_BASELINE_SHIFT_PX,
                s -> s.mixed().baselineShiftPx(),
                (s, v) -> s.withMixed(s.mixed().withBaselineShiftPx(v)),
                LangKeys.TIP_MIXED_BASELINE));

        // ——— 描边 ———
        //
        // 单开一组，因为描边是「开关 + 两个连续量」的组合：
        // 关掉时那两个滑条仍然有意义（下次打开就是这套值），
        // 所以不联动禁用，避免掉进「关掉了就调不了」的死角。
        section(LangKeys.GROUP_STROKE);

        single(CycleButton.booleanBuilder(Component.translatable(LangKeys.GENERIC_ON), Component.translatable(LangKeys.GENERIC_OFF), controller.style().stroke())
                .create(0, 0, 310, 20,
                        Component.translatable(LangKeys.STROKE),
                        (b, v) -> apply(s -> s.withStroke(v))));

        single(integer(LangKeys.OPTION_STROKE_PX,
                LyricStyleConfig.MIN_STROKE_PX, LyricStyleConfig.MAX_STROKE_PX,
                LyricStyleConfig::strokePx,
                LyricStyleConfig::withStrokePx,
                LangKeys.TIP_STROKE_PX));

        single(integer(LangKeys.OPTION_STROKE_ALPHA,
                LyricStyleConfig.MIN_STROKE_ALPHA, LyricStyleConfig.MAX_STROKE_ALPHA,
                LyricStyleConfig::strokeAlphaPercent,
                LyricStyleConfig::withStrokeAlphaPercent,
                LangKeys.TIP_STROKE_ALPHA));

        single(CycleButton.builder(
                        (Integer v) -> Component.translatable(LangKeys.STROKE_COLOR)
                                .append(Component.literal(": "))
                                .append(Component.translatable(STROKE_PRESETS[v].nameKey)),
                        strokePresetIndex())
                .withValues(java.util.stream.IntStream.range(0, STROKE_PRESETS.length)
                        .boxed().toList())
                .create(0, 0, 310, 20,
                        Component.translatable(LangKeys.STROKE_COLOR),
                        (b, v) -> apply(s -> s.withStrokeColor(STROKE_PRESETS[v].argb))));

        single(CycleButton.builder(
                        (LyricStyleConfig.InterludeStyle i) ->
                                Component.translatable(i.label),
                        controller.style().interlude())
                .withValues(LyricStyleConfig.InterludeStyle.values())
                .create(0, 0, 310, 20,
                        Component.translatable(LangKeys.OPTION_INTERLUDE),
                        (b, v) -> apply(s -> s.withInterlude(v))));

        single(new Button.Builder(
                Component.translatable(LangKeys.FONT_COLOR),
                b -> minecraft.setScreen(new ColorPickerScreen(this))).build());

        section(LangKeys.GROUP_ACTION);

        single(new Button.Builder(
                Component.translatable(LangKeys.ADJUST_POSITION),
                b -> minecraft.setScreen(
                        new LyricLocationScreen(renderer, controller, this))).build());

        single(new Button.Builder(
                Component.translatable(LangKeys.RESET_DEFAULT),
                b -> restoreDefaults()).build());
    }

    // ——— 控件工厂 ———

    /** 一个占位控件：不可见、不可交互，只用来占住一行。 */
    private static AbstractWidget hidden() {
        Button b = Button.builder(Component.empty(), x -> {
        }).build();
        b.visible = false;
        b.active = false;
        return b;
    }

    /** 独占一行的控件。 */
    private void single(AbstractWidget widget) {
        list.addSmall(widget, null);
    }

    /** 并排两个控件。 */
    private void pair(AbstractWidget left, AbstractWidget right) {
        list.addSmall(left, right);
    }

    /** 分组标题。传语言键而不是字面量，中英文才能各走各的 lang 文件。 */
    private void section(String langKey) {
        list.addSmall(new StringWidget(Component.translatable(langKey), font), null);
    }

    /** 开关：原版 CycleButton 的双值形态。 */
    private CycleButton<Boolean> onOff(String labelKey, boolean initial,
                                       BiFunction<LyricStyleConfig, Boolean,
                                               LyricStyleConfig> writer) {
        return CycleButton.booleanBuilder(Component.translatable(LangKeys.GENERIC_ON), Component.translatable(LangKeys.GENERIC_OFF), initial)
                .create(0, 0, 150, 20, Component.translatable(labelKey),
                        (b, v) -> apply(s -> writer.apply(s, v)));
    }

    /**
     * 整数滑条。
     *
     * <p>用原版 {@link OptionInstance} 而不是自己继承
     * {@code AbstractSliderButton}：原版已经把键盘左右微调、朗读文本、
     * 拖动时的取值对齐都做好了，自己实现很难与其它设置页一致。
     */
    private AbstractWidget integer(String labelKey, int min, int max,
                                   ToIntFunction<LyricStyleConfig> reader,
                                   BiFunction<LyricStyleConfig, Integer,
                                           LyricStyleConfig> writer,
                                   String tooltip) {
        OptionInstance<Integer> option = new OptionInstance<>(
                labelKey,
                OptionInstance.noTooltip(),
                (caption, value) -> Component.translatable(LangKeys.OPTION_VALUE_FORMAT,
                        caption, value, ""),
                new OptionInstance.IntRange(min, max),
                reader.applyAsInt(controller.style()),
                value -> apply(s -> writer.apply(s, value)));
        AbstractWidget widget = option.createButton(minecraft.options);
        if (tooltip != null) {
            widget.setTooltip(Tooltip.create(Component.translatable(tooltip)));
        }
        return widget;
    }

    private void apply(UnaryOperator<LyricStyleConfig> change) {
        controller.setStyle(change.apply(controller.style()));
    }

    private void restoreDefaults() {
        controller.setStyle(LyricStyleConfig.DEFAULT);
        // 重置后要让所有控件显示回默认值。
        // 重新走一遍 init 会把标题与页脚再加一次，
        // 所以只重建列表内容，不动 layout。
        rebuildList();
    }

    /**
     * 只重建列表内容，不重建整个界面。
     *
     * <p>{@code init()} 里 {@code addTitle}/{@code addFooter} 会把控件
     * 追加进 {@code HeaderAndFooterLayout}，重复调用就会叠出两份标题、
     * 两份「完成」按钮，而且 {@code clearWidgets()} 清不掉它们——
     * 那两个控件归 layout 管。所以这里只清空列表再重新加。
     */
    private void rebuildList() {
        if (list == null) {
            return;
        }
        list.children().clear();
        addOptions();
    }

    // ——— 预览与绘制 ———

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawPreview(graphics);
    }

    /**
     * 在预览占位那一行的位置上画歌词。
     *
     * <p>高度只用这一行的 20 像素：{@code OptionsList} 的行高是固定的，
     * 画高了就会盖住下一行控件。歌词自身在 20 像素里会按块心居中，
     * 单行时正好落在中间，不必额外偏移。
     */
    private void drawPreview(GuiGraphics graphics) {
        AbstractWidget anchor = previewAnchor;
        if (anchor == null) {
            return;
        }
        // 高度取「占位那几行」的总跨度：占位控件本身仍是 20 像素高，
        // 但它在列表中连续占住 extraRows + 1 行，这段纵向空间归预览使用。
        int rowStride = 20 + 4; // OptionsList 的行高与行距，与原版一致
        int height = Math.max(20, (previewExtraRows() + 1) * rowStride - 4);
        int x = anchor.getX();
        int y = anchor.getY();
        int width = anchor.getWidth();
        if (height <= 0 || width <= 0) {
            return;
        }

        renderer.setPreviewArea(new FloatingLyricRenderer.Bounds(x, y, width, height));

        graphics.fill(x, y, x + width, y + height, 0x80000000);
        graphics.fill(x, y, x + width, y + 1, 0x55FFFFFF);
        graphics.fill(x, y + height - 1, x + width, y + height, 0x55FFFFFF);

        renderer.render(LinkLyricNeoForge.painterFor(graphics, Minecraft.getInstance()));
    }

    @Override
    public void onClose() {
        renderer.setPreviewForced(false);
        renderer.setPreviewArea(null);
        super.onClose();
    }
}