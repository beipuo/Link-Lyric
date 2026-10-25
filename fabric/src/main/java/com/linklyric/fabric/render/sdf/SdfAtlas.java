package com.linklyric.fabric.render.sdf;

import com.linklyric.common.font.RawGlyph;
import com.linklyric.common.font.SkylinePacker;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 单通道（R8）字形图集，可有多张方形页。
 *
 * <p>与原版 {@code FontTexture}（固定 256×256、每页一张纹理）相比，
 * 这里的页大得多，通常一屏文字全部落在一页里，因而合并成一次绘制。
 *
 * <p>距离场页用 {@code GL_LINEAR} 过滤——场重建依赖它插值；
 * 按原尺寸精确栅格的页用 {@code GL_NEAREST}，那种页一个纹素就是
 * 一个设备像素，过滤只会把它糊掉。
 *
 * <h2>为什么预留 whiteBlock</h2>
 *
 * <p>下划线、删除线、文字底色需要一块「全不透明」的纹理。
 * 如果不预留，它们就得另开一张纹理，从而把绘制批次打断。
 * 在第一页创建时就占用它，而不是等第一次遇到下划线时才申请——
 * 那时第一页可能已经装满，申请失败会导致整条路径失效。
 */
public final class SdfAtlas implements AutoCloseable {

    /** 字形之间留的间隙，防止线性过滤把相邻两字混在一起。 */
    private static final int GUTTER = 1;

    /** 第一页上预留的全不透明块，供下划线等效果使用。 */
    private static final int WHITE_BLOCK = 8;

    private final ResourceLocation namePrefix;
    private final int pageSize;
    private final List<Page> pages = new ArrayList<>();
    private final int[] scratch = new int[2];

    private Slot whiteSlot;

    public SdfAtlas(ResourceLocation namePrefix, int pageSize) {
        this.namePrefix = namePrefix;
        this.pageSize = pageSize;
    }

    /** 一页上存放单个字形位图的区域。 */
    public record Slot(Page page, int x, int y, int width, int height) {
        public float u0() {
            return (float) x / page.size;
        }

        public float u1() {
            return (float) (x + width) / page.size;
        }

        public float v0() {
            return (float) y / page.size;
        }

        public float v1() {
            return (float) (y + height) / page.size;
        }
    }

    /**
     * 上传一张单通道位图。
     *
     * @return 分配到的区域；位图大到任何空页都放不下时返回 null
     */
    public Slot add(int width, int height, byte[] data) {
        RenderSystem.assertOnRenderThread();
        if (width <= 0 || height <= 0) {
            return null;
        }
        // 空页都放不下的位图永远放不进去；继续走 newPage() 会每次泄漏一整页纹理。
        if (width + GUTTER > pageSize || height + GUTTER > pageSize) {
            return null;
        }
        for (Page page : pages) {
            Slot slot = page.tryAdd(width, height, data);
            if (slot != null) {
                return slot;
            }
        }
        return newPage().tryAdd(width, height, data);
    }

    /** 烘焙一个字形并放进图集。 */
    public Slot addGlyph(RawGlyph glyph) {
        if (!glyph.hasBitmap()) {
            return null;
        }
        return add(glyph.width(), glyph.height(), glyph.data());
    }

    public int pageCount() {
        return pages.size();
    }

    /** @return 该图集占用的纹理字节数（单通道，一纹素一字节） */
    public long byteSize() {
        return (long) pages.size() * pageSize * pageSize;
    }

    private Page newPage() {
        ResourceLocation name = namePrefix.withSuffix("/" + pages.size());
        Page page = new Page(name, pageSize);
        boolean first = pages.isEmpty();
        pages.add(page);
        Minecraft.getInstance().getTextureManager().register(name, page);
        if (first) {
            reserveWhiteBlock(page);
        }
        return page;
    }

    /** 在新建的第一页上占下 whiteBlock，8×8 在空页上必然放得下。 */
    private void reserveWhiteBlock(Page page) {
        byte[] data = new byte[WHITE_BLOCK * WHITE_BLOCK];
        java.util.Arrays.fill(data, (byte) 0xFF);
        Slot slot = page.tryAdd(WHITE_BLOCK, WHITE_BLOCK, data);
        if (slot == null) {
            throw new IllegalStateException("空字形图集页上无法预留 whiteBlock");
        }
        // 内缩 2px：线性过滤不会触到透明的间隙。
        whiteSlot = new Slot(slot.page(), slot.x() + 2, slot.y() + 2,
                WHITE_BLOCK - 4, WHITE_BLOCK - 4);
    }

    @Override
    public void close() {
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (Page page : pages) {
            textureManager.release(page.name);
            page.close();
        }
        pages.clear();
        whiteSlot = null;
    }

    /** 一页图集，支撑为 GL_R8 纹理。 */
    public final class Page extends AbstractTexture {
        private final ResourceLocation name;
        private final int size;
        private final SkylinePacker packer;

        private Page(ResourceLocation name, int size) {
            this.name = name;
            this.size = size;
            this.packer = new SkylinePacker(size, size);
            TextureUtil.prepareImage(NativeImage.InternalGlFormat.RED, this.getId(), size, size);
            this.bind();
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // 距离场必须线性过滤：场重建靠插值。
            this.setFilter(true, false);
            // prepareImage 留下未定义内容，清零，避免未使用区域渗进字形。
            java.nio.ByteBuffer zero = MemoryUtil.memCalloc(size * size);
            try {
                upload(0, 0, size, size, MemoryUtil.memAddress0(zero));
            } finally {
                MemoryUtil.memFree(zero);
            }
        }

        public ResourceLocation name() {
            return name;
        }

        private Slot tryAdd(int width, int height, byte[] data) {
            if (!packer.insert(width + GUTTER, height + GUTTER, scratch)) {
                return null;
            }
            int x = scratch[0];
            int y = scratch[1];
            java.nio.ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
            try {
                buffer.put(data);
                this.bind();
                upload(x, y, width, height, MemoryUtil.memAddress0(buffer));
            } finally {
                MemoryUtil.memFree(buffer);
            }
            return new Slot(this, x, y, width, height);
        }

        /**
         * 上传一块单通道位图。
         *
         * <p>R8 每行不是 4 字节对齐的，沿用默认的 unpack 对齐会让行首错位，
         * 表现是字形被斜着撕开。上传后必须把改过的像素存储状态复原，
         * 否则会污染后续所有纹理上传（原版字体、GUI 图标都会受影响）。
         */
        private void upload(int x, int y, int width, int height, long pixels) {
            int previousAlignment = GlStateManager._getInteger(GL11.GL_UNPACK_ALIGNMENT);
            int previousRowLength = GlStateManager._getInteger(GL11.GL_UNPACK_ROW_LENGTH);
            int previousSkipPixels = GlStateManager._getInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            int previousSkipRows = GlStateManager._getInteger(GL11.GL_UNPACK_SKIP_ROWS);
            try {
                GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
                GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                        GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);
            } finally {
                GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, previousAlignment);
                GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, previousRowLength);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, previousSkipRows);
            }
        }

        @Override
        public void load(ResourceManager resourceManager) {
            // 内容由 add() 逐块上传，不走资源加载。
        }

        @Override
        public void close() {
            this.releaseId();
        }
    }
}
