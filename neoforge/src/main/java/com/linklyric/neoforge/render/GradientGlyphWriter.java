package com.linklyric.neoforge.render;

import com.linklyric.common.style.GlyphColors;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.joml.Matrix4f;

import java.lang.invoke.MethodHandle;
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
        vertex(buffer, pose, x0, y0, u0v, v0v, lc, light);
        vertex(buffer, pose, x0, y1, u0v, v1v, lc, light);
        vertex(buffer, pose, x1, y1, u1v, v1v, rc, light);
        vertex(buffer, pose, x1, y0, u1v, v0v, rc, light);
        return true;
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