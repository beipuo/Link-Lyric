package com.linklyric.common.config;

import com.linklyric.common.hud.LayoutMode;
import java.io.IOException;import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.common.style.MixedScript;

/**
 * 歌词位置配置的持久化。
 *
 * <p>只存两个整数偏移，格式用 {@link Properties}：
 * 无依赖、可手工编辑、损坏时能安全退回默认值。
 * 不引入 JSON 库是为了让 {@code :common} 保持零外部依赖。
 */
public final class LayoutConfig {

    /** 默认配置文件名。 */
    private static final String FILE_NAME = "linklyric-layout.properties";

    private static final String KEY_X = "offsetX";
    private static final String KEY_Y = "offsetY";
    private static final String KEY_ENABLED = "enabled";

    private static final String KEY_SUNG_COLOR = "sungColor";
    private static final String KEY_UNSUNG_COLOR = "unsungColor";
    private static final String KEY_TRANSLATION_COLOR = "translationColor";
    private static final String KEY_OFFSET_MS = "offsetMs";
    private static final String KEY_TRANSITION_MS = "lineTransitionMs";
    private static final String KEY_LINE_SCALE = "lineScale";
    private static final String KEY_WIDTH_RATIO = "maxWidthRatio";
    private static final String KEY_FEATHER = "feather";
    private static final String KEY_WEIGHT = "weight";
    private static final String KEY_ALIGN = "align";
    private static final String KEY_SHADOW = "shadow";
    private static final String KEY_LINE_COUNT = "lineCount";
    private static final String KEY_INTERLUDE = "interlude";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_LIFT_CURVE = "liftCurve";
    private static final String KEY_WEIGHT_PERCENT = "weightPercent";
    private static final String KEY_SOFT_SHADOW = "softShadow";
    private static final String KEY_LINE_GAP = "lineGap";
    private static final String KEY_STROKE = "stroke";
    private static final String KEY_STROKE_PX = "strokePx";
    private static final String KEY_STROKE_COLOR = "strokeColor";
    private static final String KEY_STROKE_ALPHA = "strokeAlpha";
    /** 超长行是否折行。默认 false，旧配置文件缺这一项时保持旧观感。 */
    private static final String KEY_WRAP = "wrap";
    /**
     * 排布形态。
     *
     * <p>存的是 {@link LayoutMode#id()} 而不是枚举名：那个 id 一旦写进用户文件
     * 就不能再改，而枚举名是代码标识，重命名不该让老配置失效。
     * 缺键时落回 {@code linear}，升级后画面与之前逐像素一致。
     */
    private static final String KEY_LAYOUT = "layout";
    /**
     * 中英混排校正的三项。
     *
     * <p>刻意拆成三个键而不是压成一个字符串：前两项是整数刻度，
     * 用户手改配置文件时能直接改数字试效果，不必研究一种迷你语法。
     * 三项同缺时整体退回关闭（见 {@code read()}），
     * 于是旧配置文件升级后画面逐像素不变。
     */
    private static final String KEY_MIXED_ENABLED = "mixedScript";
    private static final String KEY_MIXED_SCALE = "mixedScriptScale";
    private static final String KEY_MIXED_BASELINE = "mixedScriptBaseline";
    /**
     * 纵向锚点比例与左右留白。
     *
     * <p>两个键都**缺键即默认**，且默认值恰好等于改造前的硬编码行为
     * （贴底、留白 6）。于是旧配置文件升级后画面逐像素不变——
     * 这不是靠「写进文件里的值恰好相同」，而是靠缺键时返回的
     * {@code LyricStyleConfig.DEFAULT} 里那两个字段本身就是旧值。
     *
     * <p>锚点比例存浮点十进制（{@code Float.toString}）：它要能被手改，
     * 而百分比整数会让「0.15」这种常用值变成 15、从而多一层换算。
     * 留白是整数像素，像素本来就没有小数。
     */
    private static final String KEY_ANCHOR_RATIO = "anchorRatio";
    private static final String KEY_PAD_X = "padX";
    /** 已禁用的数据源 id 列表，逗号分隔。 */
    private static final String KEY_DISABLED_SOURCES = "disabledSources";

    private final Path path;
    private int offsetX;
    private int offsetY;
    private boolean enabled = true;
    private LyricStyleConfig style = LyricStyleConfig.DEFAULT;
    /** 被禁用的数据源 id。用集合而非单值：将来可能同时接入多个源。 */
    private final java.util.Set<String> disabledSources = new java.util.LinkedHashSet<>();

    private LayoutConfig(Path path) {
        this.path = path;
    }

    /** 从目录加载；文件不存在或损坏时使用默认值。 */
    public static LayoutConfig load(Path directory) {
        LayoutConfig config = new LayoutConfig(directory.resolve(FILE_NAME));
        config.read();
        return config;
    }

    private void read() {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            // 配置损坏不该让歌词画不出来，静默使用默认值。
            return;
        }
        offsetX = parseInt(props.getProperty(KEY_X), 0);
        offsetY = parseInt(props.getProperty(KEY_Y), 0);
        // 只有明确写了 "false" 才视为关闭：
        // 旧版本配置文件里没有这个键，必须默认开启，否则升级后歌词会凭空消失。
        enabled = !"false".equalsIgnoreCase(
                props.getProperty(KEY_ENABLED, "true").trim());

        // 样式逐项回退到默认值：缺失的键不能把整份样式重置掉，
        // 否则旧配置文件升级后颜色会全部变回默认。
        LyricStyleConfig d = LyricStyleConfig.DEFAULT;
        style = new LyricStyleConfig(
                parseColor(props.getProperty(KEY_SUNG_COLOR), d.sungColor()),
                parseColor(props.getProperty(KEY_UNSUNG_COLOR), d.unsungColor()),
                parseColor(props.getProperty(KEY_TRANSLATION_COLOR), d.translationColor()),
                parseInt(props.getProperty(KEY_OFFSET_MS), d.offsetMs()),
                parseInt(props.getProperty(KEY_TRANSITION_MS), d.lineTransitionMs()),
                parseFloat(props.getProperty(KEY_LINE_SCALE), d.lineScale()),
                parseFloat(props.getProperty(KEY_WIDTH_RATIO), d.maxWidthRatio()),
                !"false".equalsIgnoreCase(
                        props.getProperty(KEY_FEATHER, "true").trim()),
                parseEnum(LyricStyleConfig.Weight.class,
                        props.getProperty(KEY_WEIGHT), d.weight()),
                parseEnum(LyricStyleConfig.Align.class,
                        props.getProperty(KEY_ALIGN), d.align()),
                !"false".equalsIgnoreCase(
                        props.getProperty(KEY_SHADOW, "true").trim()),
                parseInt(props.getProperty(KEY_LINE_COUNT), d.lineCount()),
                parseEnum(LyricStyleConfig.InterludeStyle.class,
                        props.getProperty(KEY_INTERLUDE), d.interlude()),
                parseFloat(props.getProperty(KEY_OPACITY), d.opacity()),
                parseEnum(LyricStyleConfig.LiftCurve.class,
                        props.getProperty(KEY_LIFT_CURVE), d.liftCurve()),
                parseInt(props.getProperty(KEY_WEIGHT_PERCENT), d.weightPercent()),
                !"false".equalsIgnoreCase(
                        props.getProperty(KEY_SOFT_SHADOW, "true").trim()),
                parseInt(props.getProperty(KEY_LINE_GAP), d.lineGapPx()),
                !"false".equalsIgnoreCase(
                        props.getProperty(KEY_STROKE, "false").trim()),
                parseInt(props.getProperty(KEY_STROKE_PX), d.strokePx()),
                parseColor(props.getProperty(KEY_STROKE_COLOR), d.strokeColor()),
                parseInt(props.getProperty(KEY_STROKE_ALPHA), d.strokeAlphaPercent()),
                // 折行的默认值是 false，且旧配置文件里没有这个键。
                // 这里刻意用 "true" 作为默认比较值：与上面的 feather/shadow 相反，
                // 折行是「新增能力」，缺键时必须落回关闭，画面才与升级前一致。
                "true".equalsIgnoreCase(props.getProperty(KEY_WRAP, "false").trim()),
                // 排布形态走 id 反查而不是 parseEnum：id 是稳定标识，
                // 与枚举名解耦（理由见 LayoutMode 类注释）。
                // fromId 自身在无法识别时退回 LINEAR，因此旧配置缺键即横排。
                LayoutMode.fromId(props.getProperty(KEY_LAYOUT)),
                // 混排校正：三个键同缺时整体关闭。
                //
                // 这里不用 "true".equals(getProperty(KEY, "false")) 那种单行写法，
                // 因为三个键要**一起**决定语义：只写了 mixedScript=true
                // 而两个刻度缺失时，刻度各自回退到默认起点（96 / 0），
                // 那是"用户打开了但还没调"的合法状态，不该被当成缺配置而关掉。
                // 只有连 mixedScript 这个键都没有，才是真正的旧配置文件。
                new MixedScript(
                        "true".equalsIgnoreCase(
                                props.getProperty(KEY_MIXED_ENABLED, "false").trim()),
                        parseInt(props.getProperty(KEY_MIXED_SCALE),
                                MixedScript.DEFAULT_SCALE_PERCENT),
                        parseInt(props.getProperty(KEY_MIXED_BASELINE),
                                MixedScript.DEFAULT_BASELINE_SHIFT_PX)),
                // 锚点比例与左右留白：缺键即回落到样式默认值（贴底 + 留白 6），
                // 与改造前的硬编码行为相同。这里不做「读到 0 才贴底」之类的
                // 特判：0 是合法值，用它当「缺键」的哨兵会让显式写成 0 的用户
                // 在升级后被改回别的值。
                parseFloat(props.getProperty(KEY_ANCHOR_RATIO), d.anchorRatio()),
                parseInt(props.getProperty(KEY_PAD_X), d.padXPx()));

        String disabled = props.getProperty(KEY_DISABLED_SOURCES, "");
        for (String id : disabled.split(",")) {
            if (!id.isBlank()) {
                disabledSources.add(id.trim());
            }
        }
    }

    /** 该数据源是否被用户禁用。 */
    public boolean isSourceDisabled(String id) {
        return id != null && disabledSources.contains(id);
    }

    /** 记录数据源的启用状态并落盘。 */
    public void saveSourceEnabled(String id, boolean enabled) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (enabled) {
            disabledSources.remove(id);
        } else {
            disabledSources.add(id);
        }
        persistAll();
    }

    /**
     * 解析枚举，非法值或缺失时回退默认。
     *
     * <p>配置文件是玩家可以手改的，写错一个字母不该让模组崩掉。
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int parseColor(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            // 允许 #AARRGGBB 与纯十进制两种写法，前者便于手工编辑。
            String text = raw.trim();
            if (text.startsWith("#")) {
                return (int) Long.parseLong(text.substring(1), 16);
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 写入并落盘；失败时保留内存值，不影响本次游戏。 */
    public void save(int x, int y) {
        save(x, y, enabled);
    }

    /** 写入样式，位置与开关保持不变。 */
    public void saveStyle(LyricStyleConfig value) {
        save(offsetX, offsetY, enabled, value);
    }

    /** 同时写入位置与显示开关。 */
    public void save(int x, int y, boolean enabled) {
        save(x, y, enabled, style);
    }

    /** 全量写入。所有变更最终都汇到这里，避免各写一半。 */
    public void save(int x, int y, boolean enabled, LyricStyleConfig value) {
        this.offsetX = x;
        this.offsetY = y;
        this.enabled = enabled;
        this.style = value == null ? LyricStyleConfig.DEFAULT : value;
        persistAll();
    }

    /** 把当前内存状态整体写盘。所有入口最终都汇到这里。 */
    private void persistAll() {
        if (path == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(KEY_X, Integer.toString(offsetX));
        props.setProperty(KEY_Y, Integer.toString(offsetY));
        props.setProperty(KEY_ENABLED, Boolean.toString(this.enabled));
        props.setProperty(KEY_SUNG_COLOR, hex(style.sungColor()));
        props.setProperty(KEY_UNSUNG_COLOR, hex(style.unsungColor()));
        props.setProperty(KEY_TRANSLATION_COLOR, hex(style.translationColor()));
        props.setProperty(KEY_OFFSET_MS, Integer.toString(style.offsetMs()));
        props.setProperty(KEY_TRANSITION_MS, Integer.toString(style.lineTransitionMs()));
        props.setProperty(KEY_LINE_SCALE, Float.toString(style.lineScale()));
        props.setProperty(KEY_WIDTH_RATIO, Float.toString(style.maxWidthRatio()));
        // 羽化与阴影都用 "false" 才是关：这样旧配置里缺失的键不会被读成关闭。
        props.setProperty(KEY_FEATHER, Boolean.toString(style.feather()));
        props.setProperty(KEY_WEIGHT, style.weight().name());
        props.setProperty(KEY_ALIGN, style.align().name());
        props.setProperty(KEY_SHADOW, Boolean.toString(style.shadow()));
        props.setProperty(KEY_LINE_COUNT, Integer.toString(style.lineCount()));
        props.setProperty(KEY_INTERLUDE, style.interlude().name());
        props.setProperty(KEY_OPACITY, Float.toString(style.opacity()));
        props.setProperty(KEY_LIFT_CURVE, style.liftCurve().name());
        props.setProperty(KEY_WEIGHT_PERCENT, Integer.toString(style.weightPercent()));
        props.setProperty(KEY_SOFT_SHADOW, Boolean.toString(style.softShadow()));
        props.setProperty(KEY_LINE_GAP, Integer.toString(style.lineGapPx()));
        props.setProperty(KEY_STROKE, Boolean.toString(style.stroke()));
        props.setProperty(KEY_STROKE_PX, Integer.toString(style.strokePx()));
        props.setProperty(KEY_STROKE_COLOR, hex(style.strokeColor()));
        props.setProperty(KEY_STROKE_ALPHA, Integer.toString(style.strokeAlphaPercent()));
        props.setProperty(KEY_WRAP, Boolean.toString(style.wrap()));
        props.setProperty(KEY_LAYOUT, style.layout().id());
        props.setProperty(KEY_MIXED_ENABLED, Boolean.toString(style.mixed().enabled()));
        props.setProperty(KEY_MIXED_SCALE,
                Integer.toString(style.mixed().cjkScalePercent()));
        props.setProperty(KEY_MIXED_BASELINE,
                Integer.toString(style.mixed().baselineShiftPx()));
        // 锚点比例用 Float.toString 而不是格式化到固定小数位：
        // 往返必须无损，否则手改配置文件写成 0.15 的用户在下次保存后
        // 会看到 0.2——数字莫名其妙地变了，而他没改过任何东西。
        props.setProperty(KEY_ANCHOR_RATIO, Float.toString(style.anchorRatio()));
        props.setProperty(KEY_PAD_X, Integer.toString(style.padXPx()));
        props.setProperty(KEY_DISABLED_SOURCES, String.join(",", disabledSources));
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                props.store(writer, "Link Lyric layout");
            }
        } catch (IOException e) {
            // 磁盘只读等情况：本次会话内仍然生效，下次启动回到默认位置。
        }
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    public boolean enabled() {
        return enabled;
    }

    public LyricStyleConfig style() {
        return style;
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }
}
