package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;

/**
 * 行切换动画的状态机。
 *
 * <p>换行分两步：旧主行在其原位淡出，同时其余行连续上移一行补位。
 * 两者并行，旧行不参与位移，块内各行不会有错位的中间态。
 *
 * <p>旧行的内容与位置在换行瞬间冻结。若在绘制时用当前帧的块几何反推，
 * 会因为块按行数居中的规则而落到错误位置。
 */
final class LineTransition {

    /** 旧行淡出的固定时长（毫秒）。切换时长可调，淡出保持恒定。 */
    static final long FADE_MS = 100L;

    /** 间隔：当前动画对应的行索引。 */
    private int fromIndex = -1;

    /**
     * 已进行的时长（纳秒）。
     *
     * <p>初值取极大数：首帧即应视为已完成，否则进游戏第一句会淡入。
     */
    private long elapsedNanos = Long.MAX_VALUE / 4;

    /** 旧句内容，换行瞬间冻结。 */
    private String oldText;

    /** 旧句基线 y，换行瞬间冻结。 */
    private int oldBaselineY = Integer.MIN_VALUE;

    /** 旧句已唱宽度，换行瞬间冻结，用于还原配色分界。 */
    private float oldSungWidth;

    /** 上一帧主行内容。 */
    private String lastText;

    /** 上一帧主行实际绘制用的 y。 */
    private int lastBaselineY = Integer.MIN_VALUE;

    /** 上一帧主行已唱宽度。 */
    private float lastSungWidth;

    String oldText() {
        return oldText;
    }

    int oldBaselineY() {
        return oldBaselineY;
    }

    float oldSungWidth() {
        return oldSungWidth;
    }

    String lastText() {
        return lastText;
    }

    /**
     * 推进动画。
     *
     * @param index        当前行索引
     * @param frameDeltaNs 距离上一帧的纳秒数
     * @param durationMs   用户设定的切换时长
     */
    void update(int index, long frameDeltaNs, long durationMs) {
        if (index == fromIndex) {
            if (elapsedNanos < durationMs * 1_000_000L) {
                elapsedNanos += Math.max(0L, frameDeltaNs);
            }
            return;
        }
        // 换行这一刻冻结旧句快照。
        fromIndex = index;
        elapsedNanos = 0L;
        oldText = lastText;
        oldBaselineY = lastBaselineY;
        oldSungWidth = lastSungWidth;
    }

    /** 本帧是否仍在过渡中。 */
    boolean active(long durationMs) {
        return elapsedNanos < durationMs * 1_000_000L;
    }

    /** 整行上移的位移量，单位为行步长。0 表示已就位。 */
    int blockShift(long durationMs) {
        return Math.round(LyricGeometry.LINE_STEP * (1f - LyricEasing.ease(risePart(durationMs))));
    }

    /** 旧行的不透明度，0..1。 */
    float oldAlpha(long durationMs) {
        long fadeMs = Math.max(1L, FADE_MS);
        float fade = Math.min(1f, elapsedNanos / 1_000_000f / fadeMs);
        return 1f - LyricEasing.ease(fade);
    }

    /** 旧行是否还需要绘制。 */
    boolean drawsOld(long durationMs) {
        return oldText != null && oldAlpha(durationMs) > 0f;
    }

    private float risePart(long durationMs) {
        long total = Math.max(1L, durationMs);
        return Math.min(1f, elapsedNanos / 1_000_000f / total);
    }

    /** 帧末记录本帧主行的实际状态，供下次换行时冻结。 */
    void recordMain(String text, int baselineY, float sungWidth) {
        lastText = text;
        lastBaselineY = baselineY;
        lastSungWidth = sungWidth;
    }
}
