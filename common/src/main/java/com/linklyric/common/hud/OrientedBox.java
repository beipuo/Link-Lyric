package com.linklyric.common.hud;

/**
 * 带朝向的行所占的轴对齐包围盒。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * <p>弧形与圆环都要回答同一个问题：「这一行转过之后，实际占了屏幕上哪块矩形」。
 * 答案同时决定两件事——整块的上下边界（包围盒、底板、命中检测都从它派生），
 * 以及相邻两行会不会叠在一起。
 *
 * <p>两处各写一份公式是最容易出错的形态：转角的正负、宽高的对调、
 * 三角函数取绝对值，任何一处抄错都不会抛异常，只会表现为
 * 「框比字大一圈」或「环撑破屏幕」。因此只留一份。
 *
 * <h2>坐标约定</h2>
 *
 * <p>屏幕坐标，{@code y} 向下为正；角度单位为度，<b>顺时针为正</b>，
 * 与 {@link Pose2D#rotate} 一致。顺时针在 y 向下的坐标系里与数学上的
 * 逆时针互为镜像，因此这里的 {@code sin} 项符号不能照抄课本公式——
 * 直接对宽高取绝对值再做组合，方向就不影响结果：旋转 90 度时
 * 长方形的长边必然吃进纵向，取绝对值得到的正是这个结论。
 *
 * <p>结果用 {@code float[4]} 表示 {@code {left, top, right, bottom}}。
 * 用数组而不是 record 是因为它只在本包的策略之间流传、生命周期以「一次
 * 放置计算」为界，且每帧每行都要新建实例——record 的便利换不来这份开销。
 */
final class OrientedBox {

    /** 数组下标：左边界。 */
    static final int LEFT = 0;
    /** 数组下标：上边界。 */
    static final int TOP = 1;
    /** 数组下标：右边界。 */
    static final int RIGHT = 2;
    /** 数组下标：下边界。 */
    static final int BOTTOM = 3;

    private OrientedBox() {
    }

    /**
     * 一行绕自身中心旋转后的外接矩形。
     *
     * @param centerX 该行中心横坐标
     * @param centerY 该行中心纵坐标
     * @param width   该行绘制宽度（副行已乘过字号比例）
     * @param height  该行绘制高度
     * @param degrees 朝向角，顺时针为正
     * @return {@code {left, top, right, bottom}}
     */
    static float[] of(float centerX, float centerY,
                      float width, float height, float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = Math.abs((float) Math.cos(radians));
        float sin = Math.abs((float) Math.sin(radians));
        float halfW = (width * cos + height * sin) * 0.5f;
        float halfH = (width * sin + height * cos) * 0.5f;
        return new float[]{centerX - halfW, centerY - halfH,
                centerX + halfW, centerY + halfH};
    }

    /**
     * 把 {@code box} 并进 {@code union}，就地改写。
     *
     * <p>{@code union} 必须是空盒子的初值（见 {@link #empty()}），
     * 否则第一次合并会凭空得到一个包含原点的区域，
     * 表现为「整块被拉向屏幕左上角」。
     */
    static void merge(float[] union, float[] box) {
        if (box[LEFT] < union[LEFT]) {
            union[LEFT] = box[LEFT];
        }
        if (box[TOP] < union[TOP]) {
            union[TOP] = box[TOP];
        }
        if (box[RIGHT] > union[RIGHT]) {
            union[RIGHT] = box[RIGHT];
        }
        if (box[BOTTOM] > union[BOTTOM]) {
            union[BOTTOM] = box[BOTTOM];
        }
    }

    /** 空盒子：左右上下分别是正负无穷，第一次合并即等于盒子本身。 */
    static float[] empty() {
        return new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
    }

    /**
     * 两条线段的中心线最小距离。
     *
     * <p>判「相邻两行会不会叠在一起」只看中心线距离不够——两条很长的线
     * 即使中心点离得远，端点处也可能交叉（弧排布里这是真实的失败形态：
     * 中间的几行看着只是微微斜，最外两行的末端已经叠印）。
     * 因此按**线段**而不是点来计算。
     *
     * <p>算法是「取四条点到线段距离里的最小值」。对不相交的线段这是精确值；
     * 相交时距离为 0，而点到线段距离在那种情形下也必然给出 0 或极小值，
     * 判据用的是「小于某阈值」，两者不会给出相反的结论。
     * 不引入完整的线段相交判定，是因为这里只需要一个下界。
     */
    static float segmentGap(float ax, float ay, float bx, float by,
                            float cx, float cy, float dx, float dy) {
        float best = pointSegmentGap(ax, ay, cx, cy, dx, dy);
        best = Math.min(best, pointSegmentGap(bx, by, cx, cy, dx, dy));
        best = Math.min(best, pointSegmentGap(cx, cy, ax, ay, bx, by));
        best = Math.min(best, pointSegmentGap(dx, dy, ax, ay, bx, by));
        return best;
    }

    /** 点到线段的最短距离；线段退化成点时退化为点距。 */
    private static float pointSegmentGap(float px, float py,
                                         float ax, float ay, float bx, float by) {
        float vx = bx - ax;
        float vy = by - ay;
        float lengthSq = vx * vx + vy * vy;
        if (lengthSq <= 0f) {
            return (float) Math.hypot(px - ax, py - ay);
        }
        // 投影参数夹到 [0,1]，否则量的是到延长线的距离，
        // 端点外侧的交叉会被漏判成「离得很远」。
        float t = ((px - ax) * vx + (py - ay) * vy) / lengthSq;
        t = Math.max(0f, Math.min(1f, t));
        return (float) Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }
}
