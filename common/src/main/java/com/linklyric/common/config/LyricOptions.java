package com.linklyric.common.config;

import com.linklyric.common.i18n.LangKeys;
import com.linklyric.common.style.LyricStyleConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 配置界面的控件清单。
 *
 * <p>把「有哪些可调项、取值范围、如何应用到样式」放在 {@code :common}，
 * 平台侧只负责把每一项渲染成原版控件。这样 Fabric 与 NeoForge 的
 * 配置界面不会走样，加减一项也只改一处。
 *
 * <p>颜色不在此列：它需要调色板而不是单个滑条，由平台侧单独呈现。
 */
public final class LyricOptions {

    private LyricOptions() {
    }

    /** 一个可调项。 */
    public enum Option {
        /** 时间偏移，正数表示歌词提前。 */
        OFFSET_MS(LangKeys.OPTION_OFFSET, LangKeys.UNIT_MS,
                LyricStyleConfig.MIN_OFFSET_MS, LyricStyleConfig.MAX_OFFSET_MS, 10),
        /** 行切换时长。 */
        TRANSITION_MS(LangKeys.OPTION_TRANSITION_MS, LangKeys.UNIT_MS,
                LyricStyleConfig.MIN_TRANSITION_MS, LyricStyleConfig.MAX_TRANSITION_MS, 10),
        /** 字号缩放。 */
        LINE_SCALE(LangKeys.OPTION_LINE_SCALE, LangKeys.UNIT_TIMES, 100, 200, 1),
        /** 最大宽度占比。 */
        WIDTH_RATIO(LangKeys.OPTION_MAX_WIDTH, LangKeys.UNIT_PERCENT, 30, 100, 1),
        /** 整体不透明度，10% 到 100%。 */
        OPACITY(LangKeys.OPTION_OPACITY, LangKeys.UNIT_PERCENT,
                Math.round(LyricStyleConfig.MIN_OPACITY * 100f), 100, 1),
        /** 笔画加粗百分比。0 为不加粗。 */
        WEIGHT_PERCENT(LangKeys.OPTION_WEIGHT, LangKeys.UNIT_PERCENT,
                LyricStyleConfig.MIN_WEIGHT_PERCENT,
                LyricStyleConfig.MAX_WEIGHT_PERCENT, 1);

        /** 名称与单位的语言键。 */
        public final String labelKey;
        public final String unitKey;
        public final int min;
        public final int max;
        public final int step;

        Option(String labelKey, String unitKey, int min, int max, int step) {
            this.labelKey = labelKey;
            this.unitKey = unitKey;
            this.min = min;
            this.max = max;
            this.step = step;
        }

        /**
         * 从样式读出当前值，归一化到 0..1。
         *
         * <p>滑条只认 0..1，区间换算在这里完成，
         * 平台侧不必各写一遍映射。
         */
        public double read(LyricStyleConfig style) {
            int raw = readRaw(style);
            return (raw - min) / (double) (max - min);
        }

        /** 从样式读出原始整数值。 */
        public int readRaw(LyricStyleConfig style) {
            return switch (this) {
                case OFFSET_MS -> style.offsetMs();
                case TRANSITION_MS -> style.lineTransitionMs();
                case LINE_SCALE -> Math.round(style.lineScale() * 100f);
                case WIDTH_RATIO -> Math.round(style.maxWidthRatio() * 100f);
                case OPACITY -> Math.round(style.opacity() * 100f);
                case WEIGHT_PERCENT -> style.weightPercent();
            };
        }

        /** 把 0..1 的滑条值写回样式。 */
        public LyricStyleConfig write(LyricStyleConfig style, double normalized) {
            int raw = min + (int) Math.round(normalized * (max - min));
            raw = applyStep(raw);
            return switch (this) {
                case OFFSET_MS -> style.withOffsetMs(raw);
                case TRANSITION_MS -> style.withLineTransitionMs(raw);
                case LINE_SCALE -> style.withLineScale(raw / 100f);
                case WIDTH_RATIO -> style.withMaxWidthRatio(raw / 100f);
                case OPACITY -> style.withOpacity(raw / 100f);
                case WEIGHT_PERCENT -> style.withWeightPercent(raw);
            };
        }

        private int applyStep(int raw) {
            if (step <= 1) {
                return raw;
            }
            int snapped = Math.round(raw / (float) step) * step;
            return Math.max(min, Math.min(max, snapped));
        }

        /** 数值部分，供界面与单位拼接。 */
        public String displayValue(LyricStyleConfig style) {
            return String.valueOf(readRaw(style));
        }
    }

    /** 颜色项的标识；界面据此打开对应调色板。 */
    public enum ColorTarget {
        SUNG(LangKeys.COLOR_TARGET_SUNG),
        UNSUNG(LangKeys.COLOR_TARGET_UNSUNG),
        TRANSLATION(LangKeys.COLOR_TARGET_TRANSLATION);

        /** 名称的语言键。 */
        public final String labelKey;

        ColorTarget(String labelKey) {
            this.labelKey = labelKey;
        }

        public int read(LyricStyleConfig style) {
            return switch (this) {
                case SUNG -> style.sungColor();
                case UNSUNG -> style.unsungColor();
                case TRANSLATION -> style.translationColor();
            };
        }

        public LyricStyleConfig write(LyricStyleConfig style, int argb) {
            return switch (this) {
                case SUNG -> style.withSungColor(argb);
                case UNSUNG -> style.withUnsungColor(argb);
                case TRANSLATION -> style.withTranslationColor(argb);
            };
        }
    }

    /** 调色板预设：常见歌词配色，免去逐通道调。 */
    public static final List<Integer> PALETTE = List.of(
            0xFF7FD4FF, 0xFF5AB0FF, 0xFFFF8FB8, 0xFFFFD166,
            0xFF9AE66E, 0xFFFFFFFF, 0xFFD8D8D8, 0xFF9A9A9A,
            0xFF202020);

    /** 逐个通道调整时的可用增量（ARGB 各通道）。 */
    public static List<Integer> channelDeltas() {
        List<Integer> deltas = new ArrayList<>();
        for (int d : new int[]{-32, -16, -8, 8, 16, 32}) {
            deltas.add(d);
        }
        return deltas;
    }

    /** 按增量调整某个通道，结果夹在 0..255。 */
    public static int shiftChannel(int argb, Channel channel, int delta) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        switch (channel) {
            case ALPHA -> a = clamp8(a + delta);
            case RED -> r = clamp8(r + delta);
            case GREEN -> g = clamp8(g + delta);
            case BLUE -> b = clamp8(b + delta);
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp8(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** ARGB 的四个通道。 */
    public enum Channel {
        ALPHA, RED, GREEN, BLUE
    }

    /**
     * 滚动区状态。
     *
     * <p>把「滚动量怎么夹、什么时候可滚、缩略块画在哪」抽成纯计算放在 common：
     * 这些错法在界面里靠眼睛看不出来（滚动量累加、内容比视口矮却允许滚、
     * 缩略块跑出轨道），只能靠数算验证。
     */
    public static final class ScrollState {

        private final int total;
        private final int visible;
        private int offset;

        public ScrollState(int total, int visible) {
            this.total = Math.max(0, total);
            this.visible = Math.max(0, visible);
        }

        public boolean canScroll() {
            return visible > 0 && total > visible;
        }

        /** 最大滚动量；不可滚时为 0。 */
        public int maxOffset() {
            return canScroll() ? total - visible : 0;
        }

        public int offset() {
            return offset;
        }

        /** 按增量滚，结果夹在 [0, maxOffset]。 */
        public void scrollBy(int delta) {
            if (!canScroll()) {
                offset = 0;
                return;
            }
            offset = Math.max(0, Math.min(maxOffset(), offset + delta));
        }

        public void setOffset(int value) {
            if (!canScroll()) {
                offset = 0;
                return;
            }
            offset = Math.max(0, Math.min(maxOffset(), value));
        }

        /**
         * 缩略块的 {@code [y, height]}。
         *
         * <p>贴顶时 y 等于轨道顶，贴底时 y+height 等于轨道底，
         * 中间按比例线性映射。缩略块最矮 16 像素，否则内容很长时会细成一条线。
         */
        public static int[] thumb(int trackTop, int trackHeight, int visible,
                                  int total, int offset) {
            if (trackHeight <= 0 || total <= 0) {
                return new int[]{trackTop, 0};
            }
            int height = Math.max(16, trackHeight * Math.min(visible, total) / total);
            height = Math.min(height, trackHeight);
            int max = Math.max(1, total - visible);
            int travel = trackHeight - height;
            int y = trackTop + (int) ((long) travel * Math.max(0, Math.min(offset, max)) / max);
            return new int[]{y, height};
        }
    }

    /** 便捷：把样式变更回调包成消费器，便于界面统一处理。 */
    public static Consumer<LyricStyleConfig> applier(Consumer<LyricStyleConfig> sink) {
        return sink;
    }
}
