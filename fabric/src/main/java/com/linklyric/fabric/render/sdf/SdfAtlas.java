package com.linklyric.fabric.render.sdf;

import com.linklyric.common.font.RawGlyph;
import com.linklyric.common.font.SkylinePacker;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

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
        private GpuTexture texture;
        private GpuTextureView view;

        private Page(ResourceLocation name, int size) {
            this.name = name;
            this.size = size;
            this.packer = new SkylinePacker(size, size);
            // 1.21.5 起纹理不再是「拿一个 GL id 去 prepare」，改成由
            // GpuDevice 分配、GpuTexture 承载，上传走 CommandEncoder。
            GpuDevice device = RenderSystem.getDevice();
            // 1.21.6 的 createTexture 多了 usage 位与 mip 数：这张图既当
            // 纹理采样，也要接受 writeToTexture 拷贝，两者都要声明。
            this.texture = device.createTexture(
                    () -> "LinkLyricSdfAtlas/" + name,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RED8,
                    size, size, 1, 1);
            this.view = device.createTextureView(this.texture);
            // 距离场必须线性过滤：场重建靠插值；UV 不越界，钳到边缘即可。
            this.texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            this.texture.setTextureFilter(FilterMode.LINEAR, false);
            // 新建纹理内容未定义，清零，避免未使用区域渗进字形。
            upload(0, 0, size, size, new byte[size * size]);
        }

        public ResourceLocation name() {
            return name;
        }

        /** 该页的 GPU 纹理。 */
        public GpuTexture texture() {
            return this.texture;
        }

        /** 该页的纹理视图，1.21.6 起渲染绑定与 GUI 提交都用它。 */
        public GpuTextureView view() {
            return this.view;
        }

        private Slot tryAdd(int width, int height, byte[] data) {
            if (!packer.insert(width + GUTTER, height + GUTTER, scratch)) {
                return null;
            }
            int x = scratch[0];
            int y = scratch[1];
            upload(x, y, width, height, data);
            return new Slot(this, x, y, width, height);
        }

        /**
         * 上传一块单通道位图。
         *
         * <p>1.21.5 的 {@code writeToTexture} 只接受 {@link NativeImage}，
         * 因此把这块单通道位图包进一张临时 RGBA 图（各通道同值，
         * RED8 只读第一个通道），再按矩形写入。
         *
         * <p>这样也不必再碰 GL_UNPACK_* 那套全局状态：过去改完必须复原，
         * 否则会污染原版字体的纹理上传。
         */
        private void upload(int x, int y, int width, int height, byte[] pixels) {
            NativeImage scratch = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            try {
                for (int dy = 0; dy < height; dy++) {
                    for (int dx = 0; dx < width; dx++) {
                        int v = pixels[dy * width + dx] & 0xFF;
                        scratch.setPixelABGR(dx, dy, (v << 24) | (v << 16) | (v << 8) | v);
                    }
                }
                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                // 签名：纹理, 图, mip, 源x, 源y, 目标x, 目标y, 宽, 高, 目标mip
                encoder.writeToTexture(this.texture, scratch, 0, 0, 0, x, y, width, height, 0);
            } finally {
                scratch.close();
            }
        }

        // 1.21.4 移除了 AbstractTexture.load(ResourceManager)，
        // 内容本就由 add() 逐块上传，无需再覆写空实现。

        @Override
        public void close() {
            // 1.21.5 起纹理由 GpuTexture 持有，关它即释放显存。
            super.close();
        }
    }
}
