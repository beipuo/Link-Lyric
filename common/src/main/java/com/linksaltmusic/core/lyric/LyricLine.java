package com.linksaltmusic.core.lyric;

import java.util.List;

/**
 * 一行歌词。
 *
 * @param text        原文
 * @param translation 翻译，无翻译时为空串
 * @param startMs     该行开始时间
 * @param endMs       该行结束时间
 * @param words       字级时间轴；行级歌词时包含单个覆盖全行的单元
 * @param speaker     对唱声部标识，无对唱时为空串
 */
public record LyricLine(
        String text,
        String translation,
        long startMs,
        long endMs,
        List<LyricWord> words,
        String speaker) {

    public LyricLine {
        text = text == null ? "" : text;
        translation = translation == null ? "" : translation;
        speaker = speaker == null ? "" : speaker;
        startMs = Math.max(0L, startMs);
        endMs = Math.max(startMs, endMs);
        words = words == null ? List.of() : List.copyOf(words);
        if (words.isEmpty() && !text.isEmpty()) {
            // 行级歌词的统一表示：整行作为一个字级单元，渲染层无需特判。
            words = List.of(new LyricWord(text, startMs, endMs));
        }
    }

    public long durationMs() {
        return endMs - startMs;
    }

    public boolean hasTranslation() {
        return !translation.isEmpty();
    }

    public boolean hasSpeaker() {
        return !speaker.isEmpty();
    }

    /** 该行整体进度，范围 [0,1]。 */
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
