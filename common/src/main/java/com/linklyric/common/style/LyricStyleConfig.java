package com.linklyric.common.style;

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
        float opacity) {

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

    /** 显示行数下限与上限。 */
    public static final int MIN_LINES = 1;
    public static final int MAX_LINES = 10;

    /** 整体不透明度下限：再低就接近不可见，没有实际意义。 */
    public static final float MIN_OPACITY = 0.1f;

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
            1f);

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
        lineCount = clamp(lineCount, MIN_LINES, MAX_LINES);
        // 整体不透明度下限不取 0：全透明等于歌词消失，
        // 而界面上没有「看不见」这个预期，用户会以为模组坏了。
        opacity = clampFloat(opacity, MIN_OPACITY, 1f);
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
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withUnsungColor(int color) {
        return new LyricStyleConfig(sungColor, color, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withTranslationColor(int color) {
        return new LyricStyleConfig(sungColor, unsungColor, color,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withOffsetMs(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                value, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withLineTransitionMs(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, value, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withLineScale(float value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, value, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withMaxWidthRatio(float value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, value, feather,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withFeather(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, value,
                weight, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withWeight(Weight value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                value, align, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withAlign(Align value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, value, shadow, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withShadow(boolean value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, value, lineCount, interlude, opacity);
    }

    public LyricStyleConfig withLineCount(int value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, value, interlude, opacity);
    }

    public LyricStyleConfig withInterlude(InterludeStyle value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, value, opacity);
    }

    public LyricStyleConfig withOpacity(float value) {
        return new LyricStyleConfig(sungColor, unsungColor, translationColor,
                offsetMs, lineTransitionMs, lineScale, maxWidthRatio, feather,
                weight, align, shadow, lineCount, interlude, value);
    }
}