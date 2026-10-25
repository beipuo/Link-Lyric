package com.linklyric.neoforge.render.sdf;

import com.linklyric.common.font.RawGlyph;
import com.linklyric.common.font.SdfGlyphBaker;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按需把原版字形烘成 SDF 并缓存进自建图集。
 *
 * <h2>像素从哪来（已验证）</h2>
 *
 * <p>三条路都试过，只有第三条成立：
 *
 * <p>{@code BakedGlyph} 只带 UV 与四边，没有像素；
 * 从它反查图集纹理会撞上 {@code RenderType.state} 是 protected。
 *
 * <p>{@code SheetGlyphInfo.upload(int,int)} 只有目标坐标，不暴露像素缓冲区，
 * 截取不到内容。
 *
 * <p>可行的是 {@code BitmapProvider$Glyph}——它是 record，
 * 字段 {@code image / offsetX / offsetY / width / height / scale / ascent}
 * 直接持有 {@link NativeImage} 与格子位置。中文由 unifont 提供，
 * 走的是 {@code UnihexProvider$Glyph}，其 {@code LineData contents}
 * 就是位行数据。两者都覆盖后，ASCII 与 CJK 都能拿到真实像素。
 *
 * <h2>不接管原版排版</h2>
 *
 * <p>步进宽度仍取自原版 {@code FontSet}，逐字推进与原来逐像素一致。
 * 本类只额外提供一份高清字形，绘制时用它的 UV 与 SDF 着色器。
 */
public final class SdfGlyphProvider {

    private static final ResourceLocation DEFAULT_FONT =
            ResourceLocation.withDefaultNamespace("default");

    /** 每页边长。一屏文字通常落在一页里，因而合并为一次绘制。 */
    private static final int PAGE_SIZE = 1024;

    /** 码点 -> 图集区域。 */
    private static final Map<Integer, SdfAtlas.Slot> SLOTS = new HashMap<>();

    /** 码点 -> 绘制盒 {@code [left, top, right, bottom, advance]}，设计空间。 */
    private static final Map<Integer, float[]> BOXES = new HashMap<>();

    private static SdfAtlas atlas;

    private static boolean initialised;

    private static MethodHandle getFontSet;
    private static MethodHandle getActiveProviders;

    /** BitmapProvider$Glyph 的字段访问器。 */
    private static Field bmpImage;
    private static Field bmpOffsetX;
    private static Field bmpOffsetY;
    private static Field bmpWidth;
    private static Field bmpHeight;
    private static Field bmpScale;
    private static Field bmpAscent;

    /** UnihexProvider$Glyph 的字段访问器。 */
    private static Field uniContents;
    private static Field uniLeft;
    private static Field uniRight;

    /** UnihexProvider$LineData 的 line(int) / bitWidth() 方法句柄。 */
    private static MethodHandle lineOf;
    private static MethodHandle bitWidthOf;

    private SdfGlyphProvider() {
    }

    public static boolean available() {
        if (!initialised) {
            initialise();
        }
        return getFontSet != null && getActiveProviders != null;
    }

    private static synchronized void initialise() {
        if (initialised) {
            return;
        }
        try {
            MethodHandle fontSetHandle;
            try {
                fontSetHandle = MethodHandles.lookup().findVirtual(Font.class, "getFontSet",
                        MethodType.methodType(FontSet.class, ResourceLocation.class));
            } catch (ReflectiveOperationException inaccessible) {
                var m = Font.class.getDeclaredMethod("getFontSet", ResourceLocation.class);
                m.setAccessible(true);
                fontSetHandle = MethodHandles.lookup().unreflect(m);
            }
            getFontSet = fontSetHandle;

            Field providers = FontSet.class.getDeclaredField("activeProviders");
            providers.setAccessible(true);
            getActiveProviders = MethodHandles.lookup().unreflectGetter(providers);

            bmpImage = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "image");
            bmpOffsetX = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "offsetX");
            bmpOffsetY = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "offsetY");
            bmpWidth = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "width");
            bmpHeight = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "height");
            bmpScale = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "scale");
            bmpAscent = fieldOf("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph", "ascent");

            uniContents = fieldOf("net.minecraft.client.gui.font.providers.UnihexProvider$Glyph", "contents");
            uniLeft = fieldOf("net.minecraft.client.gui.font.providers.UnihexProvider$Glyph", "left");
            uniRight = fieldOf("net.minecraft.client.gui.font.providers.UnihexProvider$Glyph", "right");

            Class<?> lineDataClass =
                    Class.forName("net.minecraft.client.gui.font.providers.UnihexProvider$LineData");
            lineOf = MethodHandles.publicLookup().findVirtual(lineDataClass, "line",
                    MethodType.methodType(int.class, int.class));
            bitWidthOf = MethodHandles.publicLookup().findVirtual(lineDataClass, "bitWidth",
                    MethodType.methodType(int.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            getFontSet = null;
            getActiveProviders = null;
        } finally {
            initialised = true;
        }
    }

    private static Field fieldOf(String owner, String name) {
        try {
            Field f = Class.forName(owner).getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 取该码点的 SDF 图集区域，必要时现场烘焙。
     *
     * @return null 表示不可用或该字形无位图，调用方应退回原版绘制
     */
    public static SdfAtlas.Slot slot(int codepoint) {
        if (!available() || !SdfRenderType.usable()) {
            return null;
        }
        SdfAtlas.Slot cached = SLOTS.get(codepoint);
        if (cached != null) {
            return cached;
        }
        RawGlyph glyph = bake(codepoint);
        if (glyph == null || !glyph.hasBitmap()) {
            return null;
        }
        SdfAtlas.Slot slot = atlas().addGlyph(glyph);
        if (slot != null) {
            SLOTS.put(codepoint, slot);
        }
        return slot;
    }

    /** 该字形的绘制盒 {@code [left, top, right, bottom, advance]}；未烘焙时为 null。 */
    public static float[] box(int codepoint) {
        return BOXES.get(codepoint);
    }

    public static boolean isCached(int codepoint) {
        return SLOTS.containsKey(codepoint);
    }

    /**
     * 清空缓存并释放图集，用于资源包重载。
     *
     * <p>必须连 {@code RenderType} 缓存一起失效：
     * 那些 RenderType 绑定的是旧图集页的纹理 id，
     * 图集关闭后这些 id 已被回收，复用时可能指向别的纹理，
     * 表现就是「重启或换资源包后，正在着色的字整片透明」。
     */
    public static synchronized void reset() {
        SLOTS.clear();
        BOXES.clear();
        SdfRenderType.invalidate();
        if (atlas != null) {
            atlas.close();
            atlas = null;
        }
    }

    private static SdfAtlas atlas() {
        if (atlas == null) {
            atlas = new SdfAtlas(
                    ResourceLocation.fromNamespaceAndPath("linklyric", "sdf_glyphs"),
                    PAGE_SIZE);
        }
        return atlas;
    }

    /**
     * 覆盖率提取结果。
     *
     * @param coverage 位图，行主序
     * @param width    位图宽
     * @param height   位图高
     */
    private record Pixels(byte[] coverage, int width, int height) {
    }

    private static RawGlyph bake(int codepoint) {
        GlyphInfo info = glyphInfo(codepoint);
        if (info == null) {
            // 该码点不在任何 provider 里：可能是空格、控制字符，
            // 也可能字体里确实没有。都不占图集位置。
            return null;
        }
        boolean bitmap = true;
        Pixels pixels = bakeBitmap(info);
        if (pixels == null) {
            bitmap = false;
            pixels = bakeUnihex(info);
        }
        if (pixels == null || pixels.width() <= 0 || pixels.height() <= 0) {
            return null;
        }
        if (!bitmap) {
            // unifont 有自己的一套度量，与位图不同：
            // 字形以 2 倍过采样渲染，步进是墨迹宽的一半再 +1。
            // 这些数值取自原版 UnihexProvider 的实现（LDLib2 同样如此），
            // 换成 info.getAdvance() 会让中文字距与位置全错。
            float advance = pixels.width() / 2f + 1f;
            RawGlyph glyph = SdfGlyphBaker.bake(pixels.coverage(), pixels.width(),
                    pixels.height(), 0.5f, 16f, advance,
                    2, 2, 4f);
            BOXES.put(codepoint, new float[]{glyph.left(), glyph.top(), glyph.right(),
                    glyph.bottom(), glyph.advance()});
            return glyph;
        }

        // 位图度量取自 SheetGlyphInfo：它是原版排版用的权威值。
        // oversample 是「一个位图像素对应多少设计空间单位」的倒数。
        float[] box = sheetBox(info);
        if (box == null) {
            return null;
        }
        float oversample = box[5];
        if (oversample <= 0f) {
            oversample = 1f;
        }
        float pixelScale = 1f / oversample;
        float ascentPixels = (7f - box[1]) / pixelScale;

        RawGlyph glyph = SdfGlyphBaker.bake(pixels.coverage(), pixels.width(),
                pixels.height(), pixelScale, ascentPixels, info.getAdvance());
        BOXES.put(codepoint, new float[]{glyph.left(), glyph.top(), glyph.right(),
                glyph.bottom(), glyph.advance()});
        return glyph;
    }

    /**
     * 取原版排版用的绘制盒。
     *
     * <p>{@code GlyphInfo.bake(loader)} 的回调会给出该字形的
     * {@link SheetGlyphInfo}，其中的 left/right/top/bottom 就是原版
     * 计算四边形用的值。这里只截获它、不做真正的图集上传。
     *
     * @return {@code [left, top, right, bottom, advance, oversample]}
     */
    private static float[] sheetBox(GlyphInfo info) {
        Capturing capturing = new Capturing();
        try {
            info.bake(capturing);
        } catch (RuntimeException e) {
            return null;
        }
        SheetGlyphInfo sheet = capturing.sheet;
        if (sheet == null) {
            return null;
        }
        return new float[]{sheet.getLeft(), sheet.getTop(), sheet.getRight(),
                sheet.getBottom(), info.getAdvance(), sheet.getOversample()};
    }

    /**
     * 截获 {@link SheetGlyphInfo} 的 loader。
     *
     * <p>原语义是把位图写进图集；这里不做上传（图集是我们自己管的），
     * 只把该对象留下来供读取度量。
     */
    private static final class Capturing
            implements java.util.function.Function<SheetGlyphInfo, BakedGlyph> {

        SheetGlyphInfo sheet;

        @Override
        public BakedGlyph apply(SheetGlyphInfo info) {
            this.sheet = info;
            return null;
        }
    }

    /**
     * 位图提供者：格子就在 {@link NativeImage} 里，直接抠出来。
     *
     * <p>只取 alpha 通道——资源包里的位图字形即使本身是彩色的，
     * 也渲染成实心轮廓，与原版所有字体图都是白 + alpha 的事实一致。
     */
    private static Pixels bakeBitmap(GlyphInfo info) {
        if (bmpImage == null || !bmpImage.getDeclaringClass().isInstance(info)) {
            return null;
        }
        try {
            NativeImage image = (NativeImage) bmpImage.get(info);
            if (image == null) {
                return null;
            }
            int ox = bmpOffsetX.getInt(info);
            int oy = bmpOffsetY.getInt(info);
            int w = bmpWidth.getInt(info);
            int h = bmpHeight.getInt(info);
            if (w <= 0 || h <= 0) {
                return null;
            }
            int imgW = image.getWidth();
            int imgH = image.getHeight();
            byte[] coverage = new byte[w * h];
            for (int y = 0; y < h; y++) {
                int sy = oy + y;
                if (sy < 0 || sy >= imgH) {
                    continue;
                }
                for (int x = 0; x < w; x++) {
                    int sx = ox + x;
                    if (sx < 0 || sx >= imgW) {
                        continue;
                    }
                    // getPixelRGBA 返回 ABGR；alpha 在高 8 位，与 RGB 顺序无关。
                    coverage[y * w + x] = (byte) ((image.getPixelRGBA(sx, sy) >>> 24) & 0xFF);
                }
            }
            return new Pixels(coverage, w, h);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** unifont 固定 16 行高。 */
    private static final int UNIFONT_ROWS = 16;

    /**
     * unifont 提供者：位行数据展开成覆盖率。
     *
     * <h2>位布局（与 LDLib2 的 UnihexSdfSource 一致）</h2>
     *
     * <p>每行是 16 位数据，但<b>左对齐存放在 32 位 int 里</b>——
     * 第一个像素在 bit31，而不是 bit(bitWidth-1)。
     *
     * <p>过去我按 {@code bitWidth} 求位移，对 16 宽的字形恰好等价，
     * 但对 8 宽的字形会把有效像素整体右移 16 位，全部落到零区，
     * 拼出来的覆盖率整片为零——观感就是中文整行消失。
     *
     * <p>墨迹左右界由所有行的按位或反推，这也与 LDLib2 相同：
     * <pre>
     *   mask  = 所有行按位或
     *   left  = numberOfLeadingZeros(mask)
     *   right = 32 - numberOfTrailingZeros(mask) - 1
     * </pre>
     */
    private static Pixels bakeUnihex(GlyphInfo info) {
        if (uniContents == null || !uniContents.getDeclaringClass().isInstance(info)) {
            return null;
        }
        try {
            Object contents = uniContents.get(info);
            if (contents == null || lineOf == null) {
                return null;
            }
            int[] rows = new int[UNIFONT_ROWS];
            int mask = 0;
            for (int y = 0; y < UNIFONT_ROWS; y++) {
                rows[y] = (int) lineOf.invoke(contents, y);
                mask |= rows[y];
            }
            if (mask == 0) {
                // 全空：无墨迹，不占图集位置。
                return null;
            }
            int left = Integer.numberOfLeadingZeros(mask);
            int right = 32 - Integer.numberOfTrailingZeros(mask) - 1;
            int width = right - left + 1;
            if (width <= 0 || width > 32) {
                return null;
            }
            byte[] coverage = new byte[width * UNIFONT_ROWS];
            for (int y = 0; y < UNIFONT_ROWS; y++) {
                int row = rows[y];
                for (int x = 0; x < width; x++) {
                    // 位在本行里左对齐：第 x 个像素对应 bit(31 - left - x)。
                    int shift = 31 - left - x;
                    boolean on = shift >= 0 && shift < 32 && ((row >>> shift) & 1) != 0;
                    coverage[y * width + x] = (byte) (on ? 0xFF : 0);
                }
            }
            return new Pixels(coverage, width, UNIFONT_ROWS);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static GlyphInfo glyphInfo(int codepoint) {
        for (GlyphProvider provider : providers()) {
            try {
                if (!provider.getSupportedGlyphs().contains(codepoint)) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;
            }
            GlyphInfo info = provider.getGlyph(codepoint);
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    private static List<GlyphProvider> providers() {
        FontSet set = fontSet();
        if (set == null) {
            return List.of();
        }
        try {
            Object value = getActiveProviders.invoke(set);
            if (value instanceof List<?> list) {
                return list.stream()
                        .filter(GlyphProvider.class::isInstance)
                        .map(GlyphProvider.class::cast)
                        .toList();
            }
        } catch (Throwable t) {
            // 字段改名或模块限制：视为不可用。
        }
        return List.of();
    }

    private static FontSet fontSet() {
        if (!available()) {
            return null;
        }
        try {
            return (FontSet) getFontSet.invoke(Minecraft.getInstance().font, DEFAULT_FONT);
        } catch (Throwable t) {
            return null;
        }
    }
}
