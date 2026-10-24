package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;

import java.util.List;

/**
 * 逐字染色进度与上浮量的计算。
 *
 * <p>上浮与染色共用同一个进度量：字被染到多少就升起多少，染完即升满。
 * 两者不使用独立的缓动时间常数，否则颜色与位移会错位。
 */
final class LyricProgress {

    /** 抬升幅度，以字号比例计。 */
    static final float LIFT_FONT_RATIO = 0.10f;

    /** 逐字绘制的最多字数，超过则退回整段绘制。 */
    static final int MAX_LIFT_CHARS = 24;

    private LyricProgress() {
    }

    /** 每个字各自的染色进度，取值 0..1。 */
    static float[] charProgress(LyricLine line, String visible, long positionMs) {
        int count = visible.length();
        float[] progress = new float[count];
        if (count == 0) {
            return progress;
        }

        List<LyricWord> words = line.words();
        if (words.size() <= 1) {
            float ratio = line.progressAt(positionMs);
            float value = ratio <= 0f ? 0f : (ratio >= 1f ? 1f : ratio);
            java.util.Arrays.fill(progress, value);
            return progress;
        }

        int index = 0;
        for (LyricWord word : words) {
            if (index >= count) {
                break;
            }
            String wordText = word.text();
            if (wordText.isEmpty()) {
                continue;
            }
            int available = Math.min(wordText.length(), count - index);
            float ratio = word.progressAt(positionMs);
            float value;
            if (positionMs >= word.endMs()) {
                value = 1f;
            } else if (positionMs <= word.startMs()) {
                value = 0f;
            } else {
                value = LyricEasing.ease(ratio);
            }
            for (int k = 0; k < available; k++) {
                progress[index + k] = value;
            }
            index += available;
        }
        return progress;
    }

    /**
     * 每个字的亚像素抬升量。
     *
     * @param charCount     实际字数，决定是否值得逐字绘制
     * @param fontSizePx    估算字号，决定抬升幅度
     * @param charProgress  每个字的染色进度，与抬升共用同一量
     * @return null 表示不值得逐字绘制，调用方应退回整段绘制
     */
    static float[] lifts(int charCount, float fontSizePx, float[] charProgress) {
        if (charCount <= 1 || charCount > MAX_LIFT_CHARS || fontSizePx <= 0f) {
            return null;
        }
        int n = Math.min(charCount, charProgress.length);
        float maxOffset = fontSizePx * LIFT_FONT_RATIO;
        float[] lifts = new float[charCount];
        for (int i = 0; i < n; i++) {
            lifts[i] = maxOffset * charProgress[i];
        }
        return lifts;
    }

    /** 每个字的左边界像素坐标。 */
    static int[] charStarts(FloatingLyricRenderer.Painter painter, String text) {
        int[] starts = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            starts[i] = painter.textWidth(text.substring(0, i));
        }
        return starts;
    }

    /** 估算单个字的宽度：行宽除以字数。 */
    static float fontSizeOf(int lineWidth, int charCount) {
        if (charCount <= 0) {
            return 0f;
        }
        return lineWidth / (float) charCount;
    }

}
