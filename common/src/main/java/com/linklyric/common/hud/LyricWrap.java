package com.linklyric.common.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * 超长歌词的折行切分。
 *
 * <h2>为什么折行落在这一层，而不是放置策略里</h2>
 *
 * <p>折行要量字宽，量字宽要平台（{@link FloatingLyricRenderer.Painter}）。
 * 放置策略刻意拿不到绘制器——{@link PlacementInput} 的注释写得很明确：
 * 「行内容在这里就截断好，策略只负责摆位置」。若把折行搬进策略，
 * 每个策略都得自己实现一份「多长算超宽、在哪里断开」，而这条规则
 * 与排布形状无关，是内容问题。所以折行和截断待在同一层：
 * 渲染器把原文切成若干段，策略照旧只管堆叠。
 *
 * <p>但这层选择有一个代价，也解释了为什么 {@link PlacementInput}
 * 要多一个 {@code primarySpan}：折行**改变了行数**，而 {@link LineLayout}
 * 的行高按「主行 / 副行」二分。拆成两段的同一句，两段都是主行字号，
 * 副行才缩到 0.8。策略必须知道「前几行是主行自己的段」，
 * 否则第二段会被当成副行缩一档、行高也少算 1.8 像素。
 *
 * <h2>断点规则</h2>
 *
 * <p>英文在空格后断，中文、日文与标点可在任意字间断。
 * 判据是「前一个字符是否 ASCII 字母数字」：是就必须等到空格，
 * 否就允许直接断开。这样 {@code "lost in the echo"} 不会断成
 * {@code "lost in the ech" + "o"}，而中文不受影响。
 *
 * <p>找不到断点时硬切。长串没有空格的英文（URL、缩写堆叠）若不肯硬切，
 * 贪心会在同一处反复给出零进度——那是个死循环，不是排版。
 *
 * <h2>为什么段数有上限</h2>
 *
 * <p>行宽被 {@code maxWidthRatio} 压到屏幕的 30%..100%，
 * 一句 150 字的长句在 30% 宽下能折出十几行。行数没有上限时，
 * 整块歌词会高到把副行、把物品栏、把自己都推出屏幕——
 * 那比「读不全」更糟。{@link #MAX_LINES} 封顶，超出的尾巴截断加省略号，
 * 明确告诉读者后面还有字。
 *
 * <p>上限不取 2：双行是常见配置，若一折就吃掉全部槽位，
 * 下一句预览会永远消失，用户会觉得「开了折行就看不清下一句」。
 * 取 4 是「常规 9px 字号 + 270 高 HUD」下仍能完整显示的经验值。
 */
public final class LyricWrap {

    /**
     * 一句最多折成几行。
     *
     * <p>超过这个段数就截断。理由见类注释：行数无上限时块高会失控。
     */
    public static final int MAX_LINES = 4;

    /** 截断处的省略号。与渲染器 {@code clip} 用的是同一个字符。 */
    private static final String ELLIPSIS = "…";

    private LyricWrap() {
    }

    /**
     * 折行结果的一段。
     *
     * @param start     段首字符在**整句**里的下标（含）
     * @param end       段尾字符下标（不含）；{@code truncated} 时不含被省略的尾巴
     * @param text      本段要画的文本；{@code truncated} 时带尾随省略号
     * @param width     本段像素宽度，含省略号
     * @param startPx   段首在整句里的像素偏移，即前面各段宽度之和
     * @param truncated 是否因段数用尽而截断
     */
    public record Segment(
            int start,
            int end,
            String text,
            int width,
            int startPx,
            boolean truncated) {

        /** 段尾在整句里的像素偏移。 */
        public int endPx() {
            return startPx + width;
        }

        /**
         * 把整句的染色前沿换算成本段的局部前沿。
         *
         * <h2>这是跨行归算的全部秘密</h2>
         *
         * <p>整句的前沿只有一个标量（见 {@code FloatingLyricRenderer.sungWidth}），
         * 它是「唱到整句的第几像素」，不随折行改变。每一段只需减去自己的
         * 起点、再夹进自己的宽度，就得到本段该染到哪：
         *
         * <pre>
         *   localSung = clamp(globalSung - startPx, 0, width)
         * </pre>
         *
         * <p>这个换算天然满足连续性，而且是**三个方向**同时满足：
         * 对时间（前沿单调推进，局部量也单调）、对段边界（前一段到满、
         * 后一段从头，交界处两段之和恰好等于全局量）、对折行位置
         * （断点移动时只是 startPx 变了，全局前沿不动）。
         *
         * <p>早先想过「每行各自从头唱」的方案：那要求为每段单独维护一个
         * 平滑量，而 {@code advance()} 的指数逼近会在换段那一帧从 0 重新爬升，
         * 观感是一句话唱到行尾再从头起步——正是要避免的倒退。
         * 也想过「第一段唱完后把前沿映射到第二段的中线」：那等于凭空
         * 伪造时间，前沿速度会在交界处翻倍。
         *
         * @param globalSung 整句已唱宽度（像素，可为浮点）
         * @return 本段的局部已唱宽度，落在 {@code [0, width]}
         */
        public float localSung(float globalSung) {
            float local = globalSung - startPx;
            if (local <= 0f) {
                return 0f;
            }
            if (local >= width) {
                return width;
            }
            return local;
        }
    }

    /**
     * 把一句切成若干段。
     *
     * <p>字宽由调用方提供：{@code common} 里量不了字宽，而这里要的
     * 恰好只是「每个码点有多宽」这一件事。传 {@code painter::charWidth}
     * 与逐字绘制、{@code charStarts} 用的是同一个度量，因此断点位置与
     * 实际落笔位置不会错位。
     *
     * @param text      原文，非 null；空串返回空表
     * @param maxWidth  单段可用宽度，像素
     * @param charWidth 码点步进宽度，与绘制同一来源
     * @return 按顺序排列的段；原文为空时为空表
     */
    public static List<Segment> wrap(String text, int maxWidth, IntUnaryOperator charWidth) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxWidth);
        int length = text.length();
        int ellipsisWidth = Math.max(0, charWidth.applyAsInt(ELLIPSIS.charAt(0)));

        List<Segment> out = new ArrayList<>(MAX_LINES);
        int start = 0;
        int startPx = 0;
        while (start < length) {
            // 本段是否是「最后一格」。判据是**已经装满几段**，
            // 而不是这段有没有装到句尾：最后一段无论切到哪儿都必须带省略号收尾。
            boolean last = out.size() == MAX_LINES - 1;

            // 段数用尽时不只要切一刀，还要给省略号腾出位置：
            // 先把可放宽度减掉省略号，再按同一条贪心规则切，
            // 切出来的字加省略号就恰好不超宽。
            int cutLimit = last ? Math.max(1, limit - ellipsisWidth) : limit;
            int end = fitEnd(text, start, cutLimit, charWidth);
            // 段尾的空白不画也不计入本段宽度：拖尾空格会让这一段
            // 量出来比看起来宽，居中时整行左移几个像素。
            int tail = end;
            while (tail > start + 1 && isSpace(text.charAt(tail - 1))) {
                tail--;
            }
            int width = widthOf(text, start, tail, charWidth);
            boolean truncated = last && tail < length;

            String body = text.substring(start, tail);
            if (truncated) {
                body = body + ELLIPSIS;
                width += ellipsisWidth;
            }
            out.add(new Segment(start, tail, body, width, startPx, truncated));

            // 截断段出现即收尾。此前这里只判 {@code tail >= length}，
            // 于是「第 MAX_LINES 段被截断、但原文还没到头」会继续往下切：
            // 下一轮 {@code out.size()} 已超过上限，{@code last} 永远不再为真，
            // 循环一路切到句末——上限形同不存在，一行长句能折出二十几段。
            if (truncated || tail >= length) {
                break;
            }
            // 往下一段的起点：跳过断点处的空白。跳过的空白仍属于整句，
            // 所以 startPx 要按 [start, next) 全段推进，否则后续各段的
            // 局部前沿会累计偏小，越往后偏得越多。
            int next = tail;
            while (next < length && isSpace(text.charAt(next))) {
                next++;
            }
            if (next <= start) {
                next = start + 1;
            }
            startPx += widthOf(text, start, next, charWidth);
            start = next;
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 从 {@code start} 起最多能放到哪（不含），并回退到最近的断点。
     *
     * <p>至少放一个字符：字宽大于可用宽度时，不肯放一个就会原地踏步。
     * 宁可让这一段超宽，也不能让循环不前进。
     */
    private static int fitEnd(String text, int start, int limit, IntUnaryOperator charWidth) {
        int length = text.length();
        int width = 0;
        int i = start;
        int lastBreak = -1;
        while (i < length) {
            int w = Math.max(0, charWidth.applyAsInt(text.charAt(i)));
            if (i > start && width + w > limit) {
                break;
            }
            width += w;
            i++;
            if (isBreakAfter(text, i)) {
                lastBreak = i;
            }
        }
        if (i >= length) {
            return length;
        }
        return lastBreak > start ? lastBreak : i;
    }

    /** {@code end} 处是否允许断开：前一个字符是空白，或不是 ASCII 字母数字。 */
    private static boolean isBreakAfter(String text, int end) {
        if (end <= 0 || end > text.length()) {
            return false;
        }
        char prev = text.charAt(end - 1);
        if (isSpace(prev)) {
            return true;
        }
        return !isAsciiWord(prev);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\u3000';
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static int widthOf(String text, int from, int to, IntUnaryOperator charWidth) {
        int width = 0;
        for (int i = from; i < to; i++) {
            width += Math.max(0, charWidth.applyAsInt(text.charAt(i)));
        }
        return width;
    }
}
