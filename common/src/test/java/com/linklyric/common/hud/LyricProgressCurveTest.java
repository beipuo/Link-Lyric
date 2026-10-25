package com.linklyric.common.hud;

import com.linklyric.common.style.LyricEasing;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字上浮的取用曲线。
 *
 * <h2>这里防的是什么</h2>
 *
 * <p>上浮量由染色进度派生，但取用时必须过一道 {@link LyricEasing}。
 * 这一点被误判过：曾以「与染色保持同一条曲线」为由改成直接取线性，
 * 结果位移变成匀速平移，观感机械、像在跳格。
 *
 * <p>两类量的判据不同：<b>染色前沿</b>表达「唱到哪了」，是事实，
 * 必须与音频时间严格成正比；<b>上浮</b>表达「起来得好不好看」，
 * 是观感，需要缓动。混为一谈会得到「统一线性」这种错误的简化。
 */
class LyricProgressCurveTest {

    /** 构造成一条有四段逐字时间的行，进度会落在中段。 */
    private static LyricLine lineOfFourWords() {
        return new LyricLine("一二三四", "", 0L, 4000L, List.of(
                new LyricWord("一", 0L, 1000L),
                new LyricWord("二", 1000L, 2000L),
                new LyricWord("三", 2000L, 3000L),
                new LyricWord("四", 3000L, 4000L)), "");
    }

    /**
     * 正在演唱的那个字，其抬升进度必须等于贝塞尔值，而不是线性值。
     *
     * <p>曲线上 t=0.25 处贝塞尔已到 0.402，明显高于线性 0.25；
     * 若实现退回线性，这条断言会立刻失败。
     */
    @Test
    void 演唱中的字按贝塞尔取进度() {
        LyricLine line = lineOfFourWords();
        // 第二个字（"二"）演唱到 1/4 处
        long positionMs = 1000L + 250L;
        float[] progress = LyricProgress.charProgress(line, "一二三四", positionMs);

        float eased = LyricEasing.ease(0.25f);
        assertEquals(eased, progress[1], 1e-4f,
                "演唱中的字应取贝塞尔值 " + eased + "，实际 " + progress[1]);
        assertTrue(progress[1] > 0.25f + 0.1f,
                "贝塞尔在 t=0.25 应明显高于线性，实际 " + progress[1]);
    }

    /** 未开始与已唱完的字仍取端点，缓动不得改变 0 与 1。 */
    @Test
    void 端点不受缓动影响() {
        LyricLine line = lineOfFourWords();
        // 第三个字演唱到一半：第一、二个字已唱完，第四个字尚未开始
        float[] progress = LyricProgress.charProgress(line, "一二三四", 2000L + 500L);

        assertEquals(1f, progress[0], 1e-6f, "已唱完的字必须正好是 1");
        assertEquals(1f, progress[1], 1e-6f, "已唱完的字必须正好是 1");
        assertEquals(0f, progress[3], 1e-6f, "未开始的字必须正好是 0");
    }

    /** 进度沿演唱方向单调不减，缓动不得引入回落。 */
    @Test
    void 进度沿时间单调不减() {
        LyricLine line = lineOfFourWords();
        float previous = -1f;
        for (long ms = 0L; ms <= 4000L; ms += 25L) {
            float[] progress = LyricProgress.charProgress(line, "一二三四", ms);
            // 只看第一个字，验证它自身随时间的推进
            if (previous >= 0f) {
                assertTrue(progress[0] >= previous - 1e-6f,
                        "时刻 " + ms + " 处第一个字进度回落：" + previous + " -> " + progress[0]);
            }
            previous = progress[0];
        }
    }

    /**
     * 整行唱完时，抬升必须等于满幅，不能留下「永远差一点」的尾巴。
     *
     * <p>这是贝塞尔尾部贴顶的性质：{@code ease(1) == 1}，
     * 因此在<b>端点</b>不会像非归一化曲线那样收敛不到位。
     */
    @Test
    void 唱完时抬升达到满幅() {
        LyricLine line = lineOfFourWords();
        float fontSize = 9f;
        float[] progress = LyricProgress.charProgress(line, "一二三四", 4000L);
        float[] lifts = LyricProgress.lifts(4, fontSize, progress);

        assertEquals(4, lifts.length);
        float expected = fontSize * LyricProgress.LIFT_FONT_RATIO;
        for (int i = 0; i < lifts.length; i++) {
            assertEquals(expected, lifts[i], 1e-4f,
                    "第 " + i + " 字唱完后应达满幅 " + expected + "，实际 " + lifts[i]);
        }
    }
}
