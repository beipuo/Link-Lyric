package com.linklyric.core.lyric;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐字时间的修补。
 *
 * <p>歌词源给出的字级时间经常是残缺的，而残缺在行中间看不出来：
 * 染色逻辑只是「找到一个包含当前时刻的字」，没人所处的字会被直接跳过。
 * 但行末没有下一个字可以接住填充，于是推算会跑出数组边界，
 * 把整行报成已唱——最后一个字在一帧内跳完，永远得不到它应得的那一段时间。
 *
 * <p>这里在时间轴构造时一次性补齐，而不是在渲染层逐帧判断：
 * 修补是数据的性质，与绘制无关。
 */
public final class LyricTimingFix {

    /**
     * 一个字在无法从数据推出时长时得到的名义时长，毫秒。
     *
     * <p>只影响「连行长都没有」的极端情况；取值约等于一个字的正常演唱时间，
     * 让填充是扫过去而不是瞬间跳过去。
     */
    public static final long NOMINAL_WORD_MS = 300L;

    /**
     * 最后一个字在等待下一个时间点时的最长保持，毫秒。
     *
     * <p>歌手会把一个音拖过整段副歌，但没人能拖过间奏。
     * 超过这个长度的等待视为间奏，交还给渲染层的间奏提示处理。
     * 该值与渲染层的间奏阈值同量级，两边必须一致，否则会出现
     * 「既没有拖音也没有间奏提示」的空洞。
     */
    public static final long MAX_HELD_MS = 4000L;

    private LyricTimingFix() {
    }

    /**
     * 修补整首歌的逐字时间。
     *
     * @param lines 已按时间升序排列的行
     * @return 修补后的新列表；无需修补时返回原列表
     */
    public static List<LyricLine> fix(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines == null ? List.of() : lines;
        }
        List<LyricLine> out = new ArrayList<>(lines.size());
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            LyricLine fixed = fixLine(line, nextStart(lines, i));
            changed |= fixed != line;
            out.add(fixed);
        }
        return changed ? List.copyOf(out) : lines;
    }

    /**
     * 修补单行的逐字时间。
     *
     * @param line      待修补的行
     * @param nextStart 下一行的开始时间；没有下一行时传 {@link Long#MAX_VALUE}
     */
    public static LyricLine fixLine(LyricLine line, long nextStart) {
        if (line == null) {
            return null;
        }
        List<LyricWord> words = line.words();
        if (words.size() <= 1) {
            // 行级歌词：整行就是一个单元，时长由行本身给出，无残缺可补。
            return line;
        }
        long[] starts = new long[words.size()];
        long[] ends = new long[words.size()];
        for (int i = 0; i < words.size(); i++) {
            starts[i] = words.get(i).startMs();
            ends[i] = words.get(i).endMs();
        }
        if (!closeUntimedTail(starts, ends, line.endMs(), nextStart)) {
            return line;
        }
        List<LyricWord> fixed = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            LyricWord w = words.get(i);
            fixed.add(ends[i] == w.endMs() && starts[i] == w.startMs()
                    ? w : new LyricWord(w.text(), starts[i], ends[i]));
        }
        return new LyricLine(line.text(), line.translation(), line.startMs(), line.endMs(),
                fixed, line.speaker());
    }

    /**
     * 让「源未给出跨度」的那一段字分到它们本应占用的时间。
     *
     * <p>逐字时间常常在行结束之前就用完。一连串 {@code end == start} 的字
     * 构成一段无时间的尾巴，这段尾巴按顺序均分到「该段结束」为止：
     * 若后面还有开始更晚的字，以那个字为界；否则以行末为界；
     * 行末本身也不晚于该段起点时（只按字计时的文件就是这样），
     * 退而使用下一行的开始，但不超过 {@link #MAX_HELD_MS}。
     *
     * @return 是否有任何修改
     */
    static boolean closeUntimedTail(long[] starts, long[] ends, long lineEnd, long nextStart) {
        boolean changed = false;
        int k = 0;
        while (k < starts.length) {
            if (ends[k] > starts[k]) {
                k++;
                continue;
            }
            int last = k;
            while (last + 1 < starts.length && ends[last + 1] <= starts[last + 1]) {
                last++;
            }
            long from = starts[k];
            long to = lineEnd;
            for (int m = last + 1; m < starts.length; m++) {
                if (starts[m] > from) {
                    to = starts[m];
                    break;
                }
            }
            if (to <= from) {
                long nominal = from + NOMINAL_WORD_MS * (last - k + 1);
                to = nextStart > from && nextStart - nominal < MAX_HELD_MS ? nextStart : nominal;
            }
            int count = last - k + 1;
            for (int m = k; m <= last; m++) {
                long value = from + (to - from) * (m - k + 1) / count;
                if (ends[m] != value) {
                    ends[m] = value;
                    changed = true;
                }
            }
            k = last + 1;
        }
        return changed;
    }

    private static long nextStart(List<LyricLine> lines, int index) {
        return index + 1 < lines.size() ? lines.get(index + 1).startMs() : Long.MAX_VALUE;
    }
}
