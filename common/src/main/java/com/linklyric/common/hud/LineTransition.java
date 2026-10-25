package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;

/**
 * 行切换动画的状态机。
 *
 * <p>换行只做一件事：其余行连续上移一行补位。旧主行**不淡出**，
 * 在换行瞬间直接消失。
 *
 * <h2>为什么不淡出旧行</h2>
 *
 * <p>淡出要求把旧句重新画一遍。逐字路径的观感来自三样东西：
 * 逐字抬升、逐顶点染色、强调提亮；而淡出用的整句绘制
 * （{@code painter.drawText}）只有单一颜色，三样全无。
 * 于是已唱完的主句在消失前会先以「最基础的文字样式」
 * 在原位闪一帧，与它停留时的样子明显不符——看起来像是
 * 突然退化成另一段文字，而不是同一句在退场。
 *
 * <p>曾试图用冻结的已唱宽度分区上色来缓解，但那只能还原配色，
 * 还原不了抬升与提亮，闪变依旧。
 * 与其用一帧低配画面冒充，不如不做这个过渡：句末已经唱完，
 * 直接消失反而干净。
 */
final class LineTransition {

    /** 当前动画对应的行索引。 */
    private int fromIndex = -1;

    /**
     * 已进行的时长（纳秒）。
     *
     * <p>初值取极大数：首帧即应视为已完成，否则进游戏第一句会淡入。
     */
    private long elapsedNanos = Long.MAX_VALUE / 4;

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
        fromIndex = index;
        elapsedNanos = 0L;
    }

    /** 本帧是否仍在过渡中。 */
    boolean active(long durationMs) {
        return elapsedNanos < durationMs * 1_000_000L;
    }

    /** 整行上移的位移量，单位为行步长。0 表示已就位。 */
    int blockShift(long durationMs) {
        return Math.round(LyricGeometry.LINE_STEP
                * (1f - LyricEasing.ease(risePart(durationMs))));
    }

    private float risePart(long durationMs) {
        long total = Math.max(1L, durationMs);
        return Math.min(1f, elapsedNanos / 1_000_000f / total);
    }
}
