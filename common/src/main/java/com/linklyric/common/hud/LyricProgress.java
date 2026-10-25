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

    /** 逐字绘制的最多字数。超出仍是整段绘制，因为逐字成本随字数线性上升。 */
    static final int MAX_LIFT_CHARS = 128;

    /**
     * 余弦过渡带宽度，以字号比例计。
     *
     * <p>取值来自 Salt Player 的 {@code floatUpWidth}：过渡带略宽于一格字，
     * 前沿扫过一个字时整字都在渐变区间内，看不到逐字跳变。
     */
    static final float LIFT_BAND_FONT_RATIO = 1.2f;

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
     * 余弦上浮曲线：前沿右侧的字仍在高位附近，左侧的字已升满。
     *
     * <p>形状照搬 Salt Player 的歌词上浮（{@code spc.lyrics} 里的
     * {@code floatUpRectCells}）。归一化到过渡带内的相位 {@code t}：
     * <pre>
     *   t = (字中心 - 前沿 + 带宽/2) / 带宽
     *   k = t &lt;= 0 ? 1 : (t &gt;= 1 ? 0 : 0.5 + 0.5·cos(π·t))
     * </pre>
     * 两端梯度都为 0，所以前沿推进时字不会在起落处「弹」一下。
     * 换成正弦或线性斜坡，两端的折角在浅色字上很明显。
     *
     * @param centerDistances 每个字中心到行首的距离
     * @param front           当前演唱前沿（到行首的距离）
     * @param band            过渡带宽度，应不小于一格字宽
     * @return 与 {@code centerDistances} 等长的系数，取值 0..1
     */
    static float[] cosineLiftFactors(float[] centerDistances, float front, float band) {
        float[] factors = new float[centerDistances.length];
        if (band <= 0f) {
            java.util.Arrays.fill(factors, 1f);
            return factors;
        }
        float half = band * 0.5f;
        for (int i = 0; i < centerDistances.length; i++) {
            float t = (centerDistances[i] - front + half) / band;
            if (t <= 0f) {
                factors[i] = 1f;
            } else if (t >= 1f) {
                factors[i] = 0f;
            } else {
                factors[i] = (float) (0.5 + 0.5 * Math.cos(Math.PI * t));
            }
        }
        return factors;
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
        if (charCount <= 1) {
            return null;
        }
        // 上限只用于限制逐字绘制的开销；这里还要承载强调动画，
        // 因此放宽到覆盖常见整行。绘制本身已按可见区间提前跳过。
        if (charCount > MAX_LIFT_CHARS || fontSizePx <= 0f) {
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

    /**
     * 以余弦曲线计算整行抬升。
     *
     * <p>与 {@link #lifts} 的区别在于形状：{@code lifts} 让每个字各自随
     * 自身进度升起，长音上会看到字一个接一个跳起来；这里把整行当成
     * 一条横跨的斜坡，已唱满的字恒在最高位，未唱到的字恒在基线，
     * 两者之间用余弦带连接，过渡带永远跟着演唱点走。
     *
     * @param charStarts   每个字左边界的像素坐标
     * @param charWidths   每个字的宽度
     * @param sungWidth    已唱段宽度（到行首的像素距离），即前沿位置
     * @param fontSizePx   估算字号
     * @return null 表示不值得逐字绘制
     */
    static float[] cosineLifts(int[] charStarts, int[] charWidths, float sungWidth, float fontSizePx) {
        int count = charStarts.length;
        if (count <= 1 || count > MAX_LIFT_CHARS || fontSizePx <= 0f) {
            return null;
        }
        float[] centers = new float[count];
        for (int i = 0; i < count; i++) {
            float width = i < charWidths.length ? charWidths[i] : fontSizePx;
            centers[i] = charStarts[i] + width * 0.5f;
        }
        float band = fontSizePx * LIFT_BAND_FONT_RATIO;
        float[] factors = cosineLiftFactors(centers, sungWidth, band);
        float maxOffset = fontSizePx * LIFT_FONT_RATIO;
        float[] lifts = new float[count];
        for (int i = 0; i < count; i++) {
            lifts[i] = maxOffset * factors[i];
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
