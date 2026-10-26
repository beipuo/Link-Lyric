package com.linklyric.common.hud;

import com.linklyric.common.hud.LyricPlacement.PlacedLine;
import com.linklyric.common.hud.effect.LyricEffectEngine;
import com.linklyric.common.hud.effect.LyricUnitEffect;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.LyricStyleConfig;
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

    /**
     * 与目标的差值小于此值即吸附，避免指数逼近在行尾永久残留暗边。
     *
     * <p>必须远小于单字宽度。9px 字号下 1.5px 等于字宽六分之一，
     * 而每帧的目标推进只有约 0.5px，于是**每一帧都被吸附**，
     * 平滑逻辑实际从未执行，呈现在画面上就是逐字一跳一跳。
     * 取 0.05px：既让吸附生效于终点，又不吞掉任何一帧的推进。
     */
    private static final float SNAP_EPSILON_PX = 0.05f;

    /** 未唱文字的透明度；与逐字路径共用同一常量，见 {@link LyricGeometry#UNSUNG_ALPHA}。 */
    private static final float UNSUNG_ALPHA = LyricGeometry.UNSUNG_ALPHA;

    /**
     * 副行字号相对主行的比例。
     *
     * <p>取自 Salt Player 的 {@code LyricsComponentDefaults.SubTextFontSizeScale = 0.8f}
     * 与 {@code AppConfig.playerLyricsTranslationFontSizeScale} 的默认值。
     * 翻译是次要信息，缩到 0.8 倍才不会与原文抢注意力。
     */
    private static final float SUB_TEXT_FONT_SCALE = 0.8f;

    /** 抬升预留空间；与裁剪框共用同一常量，见 {@link LyricGeometry#LIFT_HEADROOM}。 */
    private static final int LIFT_HEADROOM = LyricGeometry.LIFT_HEADROOM;

    /** 阴影与字重余量；与裁剪框共用同一常量，见 {@link LyricGeometry#SHADOW_HEADROOM}。 */
    private static final int SHADOW_HEADROOM = LyricGeometry.SHADOW_HEADROOM;

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

    /**
     * 放置策略的显式覆盖；null 表示按样式自动选择。
     *
     * <p>正常路径下这里是 null，策略由 {@link LayoutMode} 按
     * {@code style.layout()} 给出。三个实现都无状态，
     * 因此「按样式取实例」不产生任何分配。
     *
     * <p>渲染器自己不保留任何布局算式——算式在策略里，
     * 这里只把结果交给绘制。这样换排布（弧形、环形）不必碰绘制链路，
     * 而绘制链路的正确性恰好是最难用测试钉住的部分。
     *
     * <p>声明成 volatile：界面线程可以设置覆盖（测试与诊断用），
     * 渲染在客户端线程读。策略必须无状态，因此换引用是原子的，
     * 不存在「读到一半旧一半新」的组合。
     */
    private volatile PlacementStrategy placementOverride;


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

    /**
     * 与当前行同时演唱的另一行。
     *
     * <p>只取一行：HUD 的副行槽位有限，而两行以上的重叠在实际歌词里极罕见。
     * 取开始时间最晚的那一行——它是最新进来的声音，读者最需要看到。
     */
    private static LyricLine concurrentLineAt(LyricTimeline timeline, LyricLine current,
                                              long positionMs) {
        java.util.List<LyricLine> active = timeline.activeLinesAt(positionMs);
        for (int i = active.size() - 1; i >= 0; i--) {
            LyricLine candidate = active.get(i);
            if (candidate != current && !candidate.text().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 绘制折行后的主行各段。
     *
     * <h2>为什么每段都走完整的卡拉 OK 路径</h2>
     *
     * <p>折行只是把一句排成几行，染色、上浮、渐变、强调这些**都不该消失**。
     * 若把第 2 段起当作普通副行用 {@code textScaled} 画，
     * 那段就变成静态灰字：唱到它时不变色、不上浮，
     * 观感是「一句话的后半段是别人的歌词」。
     *
     * <h2>前沿换算</h2>
     *
     * <p>全局前沿 {@code globalSung} 是「唱到整句的第几像素」，跨段连续。
     * 每段只取自己那一截：{@code clamp(globalSung - startPx, 0, width)}。
     * 这比「每段各自维护一个平滑量」稳得多——后者在换段那帧会从 0 重爬，
     * 表现为唱到行尾倒退重来。理由写在 {@link LyricWrap.Segment#localSung}。
     *
     * <h2>字序对齐</h2>
     *
     * <p>逐字上浮与强调都按「字在整句里的位置」查表。段内的字从
     * {@code segment.start()} 开始，因此抬升表与词映射都要整体后移这个偏移；
     * 否则第二段会拿第一段的字序取值，看起来是第二段在演第一段的动画。
     */
    private void drawWrappedMain(Painter painter, LyricPlacement placed,
                                 java.util.List<LyricWrap.Segment> segments, String fullText,
                                 float globalSung, float opacity, boolean canScissor,
                                 int clipRight,
                                 LyricLine line, long positionMs) {
        // 折行时各段都不滚动，落笔点即视口左界；同一句各段水平居中对齐，
        // 因此每段各自按自己的宽度算左边，短段不会贴着最长段左端。
        for (int i = 0; i < segments.size(); i++) {
            LyricWrap.Segment segment = segments.get(i);
            PlacedLine placementForSegment = placed.line(i);
            if (placementForSegment == null) {
                continue;
            }
            float segmentY = placementForSegment.y();
            // 与不折行同样的判据：越出块顶的那段不再画。判据留在浮点上，
            // 理由同主行——先取整会让越界半像素的那一帧仍然出画。
            if (segmentY < placed.blockTop() - 1) {
                continue;
            }
            int y = Math.round(segmentY);
            float segmentX = placementForSegment.x();
            int x = Math.round(segmentX);
            float localSung = segment.localSung(globalSung);

            // 朝向同样逐段生效：弧形排布下同一句的几段沿切线分开摆，
            // 若只转主行不转后续段，一句话会变成「第一段斜、后面几段正」
            // 那样明显断裂的形态。
            boolean oriented = placementForSegment.hasOrientation();
            if (oriented) {
                pushLineRotation(painter,
                        segmentX + segment.width() * 0.5f,
                        segmentY + LyricGeometry.LINE_HEIGHT * 0.5f,
                        placementForSegment.orientationDegrees());
            }

            if (!canScissor) {
                // 平台不能裁剪：退回整单元覆盖。段内仍按局部前沿推进，
                // 只是无法把已唱与未唱叠在同一格字形上。
                drawByWholeUnit(painter, line, segment.text(), x, y, localSung, opacity);
                if (oriented) {
                    painter.pose().popPose();
                }
                continue;
            }

            float[] lifts = localSung >= 0.5f
                    ? buildSegmentLifts(painter, segment, fullText, line, positionMs, localSung)
                    : null;
            // 裁剪右界取本段自己的末尾与外部视口右界的较小值。
            // 用段宽而非最宽段宽：否则短段右侧会漏出一片视口空白，
            // 逐字的强调辉光会在那里散开，看起来像段外有字。
            int segmentRight = Math.min(clipRight, Math.round(segmentX + segment.width()));
            textPainter(painter).karaoke(segment.text(), x, y, localSung,
                    x, segmentRight, lifts, opacity, sungColor(), unsungColor(), line, positionMs);
            if (oriented) {
                painter.pose().popPose();
            }
        }
    }

    /**
     * 把一行绕自身中心转到 {@code degrees}。
     *
     * <h2>为什么用「移中心 → 转 → 移回」三步</h2>
     *
     * <p>{@link Pose2D#rotate} 只绕<b>当前原点</b>转，没有中心点参数。
     * 直接调用会把整行绕屏幕左上角甩出去，表现为「角度一变歌词就游走」，
     * 且不抛任何异常，很难归因。写法与 {@code LyricCanvas.drawTextScaled}
     * 处理 {@code lineScale} 时完全一致。
     *
     * <h2>为什么中心由调用方给，而不是这里从落笔点推</h2>
     *
     * <p>绘制原语收的是落笔点（左上角），不是中心。但「这一行实际占多宽」
     * 在不同路径上不是同一个量：主行滚动时占的是整行宽度，
     * 副行占的是缩放后的宽度，折行段占的是该段自己的宽度。
     * 若在这里用「落笔点 + 某个高度」反过来推，就得把三种宽度规则搬进来，
     * 而三条规则已经分别住在各自的放置分支里。
     * 让调用方把它手里已有的那个宽度除以二，是最短的路。
     *
     * @param painter  绘制器，提供 pose 栈
     * @param centerX  该行中心横坐标
     * @param centerY  该行中心纵坐标
     * @param degrees  朝向角，顺时针为正
     */
    private void pushLineRotation(Painter painter,
                                  float centerX, float centerY, float degrees) {
        var pose = painter.pose();
        pose.pushPose();
        pose.translate(centerX, centerY);
        pose.rotate(degrees);
        pose.translate(-centerX, -centerY);
    }

    /**
     * 折行某一段的逐字抬升表。
     *
     * <p>与 {@link #buildLifts} 的差别只在坐标基准：那里第 0 个字就是行首，
     * 这里第 0 个字是整句的第 {@code segment.start()} 个字。两者必须用
     * 同一个全局前沿来定相位，否则同一句在两段上的抬升会在交界处错位半拍。
     */
    private float[] buildSegmentLifts(Painter painter, LyricWrap.Segment segment,
                                      String fullText, LyricLine line, long positionMs,
                                      float localSung) {
        int[] starts = LyricProgress.charStarts(painter, segment.text());
        int[] widths = new int[starts.length];
        for (int i = 0; i < starts.length; i++) {
            int next = i + 1 < starts.length ? starts[i + 1] : segment.width();
            widths[i] = Math.max(1, next - starts[i]);
        }
        float fontSize = LyricProgress.referenceSize(widths);
        if (fontSize <= 0f) {
            fontSize = LyricProgress.referenceSize(segment.width(), segment.text().length());
        }
        if (style.liftCurve() == com.linklyric.common.style.LyricStyleConfig.LiftCurve.LINEAR) {
            // 线性曲线按每个字自身的染色进度取值，所以切出本段那一截即可。
            float[] whole = LyricProgress.charProgress(line, fullText, positionMs);
            float[] slice = new float[segment.text().length()];
            for (int i = 0; i < slice.length; i++) {
                int at = segment.start() + i;
                slice[i] = at < whole.length ? whole[at] : 0f;
            }
            return LyricProgress.lifts(slice.length, fontSize, slice);
        }
        return LyricProgress.cosineLifts(starts, widths, localSung, fontSize);
    }

    /**
     * 副行内容：正在同时演唱的另一句 &gt; 下一句 &gt; 当前行的翻译。

     *
     * <p>时间重叠优先，因为那是**此刻正在唱**的词：主唱一句未落、伴唱已经进来时，
     * 只按开始时间取一行会让另一句连词带译文一起消失。
     * 若没有重叠，再按原规则取下一句做预览。
     */
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
     * 本帧主行折成几段；不折行时返回 {@code null}。
     *
     * <h2>折行与滚动互斥</h2>
     *
     * <p>两者都为了「读到超出一行的字」，但手段相反：折行换行加高，
     * 滚动横推。同时启用时一行既被切成几段、每段又各自横推，
     * 观感是文字在两处同时在动，且段内横推会把已唱前沿推出段外。
     * 因此折行时不再滚动：整句已在纵向上铺开，横推没有收益。
     * 这也让 {@code primaryWidth} 的语义在两种模式下都保持单一：
     * 折行时不滚动，主行宽度就是各段宽度里最宽那条。
     *
     * @param painter  用于量字宽
     * @param fullText 整句原文
     * @param maxWidth 单段可用宽度
     * @return 按顺序排列的段；不折行时为 {@code null}
     */
    private java.util.List<LyricWrap.Segment> primarySegments(
            Painter painter, String fullText, int maxWidth) {
        if (!style.wrap()) {
            // 不折行：直接返回 null，连一次量宽都不做。
            // 关闭折行的代码路径必须与加这个开关之前完全一致，
            // 多量一次宽虽然不改变像素，却会让「零变化」多一个不可验证的前提。
            return null;
        }
        // IntUnaryOperator 传 int，而 Painter 收 char：这里显式收窄。
        // 折行按 UTF-16 码元切分，与逐字绘制用的是同一粒度，
        // 因此代理对会被当成两个码元——与 {@code clip} 及 {@code charStarts}
        // 的现有行为一致，不额外引入码点语义。
        return LyricWrap.wrap(fullText, maxWidth, cp -> painter.charWidth((char) cp));
    }

    /**
     * 实际可画的行数。
     *
     * <p>受时间轴剩余行数限制，不能凭空补空行。
     *
     * <p>折行把「行」拆成两类：前 {@code span} 格属于当前句（折行段），
     * 其后的格子才是副行。段是内容的结果，必须全部画出——少画一段
     * 就等于把那几个字吞掉；副行则仍然受配置行数与时间轴剩余行数限制。
     *
     * @param slots 槽位总数上限（折行段数与配置行数取大）
     * @param span  前几格属于当前句；不折行恒为 1
     */
    private int drawableLineCount(LyricTimeline timeline, int index,
                                  LyricLine concurrent, int slots, int span) {
        int total = Math.max(1, slots);
        int drawable = Math.max(1, span);
        if (drawable >= total) {
            return total;
        }
        // 重叠行占用第一条副行槽位，且它不受「后续行是否存在」的限制：
        // 重叠可能发生在歌曲最后一句上，那时根本没有下一行。
        if (concurrent != null) {
            drawable++;
        }
        for (int i = 1; drawable < total; i++) {
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
        // 描边关闭时传 0 宽度：让绘制层走无描边分支，
        // 而不是传一个「颜色透明但宽度非零」的组合——那会让字形
        // 还在按描边几何算，只是画不出来，白算一遍。
        float stroke = style.stroke() ? style.strokePx() : 0f;
        return new LyricPainter(painter, style.weight(), style.shadow(), effects,
                stroke, style.strokeArgb(), style.feather(), style.mixed());
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

    /**
     * 本帧实际用于查时间轴的位置（外推 + 用户偏移）。
     *
     * <p>外部若用轮询上报值做对齐会错开最多一个上报周期，
     * 诊断与测试必须看到这个值才与画面一致。
     */
    private long lastRenderedPositionMs;

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

    /**
     * 当前生效的水平中心：预览区内居中，否则全屏居中并叠加用户偏移。
     *
     * <p>公式本体在 {@link LyricGeometry#anchorX}——放置策略要用同一条，
     * 而间奏提示这条独立路径也要用同一条。留一个实例入口是为了让
     * 「锚点读的是哪几个字段」在本类里可见。
     */
    private int layoutCenterX(int screenWidth) {
        return LyricGeometry.anchorX(screenWidth, offsetX, previewArea);
    }

    /** 当前生效的块心 y：预览区内垂直居中，否则贴底部并叠加用户偏移。 */
    private int layoutCenterY(int screenHeight) {
        return LyricGeometry.anchorY(screenHeight, offsetY, previewArea);
    }

    /**
     * 显式覆盖放置策略。
     *
     * <p>正常路径**不**走这里：策略由 {@code style.layout()} 决定，
     * 见 {@link #activePlacement}。这个方法保留给两处真正的需要——
     * 测试要把渲染器钉在某个特定策略上，诊断工具要临时试排布。
     *
     * <p>一旦调用，覆盖值优先于样式：用户下次改样式里的排布选项时，
     * 会经由 {@link #clearPlacementOverride()} 清掉覆盖，恢复自动选择。
     * 「永久覆盖」与「跟随配置」若都有入口却不区分，就会出现
     * 「改了配置没反应」这种只能靠重启解决的状态。
     *
     * <p>传 null 等同清除覆盖。
     */
    public void setPlacementStrategy(PlacementStrategy strategy) {
        this.placementOverride = strategy;
    }

    /** 清除显式覆盖，恢复按样式自动选择策略。 */
    public void clearPlacementOverride() {
        this.placementOverride = null;
    }

    /** 当前生效的策略：有覆盖用覆盖，否则按样式里的排布形态取。 */
    public PlacementStrategy placementStrategy() {
        PlacementStrategy override = placementOverride;
        return override != null ? override : activePlacement();
    }

    /** 按当前样式选择策略实例；实例无状态，可直接共享。 */
    private PlacementStrategy activePlacement() {
        return style.layout().strategy();
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

    /**
     * 本帧实际用于查时间轴的位置：外推后的播放位置加用户时间偏移。
     *
     * <p>诊断与测试要对着「渲染器真正看到的时间」说话。
     * 用数据源的上报值会错开最多一个上报周期（200ms），
     * 足以跨过一句歌词的边界，得出的结论会是错的。
     */
    public long currentPositionMs() {
        return lastRenderedPositionMs;
    }

    /** 上一帧对应的时间轴与行索引，用于识别换行并重置动画。 */
    private long lastSignature = Long.MIN_VALUE;
    private int lastLineIndex = -1;

    private long lastFrameNanos;


    /** 行切换动画状态：淡出进度、上移位移、旧句快照。 */
    private final LineTransition transition = new LineTransition();

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

        if (painter == null) {
            return false;
        }
        // 数据源不存活时通常不出画，但预览是例外。
        //
        // 原条件写作 {@code !source.isAlive()}，而数据源的存活
        // 包含「用户是否启用」——玩家把数据源关掉之后进配置界面，
        // 预览会整块空白，所有颜色、字号、描边的调整都看不到效果。
        // 预览要看的正是样式，与数据源此刻有没有歌词无关：
        // 时间轴没内容时下面的分支自然会返回，不需要在这里提前挡掉。
        if (!source.isAlive() && !previewForced) {
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
        lastRenderedPositionMs = positionMs;

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
        // 可用宽度在截断与滚动判定之前就要用，因此这里先按同一公式算一次。
        // 公式住在 LyricGeometry，放置策略内部也调它——两处必须是同一条，
        // 否则「截到什么长度」与「按多宽居中」会各说各话。
        int maxWidth = LyricGeometry.availableWidth(
                style.maxWidthRatio(), previewArea, screenWidth);

        // 双行布局：主行（当前句）+ 副行（下一句或翻译）。
        //
        // 主行是可逐字染色的那一行；副行只做静态预览，
        // 因为下一句的逐字时间还没到，提前染色没有意义。
        // 同时在唱的另一句优先于「下一句预览」：
        // 前者是此刻就该读到的词，后者只是提前量。
        LyricLine concurrent = concurrentLineAt(timeline, line, positionMs);
        LyricLine nextLine = concurrent != null ? concurrent : timeline.line(index + 1);

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
        boolean inPreview = LyricGeometry.isUsablePreview(previewArea);
        // 「能裁剪」与「要滚动」是两件事，此前被合成一个 {@code canScroll}：
        //   1. 裁剪：逐字绘制要把每个字限制在自己的格里；
        //   2. 滚动：整行超宽时让它横向跑。
        // 预览关掉了滚动，于是连裁剪也一起关掉，
        // 逐字绘制路径整条被跳过——预览只剩一段静态文字，
        // 上浮、渐变、强调全部看不到，与进游戏后判若两样。
        // 现在只让「滚动」受预览限制，裁剪仍按平台能力判断。
        boolean canScissor = painter.supportsScissor();

        // 折行：开启时把整句切成若干段，段数决定主行占几个槽位。
        // 关闭时为 null，后面的代码走的就是原路径。
        java.util.List<LyricWrap.Segment> segments = primarySegments(painter, fullText, maxWidth);

        // 折行与滚动互斥：折行已经把整句在纵向上铺开，再横推没有收益，
        // 且段内横推会把已唱前沿推出段外。理由见 primarySegments 的注释。
        boolean wrapping = segments != null && !segments.isEmpty();
        // 排布形态也可以否决滚动：弧形与环形让视口完整覆盖整块以绕开
        // 屏幕空间的轴对齐裁剪框（理由见 ArcPlacement 类注释）。
        // 这两种形态下继续叠加滚动偏移只会把字推出视口，
        // 因此必须在这里就关掉，而不是等策略内部忽略 scrolling——
        // 本方法上面那行 visible 的取值依赖 scrolling：它决定用整句还是截断句，
        // 两边判断不一致就会出现「策略不滚、文字却按整句画」的越界。
        PlacementStrategy active = placementStrategy();
        boolean scrolling = !wrapping && canScissor && !inPreview
                && fullWidth > maxWidth && style.layout().scrolls();

        // 滚动时不截断文本：坐标必须和完整文本保持一致，
        // 否则 sungWidth 与实际绘制位置对不上，高亮会错位。
        // 折行时每段已在切分时按 maxWidth 截好，不再走 clip。
        String visible = scrolling ? fullText
                : (wrapping ? "" : clip(painter, fullText, maxWidth));

        // 主行宽度有两个用途：滚动的推动长度、非滚动时的视口宽度。
        // 折行时取最宽的一段——各段已经都不超过 maxWidth，用最宽段做视口，
        // 逐字裁剪的右界恰好落在最长那段的末尾，短段两侧不会多出空白。
        int primaryWidth;
        if (wrapping) {
            int widest = 0;
            for (LyricWrap.Segment segment : segments) {
                if (segment.width() > widest) {
                    widest = segment.width();
                }
            }
            primaryWidth = widest;
        } else {
            primaryWidth = scrolling ? fullWidth : painter.textWidth(visible);
        }

        String secondaryText = secondaryTextOf(painter, line, nextLine, maxWidth);
        // 重叠行是歌词而不是译文，上色必须按后续歌词走，否则会淡成译文色。
        boolean nextLineIsLyric = nextLine != null && !nextLine.text().isEmpty();

        // 主行占几个行槽位：折行时等于段数，否则恒为 1。
        int span = wrapping ? segments.size() : 1;

        // 换行或时间轴变更时重置进度，避免沿用上一行的。
        //
        // 这一步必须在放置之前：滚动偏移由已唱宽度推出，而放置要把
        // 「推到哪儿了」作为输入的一部分。反过来先排位置再算进度，
        // 滚动量就会永远落后一帧，唱到的字与视口边缘错开半个字。
        //
        // 折行时前沿按**整句**算一次，各段再由 startPx 换算成本段局部值。
        // 若按段分别算，每段的前沿都从 0 起爬，第二段会在交界处倒退。
        String progressText = wrapping ? fullText : visible;
        if (index != lastLineIndex) {
            sungWidth = targetWidthAt(painter, line, progressText, positionMs);
            lastLineIndex = index;
        } else {
            float target = targetWidthAt(painter, line, progressText, positionMs);
            sungWidth = advance(sungWidth, target, frameDelta);
        }
        float clampedSung = Math.max(0f, Math.min(sungWidth, primaryWidth));

        // 多行堆叠：把当前行与后续若干行当作一整块，块心固定在锚点上。
        boolean hasSecondary = !secondaryText.isEmpty();
        // 折行时段数可能超过配置行数：段是内容的后果，不能因为行数滑条
        // 调小而把句子截掉一半。槽位总数取两者较大值，副行只在还有
        // 剩余槽位时出现——这正好保住了「行数就是我想要的总行数」的本意。
        int slotCount = Math.max(span, Math.max(1, style.lineCount()));
        int drawableLines = drawableLineCount(timeline, index, concurrent, slotCount, span);

        // 逐行内容先备好再交给放置策略。
        //
        // 策略算不出字宽（那是平台能力），因此截断与量宽必须在外面做；
        // 而「第 1 行可能是翻译兜底、其余行取后续歌词」这条规则属于内容选择，
        // 同样与位置无关，留在渲染器里才不会被每个策略各实现一遍。
        //
        // 空槽位仍然计入行数：块高按槽位数算，某一格暂时没内容时
        // 也不让整块跳一下。
        java.util.List<String> lineTexts = new java.util.ArrayList<>(drawableLines);
        int[] lineWidths = new int[drawableLines];
        int[] lineColors = new int[drawableLines];
        float[] lineScales = new float[drawableLines];
        if (wrapping) {
            // 折行：前 span 格是同一句的各段，宽度各按自己量。
            for (int i = 0; i < drawableLines; i++) {
                if (i >= span) {
                    break;
                }
                LyricWrap.Segment segment = segments.get(i);
                lineTexts.add(segment.text());
                lineWidths[i] = segment.width();
                lineColors[i] = sungColor();
                lineScales[i] = 1f;
            }
        } else {
            lineTexts.add(visible);
            // 主行宽度填原始值：策略里「非滚动时视口等于主行宽」读的是单独字段，
            // 这里保持一致，避免两处对「主行多宽」各有一个说法。
            lineWidths[0] = primaryWidth;
            lineColors[0] = sungColor();
            lineScales[0] = 1f;
        }
        for (int i = span; i < drawableLines; i++) {
            String text = "";
            if (hasSecondary) {
                // 折行时副行起点后移：第 span 格才是第一条副行，
                // 它可能来自翻译兜底，其余格一律取后续歌词。
                int extra = i - span;
                boolean firstExtra = extra == 0;
                LyricLine upcoming = firstExtra && !nextLineIsLyric
                        ? null : timeline.line(index + extra);
                text = firstExtra
                        ? secondaryText
                        : (upcoming == null ? "" : clip(painter, upcoming.text(), maxWidth));
            }
            while (lineTexts.size() <= i) {
                lineTexts.add("");
            }
            lineTexts.set(i, text);
            // 副行按 Salt 的 SubTextFontSizeScale 缩小，宽度必须同样缩放，
            // 否则居中会偏，底板预留也会算多。
            lineWidths[i] = text.isEmpty() ? 0 : painter.textWidthScaled(text, SUB_TEXT_FONT_SCALE);
            lineColors[i] = i == span && !nextLineIsLyric
                    ? translationColor() : nextLineColor();
            lineScales[i] = SUB_TEXT_FONT_SCALE;
        }


        // 行切换：其余行连续上移一行补位。旧主行直接在换行瞬间消失，
        // 理由见 {@link LineTransition} 的类注释。
        long durationMs = style.lineTransitionMs();
        // 位移步长取真实行步长（行高 + 当前行距），与放置策略内部
        // LineLayout.compute 用的是同一个 gap。写死常量的话，
        // 改行距后收起的那一像素会以跳格收尾。
        int lineStep = LyricGeometry.LINE_HEIGHT + this.style.lineGapPx();
        int blockShift = transition.blockShift(durationMs, lineStep);

        // 位置编排交给策略：几何算式全部搬出去，这里只读结果。
        //
        // 策略拿不到绘制器，也算不出字宽——量宽与排版分开，
        // 是「换一种排布不必碰绘制链路」的前提。
        PlacementInput placementInput = new PlacementInput(screenWidth, screenHeight,
                previewArea, offsetX, offsetY, style, lineTexts, lineWidths,
                lineColors, lineScales, primaryWidth, clampedSung, scrolling, blockShift,
                span);
        LyricPlacement placed = active.place(placementInput);

        // 只取绘制真正需要的几项。留白与块边界不再单独取局部变量：
        // 它们只经由 footprint 使用，多留一份副本就多一次「两份对不上」的机会。
        int centerX = placed.centerX();
        int centerTextY = placed.centerY();
        int viewWidth = placed.viewWidth();
        int viewportLeft = placed.viewportLeft();
        int textX = placed.textX();

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

        // 歌词整体的屏幕包围盒。
        //
        // 无论是否拖动都要算：命中检测（悬停、点击）依赖它，
        // 而悬停恰恰发生在「看不见底板」的平常状态。
        // 高度覆盖主行与副行，横向覆盖实际绘制宽度（滚动时是整个视口），
        // 这样点到边上的字也算命中。
        // 矩形由放置结果派生，与下面底板**共用同一个**，
        // 不再各写一遍「left-padX / blockTop / width+2padX」。
        // 预览时把包围盒裁进框内：命中检测与描边都不该超出预览区。
        Bounds footprint = placed.footprint();
        bounds = inPreview ? intersect(footprint, previewArea) : footprint;

        // 底板默认关闭：歌词直接浮在画面上。
        //
        // 只有拖动时才显示，作为可拖区域的视觉提示；
        // 平时留一块半透明底会压住画面，也违背「不打扰」的默认。
        if (dragging) {
            painter.fillRect(footprint.x(), footprint.y(),
                    footprint.width(), footprint.height(), COLOR_BACKDROP);
            // 描一圈边，拖动态下手感更明确，也标出可拖范围。
            painter.fillRect(footprint.x(), footprint.y(), footprint.width(), 1, COLOR_DRAG_BORDER);
            painter.fillRect(footprint.x(), footprint.y() + footprint.height() - 1,
                    footprint.width(), 1, COLOR_DRAG_BORDER);
            painter.fillRect(footprint.x(), footprint.y(), 1,
                    footprint.height(), COLOR_DRAG_BORDER);
            painter.fillRect(footprint.x() + footprint.width() - 1, footprint.y(), 1,
                    footprint.height(), COLOR_DRAG_BORDER);
        }

        // 逐字偏移表：底色层与高亮层必须共用同一份。
        //
        // 若只让高亮层带偏移，唱到的字会从白色原字上方落下来，
        // 看起来像两个独立的字在打架，而不是同一个字变色并上浮。
        // 逐字上浮只要求「平台能裁剪」——裁剪用来把每个字的绘制限制在
        // 自己的格内，与滚动无关。此前这里判的是 {@code canScroll}，
        // 而预览把 {@code canScroll} 强制关掉了（预览不滚动），
        // 于是**预览里永远看不到逐字上浮与渐变染色**：
        // 玩家调字号、行距、上浮曲线时，预览是一片不会动的静态文字，
        // 与进游戏后看到的完全不是一回事。
        //
        // 上浮与滚动本是两件事，只是原先被同一个布尔量绑在一起。

        // 整体不透明度作用于主行、后续行与间奏，统一在这里相乘一次。
        float opacity = this.style.opacity();

        // 旧主行不再淡出重画，换行瞬间直接消失。
        //
        // 淡出必须把旧句重新画一遍，而整句绘制只有单一颜色：
        // 逐字抬升、逐顶点染色、强调提亮三样全无。
        // 已唱完的主句因此会在原位以「最基础的文字样式」闪一帧，
        // 与它停留时的样子判若两句。句末本已唱完，直接消失更干净。
        // 块上移补位不受影响，各行仍然连续移动。

        // 主行越出块顶就不画：这是上移到底后旧行彻底让出位置的落点。
        // 判据读的是放置结果里的落笔位置与块顶，二者本来就同源。
        PlacedLine mainLine = placed.primary();
        float mainLineY = mainLine == null ? placed.blockTop() : mainLine.y();
        // 判据留在浮点上比较，不先取整：取整会把 -0.4 抬到 0，
        // 让「刚越出块顶半像素」的那一帧仍然出画，边缘闪一下。
        boolean mainVisible = mainLineY >= placed.blockTop() - 1;
        // 裁剪右界取「视口右边界」与「视口左 + 视口宽」的较小值。
        // 两者在横排策略下恒等，但保留这次取小是个不变式：
        // 将来若策略给出彼此不一致的 viewportRight/viewWidth，
        // 逐字裁剪应偏保守（少露一点），而不是把字画到视口外。
        int clipRight = Math.min(placed.viewportRight(), viewportLeft + viewWidth);

        if (wrapping) {
            drawWrappedMain(painter, placed, segments, fullText, clampedSung,
                    opacity, canScissor, clipRight, line, positionMs);
        } else if (mainVisible) {
            // 落笔位置在这里取整一次，交给只收 int 的绘制原语。
            // 默认策略吐出的一定是整数值，Math.round 因而是恒等变换；
            // 这条由 PlacementGoldenTest 的「坐标全为整数值」断言保证。
            int mainBaselineY = Math.round(mainLineY);
            float[] lifts = clampedSung >= 0.5f && canScissor
                    ? buildLifts(painter, visible, primaryWidth, clampedSung, line, positionMs)
                    : null;
            boolean oriented = mainLine != null && mainLine.hasOrientation();
            if (oriented) {
                // 中心按「落笔点 + 半宽半高」算：主行宽度取 primaryWidth
                // （滚动时是整行宽度），与放置结果的 viewWidth 同源。
                pushLineRotation(painter,
                        textX + primaryWidth * 0.5f,
                        mainLineY + LyricGeometry.LINE_HEIGHT * 0.5f,
                        mainLine.orientationDegrees());
            }
            if (canScissor) {
                textPainter(painter).karaoke(visible, textX, mainBaselineY, clampedSung,
                        viewportLeft, clipRight,
                        lifts, opacity, sungColor(), unsungColor(), line, positionMs);
            } else {
                drawByWholeUnit(painter, line, visible, textX, mainBaselineY,
                        clampedSung, opacity);
            }
            if (oriented) {
                painter.pose().popPose();
            }
        }

        // 后续各行：静态预览，不做逐字染色，字号统一收一档。
        // 它们不参与淡入，否则同一句在过渡期与稳定期的颜色不一致。
        //
        // 位置、宽度、颜色、缩放全部来自放置结果：此处不再算任何居中。
        // 空槽位在放置阶段就已剔除（占高度但不出画），因此这里只管画。
        for (PlacedLine linePlacement : placed.lines()) {
            if (linePlacement.isPrimary()) {
                continue;
            }
            // 副行同样应用字重与阴影设置。
            boolean oriented = linePlacement.hasOrientation();
            if (oriented) {
                // 副行的占位宽度是缩放后的 width*scale，中心按它算才不会偏。
                pushLineRotation(painter,
                        linePlacement.x() + linePlacement.width() * linePlacement.scale() * 0.5f,
                        linePlacement.y() + LyricGeometry.LINE_HEIGHT * linePlacement.scale() * 0.5f,
                        linePlacement.orientationDegrees());
            }
            // 落笔坐标在这里取整一次；默认策略下同样是恒等变换。
            textPainter(painter).textScaled(linePlacement.text(),
                    Math.round(linePlacement.x()),
                    Math.round(linePlacement.y()),
                    linePlacement.scale(), linePlacement.color());
            if (oriented) {
                painter.pose().popPose();
            }
        }

        if (scaling) {
            painter.pose().popPose();
        }
        return true;
    }

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
        int centerX = layoutCenterX(screenWidth);
        int centerY = layoutCenterY(screenHeight);
        int x = centerX - width / 2;
        int y = centerY - LINE_HEIGHT;

        // 字号缩放必须同样作用到间奏提示。
        //
        // 主行分支在下面才压缩放栈，而间奏走的是提前 return 的另一条路径，
        // 因此这里必须自己 push/pop。否则用户把字号调到 200% 时，
        // 歌词放大而「间奏」二字仍停在原尺寸——同一块区域里两种字号。
        float scale = this.style.lineScale();
        boolean scaling = Math.abs(scale - 1f) > 0.001f;
        if (scaling) {
            var pose = painter.pose();
            pose.pushPose();
            pose.translate(centerX, centerY);
            pose.scale(scale, scale);
            pose.translate(-centerX, -centerY);
        }

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
        if (scaling) {
            painter.pose().popPose();
        }
        return true;
    }

    /** 由行宽与字数估出字号；中文字宽约等于字号，故比值为良好近似。 */
    /**
     * 按当前设置的上浮曲线算出逐字偏移。
     *
     * <p>线性曲线让每个字各自随自身进度升起；余弦曲线把整行当成一条
     * 横跨的斜坡。两者都必须给底色层与高亮层共用同一份偏移，
     * 否则唱到的字会从原字上方落下来，看着像两个独立的字。
     */
    private float[] buildLifts(FloatingLyricRenderer.Painter painter, String visible,
                               int primaryWidth, float sungWidth,
                               com.linklyric.core.lyric.LyricLine line, long positionMs) {
        int[] starts = LyricProgress.charStarts(painter, visible);
        int[] widths = new int[starts.length];
        for (int i = 0; i < starts.length; i++) {
            int next = i + 1 < starts.length ? starts[i + 1] : primaryWidth;
            widths[i] = Math.max(1, next - starts[i]);
        }
        // 参考字宽取行内最宽字，而不是行宽除以字数。
        //
        // 后者是「平均每格宽度」，在中文上巧合等于字号，
        // 但英文、空格与窄字一多就偏小，直接把
        // 抬升幅度与过渡带一起压扁，逐字动画退化成整字跳动。
        float fontSize = LyricProgress.referenceSize(widths);
        if (fontSize <= 0f) {
            fontSize = LyricProgress.referenceSize(primaryWidth, visible.length());
        }
        if (style.liftCurve() == com.linklyric.common.style.LyricStyleConfig.LiftCurve.LINEAR) {
            return LyricProgress.lifts(visible.length(), fontSize,
                    LyricProgress.charProgress(line, visible, positionMs));
        }
        return LyricProgress.cosineLifts(starts, widths, sungWidth, fontSize);
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

    /**
     * 单个演唱单元的进度曲线，用于决定<b>染色前沿</b>的位置。
     *
     * <p>这里是位移/颜色分布的「事实层」，不能被艺术加工。
     * 染色前沿表达的是「唱到哪个字了」，它必须与音频时间严格对应：
     * 曲线一旦把前沿推进速度改成非匀速，口型与画面就会脱节，
     * 越往后误差越大（贝塞尔尾部趋平，行尾永远差一点染不满）。
     *
     * <p>因此**不能**复用行切换的 {@link LyricEasing#ease}——
     * {@code CubicBezier(0.25, 0, 0.2, 1)} 是为「整块推上去」设计的
     * 位移手感曲线，属于观感层。两者的判据不同：
     * 前沿问「唱到哪了」，上浮问「起来得好不好看」。
     *
     * <p>这里只做一次平滑起步与收尾，避免与 {@code advance()} 的平滑叠加后过冲。
     */
    private static float sustainCurve(float progress) {
        if (progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
        // 三次平滑插值：两端导数趋零的幅度远小于 CubicBezier(0.25,0,0.2,1)，
        // 中段接近线性，因此不会把一个字切成「静止—猛冲—静止」三段。
        return progress * progress * (3f - 2f * progress);
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

        // 已唱宽度必须在**整行**上是时间的单一连续函数。
        //
        // 早先的写法是逐字各自套 sustainCurve：每个字从 0 平滑到 1，
        // 而 smoothstep 在两端导数为零。于是唱到字边界时速度先降到 0
        // 再重新爬升，听感/观感上就是「一顿」，字与字之间接不上。
        // 逐字缓动把一条连续的时间线切成了 N 段各自起停的曲线。
        //
        // 正确做法：只用「字内进度」这个线性比例决定前沿落在字内何处，
        // 缓动若需要则作用于整行进度，而不是每个字各缓动一次。
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
                // 线性推进：前沿速度由该字的实际时长决定，
                // 字间不存在速度凹陷。
                long span = word.endMs() - word.startMs();
                float ratio = span <= 0L ? 1f : (positionMs - word.startMs()) / (float) span;
                width += partWidth * Math.max(0f, Math.min(1f, ratio));
            } else {
                // 本字尚未开始。位置可能在外推下还没走到这里，
                // 因此不能提前退出：外推会把位置往后推，
                // 而这里的循环只是按当前值累加。
                break;
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