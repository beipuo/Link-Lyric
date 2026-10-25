package com.linklyric.core.lyric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一首歌的完整歌词时间轴。
 *
 * <p>不可变、按 {@code startMs} 升序，供渲染层高频查询。{@link #indexAt(long)}
 * 用二分查找，避免每帧线性扫描整首歌。
 */
public final class LyricTimeline {

    private static final LyricTimeline EMPTY = new LyricTimeline(List.of());

    private final List<LyricLine> lines;
    private final long[] starts;

    /**
     * 前缀最大结束时间：{@code maxEnd[i]} 是第 0 行到第 i 行的最晚结束时刻。
     *
     * <p>用于把「向前找重叠行」的扫描限制在常数步内：若 {@code maxEnd[i] <= 当前时刻}，
     * 说明第 0 行到第 i 行全部已经唱完，再往前不可能有仍在进行的行。
     */
    private final long[] maxEnd;

    public LyricTimeline(List<LyricLine> lines) {
        List<LyricLine> sorted = new ArrayList<>(lines == null ? List.of() : lines);
        sorted.sort(Comparator.comparingLong(LyricLine::startMs));
        // 排序后才能知道「下一行」是谁，尾部时间必须在这之后补。
        this.lines = LyricTimingFix.fix(sorted);
        int count = this.lines.size();
        this.starts = new long[count];
        this.maxEnd = new long[count];
        long running = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            starts[i] = this.lines.get(i).startMs();
            running = Math.max(running, this.lines.get(i).endMs());
            maxEnd[i] = running;
        }
    }

    public static LyricTimeline empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public List<LyricLine> lines() {
        return lines;
    }

    public LyricLine line(int index) {
        if (index < 0 || index >= lines.size()) {
            return null;
        }
        return lines.get(index);
    }

    /**
     * 返回当前位置对应的行索引；尚未开始或时间轴为空时返回 {@code -1}。
     *
     * <p>取「最后一个开始时间不晚于当前位置」的行。行之间若存在间隙，
     * 间隙内仍返回上一行，由渲染层按该行是否已结束决定淡出。
     */
    public int indexAt(long positionMs) {
        if (starts.length == 0 || positionMs < starts[0]) {
            return -1;
        }
        int low = 0;
        int high = starts.length - 1;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] <= positionMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    /** 当前位置对应的行；通常用于「是否已经结束」的额外判断。 */
    public LyricLine lineAt(long positionMs) {
        return line(indexAt(positionMs));
    }

    /**
     * 与当前行在同一时刻并存的行，即对唱、主唱与伴唱叠加的情形。
     *
     * <p>歌词数据里两行时间重叠是正常现象：主唱一句还没唱完，伴唱已经进来。
     * {@link #indexAt(long)} 只能返回一个索引，另一行会被完全丢弃——
     * 不是画得难看，是那句词和它的译文都不出现在屏幕上。
     *
     * <p>返回按开始时间升序的所有「此刻仍在进行」的行，含当前行本身。
     * 没有重叠时返回单元素列表，调用方无需分支。
     */
    public List<LyricLine> activeLinesAt(long positionMs) {
        int index = indexAt(positionMs);
        if (index < 0) {
            return List.of();
        }
        // 开始时间晚于当前位置的行一律不可能在唱，index 之后无需考虑。
        // 真正需要的是「开始得更早、到此刻还没结束」的行，只能向前找。
        List<LyricLine> active = new ArrayList<>(2);
        active.add(lines.get(index));
        for (int i = index - 1; i >= 0; i--) {
            if (maxEnd[i] <= positionMs) {
                break;
            }
            if (lines.get(i).endMs() > positionMs) {
                active.add(lines.get(i));
            }
        }
        if (active.size() == 1) {
            return List.of(active.get(0));
        }
        // 向前收集的顺序是倒的，翻回开始时间升序。
        java.util.Collections.reverse(active);
        return List.copyOf(active);
    }

    /** 当前行是否已唱完（用于间隙淡出）。 */
    public boolean isFinishedAt(long positionMs) {
        LyricLine line = lineAt(positionMs);
        return line != null && positionMs >= line.endMs();
    }

    /**
     * 当前位置是否落在行间空隙里，且空隙长到足以显示提示。
     *
     * <p>判据取自 HyperLyric 的 {@code InterludeTracker}：相邻两行的间隔
     * 超过 {@code minGapMs}，且位置确实落在「前一行结束之后、后一行开始之前」。
     *
     * <p>必要性：间奏往往长达十几秒，这段时间没有任何行可显示，
     * 若照常渲染会让最后一句歌词一直停在屏幕上，看起来像卡住了。
     */
    public boolean isInterludeAt(long positionMs, long minGapMs) {
        int index = indexAt(positionMs);
        if (index < 0) {
            return false;
        }
        LyricLine current = lines.get(index);
        LyricLine next = index + 1 < lines.size() ? lines.get(index + 1) : null;
        if (next == null) {
            return false;
        }
        if (next.startMs() - current.endMs() < minGapMs) {
            return false;
        }
        return positionMs > current.endMs() && positionMs < next.startMs();
    }
}
