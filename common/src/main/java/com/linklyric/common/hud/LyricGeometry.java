package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig.Align;

/**
 * 悬浮歌词的几何计算。
 *
 * <p>屏幕布局、块内行排布、滚动偏移、屏幕包围盒都属于纯数学，
 * 与绘制和动画无关，集中在这里便于单独验证。
 *
 * <p>行布局的规则是「整块居中」：所有行按总高算出一个统一顶点再逐行往下排，
 * 行数变化时块心不动，视觉上是整体撑开而不是整体位移。
 */
public final class LyricGeometry {

    /**
     * 锚点比例取 {@code 0}（贴底）时，块心距屏幕底部的距离。
     *
     * <p>留出物品栏与经验条的空间。这个值本身**不再**是块底到屏底的留白：
     * 它给的是块心，块的下半部分还会往下探 {@code blockHeight / 2}。
     * 历史上这里是「字形底再往上留出物品栏」，与 {@link #LINE_HEIGHT}
     * 相互抵消一次，取整关系见 {@link #anchorY} 的注释。
     */
    public static final int BOTTOM_MARGIN = 58;

    /**
     * 锚点比例取 {@code 1}（贴顶）时的上边距，像素。
     *
     * <h2>为什么是 32</h2>
     *
     * <p>MC 屏幕顶部要避让的东西有三层，这个值按其中最硬的那条来定。
     *
     * <p>**Boss 血条**：{@code BossHealthOverlay.render} 里首条血条的起始 y 是 12
     * （1.21.1 字节码确认，方法体内对 y 的第一次赋值为 {@code bipush 12}），
     * 每条占 19 像素，因此单条血条的底边到 31。取 32 就是「底边之下再留 1 像素」。
     *
     * <p>**F3 调试屏**：左上角从 y=2 起逐行排、行高 9，十几行调试信息会一路排到
     * 100 像素以下。它与歌词在水平上通常不重叠（调试屏贴左、歌词居中或贴右），
     * 要避让它得把上边距推到 100 以上，那等于让「贴顶」变成「贴中」——
     * 为一种诊断态牺牲常驻布局不划算。开着 F3 时歌词被压住是可接受的，
     * 用户关掉调试屏即可。
     *
     * <p>**计分板**：右侧，首行约 y=20 上下；贴顶的窄列若摆到屏幕右侧会撞上。
     * 这里同样不做自动避让（理由见 {@code LyricStyleConfig.withAnchorRatio}：
     * 读计分板状态需要平台依赖，而 {@code common} 是零依赖的）。
     *
     * <h2>为什么不按两条血条（50）甚至更多去定</h2>
     *
     * <p>那会让 99% 的场合（没开 Boss 战）「调到顶了却还在半空」，
     * 用户会觉得滑条没用。这里选「避开最常见的一层」，多出来的血条
     * 交给用户自己往下拖一格——滑条本来就能做这件事，
     * 而这个常数只负责把「贴顶」定义在一个对单人/普通生存都合理的起点上。
     */
    public static final int TOP_MARGIN = 32;

    /**
     * 主行行高，即 MC 默认字体的行高。
     *
     * <p>必须等于 {@code font.lineHeight}（9），不能用别的数：
     * 字形在纵向只占 9 像素，多算的每一像素都会凭空变成行间空隙。
     * 这里曾经写 10，比真实字形高 1 像素，与下方 2 像素的行距叠加后，
     * 主行与次行之间出现 3 像素空白，看起来两行是分开的两块而不是一组。
     */
    public static final int LINE_HEIGHT = 9;

    /**
     * 行间距的**默认值**。
     *
     * <p>运行时真实行距以 {@code LyricStyleConfig.lineGapPx()} 为准，
     * 这个常量只用于「没有样式配置」的场合与测试基准。
     * 排版、换行位移都必须读样式里的值——此处曾经是唯一来源，
     * 于是用户把行距调大后，换行动画仍按默认步长收尾，末尾跳一像素。
     *
     * <p>取 2 像素，使默认行步长为 11，约合行高的 1.22 倍——字幕常用的区间。
     *
     * <p>用固定像素而非比例：字号缩放（{@code lineScale}）是对整块
     * 施加的姿态变换，块内的像素常量会跟着一起缩放，
     * 不需要再单独按比例折算一次，否则会缩放两遍。
     */
    public static final int LINE_GAP = 2;

    /**
     * 默认行步长：行高加默认行距。
     *
     * <p>同样只是默认值。需要按用户设置计算时，请用
     * {@code LINE_HEIGHT + style.lineGapPx()}。
     */
    public static final int LINE_STEP = LINE_HEIGHT + LINE_GAP;

    /**
     * 歌词块左右留白的**默认值**，像素。
     *
     * <p>与 {@link #LINE_GAP} 同一个处境：运行时真实留白以
     * {@code LyricStyleConfig.padXPx()} 为准，这个常量只供样式默认值使用
     * （{@code LyricStyleConfig.DEFAULT} 直接引用它，不是另写一个 6）。
     *
     * <p>此前它是唯一来源，于是三个放置策略各自抄了一行
     * {@code int padX = LyricGeometry.PAD_X;}——想让它可调就得同时改三处，
     * 而抄漏一处的症状是「底板左边缘比包围盒窄 6 像素」，
     * 只在拖动时靠手感才察觉得到。三份副本已删除，消费点统一读样式。
     *
     * <p>取 6：主行是 9 像素高的字形，两侧各留 6 像素使底板看起来是
     * 「包住一行字」而不是「贴着字画框」。再小（3 以下）底板就贴到字形边缘，
     * 拖动时的框看起来像描边而不是底板；再大（10 以上）底板会明显宽于文字，
     * 视觉上反而像一块空框。
     */
    public static final int PAD_X = 6;

    /**
     * 未唱文字的透明度。
     *
     * <p>「没唱到」用透明度表达，而不是换一个灰色。
     * 灰色是第二种颜色，会和用户选定的已唱色产生色相冲突；
     * 透明度只减弱同一颜色的存在感，唱到与没唱到始终是同一个色系。
     * 非主行用同一个值，于是「未生效」在全场景下是统一的一种视觉语言。
     *
     * <p>定义在此而非各自类里：逐字路径（{@code LyricPainter}）与整段路径
     * （{@code FloatingLyricRenderer}）都要用它。此前两处各写一份 0.38，
     * 改一边就会让「未唱」在同一帧里出现两种浓度。
     */
    public static final float UNSUNG_ALPHA = 0.38f;

    /**
     * 抬升需要额外预留的垂直空间，覆盖位移与抗锯齿余量。
     *
     * <p>同上：底板留白（渲染器）与裁剪框（绘制器）必须用同一个值，
     * 否则抬起的字顶部会被裁掉一块。
     */
    public static final int LIFT_HEADROOM = 2;

    /**
     * 阴影与字重叠加的额外余量。
     *
     * <p>原版阴影偏移 1 像素，加粗最多再向外 2 像素，取 4 已足够；
     * 留小了中文阴影会被裁剪，留大了只是多画几个透明像素。
     */
    public static final int SHADOW_HEADROOM = 4;

    private LyricGeometry() {
    }

    /** 副行相对主行的字号比例，与渲染器绘制副行时用的值必须一致。 */
    public static final float SUB_TEXT_FONT_SCALE = 0.8f;

    /** 歌词块的横向可用宽度。 */
    public static int maxWidth(int screenWidth) {
        return Math.max(40, screenWidth / 2);
    }

    /**
     * 预览区是否真的可用。
     *
     * <p>判据只有一条：非 null 且非空。此前这条判据在渲染器里写了四遍
     * （居中、垂直居中、可用宽度、包围盒裁切），任何一处漏写，
     * 就有一条路径会把预览当全屏算，或者反过来。收成一处后，
     * 「哪些地方受预览约束」变成一个可以数的问题。
     */
    public static boolean isUsablePreview(FloatingLyricRenderer.Bounds previewArea) {
        return previewArea != null && !previewArea.isEmpty();
    }

    /**
     * 单行可用宽度。
     *
     * <p>预览与全屏走**同一条公式**，只把「屏幕宽度」换成「预览框宽度」：
     * 都没绕开 {@code maxWidthRatio}。
     *
     * <p>此前预览分支直接取 {@code 框宽 - 8}，把宽度比例整个跳过了——
     * 拖动「最大宽度」滑条时预览毫无变化，而样式接口的注释偏偏写着
     * 「预览里宽度照常生效」。玩家据此调出来的宽度，进游戏才发现不是那个样子。
     *
     * <p>全屏分支里的 {@code * 2f} 是历史包袱：{@link #maxWidth(int)} 返回的是
     * 半屏宽，这里再乘回来。两个半屏叠在一起看着费解，但改动会平移所有既有像素，
     * 因此原样保留，只把公式集中到这里。
     *
     * <p>下限 40 像素：再窄的话一句中文只剩两三个字，
     * 滚动视口也窄到读不出上下文。
     */
    public static int availableWidth(float maxWidthRatio,
                                     FloatingLyricRenderer.Bounds previewArea,
                                     int screenWidth) {
        if (isUsablePreview(previewArea)) {
            return Math.max(40, (int) (previewArea.width() * maxWidthRatio));
        }
        return Math.max(40, (int) (maxWidth(screenWidth) * maxWidthRatio * 2f));
    }

    /**
     * 纵向锚点：预览区内垂直居中，否则按锚点比例在「贴底」与「贴顶」之间插值。
     *
     * <h2>贴底那一档必须与历史输出逐字节相同，以及那个抵消往返</h2>
     *
     * <p>历史实现是 {@code centerY(screenHeight) + LINE_HEIGHT + offsetY}，
     * 而 {@code centerY} 自己返回 {@code screenHeight - BOTTOM_MARGIN - LINE_HEIGHT}。
     * 展开后是 {@code screenHeight - BOTTOM_MARGIN - LINE_HEIGHT + LINE_HEIGHT + offsetY}，
     * 其中 {@code - LINE_HEIGHT + LINE_HEIGHT} 在**整数加法**上严格抵消
     * （可结合、可交换，没有溢出可能也不是浮点），实际生效的是
     * {@code screenHeight - BOTTOM_MARGIN + offsetY}。
     * 这一段曾经带着注释「照原样保留」，因为它看起来像个历史包袱。
     *
     * <p>本次把它消掉了，因为参数化之后**保留它反而危险**：
     * 新的插值公式需要一个明确的「贴底位置」作为端点，若端点仍是
     * {@code centerY(h) + LINE_HEIGHT}，那么 {@link #LINE_HEIGHT} 就
     * 悄悄参与了锚点计算——而这个常量的注释明确写着它只能等于字体的
     * {@code lineHeight}，谁都不能动。让它以 0 净额出现在算式里，
     * 等于给后来的人留了一个「改这里会平移所有像素」的陷阱。
     * 消掉之后端点写成展开后的形式，语义上就是「块心距屏底 BOTTOM_MARGIN」。
     *
     * <p>输出不变**不靠推理**：{@code LyricGeometryAnchorTest} 对
     * {@code screenHeight ∈ [100, 4000]}、{@code offsetY ∈ [-500, 500]}
     * 的全组合逐个断言新旧两式相等，另有一组把「抵消恒等式」本身
     * 与整数边界一并钉住。测试里那份旧式是逐字抄的历史代码，
     * 断言恒等而不是断言某个具体数字，因此它证明的是「两式等价」，
     * 不是「今天这组样本碰巧相等」。
     *
     * <h2>比例为什么是浮点而不是三档枚举</h2>
     *
     * <p>「灵动岛」这类布局要的是「比我刚才那档再高 30 像素」，而枚举只给
     * 贴底 / 居中 / 贴顶三个落点，中间的空档只能靠 {@code offsetY} 去凑——
     * 而 {@code offsetY} 是拖动写入的、语义是「相对默认位置的手工微调」，
     * 把它当布局参数用，拖动一次就把用户精心调的比例冲掉了。
     * 浮点比例让贴底、居中、贴顶都成为它的特例（0 / 0.5 / 1），
     * 滑条每拖一格都是有效位置，且与 {@code offsetY} 的职责彻底分开。
     *
     * <h2>比例取 0 为什么是结构性相等，而不是「算出来恰好相等」</h2>
     *
     * <p>{@link #anchorRatioY} 在 {@code ratio <= 0f} 时**直接返回贴底端点**，
     * 根本不进插值算式。于是「默认值零变化」不依赖
     * {@code 0f * (top - bottom) == 0.0} 这条浮点性质是否被满足，
     * 也不依赖 {@code Math.round} 在 0 附近的行为——那条路径不执行。
     * 这是本类对「默认观感零变化」硬判据的保证方式：
     * 结构上不走新代码，而不是新代码算出来的结果恰好相同。
     *
     * <p>预览时用户偏移必须被忽略：偏移描述的是「相对全屏底部的位置」，
     * 在预览框里没有意义，而且几十像素就能把文字顶出框外。
     * 锚点比例同理被忽略，理由写在 {@link #anchorRatioY} 的参数说明里。
     *
     * @param screenHeight 屏幕高，像素
     * @param offsetY      用户垂直偏移，像素，向下为正
     * @param previewArea  预览区；null 或空表示按全屏布局
     * @param anchorRatio  锚点比例，0 贴底、1 贴顶；由
     *                     {@code LyricStyleConfig.anchorRatio()} 夹在 [0, 1]
     * @param blockHeight  整块高度，像素；用于把「块顶贴顶」换算成块心。
     *                     必须来自 {@link LineLayout#blockHeight}，
     *                     不能由调用方另行估算，否则贴顶档会偏一个像素
     */
    public static int anchorY(int screenHeight, int offsetY,
                              FloatingLyricRenderer.Bounds previewArea,
                              float anchorRatio, int blockHeight) {
        if (isUsablePreview(previewArea)) {
            return previewArea.y() + previewArea.height() / 2;
        }
        return anchorRatioY(screenHeight, offsetY, anchorRatio, blockHeight);
    }

    /**
     * 全屏分支的纵向锚点：在贴底与贴顶两个端点之间按比例插值。
     *
     * <p>单独拆出来，是为了让「预览忽略比例」这条规则只有一处出口，
     * 也让测试可以直接对着它验证端点而不必构造预览框。
     *
     * <h3>两个端点</h3>
     *
     * <p>**贴底端点** {@code screenHeight - BOTTOM_MARGIN + offsetY}：
     * 这是历史行为的展开式（推导见 {@link #anchorY}），一字不改。
     * 注意它**不读 blockHeight**——这正是默认值零变化的结构性来源：
     * 比例 0 时块高换任何值都得到同一个数，多行歌词的默认位置因此
     * 与改造前逐像素一致，连「块高取整方式」都不可能影响它。
     *
     * <p>**贴顶端点** {@code TOP_MARGIN + blockHeight / 2 + offsetY}：
     * 让块的**上边缘**落在 {@link #TOP_MARGIN}，因此块心要往下挪半个块高。
     * 这里取的是 {@code blockHeight / 2}（整数除法，向下取整）而不是
     * {@code Math.round(blockHeight / 2f)}：整数除以 2 与向下取整在
     * 非负块高上等价，而后者会多引入一次浮点转换。块高恒为正，
     * 因此两者对奇数块高的差别只是「半个像素」——但那半个像素在
     * 多行时会让块顶少一像素，块顶贴边就不整齐了。
     * 用整数除法，块顶恰好等于 {@code TOP_MARGIN} 或 {@code TOP_MARGIN + 1}。
     *
     * <h3>为什么两个端点都加 offsetY</h3>
     *
     * <p>偏移的语义是「把歌词往下/往上挪几个像素」。若只在贴底端点加，
     * 比例调到 1 之后拖动就完全失灵——玩家会以为拖动坏了。
     * 两端都加，偏移在任何一个比例下都是同样的像素位移，
     * 「拖 10 像素就动 10 像素」这条手感在所有档位上一致。
     *
     * @param anchorRatio 0..1；≤ 0 走贴底端点，≥ 1 走贴顶端点
     * @param blockHeight 整块高度；仅贴顶端点与中间插值读取
     */
    public static int anchorRatioY(int screenHeight, int offsetY,
                                   float anchorRatio, int blockHeight) {
        // 贴底端点：历史行为的展开式。比例 ≤ 0 直接返回，不进插值——
        // 「默认值零变化」靠这一行保证，而不是靠浮点运算恰好得 0。
        int bottom = screenHeight - BOTTOM_MARGIN + offsetY;
        if (!(anchorRatio > 0f)) {
            // 写成 !(x > 0f) 而不是 x <= 0f：NaN 也要落回贴底端点。
            // 配置文件的浮点字段被改成 "NaN" 时 parseFloat 会成功返回 NaN，
            // 若判据写成 x <= 0f，NaN 两边都不成立会一路插值成垃圾值。
            return bottom;
        }
        int top = TOP_MARGIN + Math.max(1, blockHeight) / 2 + offsetY;
        if (anchorRatio >= 1f) {
            return top;
        }
        // 中间插值：用 double 做乘法再取整。delta 是 int 范围内的差值，
        // 乘积仍在 double 的精确整数范围内（|delta| < 2^31，乘一个 [0,1] 的
        // float 结果量级 ≤ 2^31），因此这里没有精度损失。
        long delta = (long) top - bottom;
        return bottom + (int) Math.round(anchorRatio * (double) delta);
    }

    /**
     * 横向锚点：预览区内居中，否则全屏居中加用户偏移。
     *
     * <p>与 {@link #anchorY} 成对：两个锚点若一处认预览、一处不认，
     * 画面上就是「水平按框算、垂直按全屏算」的斜向错位。
     */
    public static int anchorX(int screenWidth, int offsetX,
                              FloatingLyricRenderer.Bounds previewArea) {
        if (isUsablePreview(previewArea)) {
            return previewArea.x() + previewArea.width() / 2;
        }
        return centerX(screenWidth) + offsetX;
    }

    /**
     * 预览框应有的高度：恰好装下 {@code lines} 行歌词，不多不少。
     *
     * <p>此前四个界面各写一个 {@code PREVIEW_HEIGHT = 56}。那是按「两行」
     * 手算的结果，于是 1 行时上下空一大截、3 行以上时副行被裁掉一半，
     * 而玩家调「行数」滑条时预览毫无反应——恰恰把这个选项的效果藏住了。
     *
     * <p>算法与渲染器的 {@code LineLayout.compute} 完全对齐：主行取
     * {@link #LINE_HEIGHT}，副行按 {@link #SUB_TEXT_FONT_SCALE} 缩小，
     * 行间取真实行距，最后加上抬升与阴影所需的余量。
     *
     * @param lines         要显示的行数，至少按 1 行算
     * @param lineGapPx     行间距，像素
     * @param liftHeadroom  抬升余量，像素
     * @param shadowHeadroom 阴影余量，像素
     */
    public static int previewHeight(int lines, int lineGapPx,
                                    int liftHeadroom, int shadowHeadroom) {
        int count = Math.max(1, lines);
        // 直接借 LineLayout 的块高：预览框的高度与真块的高度必须同一个算式。
        // 此前这里内联抄了一遍同样的逐行累加，两份实现的差别只在
        // 「行距按 int 还是 float 参与累加」——对 lineGap ≤ 12、行数 ≤ 10
        // 这些量级，两种写法的中间值都能被 float 精确表示，结果位完全相同。
        // 但「完全相同」是靠量级成立的性质，不是靠结构；一旦将来行距上限
        // 调大或行数放宽，两份算式就会在某个量级上分叉，而症状是
        // 「预览框比实际歌词矮一像素」——只在多行时出现，很难归因。
        int block = LineLayout.blockHeight(
                count, 1, LINE_HEIGHT, lineGapPx, SUB_TEXT_FONT_SCALE);
        // 上下各留余量：抬升让字向上走，阴影向右下偏，两侧都要容得下。
        return block + liftHeadroom + shadowHeadroom;
    }

    /** 歌词块的横向中心。 */
    public static int centerX(int screenWidth) {
        return screenWidth / 2;
    }

    /**
     * 一行文本在可用宽度内的左边界。
     *
     * @param align      对齐方式
     * @param centerX    可用区域的横向中心
     * @param maxWidth   可用宽度
     * @param lineWidth  该行实际宽度
     */
    public static int left(Align align, int centerX, int maxWidth, int lineWidth) {
        if (align == Align.LEFT) {
            return centerX - maxWidth / 2;
        }
        if (align == Align.RIGHT) {
            return centerX + maxWidth / 2 - lineWidth;
        }
        return centerX - lineWidth / 2;
    }

    /**
     * 滚动偏移：演唱点越过中线后才推动文本。
     *
     * <p>规则是「高亮超过视口中线多少就向左推多少」，并夹在
     * {@code [-(行宽-视口宽), 0]} 之间，因此开头不会提前滚、结尾不会滚过头。
     * 这样演唱中的字始终停在中线附近，读起来最省力。
     */
    public static float scrollOffset(float sungWidth, int viewWidth, int lineWidth) {
        float minScroll = -(lineWidth - viewWidth);
        if (minScroll >= 0f) {
            return 0f;
        }
        float half = viewWidth / 2f;
        float push = sungWidth - half;
        if (push <= 0f) {
            return 0f;
        }
        return Math.max(minScroll, -push);
    }

    /** 把区域裁进边界内；无重叠时返回空区域。 */
    public static FloatingLyricRenderer.Bounds intersect(
            FloatingLyricRenderer.Bounds a, FloatingLyricRenderer.Bounds b) {
        int left = Math.max(a.x(), b.x());
        int top = Math.max(a.y(), b.y());
        int right = Math.min(a.x() + a.width(), b.x() + b.width());
        int bottom = Math.min(a.y() + a.height(), b.y() + b.height());
        if (right <= left || bottom <= top) {
            return FloatingLyricRenderer.Bounds.EMPTY;
        }
        return new FloatingLyricRenderer.Bounds(left, top, right - left, bottom - top);
    }
}