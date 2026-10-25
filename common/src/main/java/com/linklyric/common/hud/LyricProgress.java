package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;

import java.util.List;

/**
 * 逐字染色进度与上浮量的计算。
 *
 * <p>上浮量由染色进度派生：字被染到多少就升起多少，染完即升满。
 * 两者共用同一个进度量，不引入独立的时间常数——那样颜色与位移会错位。
 *
 * <p>但「共用进度量」不等于「共用同一条曲线」。上浮在取用进度时
 * 再过一道 {@link LyricEasing}，原因见 {@link #charProgress} 的注释。
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
                // 上浮必须过一道贝塞尔，不能直接取线性进度。
                //
                // <p>这里一度被改成 {@code value = ratio}，理由是「与染色用同一条
                // 曲线」。那个理由只对了一半：染色（{@code targetWidthAt}）走线性，
                // 是因为<b>前沿位置</b>必须与音频时间严格成正比——那是「唱到哪」，
                // 属于事实，不允许被艺术加工，否则口型对不上。
                //
                // <p>上浮是<b>观感</b>，不是事实。字从基线抬到顶点用线性，
                // 每一帧位移都相同，观感是匀速平移，机械且没有重量；
                // 而 0.9px 这么小的位移，匀速反倒显得「卡」——
                // 眼睛读到的是位置在台阶式变化，而不是「被托起来」。
                //
                // <p>{@code CubicBezier(0.25, 0, 0.2, 1)} 的形状是
                // 「起步蓄力、中段抬起、尾部贴顶」，正是位移该有的手感。
                // 它作用在<b>已经由时间决定</b>的进度上，只重新分配速度分布，
                // 不改变起止时刻与总量，因此不会让颜色和位移错位：
                // 两者仍在同一时刻开始、同一时刻结束。
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

    /**
     * 每个字的左边界像素坐标。
     *
     * <p>用逐字步进累加，不再对每个前缀重新度量整段文本。
     * 旧写法是 {@code textWidth(text.substring(0, i))}：度量是 O(i) 的，
     * 循环 n 次得到 O(n²)，并且每一步都分配一个临时字符串。
     * 一句 30 字在 60fps 下就是每秒约 27900 次字形查找与 1800 个短命字符串，
     * 全部落在渲染热路径上。累加版是 O(n) 度量、零分配。
     */
    static int[] charStarts(FloatingLyricRenderer.Painter painter, String text) {
        int count = text.length();
        int[] starts = new int[count];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            starts[i] = cursor;
            cursor += painter.charWidth(text.charAt(i));
        }
        return starts;
    }

    /**
     * 参考字宽：抬升与过渡带的尺度基准。
     *
     * <h2>旧实现的错误</h2>
     *
     * <p>这里曾经写成 {@code lineWidth / charCount}，并命名为「字号」。
     * 但那个商是<b>平均每格宽度</b>，不是字号。
     *
     * <p>中文每字正好 9px、行宽又是字数的整数倍，
     * 所以 {@code 9 × n / n} 恰好等于 9，看不出问题；
     * 一旦变成英文、空格或窄字比例高的句子，结果就偏得厉害。
     *
     * <p>实测：同样是 9px 字号，
     * {@code "abcdefghijklmnop"} 算出 6.0，
     * {@code "i i i i i i i i i i"} 算出 4.0（真实值 9）。
     *
     * <h2>为什么影响逐字动画</h2>
     *
     * <p>两个后果都从这个估值乘出来：
     * 抬升幅度 = 估值 × 0.10，过渡带 = 估值 × 1.2。
     * 过渡带变窄到接近一个字宽时，前沿扫过的那个字
     * 会整体同时起落，而不是被渐变扫过——观感就是「字在跳」。
     *
     * <h2>修正</h2>
     *
     * <p>不再从行宽推测字号：行宽只能反推「平均宽度」，
     * 而这里真正需要的是「一个字的尺度量级」。
     * 改取<b>行内最大字宽</b>：它直接取自实际字形，
     * 不受文字种类与空格数量影响，也就不会因为一句里
     * 少数几个宽字而把整句的动画压扁。
     *
     * @param charWidths 逐字宽度（像素），空白字符不计入
     * @return 参考字宽；无法取值时返回 0
     */
    static float referenceSize(int[] charWidths) {
        int max = 0;
        for (int w : charWidths) {
            if (w > max) {
                max = w;
            }
        }
        return max;
    }

    /**
     * 由行宽与字数估算参考字宽。
     *
     * <p>仅在拿不到逐字宽度时使用（例如测试替身、
     * 或调用方只有整行宽）。它仍然是平均值，
     * 但至少不会因为没有逐字信息而完全无法工作。
     */
    static float referenceSize(int lineWidth, int charCount) {
        if (charCount <= 0) {
            return 0f;
        }
        return lineWidth / (float) charCount;
    }

}
