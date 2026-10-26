package com.linklyric.common.hud;

import com.linklyric.common.hud.ScriptRuns.Run;
import com.linklyric.common.hud.ScriptRuns.Script;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中英混排的切段与度量。
 *
 * <h2>这个类能证明什么、不能证明什么</h2>
 *
 * <p><b>能证明</b>：分类规则对给定字符给出预期结果；各段宽度之和等于整行宽度；
 * 段首像素偏移逐段累加正确；代理对不会让下标错位。
 *
 * <p><b>不能证明</b>：缩到 96% 之后中英文看起来齐了。那取决于字体位图的墨迹
 * 包围盒，而那份像素在 {@code common} 层根本拿不到——它在平台的
 * {@code BitmapProvider$Glyph} 与 {@code UnihexProvider$Glyph} 里。
 * 这一条只能进游戏肉眼看，测试只能保证"喂给绘制层的数字是对的"。
 *
 * <h2>为什么度量用例用假字宽</h2>
 *
 * <p>用 {@link #EVERY_ONE} 之类的固定字宽，是为了让"宽度累加正确"这条
 * 断言**与字体无关**：真实字宽会随 MC 版本变，而累加逻辑不该变。
 * 若用真实字宽，某天字体更新导致用例失败，就分不清是逻辑坏了还是字体变了。
 */
class ScriptRunsTest {

    /** 每个码元都算 1 像素：累加结果直接等于码元数，最易读。 */
    private static final IntUnaryOperator EVERY_ONE = c -> 1;

    /**
     * 模拟本项目真实的字宽关系：CJK 9 像素，拉丁按拉丁字母占 6、标点占 3。
     *
     * <p>9 与 6/3 的比值不是随手取的：{@code SdfGlyphProvider} 里 unifont
     * 分支的 advance 是 {@code pixels.width() / 2f + 1f}，16 宽的位数据
     * 得到 9；拉丁位图字形的 advance 由 {@code GlyphInfo.getAdvance()} 给出，
     * 常见值为 6（字母）与 3（窄标点）。用它算出来的累加值
     * 才与实机量级可比。
     */
    private static final IntUnaryOperator REALISH =
            c -> ScriptRuns.scriptOf((char) c) == Script.CJK ? 9 : (c == ' ' ? 4 : 6);

    /** ASCII 可打印区判为拉丁。 */
    @Test
    void 英文字母判为拉丁() {
        for (char c = 32; c <= 126; c++) {
            assertEquals(Script.LATIN, ScriptRuns.scriptOf(c),
                    "U+" + Integer.toHexString(c) + " 应判为拉丁");
        }
    }

    /** 汉字、假名、谚文、注音都判为 CJK。 */
    @Test
    void 中日韩字符判为CJK() {
        char[] samples = {
                '中', '文', '歌', '詞',       // 汉字
                'あ', 'い', 'ア', 'イ',       // 平假名 / 片假名
                '한', '글',                   // 谚文
                'ㄅ', 'ㄆ'                    // 注音
        };
        for (char c : samples) {
            assertEquals(Script.CJK, ScriptRuns.scriptOf(c),
                    "「" + c + "」应判为 CJK");
        }
    }

    /**
     * 不确定来源的字符一律归 OTHER，不做校正。
     *
     * <p>这些包括全角标点、希腊字母、西里尔、emoji 符号。
     * 归到 OTHER 而不是 CJK，是因为**猜错方向的代价不对称**：
     * 该缩没缩只是"没改善"，不该缩却缩了是"把本来齐的字弄歪"。
     */
    @Test
    void 不确定来源的字符归为OTHER() {
        char[] samples = {
                '，', '。', '、', '！', '？',   // 全角标点
                'α', 'β',                     // 希腊
                'Д', 'Ж',                     // 西里尔
                '★', '♪', '—', '…'            // 符号
        };
        for (char c : samples) {
            assertEquals(Script.OTHER, ScriptRuns.scriptOf(c),
                    "「" + c + "」应归 OTHER（不做校正）");
        }
    }

    /** 控制字符与换行不得被当成可校正字符。 */
    @Test
    void 控制字符不做校正() {
        assertFalse(ScriptRuns.needsCorrection('\n'));
        assertFalse(ScriptRuns.needsCorrection('\t'));
        assertFalse(ScriptRuns.needsCorrection('\u0000'));
        assertTrue(ScriptRuns.needsCorrection('中'));
        assertFalse(ScriptRuns.needsCorrection('a'));
    }

    /**
     * 切段结果必须覆盖整行、无空隙、无重叠。
     *
     * <p>这条是"宽度累加正确"的骨架：只要段首尾相接、
     * 每段的 {@code start/end} 又覆盖全部码元，就不可能有字被漏算或算两次。
     */
    @Test
    void 切段覆盖整行且首尾相接() {
        String[] cases = {
                "Hello world",
                "中英混排",
                "夜の歌を聴いている",
                "Hello 世界 42",
                "한글 English 中文",
                "a",
                "中",
                ""
        };
        for (String text : cases) {
            List<Run> runs = ScriptRuns.split(text, REALISH);
            int expectedNext = 0;
            for (Run run : runs) {
                assertEquals(expectedNext, run.start(),
                        "「" + text + "」的段首必须紧接上一段段尾");
                assertTrue(run.end() > run.start(), "空段不应存在");
                expectedNext = run.end();
            }
            assertEquals(text.length(), expectedNext,
                    "「" + text + "」的段必须覆盖到最后一个码元");
        }
    }

    /**
     * 各段宽度之和必须等于逐码元独立累加的整行宽度。
     *
     * <p>这是本类最重要的一条。{@code sungWidth}、{@code LyricWrap.startPx}、
     * 折行断点全都建立在"逐字累加出来的像素位置"上；一旦分段度量与逐字累加
     * 出现分歧，染色前沿就会在某一段的边界处跳一格，而关闭态下看不到，
     * 极难归因。因此这里用两种独立算法对拍。
     */
    @Test
    void 分段宽度之和等于整行逐字累加() {
        String[] cases = {
                "Hello world",
                "中英混排 ABC",
                "夜に駆ける - YOASOBI",
                "a中b文c",
                "한글과 English",
                "12345",
                "一二三四五",
                "a b  c"
        };
        for (String text : cases) {
            int whole = 0;
            for (int i = 0; i < text.length(); i++) {
                whole += REALISH.applyAsInt(text.charAt(i));
            }
            int sum = 0;
            for (Run run : ScriptRuns.split(text, REALISH)) {
                sum += run.width();
            }
            assertEquals(whole, sum, "「" + text + "」分段宽度之和必须等于整行宽度");
        }
    }

    /** 段首像素偏移必须等于前面所有段宽之和。 */
    @Test
    void 段首偏移逐段累加() {
        String text = "Hello 世界 42";
        List<Run> runs = ScriptRuns.split(text, REALISH);
        int acc = 0;
        for (Run run : runs) {
            assertEquals(acc, run.startPx(),
                    "第 " + run.start() + " 个码元处的段首偏移应为 " + acc);
            assertEquals(acc + run.width(), run.endPx(),
                    "同段的 endPx 必须等于 startPx + width");
            acc = run.endPx();
        }
        assertEquals(acc, runs.get(runs.size() - 1).endPx(),
                "最后一段的 endPx 就是整行宽度");
    }

    /**
     * 具体的一行，逐段核对类别与宽度。
     *
     * <p>上面几条是"形状正确"，这条是"数值正确"：
     * 若有人把切分循环里的 {@code end++} 写成 {@code start++}，
     * 上面几条可能仍然通过（覆盖性与累加和都对），只有把具体数字钉住才能抓到。
     */
    @Test
    void 具体一行的分段结果符合预期() {
        // "Hello 世界"
        //   H e l l o _  = 6 码元拉丁，其中空格 4，字母 6 → 5*6 + 4 = 34
        //   世 界        = 2 码元 CJK → 18
        String text = "Hello 世界";
        List<Run> runs = ScriptRuns.split(text, REALISH);

        assertEquals(2, runs.size(), "应切出一个拉丁段和一个 CJK 段");

        Run latin = runs.get(0);
        assertEquals(Script.LATIN, latin.script());
        assertEquals(0, latin.start());
        assertEquals(6, latin.end());
        assertEquals(0, latin.startPx());
        assertEquals(34, latin.width());

        Run cjk = runs.get(1);
        assertEquals(Script.CJK, cjk.script());
        assertEquals(6, cjk.start());
        assertEquals(8, cjk.end());
        assertEquals(34, cjk.startPx(), "CJK 段必须紧接拉丁段之后");
        assertEquals(18, cjk.width());
        assertEquals(52, cjk.endPx(), "整行宽度应为 52");
    }

    /**
     * 空输入与 null 必须返回空表，不得抛异常。
     *
     * <p>歌词行可能为空（间奏），切段被调用时若抛 NPE，
     * 整帧歌词会消失——而这条路径在关闭态下不执行，
     * 只有开启后才会炸，等于给用户埋了一个开关触发的崩溃。
     */
    @Test
    void 空输入返回空表() {
        assertTrue(ScriptRuns.split(null, EVERY_ONE).isEmpty());
        assertTrue(ScriptRuns.split("", EVERY_ONE).isEmpty());
        assertFalse(ScriptRuns.isMixed(null));
        assertFalse(ScriptRuns.isMixed(""));
    }

    /**
     * 代理对必须不导致下标错位。
     *
     * <p>切段按码元走，与 {@code LyricWrap.wrap}、{@code LyricProgress.charStarts}
     * 同粒度。扩展 B 区汉字（如 U+20000）占两个码元，它的两个码元
     * 在 {@code UnicodeScript.of(char)} 下都会落到 OTHER，
     * 于是被整体归入 OTHER 段——**不缩放，但也不会让后面的下标偏移**。
     */
    @Test
    void 代理对不让下标错位() {
        String text = "字\uD840\uDC00字";   // 字 + U+20000 + 字
        assertEquals(4, text.length(), "代理对应占两个码元");
        List<Run> runs = ScriptRuns.split(text, EVERY_ONE);
        int acc = 0;
        for (Run run : runs) {
            assertEquals(acc, run.start(), "段首不得因代理对而错位");
            acc = run.end();
        }
        assertEquals(4, acc, "必须覆盖全部 4 个码元");
        // 首尾是真汉字，中段是扩展 B —— 因此至少切成三段
        assertTrue(runs.size() >= 3,
                "代理对应把它两侧的汉字分隔开，实际段数=" + runs.size());
    }

    /**
     * 纯中文行或纯英文行不判为混排。
     *
     * <p>渲染器靠这个判断跳过整条校正路径。若把纯行误判为混排，
     * 纯中文歌词也会被缩放——那不是"弥合差异"，而是单方面改小所有中文。
     */
    @Test
    void 纯行不判为混排() {
        assertFalse(ScriptRuns.isMixed("Hello world"));
        assertFalse(ScriptRuns.isMixed("夜に駆ける"));
        assertFalse(ScriptRuns.isMixed("中英混排"));
        assertFalse(ScriptRuns.isMixed("12345"));
    }

    /** 混排行必须判为混排。 */
    @Test
    void 混排行判为混排() {
        assertTrue(ScriptRuns.isMixed("Hello 世界"));
        assertTrue(ScriptRuns.isMixed("夜に駆ける - YOASOBI"));
        assertTrue(ScriptRuns.isMixed("a中"));
        assertTrue(ScriptRuns.isMixed("中a"));
    }

    /**
     * 纯中文行里夹空格，不得因此被判成混排。
     *
     * <p>否则「一 二 三 四」这种排版就会被当成混排而缩放，
     * 而它里面一个拉丁字符都没有。
     */
    @Test
    void 纯中文夹空格不判为混排() {
        assertFalse(ScriptRuns.isMixed("中 文 歌 词"),
                "空格不构成「另一类」，纯中文行夹空格仍不是混排");
        assertFalse(ScriptRuns.isMixed("中\u3000文"),
                "全角空格同理");
    }

    /**
     * 段内字符必须同类——这是切段正确性的直接断言。
     *
     * <p>上面几条都在看段与段之间的关系，这条看段内：若分类循环写错
     * （例如用 {@code scriptOf(text.charAt(start))} 而不是 {@code charAt(end)} 判下一字），
     * 段内就会混入异类码元。
     */
    @Test
    void 段内字符全部同类() {
        String[] cases = {
                "Hello 世界 42 again",
                "夜に駆ける - YOASOBI",
                "한글 and English and 中文"
        };
        for (String text : cases) {
            for (Run run : ScriptRuns.split(text, REALISH)) {
                for (int i = run.start(); i < run.end(); i++) {
                    assertEquals(run.script(), ScriptRuns.scriptOf(text.charAt(i)),
                            "「" + text + "」的段 [" + run.start() + "," + run.end()
                                    + ") 内第 " + i + " 个码元类别不一致");
                }
            }
        }
    }
}
