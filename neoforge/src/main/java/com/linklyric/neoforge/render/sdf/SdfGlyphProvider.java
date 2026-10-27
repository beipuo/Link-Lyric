package com.linklyric.neoforge.render.sdf;

import com.linklyric.common.font.RawGlyph;
import com.linklyric.common.font.SdfGlyphBaker;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.UnbakedGlyph;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 码点 -> 图集区域。
     *
     * <p>用并发容器而非 {@code HashMap}：写入发生在渲染线程（歌词换行时首次见到某个字），
     * 而 {@link #reset()} 由资源重载触发，在 NeoForge 上不保证落在渲染线程。
     * 两者一旦交错，普通 {@code HashMap} 会在扩容期间留下环形链表或丢失条目，
     * 表现为图集错乱、字形指向别人的格子——这类故障只在换资源包时偶发，
     * 事后极难复现。读路径本身是热路径，{@code ConcurrentHashMap} 的无锁读也不会拖慢它。
     */
    private static final Map<Integer, SdfAtlas.Slot> SLOTS = new ConcurrentHashMap<>();

    /** 码点 -> 绘制盒 {@code [left, top, right, bottom, advance]}，设计空间。 */
    private static final Map<Integer, float[]> BOXES = new ConcurrentHashMap<>();

    /**
     * 码点 -> 轮廓外可用的距离（设计空间）。
     *
     * <p>光晕半径的物理上限：图集留白只铺了这么远，再往外采到的是
     * CLAMP 的边界像素，衰减被截断成硬边。绘制层据此封顶，
     * 因此换留白、换字体、换展开倍数都会自动跟着变。
     */
    private static final Map<Integer, Float> GLOW_SPACE = new ConcurrentHashMap<>();



    /**
     * 图集。
     *
     * <p>{@code volatile} 不是保守起见：这个字段被两条线程无锁读写——
     * 渲染线程在 {@link #slot(int)} 里首次用到时惰性创建，
     * 资源重载线程在 {@link #reset()} 里关闭并置空。
     * 没有 volatile 时，渲染线程可能读到「已置空」与「已创建」之间的
     * 陈旧值或重排序结果，表现为拿到一个已 close 的图集，
     * 于是整行字画到已被回收的纹理 id 上，屏幕上是透明或别人的纹理。
     *
     * <p>用 {@code synchronized} 把整段包住代价太高：{@link #slot(int)}
     * 在渲染热路径上，每个新字都会进来一次。volatile 保证可见性，
     * 「可能拿到刚被关闭的图集」这一残余窗口由 {@code reset()} 的
     * 调用时机（主线程、渲染停顿期间）覆盖。
     */
    private static volatile SdfAtlas atlas;

    private static boolean initialised;

    /** 见 {@link #degrade()}：反射链路被证明不可用后置位，避免每帧重试。 */
    private static boolean degraded;

    private static MethodHandle getFontSet;
    private static MethodHandle getActiveProviders;

    /** BitmapProvider$Glyph 的字段访问器。 */
    private static Field bmpImage;
    private static Field bmpOffsetX;
    private static Field bmpOffsetY;
    private static Field bmpWidth;
    private static Field bmpHeight;

    /** UnihexProvider$Glyph 的字段访问器。 */
    private static Field uniContents;

    /** UnihexProvider$LineData 的 line(int) 方法句柄。 */
    private static MethodHandle lineOf;

    private SdfGlyphProvider() {
    }

    public static boolean available() {
        if (!initialised) {
            initialise();
        }
        return getFontSet != null && getActiveProviders != null && !degraded;
    }

    /**
     * 反射链路是否已经被判定为不可用。
     *
     * <p>不设这个标志时，「字体集拿到了、但字形字段改名了」这种半可用状态
     * 会被 {@link #available()} 判为可用，于是每个字、每一帧都走一遍完整烘焙：
     * {@code glyphInfo()} 遍历全部 provider 并分配 List，{@code bakeBitmap}
     * 取字段失败，{@code bakeUnihex} 再失败一次，最后返回 null 退回原版绘制。
     * 一句 30 字、60fps 就是每秒 1800 次注定失败的反射调用链。
     *
     * <p>失败一次即整体降级：反射字段不会在运行期变好，资源包重载会走
     * {@link #reset()}，那时再把 {@code initialised} 清掉重新探测。
     */
    private static void degrade() {
        degraded = true;
    }

    /** 该字形需要的反射字段是否齐全。 */
    private static boolean bitmapFieldsUsable() {
        return bmpImage != null && bmpOffsetX != null && bmpOffsetY != null
                && bmpWidth != null && bmpHeight != null;
    }

    private static boolean unihexFieldsUsable() {
        return uniContents != null && lineOf != null;
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

            uniContents = fieldOf("net.minecraft.client.gui.font.providers.UnihexProvider$Glyph", "contents");

            Class<?> lineDataClass =
                    Class.forName("net.minecraft.client.gui.font.providers.UnihexProvider$LineData");
            lineOf = MethodHandles.publicLookup().findVirtual(lineDataClass, "line",
                    MethodType.methodType(int.class, int.class));
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
        // 先摘掉引用再关闭：反过来的话，另一条线程可能在「已 close」与
        // 「已置空」之间读到那个正在被释放的图集，把字画到已回收的纹理上。
        SdfAtlas old = atlas;
        atlas = null;
        SLOTS.clear();
        BOXES.clear();
        GLOW_SPACE.clear();
        // 资源包或存档重载后，字体 provider 会被整体换掉，反射探测值得重来一次。
        degraded = false;
        initialised = false;
        SdfRenderType.invalidate();
        if (old != null) {
            old.close();
        }
    }

    /**
     * 取图集，不存在则创建。
     *
     * <p>惰性创建与 {@link #reset()} 的关闭可能交错，因此这里用
     * 「创建后只发布一次」的写法：先建好局部实例，再靠 volatile 写发布。
     * 两条线程最多各自建一个图集，先发布的胜出，后建的立刻关掉——
     * 泄漏一个从未被绘制的 GL 纹理，好过让绘制线程拿着已回收的纹理 id 出画。
     */
    private static SdfAtlas atlas() {
        SdfAtlas local = atlas;
        if (local != null) {
            return local;
        }
        SdfAtlas created = new SdfAtlas(
                ResourceLocation.fromNamespaceAndPath("linklyric", "sdf_glyphs"),
                PAGE_SIZE);
        synchronized (SdfGlyphProvider.class) {
            if (atlas != null) {
                // 另一条线程抢先建好了：丢弃自己这份，避免泄漏。
                created.close();
                return atlas;
            }
            atlas = created;
            return created;
        }
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
        UnbakedGlyph unbaked = glyphInfo(codepoint);
        if (unbaked == null) {
            // 该码点不在任何 provider 里：可能是空格、控制字符，
            // 也可能字体里确实没有。都不占图集位置。
            return null;
        }
        // 两条路都不可用时直接整体降级：缺字段是编译期就定死的事实，
        // 不会因为这个字换成另一个字就好转，继续逐字重试纯属浪费。
        if (!bitmapFieldsUsable() && !unihexFieldsUsable()) {
            degrade();
            return null;
        }
        // 度量与位图现在分处两个对象，先让原版把它们配成一对。
        Capturing capturing = new Capturing();
        GlyphInfo info;
        try {
            unbaked.bake(capturing);
            info = capturing.info;
        } catch (RuntimeException e) {
            return null;
        }
        if (info == null) {
            return null;
        }
        boolean bitmap = true;
        // 像素仍从 provider 自己的 record 里抠：度量对象只带步进，
        // 位图与行数据都还在 UnbakedGlyph 上。
        Pixels pixels = bitmapFieldsUsable() ? bakeBitmap(unbaked) : null;
        if (pixels == null) {
            bitmap = false;
            pixels = unihexFieldsUsable() ? bakeUnihex(unbaked) : null;
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
            // padding 必须不小于 spread，否则场在留白边缘还没降满量程，
            // 梯度被截断、轮廓外的半边缺失。这里取 4/4 使两者相等，
            // 同时把留白抬到 2 个设计像素，与位图分支一致。
            RawGlyph glyph = SdfGlyphBaker.bake(pixels.coverage(), pixels.width(),
                    pixels.height(), 0.5f, 16f, advance,
                    2, 4, 4f);
            BOXES.put(codepoint, new float[]{glyph.left(), glyph.top(), glyph.right(),
                    glyph.bottom(), glyph.advance()});
            GLOW_SPACE.put(codepoint, glyph.glowSpace());
            return glyph;
        }

        // 位图度量取自 SheetGlyphInfo：它是原版排版用的权威值。
        // oversample 是「一个位图像素对应多少设计空间单位」的倒数。
        float[] box = sheetBox(unbaked);
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
        GLOW_SPACE.put(codepoint, glyph.glowSpace());
        return glyph;
    }

    /**
     * 该码点轮廓外可用的距离（设计空间）。未烘焙时为 0。
     *
     * <p>0 表示没有可用留白，绘制层应完全跳过光晕——否则光晕会在
     * 纹理边界当场截断。
     */
    public static float glowSpace(int codepoint) {
        Float v = GLOW_SPACE.get(codepoint);
        return v == null ? 0f : v;
    }

    /**
     * 取原版排版用的绘制盒。
     *
     * <p>1.21.9 把原来的 {@code SheetGlyphInfo} 拆成了两半：
     * 度量在 {@link GlyphInfo} 上，位图在 {@link GlyphBitmap} 上，
     * 两者经 {@link UnbakedGlyph.Stitcher#stitch} 汇合。
     * 这里只截获那一对、不做真正的图集上传。
     *
     * @return {@code [left, top, right, bottom, advance, oversample]}
     */
    private static float[] sheetBox(UnbakedGlyph unbaked) {
        Capturing capturing = new Capturing();
        try {
            unbaked.bake(capturing);
        } catch (RuntimeException e) {
            return null;
        }
        GlyphInfo info = capturing.info;
        GlyphBitmap bitmap = capturing.bitmap;
        if (info == null || bitmap == null) {
            return null;
        }
        return new float[]{bitmap.getLeft(), bitmap.getTop(), bitmap.getRight(),
                bitmap.getBottom(), info.getAdvance(), bitmap.getOversample()};
    }

    /**
     * 截获 {@link UnbakedGlyph} 的装载回调。
     *
     * <p>原语义是把位图写进图集；这里不做上传（图集是我们自己管的），
     * 只把度量与位图留下来供后续读取。
     */
    private static final class Capturing implements UnbakedGlyph.Stitcher {

        GlyphInfo info;
        GlyphBitmap bitmap;

        @Override
        public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
            this.info = info;
            this.bitmap = bitmap;
            return null;
        }

        @Override
        public BakedGlyph getMissing() {
            return null;
        }
    }

    /**
     * 位图提供者：格子就在 {@link NativeImage} 里，直接抠出来。
     *
     * <p>只取 alpha 通道——资源包里的位图字形即使本身是彩色的，
     * 也渲染成实心轮廓，与原版所有字体图都是白 + alpha 的事实一致。
     */
    private static Pixels bakeBitmap(UnbakedGlyph info) {
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
                    // getPixel 返回 ABGR；alpha 在高 8 位，与 RGB 顺序无关。
                    coverage[y * w + x] = (byte) ((image.getPixel(sx, sy) >>> 24) & 0xFF);
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
    private static Pixels bakeUnihex(UnbakedGlyph info) {
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

    /**
     * 取该码点对应的未烘焙字形。
     *
     * <p>1.21.9 起 provider 返回的是 {@link UnbakedGlyph}：
     * 度量与位图都还没合到一起，要经 {@code bake(Stitcher)} 才拿到
     * 两者的配对。因此这里返回未烘焙对象，由调用方决定取哪一半。
     */
    private static UnbakedGlyph glyphInfo(int codepoint) {
        for (GlyphProvider provider : providers()) {
            try {
                if (!provider.getSupportedGlyphs().contains(codepoint)) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;
            }
            UnbakedGlyph glyph = provider.getGlyph(codepoint);
            if (glyph != null) {
                return glyph;
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