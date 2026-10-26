package com.linklyric.common.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * 一行歌词按书写系统切段与分段度量。
 *
 * <h2>为什么需要"切段"，而不是只判"这个字是不是中文"</h2>
 *
 * <p>逐字绘制时确实只需要一个 O(1) 判定（{@link #scriptOf(char)}），
 * 而这个类提供的 {@link #split} 显得多余。多出来的那部分是**验证**：
 * 中英混排要改动的是"每个 run 的缩放与基线"，而 run 一旦算错
 * （边界错一字、宽度累加漏掉代理对、空白归属错），症状是
 * "某一段整体偏移半格"——在 9px 字号下极难肉眼定位，且在关闭态下
 * 完全看不见（因为关闭时根本不走这条路径）。把它做成一个可单独喂输入、
 * 单独断言输出的纯函数，才能在没有实机的情况下钉住"宽度累加正确"。
 *
 * <p>热路径仍用 {@link #scriptOf(char)} 逐字判定、零分配；
 * {@code split} 是同一套分类规则的<b>可测展开</b>。两者必须给出同样的分类，
 * 这一条由 {@code MixedScriptRunsTest} 用"逐字判定拼出来的段"与
 * {@code split} 的结果对拍钉住——否则两条路径会静默分叉。
 *
 * <h2>切分粒度为什么是 UTF-16 码元，不是码点</h2>
 *
 * <p>任务描述里写的是"按码点区间切段"，但本项目里**所有**逐字坐标都是按
 * 码元算的：{@code LyricProgress.charStarts} 逐个 {@code char} 累加，
 * {@code LyricWrap.wrap} 按 {@code text.charAt(i)} 切分，其类注释明确写着
 * "折行按 UTF-16 码元切分，与逐字绘制用的是同一粒度，因此代理对会被当成
 * 两个码元——与 {@code clip} 及 {@code charStarts} 的现有行为一致，
 * 不额外引入码点语义"。
 *
 * <p>若这里改用码点，{@link Run#start()} 与 {@code charStarts} 的下标就会
 * 在含代理对的行上错位：一个扩展 B 区汉字占两个码元、却只算一个码点，
 * 后面的所有偏移都会少 1。而 {@code sungWidth} 是**像素**量、{@code startPx}
 * 也是像素量，二者靠"第几个字"对齐，下标一错就是整段染色错位。
 * 因此切段沿用码元粒度，与既有全部逐字逻辑同源。
 *
 * <p>CJK 判定用 {@link Character.UnicodeScript}，它接受 {@code int} 码点；
 * 这里传的是单个 {@code char}，对 BMP 内的汉字、假名、谚文完全正确
 * （它们全部在 BMP）。代理对的高低位会被判成 {@code UNKNOWN} 而归入
 * {@link Script#OTHER}——这是**安全的降级**：扩展区汉字不会被误缩，
 * 但也不会导致任何索引错位。
 *
 * <h2>这三个分类是怎么分的</h2>
 *
 * <p>分组依据不是"语言"，是**字形来源**，因为观感差异的根源在于
 * 拉丁走 {@code BitmapProvider}、中文走 {@code UnihexProvider}（见
 * {@code SdfGlyphProvider.bake} 的两条取像素路径）：
 *
 * <ul>
 *   <li>{@link Script#LATIN}：ASCII 可打印区 32..126。DangoMusic 用
 *   {@code isLatin(c) = c.code in 32..126} 划这一段，这里采用同一区间——
 *   理由不是"抄它"，而是这个区间恰好等于 MC 默认位图字体覆盖的那批字形。</li>
 *   <li>{@link Script#CJK}：汉字、平假名、片假名、谚文、注音。
 *   这些字形都来自 unifont 那一套 16 行位数据。</li>
 *   <li>{@link Script#OTHER}：其余一切——拉丁扩展、希腊、西里尔、
 *   全角标点、符号、emoji。归到 OTHER 而不是并进 CJK 或 LATIN，
 *   是因为**不知道**它们从哪个 provider 来：把它们当作不需要校正，
 *   最差是"保持现状"，而猜错方向会让本来对齐的字变歪。</li>
 * </ul>
 *
 * <p>空白（U+0020）落在 LATIN 区间内。这是有意的：空格没有墨迹，
 * 缩放与基线对它的可见效果为零，把它单列一类只会让 run 数翻倍、
 * 且"英文单词 + 空格"被切成两段后，段内统一缩放的范围反而变碎。
 */
public final class ScriptRuns {

    /** 一行按字形来源切出的段类别。 */
    public enum Script {
        /** ASCII 可打印区，MC 位图字体覆盖的那批字形。 */
        LATIN,
        /** 汉字、假名、谚文、注音，unifont 提供。 */
        CJK,
        /** 其余码元：不确定性最大的一类，保守地不做校正。 */
        OTHER
    }

    /**
     * 一段连续同类码元。
     *
     * @param script  段类别
     * @param start   段首在整行里的码元下标（含）
     * @param end     段尾码元下标（不含）
     * @param startPx 段首在整行里的像素偏移，即前面各段宽度之和
     * @param width   段宽，像素
     */
    public record Run(Script script, int start, int end, int startPx, int width) {

        /** 段尾在整行里的像素偏移。 */
        public int endPx() {
            return startPx + width;
        }

        /** 本段码元数。 */
        public int length() {
            return end - start;
        }
    }

    private ScriptRuns() {
    }

    /**
     * 单个码元的类别。
     *
     * <p>热路径入口：逐字绘制时每个字调一次，必须零分配、O(1)。
     * 用 {@code switch} 而不是查表，是因为 JIT 会把这几条
     * {@code Character.UnicodeScript.of} 的返回常量比较折成整数比较，
     * 而静态表要在类初始化时建 65536 项，代价远大于收益。
     *
     * @param c 一个 UTF-16 码元
     * @return 该码元所属类别
     */
    public static Script scriptOf(char c) {
        if (c >= 32 && c <= 126) {
            return Script.LATIN;
        }
        // UnicodeScript.of 收 int 码点。传 char 时它按无符号扩展，
        // 对 BMP 内的码元就是码点本身，因此汉字/假名/谚文都能正确识别。
        return switch (Character.UnicodeScript.of(c)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> Script.CJK;
            // 兼容汉字（U+F900..U+FAFF）与 CJK 符号标点（U+3000..U+303F）
            // 在 UnicodeScript 里各归 HAN 与 COMMON，后者会落到 OTHER——
            // 那是刻意的：中文标点的墨迹比汉字小一圈，按汉字缩放反而更不齐。
            default -> Script.OTHER;
        };
    }

    /** 该码元是否属于需要混排校正的一类。 */
    public static boolean needsCorrection(char c) {
        return scriptOf(c) == Script.CJK;
    }

    /**
     * 把一行切成若干段并逐段度量。
     *
     * <p>像素偏移与宽度一律由 {@code charWidth} 累加得到，与
     * {@link LyricWrap#wrap}、{@link LyricProgress#charStarts} 用的是
     * 同一个度量来源。因此各段 {@link Run#width()} 之和**必然**等于
     * {@code 整行宽度}，不依赖任何取整假设——这条是 {@code split} 存在的
     * 主要理由：它是"宽度累加正确"唯一可机械验证的形态。
     *
     * @param text      原文，null 或空串返回空表
     * @param charWidth 码元步进宽度，与绘制同一来源
     * @return 按顺序排列的段；原文为空时为空表
     */
    public static List<Run> split(String text, IntUnaryOperator charWidth) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Run> out = new ArrayList<>(4);
        int length = text.length();
        int start = 0;
        int startPx = 0;
        while (start < length) {
            Script script = scriptOf(text.charAt(start));
            int end = start + 1;
            int width = Math.max(0, charWidth.applyAsInt(text.charAt(start)));
            // 同类码元一路吃到底。判据只看下一个码元的类别，
            // 不看"当前累计宽度是否超限"——切段是**内容**的划分，
            // 与视口宽度无关；超宽属于折行（LyricWrap）的职责，
            // 两件事若混在一起，"缩放到哪一段"就会随屏幕宽度变化。
            while (end < length && scriptOf(text.charAt(end)) == script) {
                width += Math.max(0, charWidth.applyAsInt(text.charAt(end)));
                end++;
            }
            out.add(new Run(script, start, end, startPx, width));
            startPx += width;
            start = end;
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 该行的分段里是否存在需要校正的段。
     *
     * <p>渲染器用它做一次廉价的前置判断：纯英文或纯中文的行直接跳过
     * 整条校正路径。纯行不需要校正是显然的——校正的定义就是"弥合两类
     * 字形之间的差"，只有一类时没有差可弥合。
     *
     * <p>这个判断在逐字路径上并非必需（每个字各自判定即可），但它在
     * **段落级**有意义：折行时先判一次，就能整段跳过。
     *
     * @param text 原文
     * @return 是否至少有一个 {@link Script#CJK} 码元，且至少有一个非 CJK 码元
     */
    public static boolean isMixed(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean cjk = false;
        boolean other = false;
        for (int i = 0; i < text.length(); i++) {
            // 空白不计入"另一类"：`"你好 world"` 与 `"你好world"`
            // 应当同判为混排。若把空格算作非 CJK，纯中文行里夹一个空格
            // 就会被判成混排而进入校正路径，白白多算一遍。
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\u3000') {
                continue;
            }
            if (scriptOf(c) == Script.CJK) {
                cjk = true;
            } else {
                other = true;
            }
            if (cjk && other) {
                return true;
            }
        }
        return false;
    }
}
