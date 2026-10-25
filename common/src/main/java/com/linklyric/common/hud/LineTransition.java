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

    /** 是否已经历过至少一次换行。首次不算换行。 */
    private boolean started;

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
        // 首次出画不是换行，不能从零开始动画。
        //
        // 这里曾经无条件归零。但首帧的 index 总与
        // 初始值 -1 不同，于是每次进入世界、每次歌词时间轴重建，
        // 都会触发一次完整的「整块上移」动画——
        // 整行歌词从下方一个行距滑上来。
        //
        // 它与「先染色后定位」的首帧行为叠加在一起，
        // 就是用户看到的「逐字歌词动画异常」：
        // 字在往上滑，而染色已经是句中终态。
        //
        // 首次出画应直接就位——与字段注释写的意图一致。
        if (!started) {
            started = true;
            elapsedNanos = Long.MAX_VALUE / 4;
            return;
        }
        elapsedNanos = 0L;
    }

    /** 本帧是否仍在过渡中。 */
    boolean active(long durationMs) {
        return elapsedNanos < durationMs * 1_000_000L;
    }

    /**
     * 整行上移的位移量（像素）。0 表示已就位。
     *
     * @param durationMs 用户设定的切换时长
     * @param lineStep   一行加上行距的实际步长
     *
     * <p>步长必须由调用方传真实值。此前这里写死 {@code LyricGeometry.LINE_STEP}，
     * 而排版走的是用户可调的 {@code lineGapPx}。默认行距 2 时两者都是 11，看不出问题；
     * 一旦把行距调到 12，动画会把整块往上推 11 像素，收尾再瞬间补上第 12 像素——
     * 换行末尾出现一次一像素的跳格。行距调到 0 时则相反，会多推 2 像素再弹回。
     */
    int blockShift(long durationMs, int lineStep) {
        return Math.round(Math.max(0, lineStep)
                * (1f - LyricEasing.ease(risePart(durationMs))));
    }

    private float risePart(long durationMs) {
        long total = Math.max(1L, durationMs);
        return Math.min(1f, elapsedNanos / 1_000_000f / total);
    }
}