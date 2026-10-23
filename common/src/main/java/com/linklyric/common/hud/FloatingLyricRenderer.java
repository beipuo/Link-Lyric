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

    /** 超过该偏差即视为需要重新锚定时钟。 */
    private static final long DRIFT_RESNAP_MS = 400L;

    /**
     * 外推允许的最大超前量（毫秒）。
     *
     * <p>**这是染色能否正常工作的硬约束**。桥接端每秒才上报一次位置，
     * 外推器按本地时钟持续推进；一旦轮询因传输全量歌词而变慢，
     * 外推值就会无界地跑在真实位置前面。位置一旦越过当前行的结束时间，
     * 行选择会停在最后一行并把整行判定为已唱完，
     * 表现就是「整行恒亮、完全没有染色过程」。
     *
     * <p>因此这里给超前量设上限，超过即回落到上报位置重新锚定。
     * 取值需小于最短歌词行时长，保证不会整行被跳过。
     */
    private static final long MAX_EXTRAPOLATION_LEAD_MS = 700L;

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

        int screenWidth = painter.screenWidth();
        int screenHeight = painter.screenHeight();
        int maxWidth = Math.max(40, (int) (screenWidth * MAX_WIDTH_RATIO));

        String fullText = line.text();
        String visible = clip(painter, fullText, maxWidth);

        boolean hasTranslation = line.hasTranslation();
        String secondary = hasTranslation ? clip(painter, line.translation(), maxWidth) : "";

        int primaryWidth = painter.textWidth(visible);
        int secondaryWidth = hasTranslation ? painter.textWidth(secondary) : 0;

        int blockHeight = hasTranslation
                ? LINE_HEIGHT * 2 + LINE_GAP
                : LINE_HEIGHT;

        int centerX = screenWidth / 2;
        int textX = centerX - primaryWidth / 2;
        int textY = screenHeight - BOTTOM_MARGIN - blockHeight;

        int padX = 6;
        int padY = 3;
        painter.fillRect(textX - padX, textY - padY,
                primaryWidth + padX * 2, blockHeight + padY * 2, COLOR_BACKDROP);

        // 未唱色打底
        painter.drawText(visible, textX, textY, COLOR_UNSUNG);

        // 换行或时间轴变更时重置动画，避免沿用上一行的进度
        if (index != lastLineIndex) {
            sungWidth = targetWidthAt(painter, line, visible, positionMs);
            lastLineIndex = index;
        } else {
            float target = targetWidthAt(painter, line, visible, positionMs);
            sungWidth = advance(sungWidth, target, frameDelta);
        }

        float clampedSung = Math.max(0f, Math.min(sungWidth, primaryWidth));
        if (clampedSung >= 0.5f) {
            if (painter.supportsScissor()) {
                drawWithClipping(painter, visible, textX, textY, clampedSung);
            } else {
                drawByWholeUnit(painter, line, visible, textX, textY, clampedSung);
            }
        }

        if (hasTranslation) {
            int secondaryX = centerX - secondaryWidth / 2;
            painter.drawText(secondary, secondaryX, textY + LINE_HEIGHT + LINE_GAP,
                    COLOR_TRANSLATION);
        }
        return true;
    }

    /**
     * 用裁剪绘制已唱层，并在前端叠加羽化带。
     *
     * <p>硬切边在字体边缘会显得生硬；分两级透明度过渡可以显著柔化推进端，
     * 而 Minecraft 的文本绘制本身支持 alpha，不需要着色器。
     */
    private void drawWithClipping(Painter painter, String visible, int textX, int textY,
                                  float sungWidth) {
        int solidWidth = (int) sungWidth;

        if (solidWidth > 0) {
            painter.enableScissor(textX, textY - 2, solidWidth, LINE_HEIGHT + 6);
            painter.drawText(visible, textX, textY, COLOR_SUNG);
            painter.disableScissor();
        }

        int feather = Math.max(2, (int) (LINE_HEIGHT * FEATHER_RATIO));
        int tailStart = textX + solidWidth;

        // 一级羽化：半透明
        painter.enableScissor(tailStart, textY - 2, feather, LINE_HEIGHT + 6);
        painter.drawText(visible, textX, textY, withAlpha(COLOR_SUNG, 0x99));
        painter.disableScissor();

        // 二级羽化：更透明，收尾
        painter.enableScissor(tailStart + feather, textY - 2, feather, LINE_HEIGHT + 6);
        painter.drawText(visible, textX, textY, withAlpha(COLOR_SUNG, 0x40));
        painter.disableScissor();
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
     * 计算某位置应当达到的已唱宽度（像素，浮点）。
     *
     * <p>按单元顺序累加，正在演唱的单元按其自身进度取部分宽度，
     * 因此高亮可以停在字符内部，而不是整字跳变。
     */
    private float targetWidthAt(Painter painter, LyricLine line, String visible, long positionMs) {
        List<LyricWord> words = line.words();
        int lineWidth = painter.textWidth(visible);

        if (words.size() <= 1) {
            // 行级歌词：只有整行全亮或全灭
            return positionMs >= line.endMs() ? lineWidth : 0f;
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
                width += partWidth * word.progressAt(positionMs);
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
     * 取受限的外推位置。
     *
     * <p>外推值允许领先上报值一点，以抹平轮询间隔内的阶跃；
     * 但领先量必须有上限，否则轮询一旦滞后，位置会无界前冲，
     * 越过整首歌的歌词，导致行选择失效、染色消失。
     */
    private long boundedPosition(long reportedMs) {
        long extrapolated = clock.positionMs();
        long ceiling = reportedMs + MAX_EXTRAPOLATION_LEAD_MS;
        long result = extrapolated;
        if (extrapolated > ceiling) {
            // 超出上限说明轮询已明显滞后。
            // 不能直接返回 reportedMs：那会让位置每帧被拉回旧值，
            // 高亮永久停在原处。把时钟重锚到「上报值 + 允许超前量」，
            // 既收住超前，又保持继续前进。
            result = ceiling;
            clock.anchor(ceiling, lastPlaying);
        } else if (extrapolated < reportedMs) {
            // 外推落后于上报值，以上报值补齐，避免显示滞后
            result = Math.min(reportedMs, ceiling);
        }
        // 播放中位置只允许前进，避免轮询抖动导致高亮倒退闪烁。
        // 但上报位置若已跨过较大的距离（切行、seek、换曲），必须放行，
        // 否则渲染会永久卡在旧行上，整行恒亮且再也跟不上。
        if (lastPlaying && result < lastRenderedPositionMs) {
            long regression = lastRenderedPositionMs - result;
            if (regression <= MAX_EXTRAPOLATION_LEAD_MS) {
                return lastRenderedPositionMs;
            }
        }
        lastRenderedPositionMs = Math.max(0L, result);
        return lastRenderedPositionMs;
    }

    /**
     * 用最新快照校准时钟。
     *
     * <p>只在播放态变化或漂移过大时重锚，否则会抹掉外推带来的帧级连续性。
     */
    private void syncClock(long positionMs, boolean playing) {
        if (!clockPrimed || playing != lastPlaying) {
            clock.anchor(positionMs, playing);
            clockPrimed = true;
        } else if (clock.driftMs(positionMs) > DRIFT_RESNAP_MS) {
            clock.anchor(positionMs, playing);
        }
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
