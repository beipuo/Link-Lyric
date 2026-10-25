package com.linklyric.fabric.render;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.joml.Matrix4f;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 向 {@link VertexConsumer} 写入带逐顶点颜色的字形四边形。
 *
 * <p>原版 {@code BakedGlyph.render} 把颜色作为**标量**参数接收，
 * 一个字形只能整体一种颜色，无法表达「左半已唱、右半未唱」。
 * 但它内部写的本来就是逐顶点色：四个顶点各自 {@code setColor}。
 * 因此只要按同样的几何自己写一遍，就能给左右两侧不同颜色。
 *
 * <h2>几何与绘制顺序</h2>
 *
 * <p>原版 {@code BakedGlyph.render} 的字节码做的是：
 * <pre>
 * left  = x + glyph.left;  right = x + glyph.right;
 * up    = y + glyph.up;    down  = y + glyph.down;
 * 斜体：x0 += 1 - 0.25 * up;  x1 += 1 - 0.25 * down;
 * 顶点：(x0,up) (x0,down) (x1,down) (x1,up)
 * UV：  (u0,v0) (u0,v1) (u1,v1) (u1,v0)
 * </pre>
 * 这里逐字复刻。斜体补偿必须保留，否则斜体字的染色区会横向错位。
 *
 * <h2>为什么用 {@code VarHandle} 而不是 Mixin</h2>
 *
 * <p>需要的只有 8 个 {@code private final float} 字段：
 * {@code u0 u1 v0 v1 left right up down}。
 * 为读字段而引入 Mixin，要在 Fabric 与 NeoForge 各配一份
 * {@code mixins.json}、各写一个 accessor、并在每次 MC 更新后复核字段名。
 * {@code VarHandle} 只依赖字段名，成本低得多，且不需要打包配置。
 *
 * <p>字段名变化时不会静默出错：{@link #available()} 会转为 false，
 * 调用方退回原版单色绘制，功能降级但不会崩溃。
 */
public final class GradientGlyphWriter {

    /** 只在客户端渲染线程使用，无需同步。 */
    private static volatile boolean initialised;

    private static boolean usable;

    private static VarHandle u0;
    private static VarHandle u1;
    private static VarHandle v0;
    private static VarHandle v1;
    private static VarHandle left;
    private static VarHandle right;
    private static VarHandle up;
    private static VarHandle down;

    private GradientGlyphWriter() {
    }

    /**
     * 是否可用。字段解析失败时返回 false，调用方应退回单色绘制。
     */
    public static boolean available() {
        if (!initialised) {
            initialise();
        }
        return usable;
    }

    private static synchronized void initialise() {
        if (initialised) {
            return;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    BakedGlyph.class, MethodHandles.lookup());
            u0 = lookup.findVarHandle(BakedGlyph.class, "u0", float.class);
            u1 = lookup.findVarHandle(BakedGlyph.class, "u1", float.class);
            v0 = lookup.findVarHandle(BakedGlyph.class, "v0", float.class);
            v1 = lookup.findVarHandle(BakedGlyph.class, "v1", float.class);
            left = lookup.findVarHandle(BakedGlyph.class, "left", float.class);
            right = lookup.findVarHandle(BakedGlyph.class, "right", float.class);
            up = lookup.findVarHandle(BakedGlyph.class, "up", float.class);
            down = lookup.findVarHandle(BakedGlyph.class, "down", float.class);
            usable = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 字段改名或模块限制：降级为单色，不抛给调用方。
            // 渲染路径上抛异常会让整个 HUD 消失，比颜色不准严重得多。
            usable = false;
        } finally {
            initialised = true;
        }
    }

    /**
     * 写入一个字形四边形。
     *
     * @param buffer 目标顶点缓冲，其 RenderType 需与字形图集一致
     * @param pose   当前矩阵
     * @param glyph  已烘焙字形
     * @param x      字形左边缘横坐标
     * @param y      基线纵坐标
     * @param colors 左右端点颜色
     * @param light  打包光照坐标
     * @return 是否成功写入。false 表示降级，调用方应走原版路径
     */
    public static boolean write(VertexConsumer buffer, Matrix4f pose,
                                 BakedGlyph glyph, float x, float y,
                                 GlyphColors colors, int light) {
        if (!available()) {
            return false;
        }
        float u0v = (float) u0.get(glyph);
        float u1v = (float) u1.get(glyph);
        float v0v = (float) v0.get(glyph);
        float v1v = (float) v1.get(glyph);
        float l = (float) left.get(glyph);
        float r = (float) right.get(glyph);
        float u = (float) up.get(glyph);
        float d = (float) down.get(glyph);

        float x0 = x + l;
        float x1 = x + r;
        float y0 = y + u;
        float y1 = y + d;

        int lc = colors.left();
        int rc = colors.right();

        // 顶点顺序与 UV 对应关系必须与原版完全一致，
        // 否则字形的上下左右会被翻转，笔画变成镜像。
        quad(buffer, pose, x0, y0, x1, y1, u0v, u1v, v0v, v1v, lc, rc, light);
        // 不再绘制伪粗体副本。
        //
        // MC 的粗体是同一字形右移一个纹素再画一遍。在 9px 的位图字形上，
        // 这份副本与本体只错开 1px、重叠 8px，笔画通常只有 1px 宽，
        // 于是形成两个清晰可见的轮廓——观感就是「同一个字重叠了一次」。
        // 原版 drawString 里该偏移由字形度量统一处理，未见此问题；
        // 逐顶点路径没有同等余地，因此直接去掉。
        return true;
    }

    /**
     * 按段写入字形。
     *
     * <h2>UV 如何切分</h2>
     *
     * <p>整段文字共用一张字形纹理，横向位置与 {@code u} 坐标是线性对应：
     * <pre>
     *   u(x) = u0 + (u1 - u0) * (x - glyphLeft) / (glyphRight - glyphLeft)
     * </pre>
     * 因此段边界处的 u 直接按比例求出，不需要知道图集里这个字的具体位置。
     *
     * <p>段的横坐标来自调用方（相对行首），而字形的 left/right 是相对
     * 基线原点的偏移，两者相差 {@code x}，做一次平移即可对齐。
     *
     * @param spans 段列表，坐标与 {@code x} 同一坐标系
     */
    public static boolean writeSpans(VertexConsumer buffer, Matrix4f pose,
                                      BakedGlyph glyph, float x, float y,
                                      GlyphSpan[] spans, float originX, int light) {
        if (!available() || spans == null || spans.length == 0) {
            return false;
        }
        float u0v = (float) u0.get(glyph);
        float u1v = (float) u1.get(glyph);
        float v0v = (float) v0.get(glyph);
        float v1v = (float) v1.get(glyph);
        float l = (float) left.get(glyph);
        float r = (float) right.get(glyph);
        float u = (float) up.get(glyph);
        float d = (float) down.get(glyph);

        float glyphWidth = r - l;
        if (glyphWidth <= 0f) {
            return false;
        }
        // 段坐标是绝对屏幕坐标（相对行首），需要一个锚点换回字形局部坐标。
        //
        // 锚点必须只包含「行首位置」，不能包含阴影偏移之类的额外位移：
        // 阴影调用传进来的 x 已经左移了一格，若用它当锚点，
        // 段坐标减完再加回去，偏移就被抵消，阴影会精确压在主色上。
        //
        // 因此锚点由「字形的左边缘」反推：spans 的坐标基准与主色调用
        // 传入的 x 一致，这里用字形自身的 left 相对量做局部换算。
        float y0 = y + u;
        float y1 = y + d;

        boolean wrote = false;
        for (GlyphSpan span : spans) {
            if (span.isEmpty()) {
                continue;
            }
            // 段坐标相对行首；originX 是「行首在主色坐标系里的位置」。
            // 用 x - (主色x - originX) 的思路不可行，因此由调用方直接给出
            // 段坐标到字形局部坐标的偏移量：localX = spanX - originX。
            float sx0 = span.startX() - originX;
            float sx1 = span.endX() - originX;
            // 夹进字形可见范围：跨出字形的部分没有纹素对应，
            // 画出去只会采样到邻字的像素边缘。
            if (sx0 < l) {
                sx0 = l;
            }
            if (sx1 > r) {
                sx1 = r;
            }
            if (sx1 - sx0 <= 0f) {
                continue;
            }
            float su0 = u0v + (u1v - u0v) * ((sx0 - l) / glyphWidth);
            float su1 = u0v + (u1v - u0v) * ((sx1 - l) / glyphWidth);
            // 几何用传入的 x（含阴影位移），局部量用 sx0/sx1。
            quad(buffer, pose, x + sx0, y0, x + sx1, y1,
                    su0, su1, v0v, v1v,
                    span.startColor(), span.endColor(), light);
            wrote = true;
        }
        return wrote;
    }

    private static void quad(VertexConsumer buffer, Matrix4f pose,
                              float x0, float y0, float x1, float y1,
                              float u0v, float u1v, float v0v, float v1v,
                              int lc, int rc, int light) {
        vertex(buffer, pose, x0, y0, u0v, v0v, lc, light);
        vertex(buffer, pose, x0, y1, u0v, v1v, lc, light);
        vertex(buffer, pose, x1, y1, u1v, v1v, rc, light);
        vertex(buffer, pose, x1, y0, u1v, v0v, rc, light);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose,
                                float x, float y, float u, float v,
                                int argb, int light) {
        buffer.addVertex(pose, x, y, 0f)
                .setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
                        (argb >>> 24) & 0xFF)
                .setUv(u, v)
                .setLight(light);
    }

    /**
     * 取字形对应的 RenderType，用于开缓冲区。
     *
     * <p>必须用字形自己的类型：字体图集的绑定、混合模式、
     * 甚至是否为强度纹理都写在里面。自己新建一个 RenderType 会让
     * 字形采样到错误的图集，表现为整行变成一片方块。
     */
    public static net.minecraft.client.renderer.RenderType renderType(
            BakedGlyph glyph, boolean seeThrough) {
        GlyphRenderTypes types;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    BakedGlyph.class, MethodHandles.lookup());
            VarHandle handle = lookup.findVarHandle(BakedGlyph.class,
                    "renderTypes", GlyphRenderTypes.class);
            types = (GlyphRenderTypes) handle.get(glyph);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        if (types == null) {
            return null;
        }
        return types.select(seeThrough
                ? net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH
                : net.minecraft.client.gui.Font.DisplayMode.NORMAL);
    }
}