package com.linklyric.common.i18n;

/**
 * 界面文案的语言键。
 *
 * <p>键名集中在此，调用方只引用常量，避免字符串散落在界面代码中。
 * 实际文案由各加载器的 {@code lang/*.json} 提供。
 */
public final class LangKeys {

    /** 配置界面标题。 */
    public static final String CONFIG_TITLE = "linklyric.config.title";
    public static final String FEATHER = "linklyric.config.feather";
    public static final String FEATHER_ON = "linklyric.config.feather.on";
    public static final String FEATHER_OFF = "linklyric.config.feather.off";
    /** 超长行折行开关。 */
    public static final String WRAP = "linklyric.config.wrap";
    public static final String TRANSITION = "linklyric.config.transition";
    public static final String SHADOW = "linklyric.config.shadow";
    public static final String SHADOW_ON = "linklyric.config.shadow.on";
    public static final String SHADOW_OFF = "linklyric.config.shadow.off";
    public static final String LINE_COUNT = "linklyric.config.lineCount";
    public static final String FONT_COLOR = "linklyric.config.fontColor";
    public static final String MASTER_SWITCH = "linklyric.config.master";
    public static final String LYRICS_ON = "linklyric.config.lyrics.on";
    public static final String LYRICS_OFF = "linklyric.config.lyrics.off";
    public static final String SOURCE_ENABLED = "linklyric.config.source.enabled";
    public static final String SOURCE_DISABLED = "linklyric.config.source.disabled";
    public static final String ADJUST_POSITION = "linklyric.config.adjustPosition";
    public static final String RESET_DEFAULT = "linklyric.config.reset";
    public static final String DONE = "linklyric.config.done";
    public static final String CANCEL = "linklyric.config.cancel";
    public static final String SAVE = "linklyric.config.save";

    /** 配置界面的分组标题。 */
    public static final String GROUP_DISPLAY = "linklyric.config.group.display";
    public static final String GROUP_APPEARANCE = "linklyric.config.group.appearance";
    public static final String GROUP_LAYOUT = "linklyric.config.group.layout";
    public static final String GROUP_ACTION = "linklyric.config.group.action";

    /** 选项悬浮说明。此前是硬编码中文字面量，英文界面会露出中文。 */
    public static final String TIP_LINE_COUNT = "linklyric.tip.lineCount";
    public static final String TIP_LINE_GAP = "linklyric.tip.lineGap";
    public static final String TIP_OPACITY = "linklyric.tip.opacity";
    public static final String TIP_LINE_SCALE = "linklyric.tip.lineScale";
    public static final String TIP_MAX_WIDTH = "linklyric.tip.maxWidth";
    public static final String TIP_TRANSITION = "linklyric.tip.transition";
    public static final String TIP_OFFSET = "linklyric.tip.offset";
    public static final String TIP_WEIGHT = "linklyric.tip.weight";
    public static final String TIP_STROKE_PX = "linklyric.tip.strokePx";
    public static final String TIP_STROKE_ALPHA = "linklyric.tip.strokeAlpha";

    /**
     * 通用开关文案。
     *
     * <p>此前所有开关都借用 {@code SHADOW_ON/OFF}，于是「描边」开关的下标
     * 显示成「阴影：开」，「羽化」开关则直接用了 {@code TRANSITION}
     * （「切换」）作为标签——标签与所控制的项完全对不上。
     */
    public static final String GENERIC_ON = "linklyric.config.on";
    public static final String GENERIC_OFF = "linklyric.config.off";

    /** 描边预设色名。 */
    public static final String PRESET_BLACK = "linklyric.preset.black";
    public static final String PRESET_WHITE = "linklyric.preset.white";
    public static final String PRESET_DARK_GRAY = "linklyric.preset.darkGray";
    public static final String PRESET_DARK_BLUE = "linklyric.preset.darkBlue";
    public static final String PRESET_DARK_BROWN = "linklyric.preset.darkBrown";

    /** 颜色选择界面。 */
    public static final String COLOR_TITLE = "linklyric.color.title";
    public static final String COLOR_HUE = "linklyric.color.hue";
    public static final String COLOR_SATURATION = "linklyric.color.saturation";
    public static final String COLOR_VALUE = "linklyric.color.value";
    public static final String COLOR_PREVIEW = "linklyric.color.preview";
    public static final String COLOR_CODE = "linklyric.color.code";

    /** 调色板当前编辑的目标提示。 */
    public static final String COLOR_ADJUSTING = "linklyric.color.adjusting";

    /** 调色板的编辑目标。 */
    public static final String SLOT_STROKE = "linklyric.color.slot.stroke";
    public static final String SLOT_SUNG = "linklyric.color.slot.sung";
    public static final String SLOT_UNSUNG = "linklyric.color.slot.unsung";
    public static final String SLOT_TRANSLATION = "linklyric.color.slot.translation";
    public static final String SLOT_RESET = "linklyric.color.slot.reset";

    /** 位置调整界面。 */
    public static final String LOCATION_TITLE = "linklyric.location.title";
    public static final String LOCATION_HINT = "linklyric.location.hint";
    public static final String LOCATION_DRAG = "linklyric.location.drag";
    public static final String LOCATION_RESET = "linklyric.location.reset";

    /** 指令反馈。 */
    public static final String COMMAND_ON = "linklyric.command.on";
    public static final String COMMAND_OFF = "linklyric.command.off";
    public static final String COMMAND_SOURCE_ON = "linklyric.command.source.on";
    public static final String COMMAND_SOURCE_OFF = "linklyric.command.source.off";
    public static final String COMMAND_OFFSET = "linklyric.command.offset";
    public static final String COMMAND_RESET = "linklyric.command.reset";
    public static final String COMMAND_STATUS = "linklyric.command.status";
    public static final String COMMAND_OPEN_CONFIG = "linklyric.command.openConfig";

    /** 样式选项名称。 */
    public static final String OPTION_LINE_COUNT = "linklyric.option.lineCount";
    public static final String OPTION_MAX_WIDTH = "linklyric.option.maxWidth";
    public static final String OPTION_TRANSITION_MS = "linklyric.option.transitionMs";
    public static final String OPTION_OPACITY = "linklyric.option.opacity";
    public static final String OPTION_ALIGN = "linklyric.option.align";
    public static final String OPTION_WEIGHT = "linklyric.option.weight";

    /** 上浮曲线切换。 */
    public static final String SHADOW_SOFT = "linklyric.config.shadow.soft";

    /** 行间距。 */
    public static final String OPTION_LINE_GAP = "linklyric.config.line_gap";

    /** 像素单位。 */
    public static final String UNIT_PX = "linklyric.config.unit_px";

    /** 描边开关。 */
    public static final String STROKE = "linklyric.config.stroke";

    /** 描边宽度。 */
    public static final String OPTION_STROKE_PX = "linklyric.config.stroke_px";

    /** 描边不透明度。 */
    public static final String OPTION_STROKE_ALPHA = "linklyric.config.stroke_alpha";

    /** 描边颜色。 */
    public static final String STROKE_COLOR = "linklyric.config.stroke_color";

    /** 描边分组标题。 */
    public static final String GROUP_STROKE = "linklyric.config.group.stroke";

    /** 上浮曲线切换。 */
    public static final String LIFT_CURVE = "linklyric.config.lift_curve";
    public static final String LIFT_CURVE_LINEAR = "linklyric.config.lift_curve.linear";
    public static final String LIFT_CURVE_COSINE = "linklyric.config.lift_curve.cosine";
    public static final String OPTION_INTERLUDE = "linklyric.option.interlude";
    public static final String OPTION_OFFSET = "linklyric.option.offset";

    /** 选项显示格式：名称、数值、单位。 */
    public static final String OPTION_VALUE_FORMAT = "linklyric.option.value";

    /** 枚举型选项的显示格式：名称与当前值。 */
    public static final String OPTION_ENUM_FORMAT = "linklyric.option.enum";
    public static final String OPTION_LINE_SCALE = "linklyric.option.lineScale";

    /** 单位。 */
    public static final String UNIT_MS = "linklyric.unit.ms";
    public static final String UNIT_TIMES = "linklyric.unit.times";
    public static final String UNIT_PERCENT = "linklyric.unit.percent";

    /** 颜色项名称。 */
    public static final String COLOR_TARGET_SUNG = "linklyric.color.target.sung";
    public static final String COLOR_TARGET_UNSUNG = "linklyric.color.target.unsung";
    public static final String COLOR_TARGET_TRANSLATION = "linklyric.color.target.translation";

    /** 间奏提示文本。 */
    public static final String INTERLUDE_DEFAULT = "linklyric.interlude.default";
    public static final String INTERLUDE_BLANK = "linklyric.interlude.blank";

    /** 样式枚举名称。 */
    // 只保留 REGULAR：Weight 枚举已裁到只剩常规一项，BOLD / HEAVY 两键
    // 从此没有任何 Java 引用，只是四份文案文件里的死字符串。留着它们
    // 会让人以为还存在可选字重，而渲染层早已不认这两个值。
    public static final String WEIGHT_REGULAR = "linklyric.weight.regular";
    public static final String ALIGN_LEFT = "linklyric.align.left";
    public static final String ALIGN_CENTER = "linklyric.align.center";
    public static final String ALIGN_RIGHT = "linklyric.align.right";

    /** 排布形态：界面上的选项名，以及三种形态各自的显示名。 */
    public static final String LAYOUT = "linklyric.config.layout";

    /** 排布形态的选项说明。单独一键，因为三种形态的取舍无法压进一个短名里。 */
    public static final String LAYOUT_TOOLTIP = "linklyric.config.layout.tooltip";
    public static final String LAYOUT_LINEAR = "linklyric.layout.linear";
    public static final String LAYOUT_ARC = "linklyric.layout.arc";
    public static final String LAYOUT_RING = "linklyric.layout.ring";

    /**
     * 中英混排校正。
     *
     * <p>三项分开：开关管「要不要」，两个滑条管「校多少」。
     * 不给合并成「轻/中/重」三档，理由见
     * {@code LyricStyleConfig.withMixed} 的注释——两个量在实机上
     * 可能需要反向调整，预设表达不了那一格。
     */
    public static final String MIXED_SCRIPT = "linklyric.config.mixed";
    public static final String MIXED_SCRIPT_TOOLTIP = "linklyric.config.mixed.tooltip";
    public static final String OPTION_MIXED_SCALE = "linklyric.config.mixed_scale";
    public static final String TIP_MIXED_SCALE = "linklyric.tip.mixedScale";
    public static final String OPTION_MIXED_BASELINE = "linklyric.config.mixed_baseline";
    public static final String TIP_MIXED_BASELINE = "linklyric.tip.mixedBaseline";

    private LangKeys() {
    }
}
