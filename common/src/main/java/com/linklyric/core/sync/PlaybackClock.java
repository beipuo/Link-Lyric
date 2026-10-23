package com.linklyric.core.sync;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放位置外推器：以「锚点 + 本地单调时钟」推算当前播放位置。
 *
 * <p>宿主每秒才回调一次位置，直接用它驱动逐字动画必然抖动。这里改为
 * 收到事件时重设锚点，渲染时按 {@link System#nanoTime()} 外推。
 *
 * <p>必须使用墙钟（nanoTime）而非帧间 delta 累积：Minecraft 在区块加载、
 * GC、切维度时会掉帧数百毫秒，累积式外推会在卡顿后瞬间跑偏。
 *
 * <p>本类可被网络线程写入、渲染线程读取，因此用不可变锚点整体替换，
 * 单次引用赋值在 JVM 上是原子的，无需加锁。
 */
public final class PlaybackClock {

    private record Anchor(long positionMs, long nanos, boolean playing) {
    }

    private final AtomicReference<Anchor> anchor =
            new AtomicReference<>(new Anchor(0L, System.nanoTime(), false));

    /** 以新位置重建锚点。换曲、seek、收到新快照时调用。 */
    public void anchor(long positionMs, boolean playing) {
        anchor.set(new Anchor(Math.max(0L, positionMs), System.nanoTime(), playing));
    }

    /** 暂停：冻结在当前推算位置。 */
    public void pause() {
        Anchor current = anchor.get();
        anchor.set(new Anchor(positionAt(current, System.nanoTime()), current.nanos(), false));
    }

    /** 恢复：以冻结位置为新锚点继续推进。 */
    public void resume() {
        Anchor current = anchor.get();
        anchor.set(new Anchor(positionAt(current, System.nanoTime()), System.nanoTime(), true));
    }

    public boolean isPlaying() {
        return anchor.get().playing();
    }

    /** 当前推算出的播放位置（毫秒），永不为负。 */
    public long positionMs() {
        return positionAt(anchor.get(), System.nanoTime());
    }

    /**
     * 相对锚点的偏差。用于判断宿主上报值与本地推算是否已经漂移过大，
     * 超过阈值时静默重锚，而不是硬拉进度。
     */
    public long driftMs(long reportedPositionMs) {
        return Math.abs(positionMs() - reportedPositionMs);
    }

    private static long positionAt(Anchor anchor, long nowNanos) {
        if (!anchor.playing()) {
            return anchor.positionMs();
        }
        long elapsedMs = (nowNanos - anchor.nanos()) / 1_000_000L;
        long position = anchor.positionMs() + Math.max(0L, elapsedMs);
        return Math.max(0L, position);
    }
}
