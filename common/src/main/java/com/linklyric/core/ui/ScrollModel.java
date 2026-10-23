package com.linklyric.core.ui;

/**
 * 歌词列表的滚动状态机。
 *
 * <p>三种滚动语义在此集中处理，渲染层只读取 {@link #offsetAt(long)}：
 * <ul>
 *   <li>正常向前推进 —— 缓动滚动，避免逐行跳动</li>
 *   <li>向后回退 —— 立即瞬移。缓动会让整块歌词向上「倒带」，观感混乱</li>
 *   <li>向前跨度过大（seek）—— 同样瞬移，否则会缓慢滚动十几行</li>
 * </ul>
 *
 * <p>偏移量以「行」为单位，渲染时乘以行高。所有过渡均由 {@code now} 计算，
 * 不做 {@code offset += speed} 式累积，卡顿后可自愈。
 */
public final class ScrollModel {

    private static final long DEFAULT_DURATION_MS = 320L;
    private static final int MAX_SMOOTH_JUMP = 3;

    private int currentIndex = -1;
    private float fromOffset;
    private float toOffset;
    private long startNanos;
    private long durationNanos;

    /** 当前逻辑行索引；未开始时为 -1。 */
    public int currentIndex() {
        return currentIndex;
    }

    /** 目标偏移，即 {@link #currentIndex()} 对应的行位置。 */
    public float targetOffset() {
        return toOffset;
    }

    /**
     * 行索引发生变化时调用。
     *
     * @param index    新行索引（-1 表示清空）
     * @param nowNanos 当前单调时钟
     */
    public void moveTo(int index, long nowNanos) {
        if (index == currentIndex) {
            return;
        }

        boolean backward = currentIndex >= 0 && index < currentIndex;
        int distance = currentIndex < 0 ? 0 : Math.abs(index - currentIndex);
        boolean tooFar = distance > MAX_SMOOTH_JUMP;

        if (currentIndex < 0 || backward || tooFar) {
            snapTo(index, nowNanos);
        } else {
            fromOffset = offsetAt(nowNanos);
            toOffset = index;
            startNanos = nowNanos;
            durationNanos = DEFAULT_DURATION_MS * 1_000_000L;
            currentIndex = index;
        }
    }

    /** 立即定位，不产生动画。切歌或 seek 后使用。 */
    public void snapTo(int index, long nowNanos) {
        currentIndex = index;
        toOffset = index;
        fromOffset = index;
        startNanos = nowNanos;
        durationNanos = 0L;
    }

    /** 当前滚动偏移（行单位），渲染时乘以行高。 */
    public float offsetAt(long nowNanos) {
        if (durationNanos <= 0L) {
            return toOffset;
        }
        long elapsed = nowNanos - startNanos;
        if (elapsed >= durationNanos) {
            return toOffset;
        }
        if (elapsed <= 0L) {
            return fromOffset;
        }
        float t = (float) elapsed / (float) durationNanos;
        float eased = easeOutCubic(t);
        return fromOffset + (toOffset - fromOffset) * eased;
    }

    public boolean isAnimating(long nowNanos) {
        return durationNanos > 0L && (nowNanos - startNanos) < durationNanos;
    }

    private static float easeOutCubic(float t) {
        float inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }
}
