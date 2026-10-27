package com.linklyric.neoforge.render.sdf;

import com.linklyric.common.style.GlyphSpan;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * 用 SDF 图集绘制字形，同时保留逐顶点染色。
 *
 * <h2>为什么不改染色链路</h2>
 *
 * <p>SDF 着色器里的 {@code vertexColor} 是顶点属性，GPU 按像素线性插值。
 * 也就是说「字内渐变」和「字形锐利」是正交的两件事：
 * 前者靠顶点色，后者靠距离场重建覆盖率。
 *
 * <p>因此这里只把几何来源从原版 {@code BakedGlyph} 换成
 * {@link SdfAtlas.Slot}，段切分与颜色写入完全沿用既有逻辑——
 * 这是换成 SDF 后渐变仍然生效的根据。
 */
public final class SdfGlyphWriter {

    private SdfGlyphWriter() {
    }

    /**
     * 写入一个字形。
     *
     * @param buffer 目标顶点缓冲
     * @param pose   当前矩阵
     * @param slot   图集区域
     * @param box    绘制盒 {@code [left, top, right, bottom, advance]}
     * @param x      字形原点横坐标
     * @param y      基线纵坐标
     * @param spans  颜色段，坐标与 {@code x} 同一坐标系
     * @param originX 段坐标的锚点，必须是行首位置
     * @param light  打包光照坐标
     * @return false 表示未写入
     */
    public static boolean writeSpans(VertexConsumer buffer, Matrix4f pose,
                                     SdfAtlas.Slot slot, float[] box,
                                     float x, float y,
                                     GlyphSpan[] spans, float originX, int light) {
        if (slot == null || box == null || spans == null || spans.length == 0) {
            return false;
        }
        float l = box[0];
        float u = box[1];
        float r = box[2];
        float d = box[3];
        float glyphWidth = r - l;
        if (glyphWidth <= 0f) {
            return false;
        }
        float u0 = slot.u0();
        float u1 = slot.u1();
        float v0 = slot.v0();
        float v1 = slot.v1();

        float y0 = y + u;
        float y1 = y + d;

        boolean written = false;
        for (GlyphSpan span : spans) {
            if (span == null || span.isEmpty()) {
                continue;
            }
            float sx0 = span.startX() - originX;
            float sx1 = span.endX() - originX;
            // 夹到字形范围：超出部分不属于这个字。
            if (sx1 <= l || sx0 >= r) {
                continue;
            }
            float cx0 = Math.max(sx0, l);
            float cx1 = Math.min(sx1, r);
            if (cx1 - cx0 <= 0f) {
                continue;
            }
            // 段边界处的 UV 按比例求出，与整段文字共用一张纹理的事实一致。
            float su0 = u0 + (u1 - u0) * ((cx0 - l) / glyphWidth);
            float su1 = u0 + (u1 - u0) * ((cx1 - l) / glyphWidth);
            int lc = span.startColor();
            int rc = span.endColor();
            quad(buffer, pose, x + cx0, y0, x + cx1, y1, su0, su1, v0, v1, lc, rc, light);
            written = true;
        }
        return written;
    }

    /**
     * 顶点顺序必须与原版一致，否则字形会上下翻转。
     *
     * <p>原版是 {@code (x0,y0) (x0,y1) (x1,y1) (x1,y0)}，
     * UV 同步为 {@code (u0,v0) (u0,v1) (u1,v1) (u1,v0)}。
     */
    private static void quad(VertexConsumer buffer, Matrix4f pose,
                             float x0, float y0, float x1, float y1,
                             float u0, float u1, float v0, float v1,
                             int leftColor, int rightColor, int light) {
        vertex(buffer, pose, x0, y0, u0, v0, leftColor, light);
        vertex(buffer, pose, x0, y1, u0, v1, leftColor, light);
        vertex(buffer, pose, x1, y1, u1, v1, rightColor, light);
        vertex(buffer, pose, x1, y0, u1, v0, rightColor, light);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose,
                               float x, float y, float u, float v,
                               int color, int light) {
        buffer.addVertex(pose, x, y, 0f)
                .setColor(color)
                .setUv(u, v)
                .setLight(light);
    }
}
