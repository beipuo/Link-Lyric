package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.common.style.ColorMath;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GradientColorizer;
import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.common.style.LyricStyleConfig.Align;
import com.linklyric.common.style.LyricEasing;
import com.linklyric.common.style.LyricStyleConfig.Weight;
import com.linklyric.core.sync.PlaybackClock;
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

    /**
     * 歌词唯一颜色。
     *
     * <p>对应 Salt 的 {@code desktopLyricsColor}：桌面歌词**只有这一个颜色配置**，
     * 没有单独的「未唱色」。未唱部分靠下面的蒙版压暗同一层像素得到，
     * 不是换一种颜色再画一遍。
     */
    /** 已唱色默认值。实测值以 {@link LyricStyleConfig#DEFAULT} 为准。 */
    public static final int COLOR_SUNG = LyricStyleConfig.DEFAULT.sungColor();

    /**
     * 未唱色。
     *
     * <p>这是真正的第二种颜色，不是把已唱色调暗的结果。
     * 黑色蒙版只能把蓝压成灰蓝，观感像蒙了一层灰；
     * 而两种独立的色值才能得到「灰白 → 亮蓝」这种真正的变色。
     */
    /** 未唱色默认值。实测值以 {@link LyricStyleConfig#DEFAULT} 为准。 */
    public static final int COLOR_UNSUNG = LyricStyleConfig.DEFAULT.unsungColor();

    /**
     * 拖动时的底板色：半透明黑。
     *
     * <p>只在拖动模式使用。平时不画底板，歌词直接浮在画面上；
     * 拖动时需要一块明确的可视图层，让用户知道自己正在移动什么、边界在哪。
     */
    public static final int COLOR_BACKDROP = 0x66000000;

    /** 拖动时的边框色：偏亮的描边，用来标出可拖范围的边界。 */
    public static final int COLOR_DRAG_BORDER = 0x99FFFFFF;

    /** 文本距屏幕底部的距离。留出物品栏与经验条的空间。 */
    private static final int BOTTOM_MARGIN = 58;

    private static final int LINE_HEIGHT = 10;
    private static final int LINE_GAP = 2;

    /** 平滑时间常数：约 50ms 内逼近目标，兼顾跟手与顺滑。 */
    private static final double SMOOTH_TAU_SECONDS = 0.05;

    /** 目标值比当前值小超过该像素数时判定为 seek，直接跳变而非回退。 */
    private static final float BACKWARD_SNAP_PX = 6f;

    /** 与目标的差值小于此值即吸附，避免指数逼近在行尾永久残留暗边。 */
    private static final float SNAP_EPSILON_PX = 1.5f;

    /**
     * 未生效行（上一行、下一行）的不透明度。
     *
     * <p>取自 Salt Player 的 {@code LyricsComponentDefaults.NotCurrentAlpha = 0.3f}。
     * 它与高亮行在 700ms 内互相淡入淡出，形成「上一行退出、当前行进入」的层次。
     *
     * <p>此前副行用固定的 {@code 0xC8FFFFFF}（约 0.78），几乎与主行一样亮，
     * 两行同权就分不出谁在唱。
     */
    private static final float NOT_CURRENT_ALPHA = 0.3f;

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
     * 未生效行的缩放。
     *
     * <p>取自 Salt Player 的 {@code LyricsComponentDefaults.NotCurrentScale = 0.95f}。
     * 缩得很小会像弹窗抖动；0.95 只是在滚动时让当前行略微「浮起来」。
     *
     * <p>MC 的 HUD 没有矩阵缩放通道，因此这里不直接缩放字形，
     * 而是把该比例用于羽化带宽（见 {@link #FEATHER_RATIO}），
     * 保持与 Salt 一致的视觉比例关系。
     */
    private static final float NOT_CURRENT_SCALE = 0.95f;

    /**
     * 副行字号相对主行的比例。
     *
     * <p>取自 Salt Player 的 {@code LyricsComponentDefaults.SubTextFontSizeScale = 0.8f}
     * 与 {@code AppConfig.playerLyricsTranslationFontSizeScale} 的默认值。
     * 翻译是次要信息，缩到 0.8 倍才不会与原文抢注意力。
     */
    private static final float SUB_TEXT_FONT_SCALE = 0.8f;

    /**
     * 羽化带长度，相对于字号的比例。
     *
     * <p>取自 Salt Player：其高亮羽化带的宽度实参是
     * {@code toPx(fontSize) * NotCurrentScale}，即 {@code 字号 × 0.95f}。
     * 这是一条「已唱色 → 透明」的线性渐变，长度接近一个字宽，
     * 因此推进端的过渡只有一个字的量级，与逐字步进吻合。
     *
     * <p>此前的 0.45 来自 HyperLyric 的 6 停靠点渐变，比 Salt 窄一半，
     * 推进端因此显得更「利」，像刀切而非溶开。
     */
    /**
     * 羽化带宽度，按字号的比例。
     *
     * <p>原先借用 {@code NOT_CURRENT_SCALE}(0.95)，那本是副行的缩放比例，
     * 与羽化毫无关系。9px 字号下算得 9px 带宽，再切成 5 段就是每段 1px，
     * 视觉上只是一列像素，谈不上过渡。
     *
     * <p>现在取 0.6：约半字宽。字符宽度是 9px，边界扫过一个字时
     * 过渡带始终有 5px 左右在渐变，颜色是连续推进而不是整段跳变。
     */
    private static final float FEATHER_RATIO = 0.6f;

    /**
     * 羽化带的 alpha 梯度，由高到低。
     *
     * <p>每段取「已唱色→未唱色」的插值比例，不是 alpha。
     * 用 alpha 递减做不到颜色过渡：那是在和背景混合，
     * 字形连同阴影一起变淡，深色背景上就表现为一条竖向暗痕。
     *
     * <p>比例偏中段密集，让视觉上的颜色变化更均匀（人眼对暗部差异更敏感）。
     */
    private static final float[] FEATHER_STEPS = {0.15f, 0.35f, 0.55f, 0.75f, 1.0f};

    /**
     * 抬升幅度，以字号的比例计。
     *
     * <p>取自 Salt Player 的 {@code LyricsLineKaraokeTextProperties.floatUpPercentage}
     * 默认值 {@code 0.1f}（反编译其 KaraokeText 属性类，默认参数位 0x10）。
     * 使用前先 {@code roundToInt(字号 × 该比例)}，MC 字号 9px 时整 1px。
     *
     * <p>与此前的 0.12 相比，9px 下由约 1.08px 收到整 1px。差值本身不大，
     * 真正的观感差异来自形状而不是幅度：旧实现是「每个字各自升起再落回」的
     * 单峰，峰值只出现在演唱点附近的一个字上，读起来像一串独立的小跳；
     * 这里改成整片抬起 + 前沿斜坡，位移恒定，眼睛看到的是高亮在推着走。
     */
    private static final float LIFT_FONT_RATIO = 0.10f;

    /**
     * 过渡带宽度，以字号的比例计。
     *
     * <p>取自 Salt Player 的卡拉 OK 绘制：高亮区宽度恒为
     * {@code 字号 × 3.0f}（其 {@code LyricsGlow} 的
     * {@code toPx(fontSize) * 3.0f} 实参）。半宽 1.5 倍字号，
     * 因此同一时刻约一个半字处在斜坡上，前沿才连续。
     *
     * <p>此前取 0.9（HyperLyric 的单峰波长）：峰宽不到一个字，同一个字从
     * 抬起到落回只跨越一个字的行程，视觉上就是逐字跳动。换成 3 倍字号后
     * 斜坡横跨约三个字，前沿推进才是连续的。
     */
    private static final float LIFT_WAVE_FONT_RATIO = 3.0f;



    /**
     * 抬升坐标采集开关。
     *
     * <p>打开后每隔 {@link #DIAG_INTERVAL_NANOS} 往 {@link #DIAG_FILE} 追加一行
     * 已唱宽度与逐字偏移，用于在真实客户端里核对波形参数。
     *
     * <p>写文件而不是 stdout：NeoForge dev 环境下 {@code System.out}
     * 不进入 {@code logs/latest.log}，写文件才能被离线读取。
     */
    private static final boolean DIAG_LIFT = Boolean.getBoolean("linklyric.diagLift");

    /** 采集输出文件，落在游戏工作目录下。 */
    private static final String DIAG_FILE = "linklyric-lift.log";

    /** 采集间隔，取 200ms 以便一个字的演唱窗口内能采到多帧。 */
    private static final long DIAG_INTERVAL_NANOS = 200_000_000L;

    /**
     * 已唱部分的绘制粒度。
     *
     * <p>抬升是逐字计算的，若仍按整行一次绘制，只能给整行一个偏移，
     * 起不到「唱到哪个字哪个字动」的效果。
     */
    private static final boolean PER_CHAR_LIFT = true;

    /**
     * 抬升需要额外预留的垂直空间。
     *
     * <p>抬升量是 {@code round(字号 × LIFT_FONT_RATIO)}，MC 默认字号 9px 时
     * 整 1px。这里留 2px：既覆盖 1px 的位移，也给裁剪区留出抗锯齿的余量，
     * 否则抬起字的顶边会被削掉一条。
     */
    private static final int LIFT_HEADROOM = 2;
    /**
     * 阴影与字重叠加的额外余量。
     *
     * <p>原版阴影偏移 1 像素，加粗最多再向外 2 像素，取 4 已足够；
     * 留小了中文阴影会被裁剪，留大了只是多画几个透明像素。
     */
    private static final int SHADOW_HEADROOM = 4;

    /**
     * 逐字绘制的最多字数。
     *
     * <p>每字一次绘制调用，长行会成倍放大开销。超过这个长度就退回整段绘制：
     * 那种长度下 HUD 已经改为滚动，逐字偏移被视口裁掉大半，收益抵不过开销。
     */
    private static final int MAX_LIFT_CHARS = 24;

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
     * 行切换动画时长（纳秒）。
     *
     * <p>700ms 取自 Salt Player：其行级状态用
     * {@code animateFloatAsState(tween(durationMillis = 700, easing = Easing))}
     * 驱动，{@code Easing} 即 {@code CubicBezierEasing(0.25f, 0.0f, 0.2f, 1.0f)}
     * （其 {@code LyricsComponentDefaults} 的默认缓动）。
     *
     * <p>此前的 220ms 取自 HyperLyric，配的是四分之一圆缓动（起步快、收尾慢）。
     * 两者相差三倍，观感完全不同：220ms 是「弹一下」，700ms 才是「滑过去」。
     */


    /**
     * 桥接端的上报周期初值（毫秒）。
     *
     * <p>**实测为 1000ms**，不是轮询间隔。轮询每 200ms 发一次请求，
     * 但宿主每秒才更新一次位置，因此同一份位置会连续返回 5 次。
     * 外推余量若小于这个周期，高亮走完余量就会停住等下一次上报，
     * 表现为「一秒一跳、中间明显停顿」。
     */
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
     * 平台绘制适配层。
     *
     * <p>只暴露渲染真正需要的原语，使排版与染色逻辑可脱离游戏运行时测试。
     */
    /** 极简二维变换栈，避免在 common 里依赖任何渲染库。 */
    public interface Pose2D {
        Pose2D IDENTITY = new Pose2D() {
            @Override
            public void pushPose() {
            }

            @Override
            public void popPose() {
            }

            @Override
            public void translate(float x, float y) {
            }

            @Override
            public void scale(float x, float y) {
            }
        };

        void pushPose();

        void popPose();

        void translate(float x, float y);

        void scale(float x, float y);
    }

    public interface Painter {
        int screenWidth();

        int screenHeight();

        int textWidth(String text);

        /**
         * 绘制文本。颜色含 alpha 通道。
         *
         * <p>四参版本是既有实现的入口，保持抽象以免悄悄丢掉绘制。
         */
        void drawText(String text, int x, int y, int color);

        /**
         * 带阴影开关的绘制。
         *
         * <p>默认转调四参版本，因此只关心颜色的实现不必改写。
         * 平台层应覆盖本方法，才能真正响应阴影开关。
         *
         * @param shadow 是否画阴影。中文由 fallback 字体提供，字形比英文高，
         *               阴影更容易落到裁剪区之外，因此开关必须逐次传入而非写死。
         */
        default void drawText(String text, int x, int y, int color, boolean shadow) {
            drawText(text, x, y, color);
        }

        /**
         * 带阴影开关的亚像素抬升。
         *
         * <p>抬升量只有约 1px，若先取整再绘制，字会在 0 与 1 之间跳变，
         * 看起来是台阶而不是滑动。这里暴露浮点 y，由平台层做矩阵平移。
         *
         * <p>默认转调四参版本，让只关心抬升的实现不必改写。
         * 平台层覆盖本方法后，阴影开关才会真正生效。
         */
        default void drawTextLifted(String text, int x, int y, float lift,
                                    int color, boolean shadow) {
            // 转发到五参 drawText，而不是四参 drawTextLifted。
            //
            // 转发到四参 drawTextLifted 看似更「保真」，实际会断开链：
            // 实现者若重写了四参 drawTextLifted（测试替身常这么做），
            // 五参调用就绕过了 drawText，只记录 drawText 的替身收不到任何数据，
            // 表现为「主行完全没绘制」。转调五参 drawText 后，
            // 链路的唯一锚点就是 drawText，只要实现了它就能收到全部绘制。
            drawText(text, x, y - Math.round(lift), color, shadow);
        }

        default void drawTextLifted(String text, int x, int y, float lift, int color) {
            drawText(text, x, y - Math.round(lift), color, true);
        }

        /**
         * 绘制单个字形，左右两端可不同色。
         *
         * <p>这是**字内渐变**的唯一入口。字形是四边形，GPU 在顶点之间
         * 线性插值，因此给左两顶点一个颜色、右两顶点另一个颜色，
         * 就能让一个字内部出现连续的染色前沿。
         *
         * <p>为什么需要它：裁剪法只能用整像素边界分色，9px 的字最多
         * 9 个台阶，观感是「先亮一列，卡住，再整段跳」。逐顶点配色
         * 中颜色是浮点量，前沿走到字内任何位置都只是两端色不同，
         * 没有量化下限。
         *
         * <p>默认实现退回单色（取左端色），保证只实现单色绘制的平台
         * 与测试替身仍可工作。平台层覆盖本方法后才有真正的字内渐变。
         *
         * @param glyph  要绘制的单个字符
         * @param x      字形左边缘横坐标
         * @param y      基线纵坐标
         * @param lift   亚像素抬升量，向下为正
         * @param colors 左右端点颜色
         * @param shadow 是否绘制阴影
         */
        default void drawGlyphGradient(String glyph, float x, int y, float lift,
                                       GlyphColors colors, boolean shadow) {
            drawTextLifted(glyph, Math.round(x), y, lift, colors.left(), shadow);
        }

        /**
         * 按缩放比例绘制文本。
         *
         * <p>用于副行：翻译与下一句预告按 Salt 的
         * {@code SubTextFontSizeScale = 0.8f} 缩小，两行字号有主次之分。
         *
         * <p>{@code x}、{@code y} 是未缩放时的锚点，缩放以该点左上角为原点。
         * 默认实现忽略缩放，保证老的 Painter 仍可用。
         */
        default void drawTextScaled(String text, int x, int y, float scale,
                                    int color, boolean shadow) {
            drawTextScaled(text, x, y, scale, color);
        }

        default void drawTextScaled(String text, int x, int y, float scale, int color) {
            drawText(text, x, y, color);
        }

        /**
         * 带阴影的缩放绘制。
         *
         * <p>注意五参版本必须转发到**四参 drawTextScaled**，不能直接转发
         * {@code drawText}：那样会丢掉缩放语义，副行就不再是 0.8 倍字号。
         * 实现者若需要阴影生效，应覆盖五参版本本身。
         */

        /** 按缩放比例测量文本宽度的像素值。 */
        default int textWidthScaled(String text, float scale) {
            return Math.round(textWidth(text) * scale);
        }

        /** 绘制矩形填充，用于底板。 */
        void fillRect(int x, int y, int width, int height, int color);

        /**
         * 坐标变换栈。
         *
         * <p>字号缩放需要在绘制前后成对压栈/出栈。
         * 只有支持变换的平台才能实现缩放，其余平台退化为不缩放。
         */
        default Pose2D pose() {
            return Pose2D.IDENTITY;
        }

        /**
         * 当前字体的行高。
         *
         * <p>用于给裁剪区留足空间。不能用常量代替：中文由 fallback 字体提供，
         * 字形比拉丁字母高，写死的行高会把中文显示的下缘（尤其阴影）裁掉。
         */
        default int lineHeight() {
            return LINE_HEIGHT;
        }

        /**
         * 是否支持按像素裁剪。
         *
         * <p>裁剪是逐字平滑推进的前提：正在演唱的那个字必须能只显示
         * 已唱比例，而等宽汉字无法靠整字推进表达中间状态。
         */
        default boolean supportsScissor() {
            return false;
        }

        default void enableScissor(int x, int y, int width, int height) {
        }

        default void disableScissor() {
        }
    }

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
    private final PlaybackClock clock = new PlaybackClock();

    private boolean clockPrimed;
    private boolean lastPlaying;

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

    public void setStyle(LyricStyleConfig value) {
        this.style = value == null ? LyricStyleConfig.DEFAULT : value;
    }

    public LyricStyleConfig style() {
        return style;
    }

    /** 两个矩形的交集；无重叠时返回空区域。 */
    private static Bounds intersect(Bounds a, Bounds b) {
        int left = Math.max(a.x(), b.x());
        int top = Math.max(a.y(), b.y());
        int right = Math.min(a.x() + a.width(), b.x() + b.width());
        int bottom = Math.min(a.y() + a.height(), b.y() + b.height());
        if (right <= left || bottom <= top) {
            return Bounds.EMPTY;
        }
        return new Bounds(left, top, right - left, bottom - top);
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
        return screenWidth / 2 + offsetX;
    }

    /** 当前生效的基线 y：预览区内垂直居中，否则贴底部并叠加用户偏移。 */
    private int layoutCenterY(int screenHeight) {
        if (previewArea != null && !previewArea.isEmpty()) {
            return previewArea.y() + previewArea.height() / 2;
        }
        return screenHeight - BOTTOM_MARGIN + offsetY;
    }

    /**
     * 按对齐方式把行宽换算成左边界。
     *
     * <p>居中沿用原有的中心锚点；左/右对齐则改成以可用区间的两端为锚。
     * 用端点而不是「中心减半宽」重算，是为了让左右对齐在滚动时也稳定——
     * 滚动会改变可见宽度，若每帧以中心反推，边界会跟着抖。
     */
    private int layoutLeft(Align align, int centerX, int maxWidth, int lineWidth) {
        return switch (align) {
            case CENTER -> centerX - lineWidth / 2;
            case LEFT -> centerX - maxWidth / 2;
            case RIGHT -> centerX + maxWidth / 2 - lineWidth;
        };
    }

    /** 单行可用宽度：预览区内按框宽收窄，否则按屏幕比例。 */
    private int layoutMaxWidth(int screenWidth) {
        if (previewArea != null && !previewArea.isEmpty()) {
            return Math.max(40, previewArea.width() - 8);
        }
        return Math.max(40, (int) (screenWidth * style.maxWidthRatio()));
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

    /** 上一帧实际使用的位置，用于保证播放中位置严格单调。 */
    private long lastRenderedPositionMs;

    /** 观测到的桥接上报周期（毫秒），随实测自适应。 */
    private long uplinkPeriodMs = DEFAULT_UPLINK_PERIOD_MS;

    /** 上一次收到「位置变化」的本地时刻，用于估计上报周期。 */
    private long lastReportChangeNanos;

    /** 上一次收到的上报位置，用于识别位置是否真的更新过。 */
    private long lastReportedPositionMs = Long.MIN_VALUE;

    /** 行切换动画：本次动画对应的行索引。 */
    private int lineTransitionFromIndex = -1;

    /** 行切换动画已进行的时长（纳秒）。 */
    /**
     * 行切换已用时间。初值取一个很大的数：首帧就应视为「已完成」，
     * 否则进游戏第一句会从半透明淡入，像是卡了一下。
     */
    private long lineTransitionElapsedNanos = Long.MAX_VALUE / 4;

    /** 上一帧的主行文本，用于绘制离场淡出。 */
    private String lastMainText;

    /**
     * 上一帧主行实际绘制用的 y（不含切换偏移的稳定位置）。
     *
     * <p>换行时旧行必须回到它**真实停留过**的地方淡出。
     * 用当前帧的块几何去推旧行位置是错的：块按 drawableLines
     * 居中计算，换行后行数变化会让块整体重新居中，
     * 基线本身就跳了，在它之上再加减行步长只会算出第三个位置。
     *
     * <p>记录的是 {@code block.baseline()}（不加 blockShift）：
     * 动画期间的临时偏移不该被继承，否则连续换行会累积误差。
     */
    private int lastMainBaselineY = Integer.MIN_VALUE;

    /** 换行瞬间冻结的旧句内容，供淡出期绘制。 */
    private String oldLineText;

    /** 换行瞬间冻结的旧句基线 y，淡出期间不随块几何变化。 */
    private int oldLineBaselineY = Integer.MIN_VALUE;

    /** 换行瞬间冻结的旧句已唱宽度，淡出时用它还原配色分界。 */
    private float oldLineSungWidth;

    /**
     * 上一帧主行的已唱宽度。
     *
     * <p>旧行淡出时必须用它重画「已唱 / 未唱」的分界，
     * 否则整行会以单一颜色消失，与它停留时的样子不符。
     */
    private float lastSungWidth;

    /** 上一帧主行的行宽，用于还原旧行的分界比例。 */
    private int lastLineWidth;

    /** 上一帧是否有副行，用于判断主行基线是否需要过渡。 */
    private boolean lastSecondaryPresent;

    /** 上次写抬升诊断日志的时刻，用于限流。 */
    private long diagLastNanos;

    /** 阶段标记，用于定位采集为何缺席。 */
    private void diagStage(String stage) {
        long now = System.nanoTime();
        if (now - diagStageNanos < DIAG_INTERVAL_NANOS) {
            return;
        }
        diagStageNanos = now;
        diagWrite("stage=" + stage + " alive=" + source.isAlive());
    }

    private long diagStageNanos;

    /** 追加一行到采集文件。每行独立打开关闭，避免进程被杀时丢缓冲。 */
    private static void diagWrite(String line) {
        try (java.io.FileWriter writer = new java.io.FileWriter(DIAG_FILE, true)) {
            writer.write(line);
            writer.write(System.lineSeparator());
        } catch (java.io.IOException ignored) {
            // 采集失败不能影响渲染
        }
    }

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
            if (DIAG_LIFT) {
                diagStage("timeline-empty");
            }
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
        updateLineTransition(index, frameDelta);
        float promote = lineTransitionProgress();

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

        // 副行优先取下一句，没有下一句时退回翻译。
        //
        // 取下一句是因为「接下来唱什么」比「当前句译文」更有阅读价值；
        // 而翻译在双行布局里会与主行重复信息，只作为兜底。
        String secondaryText;
        int secondaryColor;
        if (nextLine != null && !nextLine.text().isEmpty()) {
            secondaryText = clip(painter, nextLine.text(), maxWidth);
            secondaryColor = nextLineColor();
        } else if (line.hasTranslation()) {
            secondaryText = clip(painter, line.translation(), maxWidth);
            secondaryColor = translationColor();
        } else {
            secondaryText = "";
            secondaryColor = translationColor();
        }

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

        // 多行堆叠：把「当前行 + 后续若干行」当作一整块，块心固定在
        // centerTextY，逐行往下排。
        //
        // 先前是固定双行，且主行基线随「有没有副行」上下跳一行高，
        // 于是同一句歌词在有、无下一句之间会明显位移，看起来不像居中。
        boolean hasSecondary = !secondaryText.isEmpty();
        int totalLines = Math.max(1, this.style.lineCount());
        // 实际可画的行数受时间轴剩余行数限制，不能凭空补空行。
        int drawableLines = 1;
        for (int i = 1; i < totalLines; i++) {
            LyricLine upcoming = timeline.line(index + i);
            if (upcoming == null || upcoming.text().isEmpty()) {
                break;
            }
            drawableLines++;
        }
        // 行高必须用**副行真实的字号比例**算，不能用 NOT_CURRENT_SCALE。
        //
        // 副行是按 SUB_TEXT_FONT_SCALE(0.8) 绘制的，而 NOT_CURRENT_SCALE 是 0.95。
        // 用 0.95 算出来的块比实际内容高，块心虽然居中，
        // 但内容整体偏上——这正是「文字多行不居中」的来源。
        LineLayout.Block block = LineLayout.compute(centerTextY, drawableLines, 0,
                LINE_HEIGHT, LINE_GAP, SUB_TEXT_FONT_SCALE);

        // 行切换分两段：**旧主行先消失，其余行再上移一行补位**。
        //
        // 顺序不能反。若让两者同时进行，旧行一边淡出一边被顶走，
        // 中间那段半透明旧字会与上移中的新字擦身而过，混出一层脏色；
        // 而用户要的是「先让出一格，再整体顶上」，是先后关系而非同步。
        //
        // 位移方向也要注意：换行瞬间新的当前行还停在**下一行**的位置
        // （块顶往下偏一行），随后整块上移到块顶。偏移量从 +1 行收到 0，
        // 少了这一步符号判断，新行会从上方往下掉。
        // 淡出用绝对时长算，补位用剩余时长算。
        // 这样调慢切换动画只会拉长上移，不会让旧句黏在屏幕上。
        // 两段各自的归一化进度。
        //
        // 这里曾经有一个缺陷，就是「旧行消失瞬间先抬一行、
        // 后面的行错位一下才顶上」的根源：
        //
        // 上移进度从淡出结束才开始算（分子减去 OLD_LINE_FADE_MS），
        // 淡出那 0.1 秒里被 clamp01 压成 0，于是 blockShift 恒定等于一整行。
        // 整块在淡出阶段**静止地挂在「偏移一整行」的位置上**，
        // 后面的行凭空向下错一格，然后第二阶段才跳上来。
        //
        // 也就是说那 0.1 秒里，画面既不是旧状态也不是新状态，
        // 而是「所有行整体下移一行」的第三种错位状态。
        //
        // 正确的语义：上移从换行那一刻就开始，全程连续。
        // 旧行的淡出与它并行，互不干扰——旧行不属于这个块，
        // 它停在自己的位置上等自己消失，块里的行同时往上走，
        // 中途不会出现任何一格是凭空来的。
        long transitionNanos = lineTransitionNanos();
        long elapsedMs = lineTransitionElapsedNanos / 1_000_000L;
        float oldFade = transitionNanos <= 0L
                ? 1f
                : clamp01(elapsedMs / (float) Math.max(1L, OLD_LINE_FADE_MS));
        float risePart = transitionNanos <= 0L
                ? 1f
                : clamp01(elapsedMs / (float) Math.max(1L, transitionNanos / 1_000_000L));
        int blockShift = Math.round(blockLineStep()
                * (1f - cubicBezierEase(risePart)));
        // 旧行淡出也走共享缓动。
        //
        // 原先这里是纯线性：alpha 随时间匀速降到 0，而同一时刻
        // 其余行的上移走的是贝塞尔。同一段过渡里两种节奏并存，
        // 眼睛会看到「旧字还在匀速消失，新字已经加速就位」，像两件事。
        //
        // 现在只有一条曲线：{@code LyricEasing.ease}。
        // 淡出与补位的相对先后由各自的时间窗口保证，
        // 曲线的快慢由同一条参数决定，两者不会再打架。
        float oldFadeEased = LyricEasing.ease(oldFade);
        int oldLineAlpha = Math.round(255f * (1f - oldFadeEased));

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
                ? computeLifts(painter, charStartsOf(painter, visible),
                        visible.length(), primaryWidth, clampedSung,
                        fontSizeOf(primaryWidth, visible.length()),
                        charProgressOf(painter, line, visible, positionMs))
                : null;

        diagLift(visible, primaryWidth, clampedSung, lifts, visible.length());

        // 主行始终满不透明。
        //
        // 先前主行按 promote 从 0.3 淡到 1.0，同时旧行以相反的曲线淡出，
        // 两层画在同一位置上叠加。结果是换行后一秒内颜色与稳定状态不同：
        // 半透明的新字压在半透明的旧字上，蓝灰混出的中间色并不存在于设计中。
        // 这是「一秒内和一秒后颜色不一样」的直接原因。
        //
        // 现在是单层硬切：旧行不画，新行满色。切换瞬间仍由时间轴驱动，
        // 不存在两层叠加，颜色也就没有过渡期差异。
        // 主行满色，再乘上用户设定的整体不透明度。
        //
        // 整体不透明度作用于**所有**行（主行、后续行、间奏），
        // 因此在这里统一相乘；分散到各自路径很容易漏掉某一条
        // （例如降级绘制或间奏），表现为调低之后还有一行是亮的。
        float opacity = this.style.opacity();
        // 旧行画在它**换行前最后一帧所在的位置**，原地淡出。
        //
        // 不能锚在 block.baseline() - blockLineStep()：
        // 块是按当前 drawableLines 居中算的，换行后行数变化会让
        // block 整体重新居中，block.baseline() 本身已经跳了两行左右。
        // 在这个基线之上再减一行，旧行会跑到它原位置的上方一格
        // 凭空出现一次——这就是「旧主行在高一行位置闪一瞬间」。
        //
        // 也不能只锚在 block.baseline()：那是**新主行**的槽位，
        // 旧行画在那里会和新顶上来的句子重叠。
        //
        // 正确的量是 lastMainBaselineY：上一帧主行实际绘制用的 y。
        // 它是旧句真实停留过的地方，与当前帧的块几何无关，
        // 因此换行前后不会有任何跳变。
        int oldBaselineY = oldLineBaselineY;
        if (oldFade < 1f && oldLineText != null) {
            String old = clip(painter, oldLineText, maxWidth);
            if (!old.isEmpty()) {
                int oldWidth = painter.textWidth(old);
                int oldX = layoutLeft(this.style.align(), centerX, maxWidth, oldWidth);
                // 用旧行**最后呈现的配色**淡出：已唱段满色、未唱段压淡，
                // 分界沿用上一帧的已唱宽度。
                //
                // 先前整行都用 sungColor 画，于是亮色的已唱色会把整句刷成
                // 一片均匀的亮色再淡出，旧句在消失前会「亮一下」，
                // 与它停留时的样子不符。
                // 旧行淡出同样接受整体不透明度，否则调低后
                // 换行那一瞬旧句会比新句亮一截。
                float oldOpacity = (oldLineAlpha / 255f) * this.style.opacity();
                int oldSung = withAlpha(sungColor(),
                        Math.round(255f * oldOpacity));
                int oldUnsung = withAlpha(unsungColor(),
                        Math.round(255f * oldOpacity * UNSUNG_ALPHA));
                int boundary = Math.max(0,
                        Math.min((int) oldLineSungWidth, oldWidth));

                if (boundary > 0 && boundary < oldWidth
                        && !painter.supportsScissor()) {
                    // 不能裁剪时无法分区上色。此时退回单色，
                    // 但取**已唱色**而非未唱色：旧行唱过的部分通常更多，
                    // 用未唱色整行压淡会让它显得比实际更暗，像没跟上进度。
                    painter.drawText(old, oldX, oldBaselineY,
                            withAlpha(sungColor(), Math.round(255f * oldOpacity)));
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
        }

        // 主行越出块顶就不画：这是上移到底后旧行彻底让出位置的落点。
        boolean mainVisible = mainBaselineY >= blockTop - 1;
        if (mainVisible) {
            if (canScroll) {
                drawKaraokeLine(painter, visible, textX, mainBaselineY, clampedSung,
                        primaryWidth, viewportLeft, viewportRight, lifts, opacity,
                        scrolling, viewWidth, line, positionMs);
            } else {
                drawByWholeUnit(painter, line, visible, textX, mainBaselineY,
                        clampedSung, opacity);
            }
        }

        // 后续各行：不做逐字染色，字号统一收一档，同样走对齐与块布局。
        //
        // 这里不参与 promote 淡入。先前副行按 promote 混 alpha，
        // 换行后一秒内它一直是半透明的，颜色比稳定后暗一截，
        // 于是「同一句歌词在一秒内和一秒后颜色不同」。
        // 副行本来就是静态预览，没有淡入的必要。
        if (hasSecondary) {
            for (int i = 1; i < drawableLines; i++) {
                LyricLine upcoming = timeline.line(index + i);
                if (upcoming == null || upcoming.text().isEmpty()) {
                    break;
                }
                String text = clip(painter, upcoming.text(), maxWidth);
                if (text.isEmpty()) {
                    continue;
                }
                // 左边界按**缩放后宽度**算，因为 drawTextScaled 以 (x, y)
                // 为原点缩放，缩放后文字实际占据 [x, x + width*scale]。
                //
                // 若用未缩放宽度居中，居中算出来的左边会偏左，
                // 而副行实际更窄，于是它的中心比主行偏左
                // (width - width*0.8)/2 像素。行数越多越明显，
                // 看起来就是「多行歌词不居中」。
                int width = painter.textWidthScaled(text, SUB_TEXT_FONT_SCALE);
                int x = layoutLeft(this.style.align(), centerX, maxWidth, width);
                int y = LineLayout.lineTop(block, i, 0, LINE_HEIGHT, LINE_GAP,
                        SUB_TEXT_FONT_SCALE) + blockShift;
                // 必须显式传 shadow：四参 drawTextScaled 会转调四参 drawText，
                // 而平台层的四参 drawText 把阴影写死为 true。
                // 于是切换阴影时副行不跟随，看起来只有主行在变。
                // 副行同样走原版加粗。
                //
                // 先前副行不叠字重，理由是「缩放后偏移会变成 0.8 像素、
                // 落进纹素之间产生颗粒」。改用 §l 后这个顾虑消失：
                // 原版加粗由字体管线在度量阶段处理，不是像素位移，
                // 缩放多少都不会引入半点偏移。
                Weight w = this.style.weight();
                painter.drawTextScaled(stylePrefix(w) + text + styleSuffix(w),
                        x, y, SUB_TEXT_FONT_SCALE, nextLineColor(),
                        style.shadow());
            }
        }

        if (scaling) {
            painter.pose().popPose();
        }

        lastSecondaryPresent = hasSecondary;
        lastMainText = fullText;
        // 必须记录**实际绘制**用的 mainBaselineY，不能记 block.baseline()。
        //
        // block.baseline() 是不含切换偏移的稳定基线。换行前若干帧
        // 主行仍在上一轮过渡的位移中（blockShift 尚未归零），
        // 实际画在 mainBaselineY，与稳定基线相差最多一整行。
        // 记稳定基线的话，旧行会从「主行真正停留的地方」跳到
        // 「本该去的地方」，跳变恰好等于残留的 blockShift——
        // 实测过：主行显示在 213，旧行却出现在 202，差 11px。
        lastMainBaselineY = mainBaselineY;
        // 记住分界，供下一帧的旧行淡出还原配色。
        lastSungWidth = clampedSung;
        lastLineWidth = primaryWidth;
        return true;
    }

    /**
     * 更新行切换动画状态。
     *
     * <p>仅在行索引变化时重新开始计时；同一行内不动，
     * 否则每帧都会重置，动画永远走不完。
     */
    /** 当前是否处在换行过渡中（淡出或补位尚未结束）。 */
    private boolean inTransition() {
        return lineTransitionElapsedNanos < lineTransitionNanos();
    }

    private void updateLineTransition(int index, long frameDeltaNanos) {
        if (index == lineTransitionFromIndex) {
            if (lineTransitionElapsedNanos < lineTransitionNanos()) {
                lineTransitionElapsedNanos += Math.max(0L, frameDeltaNanos);
            }
            return;
        }
        lineTransitionFromIndex = index;
        lineTransitionElapsedNanos = 0L;
        // 换行这一刻，把上一句的内容与位置打包冻结。
        //
        // 必须在这里取快照，不能在帧末按「是否过渡中」判断：
        // 帧末执行时本帧已经切到新 index，lastMainText 正要被覆盖成
        // **新句子**；而换行前最后一帧的 index 还是旧的，
        // 帧末判断读到的是上一轮残留的过渡状态，取值时机根本不对。
        //
        // 之前因此出现过两种症状：旧行闪一帧就没了（读到新句子，
        // 与主行内容相同看不出是旧行），或者旧行跑到上方一行
        // （用当前块几何去推旧位置）。在换行瞬间取值两者都不会发生。
        oldLineText = lastMainText;
        oldLineBaselineY = lastMainBaselineY;
        oldLineSungWidth = lastSungWidth;
    }

    /**
     * 一行的纵向步长：行高加行距。
     *
     * <p>切换位移必须与 {@link LineLayout} 用的步长一致，
     * 否则「上移一行」会差几个像素，副行会逐渐偏离补偿后的位置。
     */
    private static int blockLineStep() {
        return LINE_HEIGHT + LINE_GAP;
    }

    /**
     * 旧主行淡出的固定时长（毫秒）。
     *
     * <p>不用「占切换时长的比例」：切换时长可由用户调节，
     * 比例式会把淡出一起拉长，调慢过渡时旧句会长时间蒙着不散。
     * 淡出只负责让位，固定 0.1 秒即可，快到几乎察觉不到。
     */
    private static final long OLD_LINE_FADE_MS = 100L;

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 行切换的线性进度 0→1；已经走完时恒为 1。 */
    private float lineTransitionRaw() {
        long duration = lineTransitionNanos();
        // 时长设为 0 表示不要过渡动画，直接切到新行。
        if (duration <= 0L) {
            return 1f;
        }
        float raw = lineTransitionElapsedNanos / (float) duration;
        return raw >= 1f ? 1f : Math.max(0f, raw);
    }

    /** 行切换进度 0→1（已缓动）；已经走完时恒为 1。 */
    private float lineTransitionProgress() {
        return cubicBezierEase(lineTransitionRaw());
    }

    /** 按行切换缓动求值：{@code CubicBezierEasing(0.25, 0.0, 0.2, 1.0)}。 */
    private static float cubicBezierEase(float t) {
        return LyricEasing.ease(t);
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
        float minScroll = -(lineWidth - viewWidth);
        if (minScroll >= 0f) {
            return 0f;
        }
        float half = viewWidth / 2f;
        if (sungWidth <= half) {
            return 0f;
        }
        return Math.max(minScroll, Math.min(0f, half - sungWidth));
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

        // 间奏提示可配：空白 / ··· / 间奏。
        // 空白档直接不出画，但要保留 lastLineIndex 的重置语义，
        // 否则间奏结束后首帧会沿用上一行的进度。
        String text = this.style.interlude().text;
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

    /**
     * 绘制一整行卡拉 OK 歌词。
     *
     * <p>两层真实颜色：整行先以未唱色铺满，再把已唱段用已唱色重画一遍。
     * 两层共用同一个 {@code textX} 与同一份抬升表，只有裁剪区不同，
     * 因此像素严格重合，得到的是真正的**变色**而非「蒙一层灰」。
     *
     * <p>Salt Player 本身用的是另一种做法：整行画一次，再用
     * {@code Color.copy(Black, alpha)} 把未唱段压暗。它之所以如此，
     * 是因为 Compose 的 {@code Text} 必须一次画完才能局部覆盖，
     * 拿不到「同一串字分两段上色」的能力。Minecraft 的 HUD 可以
     * 用两次 {@code drawString} 配裁剪区做到，所以这里选择真正的变色。
     *
     * <p>关键在于**两层不得各自位移**：先前换行时旧行下滑、新行上升，
     * 两种颜色在中途对穿，那才是重影的成因，与层数无关。
     */
    private void drawKaraokeLine(Painter painter, String visible, int textX, int textY,
                                 float sungWidth, int lineWidth,
                                 int viewportLeft, int viewportRight, float[] lifts,
                                 float opacity, boolean scrolling, int viewWidth,
                                 LyricLine line, long positionMs) {
        int alpha = Math.round(255f * opacity);
        // 未唱部分整体压到 UNSUNG_ALPHA：没唱到的字更淡，唱到的字满色。
        // 羽化带在两者之间插值，alpha 通道也会平滑过渡，因此交界处不会突兀。
        int unsungColor = withAlpha(unsungColor(),
                Math.round(alpha * UNSUNG_ALPHA));
        int sungColor = withAlpha(sungColor(), alpha);

        int viewRight = Math.min(viewportRight, viewportLeft + viewWidth);

        // 逐字着色：每个字按**它自己的**染色进度在两种颜色之间插值。
        //
        // 不再用「裁剪边界」来分色。裁剪区只能落在整像素上，
        // 而字符只有 9 像素宽，于是分色边界最多只有 9 个台阶：
        // 表现为边界先卡在一个整数上好几天（实测多帧裁剪区完全相同），
        // 然后整像素跳一格——就是「先渲染一列像素，后面一瞬间渲染完」。
        // 无论怎么加过渡带都绕不开这个量化下限。
        //
        // 改成逐字取色后，颜色是连续量：同一帧内每个字按自己的进度
        // 取不同的中间色，进度每前进 0.01，整行所有正在染的字都会微调。
        // 字内部分用字符宽度的细分段表达，让一个 9 像素的字内部
        // 也能有渐变，而不是整字跳色。
        float fontSizePx = fontSizeOf(lineWidth, Math.max(1, visible.length()));
        float band = Math.max(1f, fontSizePx * FEATHER_RATIO);
        float[] progress = charProgressOf(painter, line, visible, positionMs);

        // 前沿的浮点位置（屏幕坐标）。亚像素信息全部保留在这里：
        // 它每帧都在动，条带颜色因此每帧都在变，
        // 不受裁剪只能整像素的限制。
        float edge = textX + sungWidth;

        // 没有逐字偏移表时退回整段绘制。
        //
        // 逐字着色必须知道每个字的抬升量；若 {@code lifts} 为 null
        // （行太长、或字号退化算不出幅度），就说明这批数据不值得逐字处理，
        // 此时仍按字拆开画只是把一次 drawText 拆成几十次，
        // 换来零收益。整行一次画完，颜色取整行平均进度。
        if (lifts == null) {
            drawWholeLineColored(painter, visible, textX, textY,
                    viewportLeft, viewRight, sungColor, unsungColor);
            return;
        }

        // 逐字一次绘制，颜色是左右两端的一对色。
        //
        // 关键变化：**不再按条带重画同一个字**。
        // 旧做法是「先铺底色，再叠 1~3 条竖带」，一个字画 2~4 次，
        // 每次都要切裁剪区。裁剪打断批次合并，是性能主成本；
        // 而且裁剪边界只能整像素，彩色前沿因此有 9 个台阶。
        //
        // 现在前沿位置完全交给顶点：一个字画一次，左两顶点给已唱色、
        // 右两顶点给未唱色，GPU 逐像素插值。绘制次数从每字 2~4 次
        // 降到 1 次，裁剪切换降到 0 次，前沿仍可落在任意亚像素位置。
        int cursor = 0;
        for (int i = 0; i < visible.length(); i++) {
            String ch = visible.substring(i, i + 1);
            int charX = textX + cursor;
            int charWidth = painter.textWidth(ch);
            cursor += charWidth;
            // 该字的右端（屏幕坐标）。先前这里写成 cursor + charWidth，
            // 那是**相对文本起点**的坐标，却拿去和屏幕坐标的 viewportLeft 比较，
            // 于是所有字都被判定为「在视口左侧之外」而整行跳过，
            // 表现为主行完全不绘制。
            int charRight = charX + charWidth;

            // 该字完全在视口外就不画，但游标必须照常推进。
            if (charRight <= viewportLeft || charX >= viewRight) {
                continue;
            }

            // 端点色由该字与前沿的相对位置决定：
            // 完全在前沿左边 = 整字已唱；完全在右边 = 整字未唱；
            // 跨越前沿 = 左已唱右未唱，插值把前沿画在字内正确位置。
            GlyphColors colors = GradientColorizer.forChar(
                    charX, charRight, edge, sungColor, unsungColor);

            drawGlyph(painter, ch, charX, textY, lifts, i,
                    viewportLeft, viewRight, colors);
        }
    }

    /**
     * 整行绘制并按已唱宽度分两段上色。
     *
     * <p>用于没有逐字偏移表的情况：只用一次（或两次）drawText
     * 表达整行的染色进度，而不是按字拆成几十次。
     */
    private void drawWholeLineColored(Painter painter, String visible,
                                       int textX, int textY,
                                       int viewportLeft, int viewRight,
                                       int sungColor, int unsungColor) {
        int lineWidth = painter.textWidth(visible);
        int solidWidth = Math.max(0,
                Math.min((int) Math.floor(sungWidth), lineWidth));
        // 未唱色铺满整行。
        drawColorLayer(painter, visible, textX, textY, null,
                viewportLeft, viewRight, textX, lineWidth, unsungColor);
        if (solidWidth > 0) {
            drawColorLayer(painter, visible, textX, textY, null,
                    viewportLeft, viewRight, textX, solidWidth, sungColor);
        }
    }

    /**
     * 绘制单个字，支持左右两端不同色。
     *
     * <p>视口裁剪仍然存在，但它只处理「字被屏幕边缘切掉」这一种情况，
     * 与染色无关：染色前沿不再依赖裁剪。因此绝大多数帧里
     * {@code full} 为 true，一次裁剪都不会开。
     *
     * <p>平台不支持渐变时退回单色（取左端色），功能降级但不崩。
     * 同色时也走单色路径，省掉自定义顶点的开销。
     */
    private void drawGlyph(Painter painter, String ch, int charX, int textY,
                           float[] lifts, int index,
                           int viewportLeft, int viewRight, GlyphColors colors) {
        int charWidth = painter.textWidth(ch);
        int left = Math.max(charX, viewportLeft);
        int right = Math.min(charX + charWidth, viewRight);
        if (right - left <= 0) {
            return;
        }
        float lift = lifts == null ? 0f : lifts[index];

        // 同色不需要顶点着色，走原版路径最省。
        if (!colors.isGradient()) {
            boolean full = left <= charX && right >= charX + charWidth;
            if (!full) {
                painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                        painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
            }
            drawTextLiftedStyled(painter, ch, charX, textY, lift, colors.left());
            if (!full) {
                painter.disableScissor();
            }
            return;
        }

        // 左右异色：交给平台的逐顶点着色。
        //
        // 抬升量原样传 float，绝不取整。
        // 顶点写入路径本身就是浮点的（addVertex 接收 float 坐标），
        // 中间取整会把连续位移压成整数台阶，这是上浮僵硬的直接原因。
        //
        // 视口裁剪仍然要开，但它此时**只服务于屏幕边界**：
        // 滚动行会把字推到面板之外，不裁剪就会画到 HUD 其他元素上。
        // 与旧方案的区别在于裁剪不再承担分色职责——
        // 染色前沿由顶点插值决定，因此裁剪的整像素限制不再影响颜色过渡。
        boolean full = left <= charX && right >= charX + charWidth;
        if (!full) {
            painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                    painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
        }

        // 加粗仍然通过样式符表达，而不是在平台层重复绘制：
        // 单字包一层 §l…§r 看似碎片化，但这一条路径上本来就一次只画
        // 一个字，包裹不会让其他文本变碎。不这么做的话，
        // 落在前沿上的那个字会变成常规字重，与同行其余字不一致。
        Weight w = this.style.weight();
        String styled = w == Weight.REGULAR ? ch : stylePrefix(w) + ch + styleSuffix(w);
        painter.drawGlyphGradient(styled, charX, textY, lift, colors,
                this.style.shadow());

        if (!full) {
            painter.disableScissor();
        }
    }

    /** 整字绘制，只受视口约束。 */
    private void drawChar(Painter painter, String ch, int charX, int textY,
                          float[] lifts, int index,
                          int viewportLeft, int viewRight, int color) {
        int charWidth = painter.textWidth(ch);
        int left = Math.max(charX, viewportLeft);
        int right = Math.min(charX + charWidth, viewRight);
        if (right - left <= 0) {
            return;
        }
        boolean full = left <= charX && right >= charX + charWidth;
        if (!full) {
            painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                    painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
        }
        if (lifts == null) {
            drawTextStyled(painter, ch, charX, textY, color);
        } else {
            drawTextLiftedStyled(painter, ch, charX, textY, lifts[index], color);
        }
        if (!full) {
            painter.disableScissor();
        }
    }

    /**
     * 画单个字符的一条竖带。
     *
     * <p>颜色已经是插值结果，这里只负责把它限制在屏幕内的窄条上。
     * 字符只有一个，裁剪区也只有一个条带，因此绘制量恒定。
     */
    private void drawSlice(Painter painter, String ch, int charX, int textY,
                           float[] lifts, int index,
                           int viewportLeft, int viewRight,
                           int clipLeft, int clipWidth, int color) {
        int left = Math.max(clipLeft, viewportLeft);
        int right = Math.min(clipLeft + clipWidth, viewRight);
        if (right - left <= 0) {
            return;
        }
        // 条带覆盖整个字宽时不必开裁剪，省一次状态切换。
        int charWidth = painter.textWidth(ch);
        boolean full = left <= charX && right >= charX + charWidth;
        if (!full) {
            painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                    painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
        }
        if (lifts == null) {
            drawTextStyled(painter, ch, charX, textY, color);
        } else {
            drawTextLiftedStyled(painter, ch, charX, textY, lifts[index], color);
        }
        if (!full) {
            painter.disableScissor();
        }
    }

    /**
     * 用指定颜色画一层文字，只显示 {@code [clipLeft, clipLeft + clipWidth)} 这一段。
     *
     * <p>两层必须传同一个 {@code textX} 和同一份 {@code lifts}：
     * 坐标一致才能保证两种颜色严丝合缝地贴在同一串字形上。
     * 裁剪区先夹进视口，滚动时文本伸到屏幕外的部分不会画出去。
     */
    private void drawColorLayer(Painter painter, String visible, int textX, int textY,
                                float[] lifts, int viewportLeft, int viewRight,
                                int clipLeft, int clipWidth, int color) {
        int left = Math.max(clipLeft, viewportLeft);
        int right = Math.min(clipLeft + clipWidth, viewRight);
        if (right - left <= 0) {
            return;
        }
        // 只有需要真正裁掉一部分时才开裁剪；整行可见时省一次状态切换。
        boolean needScissor = left > textX || right < textX + painter.textWidth(visible);
        if (needScissor) {
            // 裁剪高度必须容得下阴影。
            //
            // 原先按 LINE_HEIGHT(10) 算出一个固定 16 像素的框，
            // 英文字形低、阴影恰好落在框内，看着正常；
            // 中文由 fallback 字体提供，字形更高，阴影沉到框外被裁掉，
            // 于是出现「英文有阴影、中文没有」。这里改为按实际行高加足余量。
            painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                    painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
        }
        if (lifts == null) {
            drawTextStyled(painter, visible, textX, textY, color);
        } else {
            int cursor = 0;
            for (int i = 0; i < visible.length(); i++) {
                String ch = visible.substring(i, i + 1);
                drawTextLiftedStyled(painter, ch, textX + cursor, textY, lifts[i], color);
                cursor += painter.textWidth(ch);
            }
        }
        if (needScissor) {
            painter.disableScissor();
        }
    }

    /**
     * 采集真实渲染坐标，供离线校验抬升参数。
     *
     * <p>只在 {@link #DIAG_LIFT} 打开时写日志。记的是每帧的
     * 已唱宽度、行宽与逐字偏移，拿它反推实际波长与波幅是否吻合。
     */
    private void diagLift(String lineText, int lineWidth, float sungWidth,
                          float[] lifts, int charCount) {
        if (!DIAG_LIFT) {
            return;
        }
        long now = System.nanoTime();
        if (now - diagLastNanos < DIAG_INTERVAL_NANOS) {
            return;
        }
        diagLastNanos = now;

        StringBuilder sb = new StringBuilder(160);
        sb.append("w=").append(lineWidth)
                .append(" sung=").append(Math.round(sungWidth))
                .append(" n=").append(charCount)
                .append(" wave=").append(String.format("%.1f",
                        (charCount > 0 ? (float) lineWidth / charCount : 0f)
                                * LIFT_WAVE_FONT_RATIO))
                .append(" max=").append(String.format("%.2f",
                        (charCount > 0 ? (float) lineWidth / charCount : 0f)
                                * LIFT_FONT_RATIO))
                .append(" lifts=[");
        if (lifts != null) {
            for (int i = 0; i < lifts.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format("%.2f", lifts[i]));
            }
        }
        sb.append("] text=").append(lineText);
        diagWrite(sb.toString());
    }

    /**
     * 逐字起始横坐标（相对文本左端）。
     *
     * <p>逐字累加真实宽度得到，不能用「行宽 ÷ 字数」平均分摊：
     * 中英混排时数字与字母都窄于汉字，平均分会让偏移与字形错位，
     * 抬起的字会看着偏移了一截。
     */
    private static int[] charStartsOf(Painter painter, String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }
        int[] starts = new int[text.length()];
        int cursor = 0;
        for (int i = 0; i < text.length(); i++) {
            starts[i] = cursor;
            cursor += painter.textWidth(text.substring(i, i + 1));
        }
        return starts;
    }

    /**
     * 由行宽与字数估出字号。
     *
     * <p>MC 里中文字宽约等于字号，故「行宽 ÷ 字数」就是字号的良好近似。
     * 抬升幅度与过渡带宽度都以字号为基准，必须拿到这个值。
     *
     * <p>估算而非读取真实字号：{@code Painter} 是跨平台的极薄适配层，
     * 不希望为了一个比例值把字体对象泄漏到渲染逻辑里。字形被等比缩放时
     * 行宽与字数同步变化，比值仍成立。
     */
    private static float fontSizeOf(int lineWidth, int charCount) {
        if (charCount <= 0) {
            return 0f;
        }
        return lineWidth / (float) charCount;
    }

    /**
     * 计算每个字中心处的抬升系数。
     *
     * <p>形状取自 Salt Player 的卡拉 OK 逐字上浮（其 {@code LyricsLineLayoutResult}）：
     * 与演唱进度绑定的不是「每个字各自升起再落下」，而是**一条横跨整行的斜坡**。
     * 已唱满的字恒在最高位，未唱到的字恒在基线，两者之间用一条余弦软过渡带连接，
     * 过渡带永远跟着演唱点走。
     *
     * <p>反编译出的原始式子（{@code f18} 即归一化到过渡带内的相位）：
     * <pre>
     *   t    = (字中心 - 前沿 + 半带宽) / 带宽
     *   k    = t &lt;= 0 ? 1 : (t &gt;= 1 ? 0 : 0.5 + 0.5·cos(π·t))
     *   lift = k · round(字号 × 0.1)
     * </pre>
     * 两端梯度都为 0，所以前沿推进时字不会在起落处「弹」一下；
     * 若换成线性斜坡，两端的折角在浅色字上很明显。
     *
     * <p>原实现另有一段按行尾剩余距离压平斜坡的收尾（{@code edge}），
     * 但驱动它的局部变量在字节码里未被赋值，语义无法确证；按其可见形式
     * 代入会在行尾算出负系数，即把字往下压。这里不复刻，改用更确定的规则：
     * 整行唱完时全部取满，未唱完时斜坡照常延伸。
     *
     * <p>返回 {@code null} 表示不值得逐字绘制（行太长或数据退化为整行），
     * 此时调用方应退回整段绘制，省掉几十次绘制调用。
     */
    static float[] computeLifts(Painter painter, int[] charStarts, int charCount,
                                int lineWidth, float sungWidth, float fontSizePx,
                                float[] charProgress) {
        if (!PER_CHAR_LIFT || charCount <= 1 || charCount > MAX_LIFT_CHARS) {
            return null;
        }
        if (fontSizePx <= 0f || LIFT_FONT_RATIO <= 0f) {
            return null;
        }

        // 幅度**不取整**。
        //
        // 曾经对这里取整，理由是「整像素位移不会让字形落在半像素上」。
        // 但 MC 默认字号 9px × 0.1 = 0.9，取整后变成 1，
        // 随后绘制端又对 1×进度 再取整一次，结果整条曲线只剩 {0,1} 两档：
        // 一句 3 秒的歌，字在前 1.5 秒完全不动，然后跳 1px 再不动。
        // 这才是「上浮僵硬」的真实原因，与缓动曲线无关。
        //
        // Salt 的 floatUpPercentage 全程走 float，中间没有 round，
        // 它的位移每帧都是不同的值。这里保持一致。
        float maxOffset = fontSizePx * LIFT_FONT_RATIO;
        if (maxOffset <= 0f) {
            return null;
        }
        // 没有「整行唱完就全部置满」的短路。
        //
        // 曾经按已唱宽度判定：宽度到达行宽就认为唱完，直接把每个字抬满。
        // 但宽度是**累加**值，行级数据和逐字混合时容易出现宽度先到、
        // 而个别字的进度尚未走完的情况，此时短路的置满会盖掉字级差异，
        // 表现为「颜色没染完字已经升满」。
        // 上浮现在完全由每字进度决定，不需要宽度参与判定。

        // 上浮与染色**同步**：每个字的抬升量直接由它自己的染色进度决定。
        //
        // Salt 的行为是「逐字染色上升」，两者是同一个量：
        // 字被染到多少，就升起多少；完全染完时正好升到顶点。
        // 因此这里不引入任何额外的时间常数或缓动——
        // 染色进度本身已经是时间的函数，再叠一层会让两者错位：
        // 颜色已经铺满而字还没升到位，或者字升满了颜色却还在走。
        //
        // 先前用过两种错误做法：空间余弦斜坡（前沿扫过一个字只占波宽 1/3，
        // 上浮快到看不见），以及固定时长的独立缓动（与染色不同步，
        // 唱得慢时字已升满而颜色还在推进）。
        float[] lifts = new float[charCount];
        for (int i = 0; i < charCount; i++) {
            lifts[i] = maxOffset * charProgress[i];
        }
        return lifts;
    }

    /**
     * 每个字各自的染色进度 0..1。
     *
     * <p>直接取词单元自身的 {@code progressAt}，并套用与染色相同的
     * {@link #sustainCurve}。上浮必须与染色用同一条曲线，
     * 否则字的升起速度与颜色铺开速度不一致，两者会视觉上脱节。
     *
     * <p>行级数据（没有逐字时间）按行内时间比例线性推进，
     * 与 {@code targetWidthAt} 的处理保持一致。
     */
    private float[] charProgressOf(Painter painter, LyricLine line, String visible,
                                   long positionMs) {
        int count = visible.length();
        float[] progress = new float[count];
        if (count == 0) {
            return progress;
        }

        List<LyricWord> words = line.words();
        if (words.size() <= 1) {
            float ratio = line.progressAt(positionMs);
            float value = ratio <= 0f ? 0f : (ratio >= 1f ? 1f : ratio);
            java.util.Arrays.fill(progress, value);
            return progress;
        }

        int index = 0;
        for (LyricWord word : words) {
            if (index >= count) {
                break;
            }
            String wordText = word.text();
            if (wordText.isEmpty()) {
                continue;
            }
            int available = Math.min(wordText.length(), count - index);
            float ratio = word.progressAt(positionMs);
            float value;
            if (positionMs >= word.endMs()) {
                value = 1f;
            } else if (positionMs <= word.startMs()) {
                value = 0f;
            } else {
                value = sustainCurve(ratio);
            }
            // 一个字单元通常对应一个字符；跨多字符时同进同退。
            for (int k = 0; k < available; k++) {
                progress[index + k] = value;
            }
            index += available;
        }
        return progress;
    }

    /**
     * 更新并返回前沿推进速度（像素/秒）。
     *
     * <p>用相邻两帧的已唱宽度差除以帧间隔。数值做上下限夹紧：
     * 下限定得较高，避免停顿（长音、暂停）时速度趋近 0，
     * 导致「已过去的像素 ÷ 极慢速度」算出巨大的经过时间，
     * 字会立刻弹到顶点；上限则防止拖动进度条造成的一次性跳变。
     */
    /**
     * 上浮缓动曲线。
     *
     * <p>与行切换、染色共用 {@link LyricEasing}，不再单独用 {@code 1-(1-t)³}。
     *
     * <p>原先三条动画各用一条曲线，在 t=0.5 处输出分别为
     * 行切换约 0.60、上浮 0.875、染色 0.625。
     * 也就是说同一个时刻，字已经升到 87% 而颜色只走到 62%：
     * 视觉上表现为「字先窜上去，颜色慢慢追」，即使每帧都在更新也显得脱节。
     * 统一到一条曲线后，三者同步推进。
     *
     * @param t 0 表示刚开始上升，1 表示已到顶；越界会被夹紧
     */
    static float liftEase(float t) {
        return LyricEasing.ease(t);
    }

    /** 副行的实际行高：按 SubTextFontSizeScale 缩放后取整。 */
    private static int secondaryLineHeight() {
        return Math.round(LINE_HEIGHT * SUB_TEXT_FONT_SCALE);
    }

    /**
     * 按当前字重绘制一段文本。
     *
     * <p>原版字体只有一种笔画粗细，没有中/粗体字库可切。
     * 这里用「同色偏移叠加」模拟：把同一串字在几个方向各画一遍，
     * 笔画边缘被反复覆盖显得更实，视觉上是加粗。
     *
     * <p>代价是边缘略糊、绘制次数随层数增加，因此层数控制在 1/3/5 三档。
     */
    private void drawTextStyled(Painter painter, String text, int x, int y, int color) {
        Weight w = this.style.weight();
        painter.drawText(stylePrefix(w) + text + styleSuffix(w), x, y, color,
                style.shadow());
    }

    private void drawTextLiftedStyled(Painter painter, String text, int x, int y,
                                      float lift, int color) {
        Weight w = this.style.weight();
        // 单个字符不必逐个包 §l…§r。
        //
        // 逐字绘制时若每个字都包一层，屏幕上拼出来的文本序列会是
        // 「§l一§r§l二§r」这样的碎片。虽然可见结果正确，
        // 但任何按文本内容做的判断（日志、测试、无障碍朗读）都会失效。
        // 加粗作用于整个字形串时只需一次；单字本身也无从「延续」样式，
        // 因此这里只在多字时包一层，单字直接用裸文本。
        String body = text.length() <= 1
                ? text
                : stylePrefix(w) + text + styleSuffix(w);
        painter.drawTextLifted(body, x, y, lift, color, style.shadow());
    }



    /**
     * 字重叠加使用的偏移集合：只取**圆环**上的点，不含内部实心格。
     *
     * <p>先前用完整的 (2r+1)² 方格。方格会把字形沿每个方向各外扩一圈，
     * 横向笔画因此上下各长一像素、纵向笔画左右各长一像素，
     * 相邻笔画之间的空隙被填掉，于是笔画粘连、笔画粗细也失去区分。
     *
     * <p>圆环只保留「离中心不超过半径」的外缘点，笔画只向四周扩出一像素，
     * 不至于把空隙糊住。中心点（0,0）始终包含，保证字形本身不被稀释。
     *
     * <p>MC 的字形是位图，偏移必须整像素：任何半点偏移都会落到纹素之间，
     * 触发线性过滤产生颗粒和锯齿。
     */
    /**
     * 字重对应的格式化前缀。
     *
     * <p>MC 的 {@code Style} 自带 {@code isBold()}，字体管线在
     * {@code Font.prepareText} 里对每个字形读取
     * {@code glyphInfo.getBoldOffset()} 并据此计算笔画宽度；
     * {@code splitter} 也用 {@code info().getAdvance(bold)} 取宽度，
     * 所以加粗**连字距都是正确的**。
     *
     * <p>先前我们自己按像素偏移重复绘制同一字形来「模拟」加粗，
     * 那是错误层级上的做法：绕过了字体管线的度量，只能得到错位的副本，
     * 既不改字距也不改字形，只会出现重影和颗粒。
     *
     * <p>用 {@code §l} 开启加粗后必须补 {@code §r} 复位，
     * 否则样式会渗到后续文本，整行都变粗。
     */
    private static String stylePrefix(Weight weight) {
        return weight == Weight.REGULAR ? "" : "\u00a7l";
    }

    /** 复位后缀；无样式时为空串。 */
    private static String styleSuffix(Weight weight) {
        return weight == Weight.REGULAR ? "" : "\u00a7r";
    }



    /** 第 i 个字占用的宽度；末字取到行尾。 */
    private static float charWidthAt(int[] charStarts, int index, int lineWidth) {
        if (index + 1 < charStarts.length) {
            return Math.max(0f, charStarts[index + 1] - charStarts[index]);
        }
        return Math.max(0f, lineWidth - charStarts[index]);
    }

    /** 不支持裁剪时的降级：按整单元覆盖，至少保证逐字推进可见。 */
    private void drawByWholeUnit(Painter painter, LyricLine line, String visible,
                                 int textX, int textY, float sungWidth, float opacity) {
        // 无论唱到哪，整行都要先以歌词色画出来。
        //
        // 单层架构靠蒙版压暗未唱区，而这条分支的平台没有裁剪能力、
        // 也就垫不上蒙版；若这里不整行画一次，未唱的字会完全消失，
        // 歌词变成「一个字一个字凭空冒出来」。
        int textColor = withAlpha(sungColor(), Math.round(255f * opacity));
        // 显式传 shadow：四参 drawText 在平台层把阴影写死为 true，
        // 关闭阴影后这条路径仍会画出阴影，表现为「关了但是没关」。
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
     * 单个演唱单元的进度曲线。
     *
     * <p>线性映射的问题是长音听感发滞：字刚开口时高亮几乎不动，
     * 用户会觉得「没跟上」，而字尾又拖得过于均匀。
     *
     * <p>这里用 {@code p(2.5 - 2.5p + p²)}：媒体时间走到 9% 时宽度已到 20%，
     * 走到 26% 时到 50%，末端斜率仍保留 0.5。
     * 也就是先冲出去、再放缓收尾，与开口发音的能量包络一致。
     *
     * <p>**只用于真正的逐字数据**。行级数据是按时间比例合成的，
     * 给它套曲线只会让合成痕迹更明显。
     */
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
        long extrapolated = clock.positionMs();
        long lead = uplinkPeriodMs + LEAD_MARGIN_MS;
        // 上限锚在「收到该上报值的时刻」上，而不是固定跟着上报值走。
        // 若写成 reportedMs + lead，位置一旦追上上限就再无前进余地，
        // 高亮会停住等下一次上报——这正是「一秒一跳、中间停顿」的来源。
        // 让上限随本地时间一起推进，整个上报周期内都能连续前进。
        long ceiling = reportedMs + lead + msSinceReport();
        long result = extrapolated;
        if (extrapolated > ceiling) {
            result = ceiling;
            clock.anchor(ceiling, lastPlaying);
        } else if (extrapolated < reportedMs) {
            result = reportedMs;
        }

        // 播放中位置只允许前进。轮询抖动造成的上报回落不能直接采信，
        // 否则高亮会倒退闪烁；但大跨度回落（切行、seek、换曲）必须放行，
        // 不然会永久卡在旧行上。
        if (lastPlaying && result < lastRenderedPositionMs) {
            long regression = lastRenderedPositionMs - result;
            if (regression <= lead) {
                return lastRenderedPositionMs;
            }
        }
        lastRenderedPositionMs = Math.max(0L, result);
        return lastRenderedPositionMs;
    }

    /** 距最近一次收到位置变化的本地时长（毫秒）。 */
    private long msSinceReport() {
        if (lastReportChangeNanos == 0L) {
            return 0L;
        }
        long elapsed = (System.nanoTime() - lastReportChangeNanos) / 1_000_000L;
        return Math.max(0L, Math.min(elapsed, MAX_UPLINK_PERIOD_MS + LEAD_MARGIN_MS));
    }

    /**
     * 用最新快照校准时钟。
     *
     * <p>锚点取「上报值 + 已流逝的等待时间」，而不是把上报值锚到当前时刻。
     * 上报值反映的是它被采集时的位置，之后还要经过轮询间隔才被读到，
     * 直接锚到当前时刻会引入一段固定滞后，外推就永远赶不上真实进度。
     */
    private void syncClock(long positionMs, boolean playing) {
        long now = System.nanoTime();

        if (!clockPrimed || playing != lastPlaying) {
            clock.anchor(positionMs, playing);
            clockPrimed = true;
            lastReportChangeNanos = now;
            // 必须同时记录该位置，否则第二帧会把「同一次上报」误判为变化，
            // 再次重锚并刷新计时，上报周期永远测不到，外推上限也就一直不放开。
            lastReportedPositionMs = positionMs;
            lastPlaying = playing;
            return;
        }

        boolean positionChanged = positionMs != lastReportedPositionMs;
        if (positionChanged) {
            // 观测上报周期：同一份位置会连续返回多次，只在变化的那次计入
            if (lastReportChangeNanos != 0L) {
                long observedMs = (now - lastReportChangeNanos) / 1_000_000L;
                if (observedMs >= MIN_UPLINK_PERIOD_MS && observedMs <= MAX_UPLINK_PERIOD_MS) {
                    // 平滑更新，避免单次抖动带偏上限
                    uplinkPeriodMs = (uplinkPeriodMs * 3 + observedMs) / 4;
                }
            }
            lastReportChangeNanos = now;
            lastReportedPositionMs = positionMs;

            // 补偿上报值自身的陈旧时间：它至多已过去一个轮询间隔。
            // 只在播放中补偿——暂停时位置本就不动，加了补偿会让高亮缓慢爬行。
            if (playing) {
                clock.anchor(positionMs + Math.min(uplinkPeriodMs / 2, LEAD_MARGIN_MS), true);
            } else {
                clock.anchor(positionMs, false);
            }
            lastPlaying = playing;
            return;
        }

        // 位置未变：只处理播放态切换与明显的漂移
        lastPlaying = playing;
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

    /**
     * 在颜色已有 alpha 的基础上再乘一个系数。
     *
     * <p>羽化梯度与行级不透明度都必须生效：前者负责前沿那几个像素的溶开，
     * 后者负责整行在切换过程中的明暗。若直接覆盖 alpha，
     * 过渡期间羽化就会失效，前沿重新变成硬切。
     *
     * @param weight 0..255 的权重，255 保持原色
     */
    private static int scaleAlpha(int color, int weight) {
        int base = (color >>> 24) & 0xFF;
        int scaled = Math.round(base * (weight / 255f));
        return withAlpha(color, Math.max(0, Math.min(255, scaled)));
    }
}
