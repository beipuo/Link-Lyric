package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
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

    /** 已唱色：明亮的天蓝，与未唱灰形成明显色差。 */
    public static final int COLOR_SUNG = 0xFF7FD4FF;

    /** 未唱色：浅灰，避免在暗场景里消失。 */
    public static final int COLOR_UNSUNG = 0xFFD8D8D8;

    /** 翻译色：更暗的灰，弱化次要信息。 */
    public static final int COLOR_TRANSLATION = 0xFF9A9A9A;

    /** 底板色：半透明黑，保证亮场景可读。 */
    public static final int COLOR_BACKDROP = 0x66000000;

    /** 文本距屏幕底部的距离。留出物品栏与经验条的空间。 */
    private static final int BOTTOM_MARGIN = 58;

    /** 单行最大宽度占屏比。 */
    private static final float MAX_WIDTH_RATIO = 0.72f;

    private static final int LINE_HEIGHT = 10;
    private static final int LINE_GAP = 2;

    /** 平滑时间常数：约 50ms 内逼近目标，兼顾跟手与顺滑。 */
    private static final double SMOOTH_TAU_SECONDS = 0.05;

    /** 目标值比当前值小超过该像素数时判定为 seek，直接跳变而非回退。 */
    private static final float BACKWARD_SNAP_PX = 6f;

    /** 羽化带长度，相对于字号的比例。 */
    private static final float FEATHER_RATIO = 0.45f;

    /**
     * 羽化带的 alpha 梯度，由高到低。
     *
     * <p>取值来自对 HyperLyric `TextDrawer` 那组 6 停靠点渐变
     * （alpha 255→244→184→92→24→0，stops 0/0.18/0.42/0.66/0.84/1）
     * 的分段中点采样。原实现用两段（153、64），
     * 过渡带里第一级偏暗约 70 个 alpha 单位，高亮一过界就明显发灰。
     * 这里用 5 段逼近原曲线，末级接近 0 保证收尾干净。
     */
    private static final int[] FEATHER_ALPHA = {0xF5, 0xC3, 0x5A, 0x16, 0x00};

    /**
     * 唱到时单个字的抬升高度，固定像素。
     *
     * <p>不用比例：行高与 GUI 缩放都会影响比例换算，
     * 5px 是直接量出来的观感值，写成固定值才可预期。
     */
    private static final int LIFT_PX = 5;

    /**
     * 升起行程长度，以平均字宽为单位。
     *
     * <p>0.7 表示字唱到 70% 处升满，之后永久保持在顶端。
     * 再大就会「升到一半多还没到顶」，升起过程显得拖沓。
     */
    private static final float LIFT_RISE_UNITS = 0.7f;

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
     * <p>必须不小于 {@link #LIFT_PX}，否则抬起的字顶部会被裁剪削平。
     */
    private static final int LIFT_HEADROOM = LIFT_PX;

    /**
     * 逐字绘制的最多字数。
     *
     * <p>每字一次绘制调用，长行会成倍放大开销。
     * 超过这个长度就退回整段绘制——那种长度下每字偏移也看不清了。
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
     * <p>比未唱色更淡：它是预告信息，不该与主行争夺注意力。
     * 取未唱色调低不透明度，保证与主行同色系、只是更弱。
     */
    private static final int COLOR_NEXT_LINE = 0xC8FFFFFF;

    /**
     * 行切换动画时长（纳秒）。
     *
     * <p>220ms 取自 HyperLyric 的 {@code NEXT_LINE_PROMOTION_DURATION}。
     * 短于 150ms 会显得生硬，长于 300ms 会与演唱进度脱节。
     */
    private static final long LINE_TRANSITION_NANOS = 220_000_000L;

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
    public interface Painter {
        int screenWidth();

        int screenHeight();

        int textWidth(String text);

        /** 绘制带阴影的文本。颜色含 alpha 通道。 */
        void drawText(String text, int x, int y, int color);

        /** 绘制矩形填充，用于底板。 */
        void fillRect(int x, int y, int width, int height, int color);

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

    private final LyricFeed source;

    /** 位置外推器：把每秒一次的上报插值成帧级连续位置。 */
    private final PlaybackClock clock = new PlaybackClock();

    private boolean clockPrimed;
    private boolean lastPlaying;

    /** 跨帧保留的已唱宽度（像素）。这是染色连续性的来源。 */
    private float sungWidth;

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
    private long lineTransitionElapsedNanos = LINE_TRANSITION_NANOS;

    /** 上一帧的主行文本，用于绘制离场淡出。 */
    private String lastMainText;

    /** 上一帧是否有副行，用于判断主行基线是否需要过渡。 */
    private boolean lastSecondaryPresent;

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
        if (painter == null || !source.isAlive()) {
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
        long positionMs = boundedPosition(snapshot.positionMs());

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
        int maxWidth = Math.max(40, (int) (screenWidth * MAX_WIDTH_RATIO));
        int centerX = screenWidth / 2;

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
        boolean canScroll = painter.supportsScissor();
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
            secondaryColor = COLOR_NEXT_LINE;
        } else if (line.hasTranslation()) {
            secondaryText = clip(painter, line.translation(), maxWidth);
            secondaryColor = COLOR_TRANSLATION;
        } else {
            secondaryText = "";
            secondaryColor = COLOR_TRANSLATION;
        }

        int primaryWidth = scrolling ? fullWidth : painter.textWidth(visible);
        int secondaryWidth = secondaryText.isEmpty() ? 0 : painter.textWidth(secondaryText);

        int centerTextY = screenHeight - BOTTOM_MARGIN;
        int padX = 6;
        // 底板上下留白必须容纳最大抬升，否则抬起字的顶部会顶出底板、
        // 在画面上变成「文字浮在框外」。
        int padY = Math.max(3, LIFT_HEADROOM + 1);

        // 主行底部位置：副行存在时，主行上移一行加行距，给副行腾位置。
        boolean hasSecondary = !secondaryText.isEmpty();
        int mainBaselineY = hasSecondary
                ? centerTextY - LINE_HEIGHT - LINE_GAP
                : centerTextY;

        // 换行动画期间，新主行从副行位置升上来。
        // promote 从 0 到 1：0 表示还在副行位置，1 表示已就位。
        //
        // 偏移量固定为一行加行距，且只在确实有副行时才生效：
        // 没有副行时主行本就该直接落位，不该有任何过渡位移。
        int transitionOffset = hasSecondary
                ? Math.round((1f - promote) * (LINE_HEIGHT + LINE_GAP))
                : 0;
        int drawMainY = mainBaselineY + transitionOffset;

        int viewWidth = scrolling ? maxWidth : primaryWidth;
        int viewportLeft = centerX - viewWidth / 2;
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

        int blockTop = mainBaselineY - padY;
        int blockBottom = hasSecondary
                ? mainBaselineY + LINE_HEIGHT + LINE_GAP + LINE_HEIGHT + padY
                : mainBaselineY + LINE_HEIGHT + padY;
        painter.fillRect(viewportLeft - padX, blockTop,
                viewWidth + padX * 2, blockBottom - blockTop, COLOR_BACKDROP);

        // 逐字偏移表：底色层与高亮层必须共用同一份。
        //
        // 若只让高亮层带偏移，唱到的字会从白色原字上方落下来，
        // 看起来像两个独立的字在打架，而不是同一个字变色并上浮。
        int[] lifts = clampedSung >= 0.5f && canScroll
                ? computeLifts(painter, charStartsOf(painter, visible),
                        visible.length(), primaryWidth, clampedSung)
                : null;

        // 旧主行的淡出：换行动画前半程让它上移并消失。
        if (promote < 1f && lastMainText != null) {
            drawOutgoingLine(painter, maxWidth, centerX, mainBaselineY, promote);
        }

        // 未唱色打底。滚动时文本会伸出视口，必须裁掉，
        // 否则会画到面板外面去。
        drawBaseLayer(painter, visible, textX, drawMainY, lifts, scrolling,
                viewportLeft, viewWidth);

        if (clampedSung >= 0.5f) {
            if (canScroll) {
                drawWithClipping(painter, visible, textX, drawMainY, clampedSung,
                        primaryWidth, viewportLeft, viewportRight, lifts);
            } else {
                drawByWholeUnit(painter, line, visible, textX, drawMainY, clampedSung);
            }
        }

        // 副行：不做逐字染色，且带着自己的淡入。
        if (hasSecondary) {
            int secondaryX = centerX - secondaryWidth / 2;
            int secondaryY = centerTextY;
            // 新副行在主行升上来之后才淡入（对应 NEXT_LINE_PREVIEW_FADE_DURATION）。
            int alpha = 255;
            if (promote < 1f) {
                alpha = Math.round(255f * promote);
            }
            painter.drawText(secondaryText, secondaryX, secondaryY,
                    withAlpha(secondaryColor, alpha));
        }

        lastSecondaryPresent = hasSecondary;
        lastMainText = fullText;
        return true;
    }

    /**
     * 更新行切换动画状态。
     *
     * <p>仅在行索引变化时重新开始计时；同一行内不动，
     * 否则每帧都会重置，动画永远走不完。
     */
    private void updateLineTransition(int index, long frameDeltaNanos) {
        if (index == lineTransitionFromIndex) {
            if (lineTransitionElapsedNanos < LINE_TRANSITION_NANOS) {
                lineTransitionElapsedNanos += Math.max(0L, frameDeltaNanos);
            }
            return;
        }
        lineTransitionFromIndex = index;
        lineTransitionElapsedNanos = 0L;
    }

    /** 行切换进度 0→1；已经走完时恒为 1。 */
    private float lineTransitionProgress() {
        if (LINE_TRANSITION_NANOS <= 0L) {
            return 1f;
        }
        float raw = lineTransitionElapsedNanos / (float) LINE_TRANSITION_NANOS;
        if (raw >= 1f) {
            return 1f;
        }
        // 减速插值，对应 LinearOutSlowInInterpolator：
        // 起步快、收尾慢，读起来像「滑到位」而不是「弹到位」。
        float t = Math.max(0f, raw);
        return 1f - (1f - t) * (1f - t);
    }

    /** 画出正在离场的上一行：向上移动并淡出。 */
    private void drawOutgoingLine(Painter painter, int maxWidth, int centerX,
                                  int mainBaselineY, float promote) {
        String old = clip(painter, lastMainText, maxWidth);
        if (old.isEmpty()) {
            return;
        }
        int width = painter.textWidth(old);
        int x = centerX - width / 2;
        // 上移 0.65 行高，与 HyperLyric 的 main.height * 0.65f 一致。
        int rise = Math.round(LINE_HEIGHT * 0.65f * (1f - promote));
        int alpha = Math.round(255f * (1f - promote));
        painter.drawText(old, x, mainBaselineY - rise, withAlpha(COLOR_UNSUNG, alpha));
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

        int screenWidth = painter.screenWidth();
        int screenHeight = painter.screenHeight();
        int width = painter.textWidth(INTERLUDE_TEXT);
        int x = screenWidth / 2 - width / 2;
        int y = screenHeight - BOTTOM_MARGIN - LINE_HEIGHT;

        painter.fillRect(x - 6, y - 3, width + 12, LINE_HEIGHT + 6, COLOR_BACKDROP);
        painter.drawText(INTERLUDE_TEXT, x, y, COLOR_INTERLUDE);
        return true;
    }

    /**
     * 绘制未唱色底色层。
     *
     * <p>关键点：有抬升表时必须逐字绘制并套用同一份偏移，
     * 让「同一个字」既有底色也有高亮、且位置一致。
     *
     * <p>若这里仍按整行一次绘制（基线不动），而高亮层带偏移，
     * 唱到的字就会从白色原字上方落下来，看起来是两个独立的字，
     * 这正是「高亮字落在原歌词上」的观感来源。
     */
    private void drawBaseLayer(Painter painter, String visible, int textX, int textY,
                               int[] lifts, boolean scrolling,
                               int viewportLeft, int viewWidth) {
        if (scrolling) {
            painter.enableScissor(viewportLeft, textY - LIFT_HEADROOM, viewWidth,
                    LINE_HEIGHT + LIFT_HEADROOM + 4);
        }
        if (lifts == null) {
            painter.drawText(visible, textX, textY, COLOR_UNSUNG);
        } else {
            int cursor = 0;
            for (int i = 0; i < visible.length(); i++) {
                String ch = visible.substring(i, i + 1);
                painter.drawText(ch, textX + cursor, textY - lifts[i], COLOR_UNSUNG);
                cursor += painter.textWidth(ch);
            }
        }
        if (scrolling) {
            painter.disableScissor();
        }
    }

    /**
     * 绘制已唱部分。
     *
     * <p>分两种情形：谱面走完就整体全亮，不再羽化；
     * 否则实心段 + 一条逐级变淡的过渡带。
     */
    private void drawWithClipping(Painter painter, String visible, int textX, int textY,
                                  float sungWidth, int lineWidth,
                                  int viewportLeft, int viewportRight, int[] lifts) {
        int solidWidth = (int) sungWidth;
        if (solidWidth > 0) {
            drawClip(painter, visible, textX, textY, viewportLeft, textX,
                    solidWidth, viewportRight, COLOR_SUNG, lifts);
        }

        // 整行唱完就不再羽化：否则行尾会拖一条半透明的尾巴，
        // 看起来像永远没唱干净。
        if (solidWidth >= lineWidth) {
            return;
        }

        // 过渡带按字号比例取宽，与字形高度成比例，不会随分辨率失衡。
        int feather = Math.max(FEATHER_ALPHA.length, (int) (LINE_HEIGHT * FEATHER_RATIO));
        int segment = Math.max(1, feather / FEATHER_ALPHA.length);
        int x = textX + solidWidth;
        int remaining = feather;
        for (int i = 0; i < FEATHER_ALPHA.length && remaining > 0; i++) {
            int width = Math.min(segment, remaining);
            drawClip(painter, visible, textX, textY, viewportLeft, x,
                    width, viewportRight, withAlpha(COLOR_SUNG, FEATHER_ALPHA[i]), lifts);
            x += width;
            remaining -= width;
        }
    }

    /**
     * 按裁剪区画一段高亮，并夹在视口内。
     *
     * <p>滚动时文本会伸出屏幕两侧，若裁剪区不夹视口，
     * 已唱段会画到面板外面，压到别的 HUD 元素上。
     *
     * <p>每一段的 alpha 叠加在下面已铺好的未唱色之上，结果是
     * {@code a*已唱 + (1-a)*未唱}，也就是正确的线性插值——
     * 这正是逐级变淡的成因，不是重复混色。
     *
     * @param lifts 逐字竖直偏移；为 {@code null} 时整段一次绘制
     */
    private void drawClip(Painter painter, String visible, int textX, int textY,
                          int viewportLeft, int clipX, int clipWidth,
                          int viewportRight, int color, int[] lifts) {
        int left = Math.max(clipX, viewportLeft);
        int right = Math.min(clipX + clipWidth, viewportRight);
        if (right - left <= 0) {
            return;
        }

        if (lifts == null) {
            painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                    LINE_HEIGHT + LIFT_HEADROOM + 4);
            painter.drawText(visible, textX, textY, color);
            painter.disableScissor();
            return;
        }

        // 逐字绘制：每个字带自己的竖直偏移。
        // 裁剪仍按像素做，所以正在唱的那个字依然只显示已唱比例。
        //
        // 全行统一用 LIFT_HEADROOM 的裁剪高度，而不是按各自偏移收紧，
        // 避免相邻字裁剪区相互错开而在接缝处露出底色。
        painter.enableScissor(left, textY - LIFT_HEADROOM, right - left,
                LINE_HEIGHT + LIFT_HEADROOM + 4);
        int cursor = 0;
        for (int i = 0; i < visible.length(); i++) {
            String ch = visible.substring(i, i + 1);
            int chWidth = painter.textWidth(ch);
            int lift = Math.min(i, lifts.length - 1) >= 0 && i < lifts.length
                    ? lifts[i] : 0;
            painter.drawText(ch, textX + cursor, textY - lift, color);
            cursor += chWidth;
        }
        painter.disableScissor();
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
     * 平均字宽。
     *
     * <p>中英混排时各字宽度不同，用平均值为基准计算回落行程即可：
     * 行程只需与字的尺度同量级，不要求精确。
     */
    private static float charWidthAverage(int[] charStarts, int charCount, int lineWidth) {
        if (charCount <= 0) {
            return 0f;
        }
        return lineWidth / (float) charCount;
    }

    /**
     * 计算每个字的抬升像素。
     *
     * <p>规则取自 HyperLyric 的 {@code computeUnitLift}：
     * 高亮经过某个字时它抬到最高，随后在一个波长的行程里线性落回基线。
     * 也就是「唱到的字上浮，唱过的字缓缓归位」。
     *
     * <p>返回 {@code null} 表示不值得逐字绘制（行太长或数据退化为整行），
     * 此时调用方应退回整段绘制，省掉几十次绘制调用。
     */
    private int[] computeLifts(Painter painter, int[] charStarts, int charCount,
                               int lineWidth, float sungWidth) {
        if (!PER_CHAR_LIFT || charCount <= 1 || charCount > MAX_LIFT_CHARS) {
            return null;
        }
        int maxLift = LIFT_PX;
        if (maxLift <= 0) {
            return null;
        }

        // 升起行程：字被唱到时用约一个多字宽的行程缓慢升满。
        int riseLength = Math.max(1,
                Math.round(charWidthAverage(charStarts, charCount, lineWidth)
                        * LIFT_RISE_UNITS));

        int[] lifts = new int[charCount];
        for (int i = 0; i < charCount; i++) {
            int unitStart = charStarts[i];

            // 尚未唱到这个字：停在基线。
            // 必须显式判断，否则相位公式会把结果夹成最大值，
            // 导致整行未唱部分一起悬空。
            if (sungWidth <= unitStart) {
                continue;
            }

            // 升起：从该字起点开始线性升到满值，之后永久保持。
            float rise = (sungWidth - unitStart) / (float) riseLength;
            rise = Math.max(0f, Math.min(1f, rise));
            lifts[i] = Math.round(maxLift * rise);
        }
        return lifts;
    }

    /** 不支持裁剪时的降级：按整单元覆盖，至少保证逐字推进可见。 */
    private void drawByWholeUnit(Painter painter, LyricLine line, String visible,
                                 int textX, int textY, float sungWidth) {
        List<LyricWord> words = line.words();
        if (words.size() <= 1) {
            if (sungWidth >= painter.textWidth(visible) - 0.5f) {
                painter.drawText(visible, textX, textY, COLOR_SUNG);
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
            painter.drawText(part, textX + cursor, textY, COLOR_SUNG);
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
        if (progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
        return progress * (2.5f - 2.5f * progress + progress * progress);
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
}
