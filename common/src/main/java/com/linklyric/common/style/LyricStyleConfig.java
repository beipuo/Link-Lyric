package com.linklyric.common.style;

import com.linklyric.common.hud.ArcPlacement;
import com.linklyric.common.hud.LayoutMode;
import com.linklyric.common.i18n.LangKeys;

/**
 * 用户可调的歌词样式参数。
 *
 * <p>刻意做成不可变对象：渲染器每帧只读，改配置时整体替换引用。
 * 若做成可变字段，界面线程与控制线程会读到「改了一半」的组合状态，
 * 表现为颜色或时长忽然跳变。
 *
 * <p>这里只放**真正影响观感**的量。渲染器内部用于平滑、羽化的常量
 * 不在此列——那些是算法参数，调坏了只会让人困惑。
 *
 * @param sungColor       已唱部分颜色，ARGB
 * @param unsungColor     未唱部分颜色，ARGB
 * @param translationColor 翻译行颜色，ARGB
 * @param offsetMs        时间偏移，正数表示歌词提前显示
 * @param lineTransitionMs 歌词片段切换时长，0 表示瞬间切换
 * @param lineScale       当前行字号缩放
 * @param maxWidthRatio   歌词最大宽度占屏幕比例
 * @param feather         是否启用交界羽化过渡
 * @param weight          字重，见 {@link Weight}
 * @param align           水平对齐，见 {@link Align}
 * @param shadow          是否绘制文字阴影
 * @param liftCurve       上浮曲线形状，见 {@link LiftCurve}
 * @param weightPercent   笔画加粗，0..100，0 为不加粗
 * @param softShadow      阴影是否柔和。关闭则退回原版的硬边投影
 * @param lineGapPx       行间距，像素。指两行字形之间的真实空白
 * @param stroke          是否给字形描边
 * @param strokePx        描边宽度，像素
 * @param strokeColor     描边颜色，ARGB
 * @param strokeAlphaPercent 描边不透明度百分比，0..100
 * @param wrap            超长行是否折行。默认关闭，见 {@link #withWrap} 的注释
 * @param layout          歌词的排布形态：横排堆叠、弧形、环形。
 *                        默认 {@link LayoutMode#LINEAR}，见 {@link #withLayout}
 */
public record LyricStyleConfig(
        int sungColor,
        int unsungColor,
        int translationColor,
        int offsetMs,
        int lineTransitionMs,
        float lineScale,
        float maxWidthRatio,
        boolean feather,
        Weight weight,
        Align align,
        boolean shadow,
        int lineCount,
        InterludeStyle interlude,
        float opacity,
        LiftCurve liftCurve,
        int weightPercent,
        boolean softShadow,
        int lineGapPx,
        boolean stroke,
        int strokePx,
        int strokeColor,
        int strokeAlphaPercent,
        boolean wrap,
        LayoutMode layout) {

    /** 间奏提示的显示方式。 */
    public enum InterludeStyle {
        /** 完全不出画，保持空白。 */
        BLANK(LangKeys.INTERLUDE_BLANK, ""),
        /** 三个点，最安静的状态说明。 */
        DOTS("···", "···"),
        /** 直接写「间奏」，文案可翻译。 */
        TEXT(LangKeys.INTERLUDE_DEFAULT, LangKeys.INTERLUDE_DEFAULT);

        public final String label;
        /** 绘制内容的语言键；空串表示不出画。 */
        public final String text;

        InterludeStyle(String label, String text) {
            this.label = label;
            this.text = text;
        }
    }

    /**
     * 逐字上浮的曲线形状。
     *
     * <p>两者都让「已唱的字在最高位」，差别在过渡带里的形状：
     * <ul>
     *   <li>{@link #LINEAR}：抬升量与演唱进度成正比。实现最简单，
     *   但每个字各自起落，长音上能看到字一个接一个跳起来。</li>
     *   <li>{@link #COSINE}：整行当成一条横跨的斜坡，两端梯度为 0，
     *   过渡带跟着演唱点走。形状取自 Salt Player 的歌词上浮。</li>
     * </ul>
     */
    public enum LiftCurve {
        LINEAR(LangKeys.LIFT_CURVE_LINEAR),
        COSINE(LangKeys.LIFT_CURVE_COSINE);

        public final String labelKey;

        LiftCurve(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    /** 显示行数下限与上限。 */
    public static final int MIN_LINES = 1;
    public static final int MAX_LINES = 10;

    /** 整体不透明度下限：再低就接近不可见，没有实际意义。 */
    public static final float MIN_OPACITY = 0.1f;

    /** 笔画加粗范围，单位为百分比。上限压在 15%：再高笔画会粘连。 */
    public static final int MIN_WEIGHT_PERCENT = 0;
    public static final int MAX_WEIGHT_PERCENT = 100;

    /** 行间距范围，像素。 */
    public static final int MIN_LINE_GAP = 0;
    public static final int MAX_LINE_GAP = 12;

    /** 默认行间距，像素。取 2 使行步长为 11，约合字号的 1.22 倍。 */
    public static final int DEFAULT_LINE_GAP = 2;

    /** 描边宽度范围，像素。上限 4：9px 的字上再宽就糊成一团。 */
    public static final int MIN_STROKE_PX = 1;
    public static final int MAX_STROKE_PX = 4;

    /** 描边不透明度范围，百分比。 */
    public static final int MIN_STROKE_ALPHA = 0;
    public static final int MAX_STROKE_ALPHA = 100;

    /** 默认描边宽度。1 像素在小字号上刚好能看清轮廓。 */
    public static final int DEFAULT_STROKE_PX = 1;

    /**
     * 默认描边颜色：不透明黑。
     *
     * <p>黑色描边在浅色底和彩色歌词上都能压住轮廓，
     * 是最不容易出错的选择。
     */
    public static final int DEFAULT_STROKE_COLOR = 0xFF000000;

    /** 默认描边不透明度。全不透明，描边才立得住。 */
    public static final int DEFAULT_STROKE_ALPHA = 70;

    /**
     * 字重。
     *
     * <p>**只保留常规一档。** 加粗在逐字绘制路径上无法正确实现：
     * MC 的粗体是同一字形右移一个纹素再画一遍，在 9px 的位图字形上
     * 这份错位副本会形成肉眼可见的第二个轮廓，看起来像两个字重叠。
     *
     * <p>保留枚举本身是为了配置文件与界面选项的结构不变：
     * 旧配置里的 {@code BOLD} 由解析器回退到 {@link #REGULAR}，不影响启动。
     */
    public enum Weight {
        /** 常规字重。 */
        REGULAR(LangKeys.WEIGHT_REGULAR);

        /** 名称的语言键。 */
        public final String labelKey;

        Weight(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    /** 水平对齐。 */
    public enum Align {
        LEFT(LangKeys.ALIGN_LEFT),
        CENTER(LangKeys.ALIGN_CENTER),
        RIGHT(LangKeys.ALIGN_RIGHT);

        /** 名称的语言键。 */
        public final String labelKey;

        Align(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    /** 时间偏移的上下限。超过 5 秒基本是误操作，且会让歌词与音频明显脱节。 */
    public static final int MIN_OFFSET_MS = -5000;
    public static final int MAX_OFFSET_MS = 5000;

    /** 切换时长的上下限。上限 3 秒，再长会显得迟滞。 */
    public static final int MIN_TRANSITION_MS = 0;
    public static final int MAX_TRANSITION_MS = 3000;

    public static final float MIN_LINE_SCALE = 0.6f;
    public static final float MAX_LINE_SCALE = 2.0f;

    public static final float MIN_WIDTH_RATIO = 0.3f;
    public static final float MAX_WIDTH_RATIO = 1.0f;

    /** 默认样式，与渲染器原先的硬编码值保持一致。 */
    public static final LyricStyleConfig DEFAULT = new LyricStyleConfig(
            0xFF7FD4FF,
            0xFFD8D8D8,
            0xFF9A9A9A,
            0,
            700,
            1.0f,
            0.72f,
            true,
            Weight.REGULAR,
            Align.CENTER,
            true,
            2,
            InterludeStyle.TEXT,
            1f,
            LiftCurve.COSINE,
            0,
            true,
            DEFAULT_LINE_GAP,
            false,
            DEFAULT_STROKE_PX,
            DEFAULT_STROKE_COLOR,
            DEFAULT_STROKE_ALPHA,
            false,
            LayoutMode.LINEAR);

    public LyricStyleConfig {
        // 构造时就夹到合法区间：配置文件和界面都可能给出越界值，
        // 在这里收口比在每个使用者那里各判一次可靠。
        offsetMs = clamp(offsetMs, MIN_OFFSET_MS, MAX_OFFSET_MS);
        lineTransitionMs = clamp(lineTransitionMs, MIN_TRANSITION_MS, MAX_TRANSITION_MS);
        lineScale = clampFloat(lineScale, MIN_LINE_SCALE, MAX_LINE_SCALE);
        maxWidthRatio = clampFloat(maxWidthRatio, MIN_WIDTH_RATIO, MAX_WIDTH_RATIO);
        // 枚举可能来自配置文件里的旧值或拼错的字符串，统一兜底。
        if (weight == null) {
            weight = Weight.REGULAR;
        }
        if (align == null) {
            align = Align.CENTER;
        }
        if (interlude == null) {
            interlude = InterludeStyle.TEXT;
        }
        if (liftCurve == null) {
            liftCurve = LiftCurve.COSINE;
        }
        // 排布形态同样兜底：老配置文件里没有这个键，读出来会是 null。
        // 退回横排而不是抛异常，理由与 fromId 相同：最差的结果是回到
        // 所有版本都支持的形态，而不是让歌词整个不显示。
        if (layout == null) {
            layout = LayoutMode.LINEAR;
        }
        lineCount = clamp(lineCount, MIN_LINES, MAX_LINES);
        // 整体不透明度下限不取 0：全透明等于歌词消失，
        // 而界面上没有「看不见」这个预期，用户会以为模组坏了。
        opacity = clampFloat(opacity, MIN_OPACITY, 1f);
        weightPercent = clamp(weightPercent, MIN_WEIGHT_PERCENT, MAX_WEIGHT_PERCENT);
        // 下面三项此前只在对应的 with* 方法里夹取，构造器本身没有。
        // 而 LayoutConfig.read() 是直接调构造器的（21 个参数一次填满），
        // 于是手改配置文件把 lineGap 写成 999 就能穿透到渲染层：
        // 行距会让歌词彼此叠印，描边宽度会让笔画糊成一团，
        // 描边不透明度 999% 会经 strokeArgb() 溢出成 0xF3 的 alpha。
        // 夹取必须收口在构造器，否则「哪个入口记得夹」就成了运气问题。
        lineGapPx = clamp(lineGapPx, MIN_LINE_GAP, MAX_LINE_GAP);
        strokePx = clamp(strokePx, MIN_STROKE_PX, MAX_STROKE_PX);
        strokeAlphaPercent = clamp(strokeAlphaPercent, MIN_STROKE_ALPHA, MAX_STROKE_ALPHA);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public LyricStyleConfig withSungColor(int color) {
        return new LyricStyleConfig(color, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withUnsungColor(int color) {
        return new LyricStyleConfig(sungColor, color, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withTranslationColor(int color) {
        return new LyricStyleConfig(sungColor, unsungColor, color,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withOffsetMs(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                value, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withLineTransitionMs(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, value, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withLineScale(float value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, value, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withMaxWidthRatio(float value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, value, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withFeather(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, value,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withWeight(Weight value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                value, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withAlign(Align value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, value, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withShadow(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, value, lineCount, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withLineCount(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, value, interlude, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withInterlude(InterludeStyle value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, value, opacity, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withLiftCurve(LiftCurve value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, value, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withSoftShadow(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, weightPercent, value, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    /** 开关描边。 */
    public LyricStyleConfig withStroke(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow, lineGapPx, value, strokePx, strokeColor,
                strokeAlphaPercent, wrap, layout);
    }

    /** 描边宽度，像素。 */
    public LyricStyleConfig withStrokePx(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow, lineGapPx, stroke,
                value,
                strokeColor, strokeAlphaPercent, wrap, layout);
    }

    /** 描边颜色，ARGB。 */
    public LyricStyleConfig withStrokeColor(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow, lineGapPx, stroke, strokePx, value,
                strokeAlphaPercent, wrap, layout);
    }

    /** 描边不透明度，百分比。 */
    public LyricStyleConfig withStrokeAlphaPercent(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor,
                value, wrap, layout);
    }

    /**
     * 描边最终用于绘制的 ARGB：把百分比乘进 alpha。
     *
     * <p>颜色与不透明度分开存，是为了让调色板与滑条各自独立——
     * 改透明度不该把用户选的颜色冲掉。
     */
    public int strokeArgb() {
        int a = Math.round(((strokeColor >>> 24) & 0xFF) * (strokeAlphaPercent / 100f));
        return (a << 24) | (strokeColor & 0x00FFFFFF);
    }

    public LyricStyleConfig withLineGapPx(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow,
                value,
                stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    /**
     * 开关折行。
     *
     * <h2>为什么是「开关」而不是「自动」</h2>
     *
     * <p>折行会改变可见行数，而可见行数决定块高、包围盒、命中区与行切换
     * 位移。这些量全部参与既有像素快照。若做成「窄于某比例就自动折」，
     * 同一句歌词在窄屏上折、宽屏上滚，两种排版的观感差别远大于「滚或不滚」——
     * 而用户没有任何手段回到旧行为。这里给显式开关：
     * 默认 {@code false}，一切既有画面逐像素不变；打开后长句才折。
     *
     * <p>也不做「折行优先、折不下再滚」的兜底：单行宽度被 {@code maxWidthRatio}
     * 限制在屏幕的 30%..100%，一句超长歌词折出来可能有五六行，
     * 那会把副行全部挤下屏幕。折行段数由 {@link LyricWrap#MAX_LINES} 封顶，
     * 折不下的余量仍走原来的滚动，两种机制各管一段，不互相冒充。
     */
    public LyricStyleConfig withWrap(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor,
                strokeAlphaPercent, value, layout);
    }

    public LyricStyleConfig withWeightPercent(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve, value,
                softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    public LyricStyleConfig withOpacity(float value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, value, liftCurve, weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor, strokeAlphaPercent, wrap, layout);
    }

    /**
     * 换一种排布形态。
     *
     * <h2>为什么这是一个「形态」而不是若干细参数</h2>
     *
     * <p>弧形与环形各自的几何参数（半径、张角、起始方位）都由策略
     * 从行数与行高反解——它们不是可以随手调的旋钮，而是「让这种形态
     * 不叠字、不溢出」的必要条件。把这些量暴露给用户，等于让用户可以
     * 把排布调坏，而界面上的滑块既说不清「半径 180 意味着什么」，
     * 也无法阻止用户拖到会叠字的位置。
     *
     * <p>因此这里只给「选哪种形态」，具体几何交给策略自己守不变式。
     * 想再加一种形态，是新增一个 {@link LayoutMode} 常量与一个实现类，
     * 而不是再加三个滑块。
     *
     * <h2>副作用：形态会否决滚动与折行</h2>
     *
     * <p>弧形与环形不滚动（理由见 {@link ArcPlacement} 类注释）。
     * 它们**不**否决折行：折行决定的是「有哪几行」，
     * 而每种形态都能摆若干行。但折行与滚动本就互斥，
     * 于是切到弧形后「超长句」既不会滚也不会折——超出屏幕的部分不显示。
     * 这一点写在选项说明里，用户能预期。
     */
    public LyricStyleConfig withLayout(LayoutMode value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity, liftCurve,
                weightPercent, softShadow, lineGapPx, stroke, strokePx, strokeColor,
                strokeAlphaPercent, wrap, value);
    }
}