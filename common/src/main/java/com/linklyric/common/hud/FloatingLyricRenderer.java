package com.linklyric.common.hud;

import com.linklyric.common.hud.effect.LyricEffectEngine;
import com.linklyric.common.hud.effect.LyricUnitEffect;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.common.style.LyricStyleConfig.Align;
import com.linklyric.common.i18n.TextResolver;
import com.linklyric.common.style.LyricEasing;
import com.linklyric.core.sync.PlayerSnapshot;

import java.util.List;

/**
 * 悬浮歌词渲染器：单行居中、逐字染色。
 *
 * <p>染色的核心不是「算出已唱宽度然后画一条线」，而是让已唱宽度
 * **跨帧连续推进**。桥接端每秒才上报一次位置，即便外推到当前时刻，
 * 采样仍是粗糙的；若每帧直接采信目标值，高亮就会一顿一顿地跳。
 * 因此这里保留一个跨帧的 {@link #sungWidth}，每帧朝目标值平滑逼近，
 * 观感才是连续移动的。
 */
public final class FloatingLyricRenderer {

    /** 平台绘制适配层的别名，保持调用方书写一致。 */
    public interface Painter extends LyricCanvas {
    }

    /** 二维变换栈的别名，保持调用方书写一致。 */
    public interface Pose2D extends com.linklyric.common.hud.Pose2D {
    }

    /** 已唱色默认值，以 {@link LyricStyleConfig#DEFAULT} 为准。 */
    public static final int COLOR_SUNG = LyricStyleConfig.DEFAULT.sungColor();

    /** 未唱色默认值。与已唱色是两种独立色值，而非调暗的结果。 */
    public static final int COLOR_UNSUNG = LyricStyleConfig.DEFAULT.unsungColor();

    /** 拖动时的底板色，半透明黑，用于标示可拖区域。 */
    public static final int COLOR_BACKDROP = 0x66000000;

    /** 拖动时的边框色：偏亮的描边，用来标出可拖范围的边界。 */
    public static final int COLOR_DRAG_BORDER = 0x99FFFFFF;

    private static final int LINE_HEIGHT = LyricGeometry.LINE_HEIGHT;

    /** 平滑时间常数：约 50ms 内逼近目标，兼顾跟手与顺滑。 */
    private static final double SMOOTH_TAU_SECONDS = 0.05;

    /** 目标值比当前值小超过该像素数时判定为 seek，直接跳变而非回退。 */
    private static final float BACKWARD_SNAP_PX = 6f;

    /** 与目标的差值小于此值即吸附，避免指数逼近在行尾永久残留暗边。 */
    private static final float SNAP_EPSILON_PX = 1.5f;

    /**
     * 未唱文字的透明度。
     *
     * <p>「没唱到」用透明度表达，而不是换一个灰色。
     * 灰色是第二种颜色，会和用户选定的已唱色产生色相冲突；
     * 透明度只减弱同一颜色的存在感，唱到与没唱到始终是同一个色系。
     * 非主行用同一个值，于是「未生效」在全场景下是统一的一种视觉语言。
     */
    private static final float UNSUNG_ALPHA = 0.38f;

    /**
     * 副行字号相对主行的比例。
     *
     * <p>取自 Salt Player 的 {@code LyricsComponentDefaults.SubTextFontSizeScale = 0.8f}
     * 与 {@code AppConfig.playerLyricsTranslationFontSizeScale} 的默认值。
     * 翻译是次要信息，缩到 0.8 倍才不会与原文抢注意力。
     */
    private static final float SUB_TEXT_FONT_SCALE = 0.8f;

    /** 抬升需要额外预留的垂直空间，覆盖位移与抗锯齿余量。 */
    private static final int LIFT_HEADROOM = 2;
    /**
     * 阴影与字重叠加的额外余量。
     *
     * <p>原版阴影偏移 1 像素，加粗最多再向外 2 像素，取 4 已足够；
     * 留小了中文阴影会被裁剪，留大了只是多画几个透明像素。
     */
    private static final int SHADOW_HEADROOM = 4;

    /** 逐字绘制的最多字数，超过则退回整段绘制以控制绘制调用数。 */
    private static final int MAX_LIFT_CHARS = LyricProgress.MAX_LIFT_CHARS;

    /**
     * 间奏判定的最短空隙（毫秒）。
     *
     * <p>取 8000 与 HyperLyric 的 {@code InterludeTracker.minGapMs} 一致。
     * 低于这个长度的空隙多半只是句间呼吸，插提示反而碍眼。
     */
    private static final long INTERLUDE_MIN_GAP_MS = 8000L;

    private static final String INTERLUDE_TEXT = "间奏";

    /** 间奏提示色，刻意比正常歌词淡，避免抢注意力。 */
    private static final int COLOR_INTERLUDE = 0xFF8A8A8A;

    /**
     * 副行（下一句预览）颜色。
     *
     * <p>按 Salt 的 {@code NotCurrentAlpha = 0.3f} 作用在未唱色上：
     * 同一色系、只降低不透明度，不换颜色。换色会让两行看起来来自不同组件。
     */


    /** 上报周期初值（毫秒）：轮询更频繁，但宿主每秒才更新一次位置。 */
    private static final long DEFAULT_UPLINK_PERIOD_MS = 1000L;

    /** 上报周期估计的合理范围，用于过滤异常观测值。 */
    private static final long MIN_UPLINK_PERIOD_MS = 150L;
    private static final long MAX_UPLINK_PERIOD_MS = 4000L;

    /**
     * 外推余量在上报周期之外的额外裕度。
     *
     * <p>留出这段裕度，才能覆盖上报自身的抖动与网络延迟，
     * 不会在下一个上报到达前就撞到上限。
     */
    private static final long LEAD_MARGIN_MS = 400L;



    /**
     * 屏幕坐标下的矩形区域，左上角为原点、y 向下为正。
     *
     * <p>歌词一帧内实际占用的范围。用于命中检测（悬停或点击是否落在歌词上）
     * 与外部布局计算。区域包含文字本身与上下留白，
     * 因此抬起的字也不会跑到框外。
     *
     * @param x      左边界（像素）
     * @param y      上边界（像素）
     * @param width  宽度（像素），恒为正
     * @param height 高度（像素），恒为正
     */
    public record Bounds(int x, int y, int width, int height) {

        /** 空区域：没有歌词可显示时使用。 */
        public static final Bounds EMPTY = new Bounds(0, 0, 0, 0);

        /** 点是否落在区域内。右边界与下边界视为不包含，与屏幕裁剪语义一致。 */
        public boolean contains(int px, int py) {
            return width > 0 && height > 0
                    && px >= x && px < x + width
                    && py >= y && py < y + height;
        }

        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }
    }

    private final LyricFeed source;


    /** 位置外推器：把每秒一次的上报插值成帧级连续位置。 */
    private final PositionTracker positions = new PositionTracker();

    /** 逐字动画特效链；默认浮动上浮 + 强调辉光。 */
    private final LyricEffectEngine effects = LyricEffectEngine.defaults();

    /** 用户拖动产生的水平偏移（像素），以屏幕中线为原点，向右为正。 */
    private int offsetX;
    /** 用户拖动产生的垂直偏移（像素），相对默认底部位置，向下为正。 */
    private int offsetY;
    /** 拖动模式：为 true 时显示底板并允许拖拽定位。 */
    private boolean dragging;
    /** 配置界面强制预览：即使歌词被关闭也要出画，否则调样式看不见效果。 */
    private boolean previewForced;
    /** 预览绘制区；null 表示按全屏布局。 */
    private Bounds previewArea;

    /** 打开/关闭强制预览。配置界面进入时打开，退出时关闭。 */
    public void setPreviewForced(boolean value) {
        this.previewForced = value;
    }

    /** 数据源是否被用户启用。 */
    public boolean sourceEnabled() {
        return source.isEnabled();
    }

    /** 启用或禁用数据源；只影响出画，不影响连接。 */
    public void setSourceEnabled(boolean value) {
        source.setEnabled(value);
    }

    /** 数据源标识，用于持久化开关。 */
    public String sourceId() {
        return source.id();
    }
    /** 是否显示歌词；由 {@code /linklyric on|off} 控制并持久化。 */
    private boolean enabled = true;
    /** 最近一帧的屏幕包围盒；本帧没有出画时为 {@link Bounds#EMPTY}。 */
    private Bounds bounds = Bounds.EMPTY;

    /**
     * 用户可调样式。整体替换而非就地修改：
     * 渲染每帧只读一次局部引用，改配置时不会读到半成品。
     */
    private volatile LyricStyleConfig style = LyricStyleConfig.DEFAULT;

    /** 界面文本解析器，由平台层注入。 */
    private volatile TextResolver textResolver = TextResolver.IDENTITY;

    public void setStyle(LyricStyleConfig value) {
        this.style = value == null ? LyricStyleConfig.DEFAULT : value;
    }

    public LyricStyleConfig style() {
        return style;
    }

    /** 注入语言键解析器；未注入时原样返回键名。 */
    public void setTextResolver(TextResolver resolver) {
        this.textResolver = resolver == null ? TextResolver.IDENTITY : resolver;
    }

    /** 解析语言键；空键返回空串，表示该状态不出画。 */
    private String resolveText(String key) {
        return key == null || key.isEmpty() ? "" : textResolver.resolve(key);
    }

    /** 是否存在真正的下一句；否则副行取当前行的翻译。 */
    private static boolean hasNextLine(LyricLine line, LyricLine nextLine) {
        return nextLine != null && !nextLine.text().isEmpty();
    }

    /** 副行内容：优先取下一句，没有下一句时退回当前行的翻译。 */
    private String secondaryTextOf(Painter painter, LyricLine line,
                                   LyricLine nextLine, int maxWidth) {
        if (nextLine != null && !nextLine.text().isEmpty()) {
            return clip(painter, nextLine.text(), maxWidth);
        }
        if (line.hasTranslation()) {
            return clip(painter, line.translation(), maxWidth);
        }
        return "";
    }

    /**
     * 实际可画的行数。
     *
     * <p>受时间轴剩余行数限制，不能凭空补空行。
     */
    private int drawableLineCount(LyricTimeline timeline, int index) {
        int total = Math.max(1, style.lineCount());
        int drawable = 1;
        for (int i = 1; i < total; i++) {
            LyricLine upcoming = timeline.line(index + i);
            if (upcoming == null || upcoming.text().isEmpty()) {
                break;
            }
            drawable++;
        }
        return drawable;
    }

    /** 逐字动画特效链，可按名字增删。 */
    public LyricEffectEngine effects() {
        return effects;
    }

    /** 按当前样式构造绘制器；特效引擎跨帧复用，避免每帧重建。 */
    private LyricPainter textPainter(Painter painter) {
        return new LyricPainter(painter, style.weight(), style.shadow(), effects);
    }

    /** 两个矩形的交集；无重叠时返回空区域。 */
    private static Bounds intersect(Bounds a, Bounds b) {
        return LyricGeometry.intersect(a, b);
    }

    private int sungColor() {
        return style.sungColor();
    }

    private int unsungColor() {
        return style.unsungColor();
    }

    private int translationColor() {
        return style.translationColor();
    }

    /** 下一句的颜色：由未唱色按非当前行不透明度派生，保证与主体同源。 */
    private int nextLineColor() {
        return withAlpha(style.unsungColor(), Math.round(UNSUNG_ALPHA * 255f));
    }

    private long lineTransitionNanos() {
        return style.lineTransitionMs() * 1_000_000L;
    }

    /**
     * 最近一帧的歌词屏幕包围盒。
     *
     * <p>这是「渲染的副作用」而非纯查询：布局依赖字体宽度与滚动偏移，
     * 只有真正渲染过一次才知道精确范围。因此调用方应先渲染，
     * 下一帧读取；在没有出画（关闭、无歌词、间奏）时返回
     * {@link Bounds#EMPTY}，{@link Bounds#isEmpty()} 可直接判空。
     */
    public Bounds bounds() {
        return bounds;
    }

    /** 设置显示开关。 */
    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 跨帧保留的已唱宽度（像素）。这是染色连续性的来源。 */
    private float sungWidth;

    /** 设置用户偏移量；由指令与拖动共同写入。 */
    public void setOffset(int x, int y) {
        this.offsetX = x;
        this.offsetY = y;
    }

    /**
     * 把绘制限制在一个矩形区域内，并按该区域重新居中。
     *
     * <p>配置界面用它把歌词画进顶部预览框。传入 null 恢复正常全屏布局。
     *
     * <p>预览时用户偏移被忽略：偏移描述的是「相对全屏底部的位置」，
     * 在预览框里没有意义，而且偏移几十像素就会把文字顶出框外。
     * 样式（颜色、字号、宽度、过渡）照常生效，这才是预览要看的东西。
     */
    public void setPreviewArea(Bounds area) {
        this.previewArea = area;
    }

    public Bounds previewArea() {
        return previewArea;
    }

    /** 当前生效的水平中心：预览区内居中，否则全屏居中并叠加用户偏移。 */
    private int layoutCenterX(int screenWidth) {
        if (previewArea != null && !previewArea.isEmpty()) {
            return previewArea.x() + previewArea.width() / 2;
        }
        return LyricGeometry.centerX(screenWidth) + offsetX;
    }

    /** 当前生效的基线 y：预览区内垂直居中，否则贴底部并叠加用户偏移。 */
    private int layoutCenterY(int screenHeight) {
        if (previewArea != null && !previewArea.isEmpty()) {
            return previewArea.y() + previewArea.height() / 2;
        }
        return LyricGeometry.centerY(screenHeight) + LyricGeometry.LINE_HEIGHT + offsetY;
    }

    /**
     * 按对齐方式把行宽换算成左边界。
     *
     * <p>居中沿用原有的中心锚点；左/右对齐则改成以可用区间的两端为锚。
     * 用端点而不是「中心减半宽」重算，是为了让左右对齐在滚动时也稳定——
     * 滚动会改变可见宽度，若每帧以中心反推，边界会跟着抖。
     */
    private int layoutLeft(Align align, int centerX, int maxWidth, int lineWidth) {
        return LyricGeometry.left(align, centerX, maxWidth, lineWidth);
    }

    /** 单行可用宽度：预览区内按框宽收窄，否则按屏幕比例。 */
    private int layoutMaxWidth(int screenWidth) {
        if (previewArea != null && !previewArea.isEmpty()) {
            return Math.max(40, previewArea.width() - 8);
        }
        return Math.max(40, (int) (LyricGeometry.maxWidth(screenWidth)
                * style.maxWidthRatio() * 2f));
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    /**
     * 进入或退出拖动模式。
     *
     * <p>拖动时显示底板，让用户看清可拖区域与当前位置；
     * 退出后底板消失，歌词回到「不打扰」的常态。
     */
    public void setDragging(boolean value) {
        this.dragging = value;
    }

    public boolean isDragging() {
        return dragging;
    }

    /**
     * 最近一帧的已唱宽度，单位像素。
     *
     * <p>供测试断言使用。有了它，测试不必再从绘制指令里反推
     * 「已唱到哪儿」——那种反推依赖替身对裁剪与 alpha 的理解，
     * 一旦实现细节变化就会误报。
     */
    public float currentSungWidth() {
        return sungWidth;
    }

    /** 上一帧对应的时间轴与行索引，用于识别换行并重置动画。 */
    private long lastSignature = Long.MIN_VALUE;
    private int lastLineIndex = -1;

    private long lastFrameNanos;


    /** 行切换动画状态：淡出进度、上移位移、旧句快照。 */
    private final LineTransition transition = new LineTransition();

    /** 上一帧主行的已唱宽度。 */
    private float lastSungWidth;

    /** 上一帧主行的行宽，用于还原旧行的分界比例。 */
    private int lastLineWidth;

    private LyricTimeline lastTimeline = LyricTimeline.empty();

    public FloatingLyricRenderer(LyricFeed source) {
        this.source = source;
    }

    /**
     * 绘制当前歌词行。
     *
     * @return 是否实际绘制了内容
     */
    public boolean render(Painter painter) {
        // 每帧先清空，再由真正出画的那条路径写入。
        //
        // 逐分支清零容易漏：只要有一个 return 忘了清，包围盒就会残留上一帧的区域，
        // 悬停命中一块已经不存在的歌词。开头统一清空可以杜绝这类遗漏。
        bounds = Bounds.EMPTY;

        if (painter == null || !source.isAlive()) {
            return false;
        }
        // 关闭状态下完全不出画。
        //
        // 两种例外都必须出画，否则用户看到的是一片空白：
        // 拖动模式（正在靠它调位置）与配置预览（正在靠它调样式）。
        if (!enabled && !dragging && !previewForced) {
            return false;
        }

        LyricTimeline timeline = timelineForFrame();
        if (timeline.isEmpty()) {
            return false;
        }

        // 每帧开头统一更新帧间隔。
        // 必须在所有分支之前调用：若只在「同行的平滑推进」分支里调用，
        // 换行那一帧不会记录时间戳，下一帧的间隔就会被算成 0，
        // 表现为每次换行后高亮要僵住一帧才开始移动。
        long frameDelta = frameDeltaNanos();

        // 每帧只取一次快照，避免两次调用之间数据源更新导致位置与播放态不一致
        PlayerSnapshot snapshot = source.pollSnapshot();
        syncClock(snapshot.positionMs(), snapshot.playing());
        // 时间偏移只作用于「查时间轴」这一步，不动外推时钟。
        //
        // 若直接偏移 clock 的输入，外推锚点会跟着漂移，暂停/恢复后累积误差；
        // 在这里加一次常量相当于整体平移时间轴，语义干净且可逆。
        long positionMs = boundedPosition(snapshot.positionMs()) + style.offsetMs();

        int index = timeline.indexAt(positionMs);
        if (index < 0) {
            lastLineIndex = -1;
            return false;
        }
        LyricLine line = timeline.line(index);
        if (line == null || line.text().isEmpty()) {
            return false;
        }

        // 长间奏里没有任何行可显示。若不处理，最后一句会一直停在屏幕上，
        // 看起来像是卡住了。这里改显示一段静态提示，让状态可被理解。
        if (timeline.isInterludeAt(positionMs, INTERLUDE_MIN_GAP_MS)) {
            return renderInterlude(painter);
        }

        int screenWidth = painter.screenWidth();
        int screenHeight = painter.screenHeight();
        int maxWidth = layoutMaxWidth(screenWidth);

        // 位置由用户偏移量决定，默认居中偏下。
        //
        // 偏移以屏幕中心为原点：x 向右为正、y 向下为正。
        // 存成相对量而不是绝对坐标，换分辨率时歌词不会跑到屏幕外。
        // 配置界面的预览区传入后，改为在框内居中。
        int centerX = layoutCenterX(screenWidth);

        // 双行布局：主行（当前句）+ 副行（下一句或翻译）。
        //
        // 主行是可逐字染色的那一行；副行只做静态预览，
        // 因为下一句的逐字时间还没到，提前染色没有意义。
        LyricLine nextLine = timeline.line(index + 1);

        // 行切换动画进度：换行后用 220ms 把上一行推上去、把新行推到主位。
        // 取自 RichLyricLineView.animateNextLinePromotion：
        // 旧主行 alpha→0 且 translationY 上移 0.65 行高；
        // 新行从副行位置移到主行位置，并放大到主行字号。
        transition.update(index, frameDelta, style.lineTransitionMs());

        String fullText = line.text();
        int fullWidth = painter.textWidth(fullText);

        // 超出可用宽度时改为滚动，而不是截断加省略号。
        //
        // 截断会丢掉副歌后半句，而歌词恰恰是「看下一句」的需求最强；
        // 滚动能让整句都读到，且不需要用户操作。
        // 只有在无法裁剪的平台上才退回截断——那种情况滚动会画到面板外。
        // 预览框里不滚动：滚动会使文字横向跑出框外，
        // 而预览要看的是颜色与排版，不是滚动行为。超长时截断即可。
        boolean inPreview = previewArea != null && !previewArea.isEmpty();
        boolean canScroll = painter.supportsScissor() && !inPreview;
        boolean scrolling = canScroll && fullWidth > maxWidth;

        // 滚动时不截断文本：坐标必须和完整文本保持一致，
        // 否则 sungWidth 与实际绘制位置对不上，高亮会错位。
        String visible = scrolling ? fullText : clip(painter, fullText, maxWidth);

        String secondaryText = secondaryTextOf(painter, line, nextLine, maxWidth);

        int primaryWidth = scrolling ? fullWidth : painter.textWidth(visible);
        // 副行按 Salt 的 SubTextFontSizeScale 缩小，宽度必须同样缩放，
        // 否则居中会偏，底板预留也会算多。
        int secondaryWidth = secondaryText.isEmpty()
                ? 0
                : painter.textWidthScaled(secondaryText, SUB_TEXT_FONT_SCALE);

        int centerTextY = layoutCenterY(screenHeight);
        int padX = 6;
        // 底板上下留白必须容纳最大抬升，否则抬起字的顶部会顶出底板、
        // 在画面上变成「文字浮在框外」。
        int padY = Math.max(3, LIFT_HEADROOM + 1);

        // 多行堆叠：把当前行与后续若干行当作一整块，块心固定在 centerTextY。
        boolean hasSecondary = !secondaryText.isEmpty();
        int drawableLines = drawableLineCount(timeline, index);
        LineLayout.Block block = LineLayout.compute(centerTextY, drawableLines, 0,
                LyricGeometry.LINE_HEIGHT, LyricGeometry.LINE_GAP, SUB_TEXT_FONT_SCALE);

        // 行切换：旧主行在其原位淡出，其余行同时连续上移一行补位。
        // 两者并行，块内不会出现「整体偏移一行」的中间态。
        long durationMs = style.lineTransitionMs();
        int blockShift = transition.blockShift(durationMs);
        int oldLineAlpha = Math.round(255f * transition.oldAlpha(durationMs));
        int mainBaselineY = block.baseline() + blockShift;

        // 字号缩放：整块（主行 + 副行）一起放大缩小。
        //
        // 之前 lineScale 只参与羽化宽度计算，从未真正缩放过文字，
        // 滑条拖动时画面毫无变化。这里在主行与副行绘制前后夹一层逆变换，
        // 使所有坐标仍按原字号计算，缩放只影响最终成像。
        float scale = this.style.lineScale();
        boolean scaling = Math.abs(scale - 1f) > 0.001f;
        if (scaling) {
            var pose = painter.pose();
            // 以**块心**为原点缩放，位置才不会随字号漂移。
            //
            // 这里不能用主行基线：切换动画期间它每帧都在移动，
            // 缩放中心跟着移动会让整块在放大缩小的同时微微游走。
            pose.pushPose();
            pose.translate(centerX, centerTextY);
            pose.scale(scale, scale);
            pose.translate(-centerX, -centerTextY);
        }

        int viewWidth = scrolling ? maxWidth : primaryWidth;
        // 视口跟随对齐方式：左对齐时贴左端、右对齐时贴右端，
        // 否则滚动起点会偏，正在唱的字会跑出可视区。
        int viewportLeft = layoutLeft(this.style.align(), centerX, maxWidth, viewWidth);
        int viewportRight = viewportLeft + viewWidth;

        // 换行或时间轴变更时重置动画，避免沿用上一行的进度
        if (index != lastLineIndex) {
            sungWidth = targetWidthAt(painter, line, visible, positionMs);
            lastLineIndex = index;
        } else {
            float target = targetWidthAt(painter, line, visible, positionMs);
            sungWidth = advance(sungWidth, target, frameDelta);
        }
        float clampedSung = Math.max(0f, Math.min(sungWidth, primaryWidth));

        // 滚动偏移由已唱宽度直接决定，不额外维护动画状态：
        // sungWidth 本身已经平滑过，偏移自然跟着平滑。
        int scroll = scrolling
                ? Math.round(scrollOffsetFor(clampedSung, viewWidth, primaryWidth))
                : 0;
        int textX = viewportLeft + scroll;


        // 歌词整体的屏幕包围盒。
        //
        // 无论是否拖动都要算：命中检测（悬停、点击）依赖它，
        // 而悬停恰恰发生在「看不见底板」的平常状态。
        // 高度覆盖主行与副行，横向覆盖实际绘制宽度（滚动时是整个视口），
        // 这样点到边上的字也算命中。
        // 包围盒按**块的稳定位置**算，不含切换偏移。
        // 否则换行那几帧区域会跟着位移，悬停命中框抖动。
        int stableMainY = block.baseline();
        int blockTop = stableMainY - padY;
        int blockBottom = stableMainY + block.height() + padY;
        Bounds own = new Bounds(viewportLeft - padX, blockTop,
                viewWidth + padX * 2, blockBottom - blockTop);
        // 预览时把包围盒裁进框内：命中检测与描边都不该超出预览区。
        bounds = inPreview ? intersect(own, previewArea) : own;

        // 底板默认关闭：歌词直接浮在画面上。
        //
        // 只有拖动时才显示，作为可拖区域的视觉提示；
        // 平时留一块半透明底会压住画面，也违背「不打扰」的默认。
        if (dragging) {
            painter.fillRect(viewportLeft - padX, blockTop,
                    viewWidth + padX * 2, blockBottom - blockTop, COLOR_BACKDROP);
            // 描一圈边，拖动态下手感更明确，也标出可拖范围。
            painter.fillRect(viewportLeft - padX, blockTop,
                    viewWidth + padX * 2, 1, COLOR_DRAG_BORDER);
            painter.fillRect(viewportLeft - padX, blockBottom - 1,
                    viewWidth + padX * 2, 1, COLOR_DRAG_BORDER);
            painter.fillRect(viewportLeft - padX, blockTop, 1,
                    blockBottom - blockTop, COLOR_DRAG_BORDER);
            painter.fillRect(viewportLeft + padX + viewWidth - 1, blockTop, 1,
                    blockBottom - blockTop, COLOR_DRAG_BORDER);
        }

        // 逐字偏移表：底色层与高亮层必须共用同一份。
        //
        // 若只让高亮层带偏移，唱到的字会从白色原字上方落下来，
        // 看起来像两个独立的字在打架，而不是同一个字变色并上浮。
        float[] lifts = clampedSung >= 0.5f && canScroll
                ? LyricProgress.lifts(visible.length(),
                        LyricProgress.fontSizeOf(primaryWidth, visible.length()),
                        LyricProgress.charProgress(line, visible, positionMs))
                : null;

        // 整体不透明度作用于主行、后续行与间奏，统一在这里相乘一次。
        float opacity = this.style.opacity();

        // 旧行画在它换行前最后一帧所在的位置，原地淡出。
        // 位置在换行瞬间由 LineTransition 冻结，不随当前块几何变化。
        int oldBaselineY = transition.oldBaselineY();
        if (transition.drawsOld(style.lineTransitionMs())) {
            String old = clip(painter, transition.oldText(), maxWidth);
            int oldWidth = painter.textWidth(old);
            int oldX = layoutLeft(this.style.align(), centerX, maxWidth, oldWidth);
            // 用旧行最后呈现的配色淡出：已唱段满色、未唱段压淡，
            // 分界沿用换行瞬间的已唱宽度。
            float oldOpacity = (oldLineAlpha / 255f) * opacity;
            int oldSung = withAlpha(sungColor(), Math.round(255f * oldOpacity));
            int oldUnsung = withAlpha(unsungColor(),
                    Math.round(255f * oldOpacity * UNSUNG_ALPHA));
            int boundary = Math.max(0,
                    Math.min((int) transition.oldSungWidth(), oldWidth));

            if (boundary > 0 && boundary < oldWidth && !painter.supportsScissor()) {
                // 不能裁剪时无法分区上色，退回已唱色单色。
                painter.drawText(old, oldX, oldBaselineY, oldSung);
            } else {
                painter.drawText(old, oldX, oldBaselineY, oldUnsung);
                if (boundary > 0) {
                    painter.enableScissor(oldX, oldBaselineY - LIFT_HEADROOM,
                            boundary, painter.lineHeight() + LIFT_HEADROOM
                                    + SHADOW_HEADROOM);
                    painter.drawText(old, oldX, oldBaselineY, oldSung);
                    painter.disableScissor();
                }
            }
        }

        // 主行越出块顶就不画：这是上移到底后旧行彻底让出位置的落点。
        boolean mainVisible = mainBaselineY >= blockTop - 1;
        if (mainVisible) {
            if (canScroll) {
                textPainter(painter).karaoke(visible, textX, mainBaselineY, clampedSung,
                        viewportLeft, Math.min(viewportRight, viewportLeft + viewWidth),
                        lifts, opacity, sungColor(), unsungColor(), line, positionMs);
            } else {
                drawByWholeUnit(painter, line, visible, textX, mainBaselineY,
                        clampedSung, opacity);
            }
        }

        // 后续各行：静态预览，不做逐字染色，字号统一收一档。
        // 它们不参与淡入，否则同一句在过渡期与稳定期的颜色不一致。
        if (hasSecondary) {
            for (int i = 1; i < drawableLines; i++) {
                boolean firstExtra = i == 1;
                // 首条副行可能来自翻译兜底，其余行一律取后续歌词。
                LyricLine upcoming = firstExtra && !hasNextLine(line, nextLine)
                        ? null : timeline.line(index + i);
                String text = firstExtra
                        ? secondaryText
                        : (upcoming == null ? "" : clip(painter, upcoming.text(), maxWidth));
                if (text.isEmpty()) {
                    continue;
                }
                int color = firstExtra && !hasNextLine(line, nextLine)
                        ? translationColor() : nextLineColor();
                // 左边界按**缩放后宽度**算，因为 drawTextScaled 以 (x, y)
                // 为原点缩放，缩放后文字实际占据 [x, x + width*scale]。
                //
                // 若用未缩放宽度居中，居中算出来的左边会偏左，
                // 而副行实际更窄，于是它的中心比主行偏左
                // (width - width*0.8)/2 像素。行数越多越明显，
                // 看起来就是「多行歌词不居中」。
                int width = painter.textWidthScaled(text, SUB_TEXT_FONT_SCALE);
                int x = layoutLeft(this.style.align(), centerX, maxWidth, width);
                int y = LineLayout.lineTop(block, i, 0, LyricGeometry.LINE_HEIGHT,
                        LyricGeometry.LINE_GAP, SUB_TEXT_FONT_SCALE) + blockShift;
                // 副行同样应用字重与阴影设置。
                textPainter(painter).textScaled(text, x, y,
                        SUB_TEXT_FONT_SCALE, color);
            }
        }

        if (scaling) {
            painter.pose().popPose();
        }
        // 记录本帧主行的实际绘制位置与已唱宽度，供下次换行时冻结旧句。
        transition.recordMain(fullText, mainBaselineY, clampedSung);
        lastLineWidth = primaryWidth;
        return true;
    }

    /**
     * 滚动偏移：演唱点越过中线后才推动文本。
     *
     * <p>取自 HyperLyric 的 {@code ScrollStepper}。规则是
     * 「高亮超过视口中线多少，就向左推多少」，并夹在
     * {@code [-(行宽-视口宽), 0]} 之间，因此开头不会提前滚、结尾不会滚过头。
     *
     * <p>这样演唱中的字始终停在中线附近，读起来最省力；
     * 若改成按时间匀速滚，速度会与演唱速度脱节，反而更难跟。
     */
    private static float scrollOffsetFor(float sungWidth, int viewWidth, int lineWidth) {
        return LyricGeometry.scrollOffset(sungWidth, viewWidth, lineWidth);
    }

    /**
     * 用裁剪绘制已唱层，并在前端叠加羽化带。
     *
     * <p>硬切边在字体边缘会显得生硬；分两级透明度过渡可以显著柔化推进端，
     * 而 Minecraft 的文本绘制本身支持 alpha，不需要着色器。
     */
    /**
     * 绘制间奏提示。
     *
     * <p>刻意做得比正常歌词更淡：它是状态说明，不该抢注意力。
     * 同时重置换行状态，让间奏结束后的首帧直接对齐到新行，
     * 而不是从上一行的进度平滑过去。
     */
    private boolean renderInterlude(Painter painter) {
        lastLineIndex = -1;
        sungWidth = 0f;

        // 间奏提示可配：空白、···、间奏文本。
        // 空白档直接不出画，但仍要重置行索引，否则间奏结束后
        // 首帧会沿用上一行的进度。
        String text = resolveText(this.style.interlude().text);
        if (text.isEmpty()) {
            return false;
        }

        int screenWidth = painter.screenWidth();
        int screenHeight = painter.screenHeight();
        int width = painter.textWidth(text);
        // 与正常歌词共用同一份偏移，切换歌词与间奏时不会跳动。
        int x = layoutCenterX(screenWidth) - width / 2;
        int y = layoutCenterY(screenHeight) - LINE_HEIGHT;

        // 间奏提示也能被悬停命中：它同样是可见歌词的一部分，
        // 若包围盒只覆盖正常行，鼠标停在「间奏」上就拖不动。
        bounds = new Bounds(x - 6, y - 3, width + 12, LINE_HEIGHT + 6);

        if (dragging) {
            painter.fillRect(x - 6, y - 3, width + 12, LINE_HEIGHT + 6, COLOR_BACKDROP);
        }
        painter.drawText(text, x, y,
                withAlpha(COLOR_INTERLUDE,
                        Math.round(((COLOR_INTERLUDE >>> 24) & 0xFF)
                                * this.style.opacity())),
                style.shadow());
        return true;
    }

    /** 由行宽与字数估出字号；中文字宽约等于字号，故比值为良好近似。 */
    private static float fontSizeOf(int lineWidth, int charCount) {
        return LyricProgress.fontSizeOf(lineWidth, charCount);
    }

    /**
     * 计算每个字的亚像素抬升量，按各自的染色进度线性取值。
     *
     * <p>返回 {@code null} 表示不值得逐字绘制（单字、行太长或字号退化），
     * 调用方应退回整段绘制。
     */
    static float[] computeLifts(int charCount, float fontSizePx, float[] charProgress) {
        return LyricProgress.lifts(charCount, fontSizePx, charProgress);
    }



    /** 不支持裁剪时的降级：按整单元覆盖，至少保证逐字推进可见。 */
    private void drawByWholeUnit(Painter painter, LyricLine line, String visible,
                                 int textX, int textY, float sungWidth, float opacity) {
        // 平台没有裁剪能力时垫不上蒙版，整行必须先以歌词色画出来，
        // 否则未唱的字会完全消失。
        int textColor = withAlpha(sungColor(), Math.round(255f * opacity));
        painter.drawText(visible, textX, textY, textColor, style.shadow());

        int sungColor = textColor;
        List<LyricWord> words = line.words();
        if (words.size() <= 1) {
            if (sungWidth >= painter.textWidth(visible) - 0.5f) {
                painter.drawText(visible, textX, textY, sungColor, style.shadow());
            }
            return;
        }
        int cursor = 0;
        int consumed = 0;
        for (LyricWord word : words) {
            if (consumed >= visible.length()) {
                break;
            }
            String wordText = word.text();
            if (wordText.isEmpty()) {
                continue;
            }
            int available = Math.min(wordText.length(), visible.length() - consumed);
            String part = wordText.substring(0, available);
            int partWidth = painter.textWidth(part);
            if (cursor + partWidth > sungWidth + 0.5f) {
                break;
            }
            painter.drawText(part, textX + cursor, textY, sungColor, style.shadow());
            cursor += partWidth;
            consumed += available;
        }
    }

    /** 单个演唱单元的进度曲线，与行切换共用同一条缓动。 */
    private static float sustainCurve(float progress) {
        return LyricEasing.ease(progress);
    }

    /**
     * 计算某位置应当达到的已唱宽度（像素，浮点）。
     *
     * <p>按单元顺序累加，正在演唱的单元按其自身进度取部分宽度，
     * 因此高亮可以停在字符内部，而不是整字跳变。
     */
    private float targetWidthAt(Painter painter, LyricLine line, String visible, long positionMs) {
        List<LyricWord> words = line.words();
        int lineWidth = painter.textWidth(visible);

        if (words.size() <= 1) {
            // 行级歌词：宿主只给了整行时间，没有逐字时间。
            //
            // 这类数据不能按「到点全亮」处理。多数播放器对行级歌词
            // 一首歌每秒才更新一次位置，二值跳变会让高亮看起来是一秒一闪。
            // 按行内时间比例做线性推进，观感接近卡拉OK，
            // 且不会伪造出原数据并不存在的字级时间。
            long duration = line.endMs() - line.startMs();
            if (duration <= 0L) {
                return positionMs >= line.endMs() ? lineWidth : 0f;
            }
            float ratio = (positionMs - line.startMs()) / (float) duration;
            if (ratio <= 0f) {
                return 0f;
            }
            if (ratio >= 1f) {
                return lineWidth;
            }
            return lineWidth * ratio;
        }

        float width = 0f;
        int consumed = 0;
        for (LyricWord word : words) {
            if (consumed >= visible.length()) {
                break;
            }
            String wordText = word.text();
            if (wordText.isEmpty()) {
                continue;
            }
            // 截断时只累计实际可见部分，避免高亮越过裁切边界
            int available = Math.min(wordText.length(), visible.length() - consumed);
            String part = wordText.substring(0, available);
            float partWidth = painter.textWidth(part);

            if (positionMs >= word.endMs()) {
                width += partWidth;
            } else if (positionMs > word.startMs()) {
                width += partWidth * sustainCurve(word.progressAt(positionMs));
            }
            consumed += available;
        }
        return Math.min(width, lineWidth);
    }

    /**
     * 朝目标宽度平滑推进。
     *
     * <p>用指数逼近而非线性插值：帧间隔抖动时结果依然稳定。
     * 目标显著小于当前值时判定为回退（seek 或重播），直接跳变。
     */
    private static float advance(float current, float target, long deltaNanos) {
        if (target < current - BACKWARD_SNAP_PX) {
            return target;
        }
        if (deltaNanos <= 0L) {
            return current;
        }
        // 指数逼近永远到不了终点：差值按比例缩小后会在亚像素处停滞。
        // 行尾因此永久残留一条几像素的暗边，看起来像「永远差一个字没唱完」。
        // 差得足够小就直接吸附，视觉上无跳变。
        if (Math.abs(target - current) < SNAP_EPSILON_PX) {
            return target;
        }
        double dt = deltaNanos / 1_000_000_000.0;
        // 单帧上限，防止长时间卡顿后一步跳到底
        dt = Math.min(dt, 0.25);
        double factor = 1.0 - Math.exp(-dt / SMOOTH_TAU_SECONDS);
        return (float) (current + (target - current) * factor);
    }

    private long frameDeltaNanos() {
        long now = System.nanoTime();
        long delta = lastFrameNanos == 0L ? 0L : now - lastFrameNanos;
        lastFrameNanos = now;
        return Math.max(0L, delta);
    }

    /**
     * 取该帧使用的播放位置。
     *
     * <p>三条约束缺一不可：
     * 上报值带一秒延迟，必须用时钟外推才能帧级连续；外推要有上限，
     * 否则轮询滞后会让位置无界前冲；播放中又不能倒退，否则高亮闪烁。
     * 上限随实测上报周期自适应，保证整个上报周期内都有足够的推进空间。
     */
    private long boundedPosition(long reportedMs) {
        return positions.position(reportedMs, System.nanoTime());
    }

    private void syncClock(long positionMs, boolean playing) {
        positions.calibrate(positionMs, playing, System.nanoTime());
    }

    /**
     * 每帧取时间轴。
     *
     * <p>数据源短暂清空时保留上一份，避免插件重载造成闪断；
     * 是否整体隐藏由入口处的 {@code isAlive()} 判定。
     */
    private LyricTimeline timelineForFrame() {
        long signature = source.lyricSignature();
        if (signature != lastSignature) {
            LyricTimeline fresh = source.lyric();
            if (!fresh.isEmpty()) {
                // 只有时间轴内容真的不同才重置换行状态。
                // 若不加判断，任何一次刷新都会让下一帧跳过平滑推进、
                // 直接把已唱宽度赋成目标值，染色就会变成整字跳变。
                boolean changed = fresh.size() != lastTimeline.size()
                        || (lastTimeline.size() > 0 && fresh.line(0) != null
                        && lastTimeline.line(0) != null
                        && !fresh.line(0).text().equals(lastTimeline.line(0).text()));
                lastTimeline = fresh;
                if (changed) {
                    lastLineIndex = -1;
                }
            }
            lastSignature = signature;
        }
        return lastTimeline;
    }

    /** 按像素宽度截断并加省略号。逐字符累加，中英混排下最稳。 */
    private String clip(Painter painter, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (painter.textWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = painter.textWidth(ellipsis);
        StringBuilder sb = new StringBuilder(text.length());
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int chWidth = painter.textWidth(ch);
            if (width + chWidth + ellipsisWidth > maxWidth) {
                break;
            }
            sb.append(ch);
            width += chWidth;
        }
        return sb.isEmpty() ? ellipsis : sb + ellipsis;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

}
