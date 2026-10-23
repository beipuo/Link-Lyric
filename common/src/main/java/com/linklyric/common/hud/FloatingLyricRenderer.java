package com.linklyric.common.hud;

import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;

/**
 * 悬浮歌词渲染器。
 *
 * <p>数据来源是 {@link LyricFeed}，状态计算全部在渲染时按当前播放位置
 * 现算，因此逻辑与 MC 版本无关：Fabric 与 NeoForge 两端只负责把
 * {@link #render} 挂到各自的 HUD 事件上，绘制本身共用这一份实现。
 *
 * <p>性能约定：
 * <ul>
 *   <li>每帧最多取一次时间轴与位置，不在循环里重复查询数据源。</li>
 *   <li>不为歌词文本构造 {@code Component}，直接用 String 绘制——
 *       中文歌词每帧重建 {@code Component} 会产生大量短命对象。</li>
 *   <li>不申请任何按行缓存：悬浮样式只画当前行，无需多行布局。</li>
 * </ul>
 *
 * <p>本类不做平台判断，只依赖 {@link Painter} 这层极薄适配，
 * 以便在没有 MC 运行时的单测中验证排版计算。
 */
public final class FloatingLyricRenderer {

    /** 底部安全距离：避开物品栏与血量条。 */
    private static final int BOTTOM_MARGIN = 58;

    /** 文本最大宽度占屏幕比例，超出则截断而非换行（悬浮样式保持单行）。 */
    private static final float MAX_WIDTH_RATIO = 0.72f;

    /** 主文本颜色与副文本（翻译）颜色。 */
    public static final int COLOR_PRIMARY = 0xFFFFFFFF;
    public static final int COLOR_TRANSLATION = 0xFFB0B0B0;
    public static final int COLOR_BACKDROP = 0x66000000;

    /**
     * 平台绘制适配层。
     *
     * <p>只暴露最小原语，让渲染逻辑可脱离 MC 运行时测试。
     */
    public interface Painter {
        int screenWidth();

        int screenHeight();

        int textWidth(String text);

        /** 绘制带阴影的文本。 */
        void drawText(String text, int x, int y, int color);

        /** 绘制半透明底板，用于保证在明亮场景下可读。 */
        void fillRect(int x, int y, int width, int height, int color);
    }

    private final LyricFeed source;

    /** 上一次绘制的时间轴，避免数据源在断连清空时立刻丢帧。 */
    private LyricTimeline lastTimeline = LyricTimeline.empty();
    private long lastSignature = Long.MIN_VALUE;

    public FloatingLyricRenderer(LyricFeed source) {
        this.source = source;
    }

    /**
     * 绘制当前歌词行。
     *
     * @return 是否实际绘制了内容。false 表示当前无歌词或数据源不可用。
     */
    public boolean render(Painter painter) {
        if (painter == null) {
            return false;
        }
        if (!source.isAlive()) {
            return false;
        }

        LyricTimeline timeline = timelineForFrame();
        if (timeline.isEmpty()) {
            return false;
        }

        long positionMs = source.pollSnapshot().positionMs();
        int index = timeline.indexAt(positionMs);
        if (index < 0) {
            return false;
        }
        LyricLine line = timeline.line(index);
        if (line == null || line.text().isEmpty()) {
            return false;
        }

        int screenWidth = painter.screenWidth();
        int screenHeight = painter.screenHeight();
        int maxWidth = Math.max(40, (int) (screenWidth * MAX_WIDTH_RATIO));

        String primary = clip(painter, line.text(), maxWidth);
        boolean hasTranslation = line.hasTranslation();
        String secondary = hasTranslation ? clip(painter, line.translation(), maxWidth) : "";

        int primaryWidth = painter.textWidth(primary);
        int secondaryWidth = hasTranslation ? painter.textWidth(secondary) : 0;
        int contentWidth = Math.max(primaryWidth, secondaryWidth);

        int lineHeight = 10;
        int paddingX = 6;
        int paddingY = 4;
        int gap = hasTranslation ? 2 : 0;
        int blockHeight = paddingY * 2 + lineHeight + (hasTranslation ? lineHeight + gap : 0);

        int centerX = screenWidth / 2;
        int boxLeft = centerX - contentWidth / 2 - paddingX;
        int boxTop = screenHeight - BOTTOM_MARGIN - blockHeight;

        painter.fillRect(boxLeft, boxTop, contentWidth + paddingX * 2, blockHeight, COLOR_BACKDROP);

        int primaryX = centerX - primaryWidth / 2;
        int primaryY = boxTop + paddingY;
        painter.drawText(primary, primaryX, primaryY, COLOR_PRIMARY);

        if (hasTranslation) {
            int secondaryX = centerX - secondaryWidth / 2;
            painter.drawText(secondary, secondaryX, primaryY + lineHeight + gap, COLOR_TRANSLATION);
        }
        return true;
    }

    /**
     * 每帧取时间轴。
     *
     * <p>数据源清空时保留上一帧内容，避免插件重载瞬间歌词闪掉；
     * 真正的清空由 {@code isAlive()} 判定，已在入口处拦截。
     */
    private LyricTimeline timelineForFrame() {
        long signature = source.lyricSignature();
        if (signature != lastSignature) {
            LyricTimeline fresh = source.lyric();
            if (!fresh.isEmpty()) {
                lastTimeline = fresh;
            }
            lastSignature = signature;
        }
        return lastTimeline;
    }

    /** 按像素宽度截断，超宽时加省略号。中英文混排下按字符逐个累加最稳。 */
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
}
