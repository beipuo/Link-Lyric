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

    /** 文本距屏幕底部的距离，留出物品栏与经验条的空间。 */
    public static final int BOTTOM_MARGIN = 58;

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

    /** 歌词块左右留白。 */
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
     * 纵向锚点：预览区内垂直居中，否则贴底部加用户偏移。
     *
     * <p>全屏分支在 {@link #centerY(int)} 之上再补一个 {@link #LINE_HEIGHT}，
     * 因为那里给的是「字形底再往上留出物品栏」的位置，而这里要的是**块心**。
     * 少补这一下歌词整体差一行高——只有把新旧截图逐像素叠起来才看得出来，
     * 属于那种「看起来对、其实一直偏了 9 像素」的历史包袱，照原样保留。
     *
     * <p>预览时用户偏移必须被忽略：偏移描述的是「相对全屏底部的位置」，
     * 在预览框里没有意义，而且几十像素就能把文字顶出框外。
     */
    public static int anchorY(int screenHeight, int offsetY,
                              FloatingLyricRenderer.Bounds previewArea) {
        if (isUsablePreview(previewArea)) {
            return previewArea.y() + previewArea.height() / 2;
        }
        return centerY(screenHeight) + LINE_HEIGHT + offsetY;
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
        // 与 LineLayout.compute 同构：逐行累加浮点，最后只取整一次。
        float text = 0f;
        for (int i = 0; i < count; i++) {
            text += i == 0 ? LINE_HEIGHT : Math.max(1f, LINE_HEIGHT * SUB_TEXT_FONT_SCALE);
        }
        if (count > 1) {
            text += (float) lineGapPx * (count - 1);
        }
        int block = Math.round(text);
        // 上下各留余量：抬升让字向上走，阴影向右下偏，两侧都要容得下。
        return block + liftHeadroom + shadowHeadroom;
    }

    /** 歌词块的横向中心。 */
    public static int centerX(int screenWidth) {
        return screenWidth / 2;
    }

    /** 歌词块的纵向中心。 */
    public static int centerY(int screenHeight) {
        return screenHeight - BOTTOM_MARGIN - LINE_HEIGHT;
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