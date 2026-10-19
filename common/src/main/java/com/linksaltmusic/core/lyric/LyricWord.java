package com.linksaltmusic.core.lyric;

/**
 * 单个逐字单元。
 *
 * <p>时间取自 TTML 的字级时间轴。若歌词源只提供行级时间，整行会被表示为
 * 一个覆盖全行时长的 {@code LyricWord}，渲染层无需分支处理。
 */
public record LyricWord(String text, long startMs, long endMs) {

    public LyricWord {
        text = text == null ? "" : text;
        startMs = Math.max(0L, startMs);
        endMs = Math.max(startMs, endMs);
    }

    public long durationMs() {
        return endMs - startMs;
    }

    /** 该字是否正被演唱。 */
    public boolean activeAt(long positionMs) {
        return positionMs >= startMs && positionMs < endMs;
    }

    /** 该字的演唱进度，范围 [0,1]，已唱完为 1，未开始为 0。 */
    public float progressAt(long positionMs) {
        long length = durationMs();
        if (length <= 0L) {
            return positionMs >= endMs ? 1f : 0f;
        }
        long elapsed = positionMs - startMs;
        if (elapsed <= 0L) {
            return 0f;
        }
        if (elapsed >= length) {
            return 1f;
        }
        return (float) elapsed / (float) length;
    }
}
