package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
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

    /**
     * 预览里的可用宽度必须跟随「最大宽度」比例。
     *
     * <p>此前预览分支直接取 {@code 框宽 - 8}，把 {@code maxWidthRatio}
     * 整个跳过：拖动滑条时预览宽度毫无变化，玩家照着调出来的效果
     * 进游戏才发现不一样。真实路径一直是乘比例的，
     * 预览只要漏乘就和「所见即所得」背道而驰。
     *
     * <p>判据用**长句**：短句本身比收窄后的可用宽度还短，
     * 两条路径会得到同样的包围盒，测不出差异——这正是这类
     * 「看起来接了、其实没接」的缺陷最容易骗过测试的地方。
     */
    @Test
    void 预览宽度跟随最大宽度比例() {
        // 必须用长句：文本本身比可用宽度窄时，两条路径都会量到
        // 同样的「文字宽度」，比例根本没有机会体现，测试会假通过。
        FloatingLyricRenderer r = new FloatingLyricRenderer(longLineFeed());
        int boxWidth = 300;
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(50, 20, boxWidth, 60));

        r.setStyle(LyricStyleConfig.DEFAULT.withMaxWidthRatio(0.3f));
        r.render(new BoundsPainter());
        int narrow = r.bounds().width();

        r.setStyle(LyricStyleConfig.DEFAULT.withMaxWidthRatio(1.0f));
        r.render(new BoundsPainter());
        int wide = r.bounds().width();

        org.junit.jupiter.api.Assertions.assertTrue(wide > narrow,
                "比例调大后预览宽度应变宽：0.3→" + narrow + "，1.0→" + wide);
        // 0.3 与 1.0 的实际可用宽度差是 0.7×300 = 210 像素，
        // 包围盒还含左右留白，因此留足余量仍能区分「接上了」与「没接」。
        org.junit.jupiter.api.Assertions.assertTrue(wide - narrow > boxWidth / 4,
                "宽度差异过小，疑似比例未接入：0.3→" + narrow + "，1.0→" + wide);
    }

    /** 一行足够长的歌词，使文本宽度真正受可用宽度约束。 */
    private static LyricFeed longLineFeed() {
        String text = "一二三四五六七八九十一二三四五六七八九十";
        long step = 1000L;
        java.util.List<com.linklyric.core.lyric.LyricWord> words = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            words.add(new com.linklyric.core.lyric.LyricWord(String.valueOf(text.charAt(i)),
                    step * i, step * (i + 1)));
        }
        com.linklyric.core.lyric.LyricTimeline timeline =
                new com.linklyric.core.lyric.LyricTimeline(java.util.List.of(
                        new com.linklyric.core.lyric.LyricLine(text, "", 0, step * text.length(),
                                java.util.List.copyOf(words), "")));
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public com.linklyric.core.sync.PlayerSnapshot pollSnapshot() {
                return TestFeeds.snapshot(500L);
            }

            @Override
            public com.linklyric.core.lyric.LyricTimeline lyric() {
                return timeline;
            }

            @Override
            public long lyricSignature() {
                return 7L;
            }
        };
    }

    /**
     * 数据源被关掉时，预览仍须出画。
     *
     * <p>数据源的 {@code isAlive()} 包含「用户是否启用」。玩家关掉数据源后
     * 进配置界面，原本整块预览是空的——颜色、字号、描边怎么调都看不见，
     * 而配置界面恰恰是唯一需要看见它们的地方。
     *
     * <p>预览要的是样式，与此刻有没有歌词无关。
     */
    @Test
    void 数据源关闭时预览仍出画() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(disabledFeed());
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(50, 20, 200, 60));
        r.setPreviewForced(true);

        r.render(new BoundsPainter());

        org.junit.jupiter.api.Assertions.assertFalse(r.bounds().isEmpty(),
                "数据源关闭时预览仍应有内容，实际包围盒为空");

        // 反向：同样关闭的数据源，非预览场景必须不出画。
        FloatingLyricRenderer hidden = new FloatingLyricRenderer(disabledFeed());
        hidden.setPreviewForced(false);
        hidden.render(new BoundsPainter());
        org.junit.jupiter.api.Assertions.assertTrue(hidden.bounds().isEmpty(),
                "非预览且数据源关闭时不应出画");
    }

    /** 一个自报不可用的数据源，模拟玩家关掉了数据源开关。 */
    private static LyricFeed disabledFeed() {
        com.linklyric.core.lyric.LyricTimeline timeline = TestFeeds.timeline();
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return false;
            }

            @Override
            public com.linklyric.core.sync.PlayerSnapshot pollSnapshot() {
                return TestFeeds.snapshot(500L);
            }

            @Override
            public com.linklyric.core.lyric.LyricTimeline lyric() {
                return timeline;
            }

            @Override
            public long lyricSignature() {
                return 3L;
            }
        };
    }

    /**
     * 预览里必须看得到逐字上浮。
     *
     * <p>上浮只要求「平台能裁剪」——裁剪把每个字的绘制限制在自己格内。
     * 此前这里复用了 {@code canScroll}，而预览把 {@code canScroll}
     * 强制关掉（预览不滚动），于是预览成了一片不会动的静态文字：
     * 玩家调字号、行距、上浮曲线时看不到任何变化，进游戏才发现不一样。
     *
     * <p>上浮与滚动本是两件事，被同一个布尔量绑在一起才是根因。
     */
    @Test
    void 预览内出现逐字上浮() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setPreviewArea(new FloatingLyricRenderer.Bounds(50, 20, 300, 60));
        r.setPreviewForced(true);
        // 比例调到最大，让可用宽度足够容纳整行四字，
        // 否则视口比文字还窄，大部分字会因越界被跳过。
        r.setStyle(LyricStyleConfig.DEFAULT.withMaxWidthRatio(1.0f));

        YRecordingPainter p = new YRecordingPainter();
        r.render(p);

        // 已唱过的字被抬起，其 y 应比未唱的字更小（屏幕坐标向上为负）。
        java.util.Map<String, Integer> ys = p.oneCharY();
        org.junit.jupiter.api.Assertions.assertTrue(ys.size() >= 2,
                "应记录到多个单字，实际=" + ys.keySet());

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int y : ys.values()) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        org.junit.jupiter.api.Assertions.assertTrue(max > min,
                "预览内应出现逐字上浮（各字 y 不同），实际全部为 " + ys);
    }

    /** 只记录单字 y 的绘制器，用于观测上浮是否发生。 */
    private static final class YRecordingPainter implements FloatingLyricRenderer.Painter {
        private final java.util.Map<String, Integer> oneCharY = new java.util.LinkedHashMap<>();

        java.util.Map<String, Integer> oneCharY() {
            return oneCharY;
        }

        @Override
        public int screenWidth() {
            return 480;
        }

        @Override
        public int screenHeight() {
            return 270;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text != null && text.length() == 1) {
                oneCharY.putIfAbsent(text, y);
            }
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }
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
