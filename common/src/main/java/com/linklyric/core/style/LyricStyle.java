package com.linklyric.core.style;

/**
 * 歌词样式：四个正交维度的组合，而非五套独立实现。
 *
 * <p>新增样式只需新增一个预设常量（或将 JSON 反序列化为本记录），
 * 不需要新增渲染代码。只有「挂载原生 UI」与「特殊几何」两类才需要
 * 独立的 Renderer 实现。
 *
 * @param id                 样式标识，用于配置持久化
 * @param displayName        展示名，存语言键，由平台层翻译
 * @param anchor             挂载位置
 * @param chrome             外观容器
 * @param lineHeight         行高（GUI 像素）
 * @param currentLineScale   当前行放大倍率
 * @param neighborAlpha      非当前行不透明度
 * @param scrollDurationMs   滚动过渡时长
 * @param perWordHighlight   是否启用逐字高亮（关闭可用整行裁切换性能）
 * @param showTranslation    是否显示翻译行
 */
public record LyricStyle(
        String id,
        String displayName,
        Anchor anchor,
        Chrome chrome,
        float lineHeight,
        float currentLineScale,
        float neighborAlpha,
        long scrollDurationMs,
        boolean perWordHighlight,
        boolean showTranslation) {

    /** 挂载位置。 */
    public enum Anchor {
        /** 底部居中单行字幕，最不打扰，默认样式。 */
        BOTTOM_CENTER,
        /** 顶部居中横条，复用 BossBar 语境。 */
        TOP_CENTER,
        /** 屏幕右侧竖排，需避让计分板。 */
        SIDE_RIGHT,
        /** 物品栏与屏幕底边之间的空白带。 */
        ABOVE_HOTBAR,
        /** 可拖动的自由悬浮。 */
        FLOATING
    }

    /** 外观容器。全部为矩形系，共用同一套绘制基元。 */
    public enum Chrome {
        /** 无底板，纯文字 + 阴影。 */
        NONE,
        /** 半透明底板。 */
        PANEL,
        /** 底板 + 描边，参考 Tooltip 配色。 */
        BORDERED,
        /** BossBar 式横条 + 渐变边。 */
        BOSS_BAR,
        /** 进度条式，填充比例映射当前行进度。 */
        PROGRESS_BAR
    }

    /**
     * 样式预设的展示名语言键。
     *
     * <p>定义在此而非 {@code common.i18n.LangKeys}，因为 {@code core} 是被
     * {@code common} 依赖的下层，不能反向引用。{@code LangKeys} 中登记了
     * 同一批键，测试锁住两份一致。
     */
    public static final String KEY_SUBTITLE = "linklyric.style.subtitle";
    public static final String KEY_ISLAND = "linklyric.style.island";
    public static final String KEY_SIDEBAR = "linklyric.style.sidebar";
    public static final String KEY_HOTBAR = "linklyric.style.hotbar";
    public static final String KEY_FLOATING = "linklyric.style.floating";

    /** 默认样式：底部单行字幕。默认必须是不打扰的。 */
    public static final LyricStyle SUBTITLE = new LyricStyle(
            "subtitle", KEY_SUBTITLE, Anchor.BOTTOM_CENTER, Chrome.NONE,
            10f, 1.0f, 0.0f, 0L, true, false);

    /** 灵动岛：顶部居中横条，MC 原生风格，非圆角胶囊。 */
    public static final LyricStyle ISLAND = new LyricStyle(
            "island", KEY_ISLAND, Anchor.TOP_CENTER, Chrome.BOSS_BAR,
            10f, 1.05f, 0.45f, 320L, true, true);

    /** 侧边栏：右侧竖排多行。 */
    public static final LyricStyle SIDEBAR = new LyricStyle(
            "sidebar", KEY_SIDEBAR, Anchor.SIDE_RIGHT, Chrome.PANEL,
            10f, 1.1f, 0.55f, 320L, true, true);

    /** 物品栏：进度条式，不做遮挡血量与饥饿度。 */
    public static final LyricStyle HOTBAR = new LyricStyle(
            "hotbar", KEY_HOTBAR, Anchor.ABOVE_HOTBAR, Chrome.PROGRESS_BAR,
            9f, 1.0f, 0.0f, 0L, false, false);

    /** 悬浮：可拖动自由定位。 */
    public static final LyricStyle FLOATING = new LyricStyle(
            "floating", KEY_FLOATING, Anchor.FLOATING, Chrome.BORDERED,
            10f, 1.0f, 0.5f, 240L, true, true);

    public LyricStyle {
        id = id == null ? "custom" : id;
        displayName = displayName == null ? id : displayName;
        lineHeight = Math.max(6f, lineHeight);
        currentLineScale = Math.max(0.5f, currentLineScale);
        neighborAlpha = Math.min(1f, Math.max(0f, neighborAlpha));
        scrollDurationMs = Math.max(0L, scrollDurationMs);
    }

    public static LyricStyle defaultStyle() {
        return SUBTITLE;
    }
}
