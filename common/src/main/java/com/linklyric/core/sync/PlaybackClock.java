package com.linklyric.core.sync;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放位置外推器：以「锚点 + 单调时钟」推算当前播放位置。
 *
 * <p>宿主轮询间隔有几百毫秒，直接用它驱动逐字动画必然抖动。这里改为
 * 收到上报时重设锚点，渲染时按单调时钟外推。
 *
 * <p>必须使用单调时钟而非帧间 delta 累积：Minecraft 在区块加载、
 * GC、切维度时会掉帧数百毫秒，累积式外推会在卡顿后瞬间跑偏。
 *
 * <p>本类可被网络线程写入、渲染线程读取，因此用不可变锚点整体替换，
 * 单次引用赋值在 JVM 上是原子的，无需加锁。
 *
 * <h2>时间基准为什么可注入</h2>
 *
 * <p>外推的两个输入——「这一刻的纳秒」与「锚点时刻」——必须来自同一个基准。
 * 若本类内部自己去读 {@link System#nanoTime()}，而调用方又在别处读一次传进来，
 * 两个值的差值就混进了两次读取之间的耗时，测不出真实间隔，
 * 单测里更是完全无法复现「过了 5 秒」。因此时刻一律由调用方给出。
 */
public final class PlaybackClock {

    private record Anchor(long positionMs, long nanos, boolean playing) {
    }

    private final AtomicReference<Anchor> anchor;

    /** 以「零位置、暂停、时刻 0」起始。调用方应在第一帧就 {@link #anchor} 一次。 */
    public PlaybackClock() {
        this.anchor = new AtomicReference<>(new Anchor(0L, 0L, false));
    }

    /**
     * 以新位置重建锚点。换曲、seek、收到新快照时调用。
     *
     * @param positionMs 上报的播放位置
     * @param playing    是否正在播放
     * @param nowNanos   当前单调时刻，与 {@link #positionMs} 用同一基准
     */
    public void anchor(long positionMs, boolean playing, long nowNanos) {
        anchor.set(new Anchor(Math.max(0L, positionMs), nowNanos, playing));
    }

    /** 当前推算出的播放位置（毫秒），永不为负。 */
    public long positionMs(long nowNanos) {
        return positionAt(anchor.get(), nowNanos);
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
