package com.linklyric.common.font;

/**
 * 把覆盖率掩膜烘成有符号距离场字形。
 *
 * <h2>为什么要先放大</h2>
 *
 * <p>原版字体是 8 像素高的位图，一根竖笔往往只有 1 像素宽。
 * 直接求距离场，这根笔画横跨只有一两个采样点，场描述不出轮廓，
 * 着色器无论怎么抗锯齿都只会得到一团糊。
 *
 * <p>先按整数倍最近邻放大，不会凭空造出细节，但能让场准确描述
 * 这个方块轮廓——足够着色器干净地把边缘解析出来。
 * 这是「9px 字形太小」不需要换字体就能解决的根据。
 *
 * <h2>spread 与 padding 必须匹配</h2>
 *
 * <p>{@code spread} 是「多远映射满量程」。它必须大于字形自身的
 * 尺寸量级，否则场在内部就饱和、零等值线以外没有梯度可用。
 * {@code padding} 则至少要覆盖 {@code spread}，否则梯度在图像
 * 边界被截断，边缘半边缺失。
 */
public final class SdfGlyphBaker {

    /** 位图字体的放大倍数。8px 高 × 4 = 32px，足够描述 1px 笔画。 */
    public static final int DEFAULT_UPSCALE = 4;

    /** 每边留白（放大后的像素）。需覆盖 spread。 */
    public static final int DEFAULT_PADDING = 8;

    /** 满量程对应的距离（放大后的像素）。与 padding 同量级。 */
    public static final float DEFAULT_SPREAD = 8f;

    private SdfGlyphBaker() {
    }

    /**
     * 烘焙一个字形。
     *
     * @param coverage   覆盖率掩膜，{@code cellWidth * cellHeight} 字节，按 alpha 取值
     * @param cellWidth  掩膜宽
     * @param cellHeight 掩膜高
     * @param scale      设计空间缩放：一个格子像素对应多少设计空间单位
     * @param ascent     字体上升高度，决定基线位置
     * @param advance    步进（设计空间）
     * @param upscale    放大倍数
     * @param padding    留白（放大后的像素）
     * @param spread     满量程距离（放大后的像素）
     * @return 已烘焙字形；掩膜为空时返回只有步进的空白字形
     */
    public static RawGlyph bake(byte[] coverage, int cellWidth, int cellHeight,
                                float scale, float ascent, float advance,
                                int upscale, int padding, float spread) {
        if (cellWidth <= 0 || cellHeight <= 0) {
            return RawGlyph.space(advance);
        }
        byte[] upscaled = DistanceTransform.upscale(coverage, cellWidth, cellHeight, upscale);
        int upWidth = cellWidth * upscale;
        int upHeight = cellHeight * upscale;
        // 用覆盖率路径：位图字体若带抗锯齿灰阶，能恢复次像素轮廓；
        // 纯硬边会自动回退到掩膜路径。
        byte[] sdf = DistanceTransform.fromCoverage(upscaled, upWidth, upHeight, padding, spread);

        // 留白把四边形在设计空间里向四周扩大同样的量。
        float designPadding = padding * scale / upscale;
        float top = 7f - ascent;
        float right = cellWidth * scale;
        float bottom = top + cellHeight * scale;

        // 留白有多少真正可用，取决于它能装下的距离。
        //
        // 场值在留白边缘就断了：纹理之外是 CLAMP 的边界像素，衰减会在
        // 那里被硬生生截断。若光晕半径超过这段距离，外圈会变成一道硬边，
        // 观感是给字套了描边而不是散开。
        //
        // 可用距离 = min(留白宽度, spread)：spread 描述「多远映射满量程」，
        // 留白宽度描述「场向轮廓外铺了多远」，两者取小才是有效范围。
        // 这里按设计空间给出，绘制层再按实际缩放换算成屏幕像素。
        float glowSpace = Math.min(designPadding, spread * scale / upscale);

        return RawGlyph.of(sdf, upWidth + padding * 2, upHeight + padding * 2,
                -designPadding, top - designPadding,
                right + designPadding, bottom + designPadding, advance, glowSpace);
    }

    /** 使用默认参数的版本。 */
    public static RawGlyph bake(byte[] coverage, int cellWidth, int cellHeight,
                                float scale, float ascent, float advance) {
        return bake(coverage, cellWidth, cellHeight, scale, ascent, advance,
                DEFAULT_UPSCALE, DEFAULT_PADDING, DEFAULT_SPREAD);
    }

    /**
     * 从 RGBA 像素数组提取 alpha 覆盖率。
     *
     * <p>只用 alpha 通道：资源包里的位图字形即使本身是彩色的，
     * 也渲染成实心轮廓。原版所有字体图都是白色 + alpha，
     * 因此实际观感没有差别。
     *
     * @param argb     行主序 ARGB 像素
     * @param width    掩膜宽
     * @param height   掩膜高
     * @param rowStride 源图每行像素数（源图可能比掩膜宽）
     * @param originX  掩膜在源图中的起点 x
     * @param originY  掩膜在源图中的起点 y
     */
    public static byte[] extractCoverage(int[] argb, int imgWidth, int imgHeight,
                                         int width, int height,
                                         int originX, int originY) {
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int sy = originY + y;
            if (sy < 0 || sy >= imgHeight) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                int sx = originX + x;
                if (sx < 0 || sx >= imgWidth) {
                    continue;
                }
                out[y * width + x] = (byte) ((argb[sy * imgWidth + sx] >>> 24) & 0xFF);
            }
        }
        return out;
    }
}
