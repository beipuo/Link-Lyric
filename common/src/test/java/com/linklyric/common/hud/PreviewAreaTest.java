package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

/**
 * 预览区布局：歌词必须画进指定矩形，而不是按全屏底部摆放。
 */
class PreviewAreaTest {

    private static FloatingLyricRenderer renderer() {
        return new FloatingLyricRenderer(new TestFeeds.Simple());
    }

    /** 预览时歌词应在框内水平居中。 */
    @Test
    void 预览内水平居中() {
        FloatingLyricRenderer r = renderer();
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(100, 20, 200, 60));

        BoundsPainter p = new BoundsPainter();
        r.render(p);

        FloatingLyricRenderer.Bounds b = r.bounds();
        int boxCenter = 100 + 200 / 2;
        int textCenter = b.x() + b.width() / 2;
        // 允许几像素误差：文字宽度的奇偶会让整数居中偏 1 像素。
        org.junit.jupiter.api.Assertions.assertTrue(Math.abs(textCenter - boxCenter) <= 3,
                "应大致水平居中，文字中心=" + textCenter + " 框中心=" + boxCenter);
    }

    /** 预览时用户偏移必须被忽略，否则文字会被顶出框。 */
    @Test
    void 预览忽略用户偏移() {
        FloatingLyricRenderer r = renderer();
        r.setOffset(999, -999);
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(50, 10, 200, 60));

        r.render(new BoundsPainter());

        FloatingLyricRenderer.Bounds b = r.bounds();
        org.junit.jupiter.api.Assertions.assertTrue(b.x() >= 50 && b.x() < 250,
                "包围盒应在预览框内，实际 x=" + b.x());
    }

    /** 包围盒必须被裁进预览框，命中检测不能超框。 */
    @Test
    void 包围盒裁进预览框() {
        FloatingLyricRenderer r = renderer();
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(100, 30, 120, 40));

        r.render(new BoundsPainter());

        FloatingLyricRenderer.Bounds b = r.bounds();
        org.junit.jupiter.api.Assertions.assertTrue(b.x() >= 100, "左边界不应超出框");
        org.junit.jupiter.api.Assertions.assertTrue(b.y() >= 30, "上边界不应超出框");
        org.junit.jupiter.api.Assertions.assertTrue(b.x() + b.width() <= 220,
                "右边界不应超出框，实际=" + (b.x() + b.width()));
    }

    /** 清除预览区后应恢复全屏布局。 */
    @Test
    void 清除后恢复全屏布局() {
        FloatingLyricRenderer r = renderer();
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(100, 30, 120, 40));
        r.render(new BoundsPainter());
        int previewY = r.bounds().y();

        r.setPreviewArea(null);
        r.render(new BoundsPainter());
        int fullY = r.bounds().y();

        org.junit.jupiter.api.Assertions.assertNotEquals(previewY, fullY,
                "清除预览区后位置应回到全屏底部");
    }

    private static final class BoundsPainter implements FloatingLyricRenderer.Painter {
        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }
    }
}
