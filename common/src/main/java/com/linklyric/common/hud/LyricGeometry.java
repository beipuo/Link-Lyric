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

    /** 主行行高。 */
    public static final int LINE_HEIGHT = 10;

    /** 行间距。 */
    public static final int LINE_GAP = 2;

    /** 一行的纵向步长：行高加行距。 */
    public static final int LINE_STEP = LINE_HEIGHT + LINE_GAP;

    /** 歌词块左右留白。 */
    public static final int PAD_X = 6;

    private LyricGeometry() {
    }

    /** 歌词块的横向可用宽度。 */
    public static int maxWidth(int screenWidth) {
        return Math.max(40, screenWidth / 2);
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
