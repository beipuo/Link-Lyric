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

    public LyricTimeline(List<LyricLine> lines) {
        List<LyricLine> sorted = new ArrayList<>(lines == null ? List.of() : lines);
        sorted.sort(Comparator.comparingLong(LyricLine::startMs));
        this.lines = List.copyOf(sorted);
        this.starts = new long[this.lines.size()];
        for (int i = 0; i < this.lines.size(); i++) {
            starts[i] = this.lines.get(i).startMs();
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

    /** 当前行是否已唱完（用于间隙淡出）。 */
    public boolean isFinishedAt(long positionMs) {
        LyricLine line = lineAt(positionMs);
        return line != null && positionMs >= line.endMs();
    }
}
