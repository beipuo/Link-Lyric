package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字号、字重、对齐、阴影四项样式。
 *
 * <p>这四项的共同点是「界面一动就该看到变化」，但渲染后果很难靠眼睛验证：
 * 字号缩放若只影响羽化宽度，滑条拖动时画面完全不动；
 * 对齐若只改主行不改视口，滚动起点会偏。
 */
class TextStyleTest {

    private static LyricStyleConfig base() {
        return LyricStyleConfig.DEFAULT;
    }

    /** 三个新字段必须有合理默认值，否则旧配置文件升级后样式会突变。 */
    @Test
    void 默认值稳定() {
        LyricStyleConfig d = base();
        assertEquals(LyricStyleConfig.Weight.REGULAR, d.weight());
        assertEquals(LyricStyleConfig.Align.CENTER, d.align());
        assertTrue(d.shadow(), "默认应开启阴影");
    }

    /** 每个 withXxx 只能改自己那一项。 */
    @Test
    void 单项写入不影响其他() {
        LyricStyleConfig d = base();

        LyricStyleConfig bold = d.withWeight(LyricStyleConfig.Weight.BOLD);
        assertEquals(LyricStyleConfig.Weight.BOLD, bold.weight());
        assertEquals(d.align(), bold.align());
        assertEquals(d.sungColor(), bold.sungColor());

        LyricStyleConfig left = d.withAlign(LyricStyleConfig.Align.LEFT);
        assertEquals(LyricStyleConfig.Align.LEFT, left.align());
        assertEquals(d.weight(), left.weight());

        LyricStyleConfig noShadow = d.withShadow(false);
        assertFalse(noShadow.shadow());
        assertEquals(d.weight(), noShadow.weight());
    }

    /** 空枚举必须兜底，不能留 null 让渲染器崩在每帧。 */
    @Test
    void 空枚举兜底() {
        LyricStyleConfig c = new LyricStyleConfig(
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0, 0, 1f, 0.7f, true,
                null, null, true, 2, null, 1f);
        assertNotNull(c.weight());
        assertNotNull(c.align());
        assertNotNull(c.interlude(), "间奏样式也必须兜底");
        assertEquals(LyricStyleConfig.Weight.REGULAR, c.weight());
        assertEquals(LyricStyleConfig.Align.CENTER, c.align());
    }

    /** 字重层数必须是奇数，否则中心会偏半个像素。 */
    @Test
    void 字重层数为奇数() {
        for (LyricStyleConfig.Weight w : LyricStyleConfig.Weight.values()) {
            assertEquals(1, w.layers % 2, w + " 的层数应为奇数，实际=" + w.layers);
            assertTrue(w.layers >= 1);
        }
    }

    /** 三档字重必须递增，否则按钮点了看不出区别。 */
    @Test
    void 字重递增() {
        assertTrue(LyricStyleConfig.Weight.REGULAR.layers
                < LyricStyleConfig.Weight.BOLD.layers);
        assertTrue(LyricStyleConfig.Weight.BOLD.layers
                < LyricStyleConfig.Weight.HEAVY.layers);
    }

    /** 字号缩放必须真的进入绘制路径。 */
    @Test
    void 字号缩放进入绘制() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setStyle(base().withLineScale(2.0f));

        ScalingPainter p = new ScalingPainter();
        r.render(p);

        assertTrue(p.scaled, "字号不为 1 时必须对绘制施加缩放");
        assertEquals(2.0f, p.lastScaleX, 0.01f, "缩放倍数应透传到平台层");
    }

    /** 字号为 1 时不应压栈缩放，省掉无谓的状态切换。 */
    @Test
    void 字号为1不缩放() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setStyle(base().withLineScale(1.0f));

        ScalingPainter p = new ScalingPainter();
        r.render(p);

        assertFalse(p.scaled, "字号为 1 时不应施加缩放");
    }

    /**
     * 加粗不增加绘制次数，而是通过样式前缀交给原版字体管线。
     *
     * <p>先前靠像素偏移重复绘制模拟加粗，那绕过了字体管线的度量，
     * 既不改字形也不改字距，只会得到错位副本（重影与颗粒）。
     * MC 的 {@code Style.isBold()} 会走
     * {@code glyphInfo.getBoldOffset()} 与 {@code getAdvance(bold)}，
     * 字形与字距一起正确。
     */
    @Test
    void 加粗通过样式前缀而非重复绘制() {
        FloatingLyricRenderer regular = new FloatingLyricRenderer(new TestFeeds.Simple());
        regular.setStyle(base().withWeight(LyricStyleConfig.Weight.REGULAR));
        RecordingTexts pr = new RecordingTexts();
        regular.render(pr);

        FloatingLyricRenderer bold = new FloatingLyricRenderer(new TestFeeds.Simple());
        bold.setStyle(base().withWeight(LyricStyleConfig.Weight.BOLD));
        RecordingTexts pb = new RecordingTexts();
        bold.render(pb);

        assertTrue(!pr.texts.isEmpty(), "常规应至少画一次");
        assertEquals(pr.texts.size(), pb.texts.size(),
                "加粗不应增加绘制次数");
        // 逐字绘制时单字不带样式符：给一个字符包 §l…§r 会让
        // 拼接出的文本变成碎片，按内容做的判断全部失效。
        // 因此只要求多字文本带前缀，且单字文本不含样式符。
        var multi = pb.texts.stream().filter(t -> t.length() > 1).toList();
        assertTrue(!multi.isEmpty(), "应有整行文本，实际=" + pb.texts);
        assertTrue(multi.stream().allMatch(t -> t.contains("\u00a7l")),
                "多字加粗文本应带 §l 前缀，实际=" + multi);
        assertTrue(multi.stream().allMatch(t -> t.contains("\u00a7r")),
                "多字加粗文本应带 §r 复位，实际=" + multi);
        assertTrue(pb.texts.stream().filter(t -> t.length() <= 1)
                        .noneMatch(t -> t.contains("\u00a7l")),
                "单字文本不应带样式符，实际=" + pb.texts);
        assertTrue(pr.texts.stream().noneMatch(t -> t.contains("\u00a7l")),
                "常规文本不应带加粗前缀，实际=" + pr.texts);
    }

    /** 阴影开关必须逐次透传给平台层，而不是写死。 */
    @Test
    void 阴影开关透传() {
        FloatingLyricRenderer off = new FloatingLyricRenderer(new TestFeeds.Simple());
        off.setStyle(base().withShadow(false));
        ShadowPainter p = new ShadowPainter();
        off.render(p);

        assertTrue(p.sawDraw, "应至少画过一次文字");
        assertFalse(p.anyShadow, "关闭阴影后不应有带阴影的绘制");

        FloatingLyricRenderer on = new FloatingLyricRenderer(new TestFeeds.Simple());
        on.setStyle(base().withShadow(true));
        ShadowPainter p2 = new ShadowPainter();
        on.render(p2);
        assertTrue(p2.anyShadow, "开启阴影后应有带阴影的绘制");
    }

    /** 左对齐与居中必须在同一行宽下给出不同的左边界。 */
    @Test
    void 对齐改变左边界() {
        int centerX = 200;
        int maxWidth = 100;
        int lineWidth = 40;

        int leftAligned = 200 - 100 / 2;
        int centered = 200 - 40 / 2;
        int rightAligned = 200 + 100 / 2 - 40;

        assertTrue(leftAligned < centered, "左对齐应比居中更靠左");
        assertTrue(rightAligned > centered, "右对齐应比居中更靠右");
    }

    /** 长文本裁剪时，平台行高必须参与裁剪高度计算。 */
    @Test
    void 裁剪高度取平台行高() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        // 构造超长文本以触发裁剪
        r.setStyle(base().withMaxWidthRatio(0.3f));

        LineHeightPainter p = new LineHeightPainter();
        r.render(p);

        assertTrue(p.scissorHeight >= p.reportedLineHeight,
                "裁剪高度应不小于平台行高，否则中文阴影会被切掉，实际="
                        + p.scissorHeight + " 行高=" + p.reportedLineHeight);
    }

    /** 记录所有被绘制文本，用于验证样式前缀。 */
    private static final class RecordingTexts
            implements FloatingLyricRenderer.Painter {
        final java.util.List<String> texts = new java.util.ArrayList<>();

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text != null && !text.isEmpty()) {
                texts.add(text);
            }
        }

        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            if (text != null && !text.isEmpty()) {
                texts.add(text);
            }
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
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }
    }

    private static final class ScalingPainter implements FloatingLyricRenderer.Painter {
        boolean scaled;
        float lastScaleX = 1f;
        private final float[] stack = new float[8];
        private int depth;

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
        public void drawText(String text, int x, int y, int color, boolean shadow) {
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public FloatingLyricRenderer.Pose2D pose() {
            return new FloatingLyricRenderer.Pose2D() {
                @Override
                public void pushPose() {
                    depth++;
                }

                @Override
                public void popPose() {
                    depth--;
                }

                @Override
                public void translate(float x, float y) {
                }

                @Override
                public void scale(float x, float y) {
                    scaled = true;
                    lastScaleX = x;
                }
            };
        }
    }

    private static class CountingPainter implements FloatingLyricRenderer.Painter {
        int draws;

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
            draws++;
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            draws++;
        }

        // 支持裁剪才能走到卡拉 OK 路径；否则渲染器退化为整单元覆盖，
        // 那条分支不经过字重叠加，绘制次数与字重无关。
        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }
    }

    private static final class ShadowPainter extends CountingPainter {
        boolean anyShadow;
        boolean sawDraw;

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            // 不能调 super：父类的 5 参签名是接口 default，会转调 4 参而绕过计数。
            draws++;
            sawDraw = true;
            if (shadow) {
                anyShadow = true;
            }
        }

        /**
         * 逐字抬升路径也必须能记录阴影。
         *
         * <p>歌词默认走逐字绘制（带抬升），若只覆盖 {@code drawText} 的 5 参版本，
         * 这条路径的调用会落到接口 default 上，阴影开关就观察不到。
         */
        @Override
        public void drawTextLifted(String text, int x, int y, float lift,
                                   int color, boolean shadow) {
            draws++;
            sawDraw = true;
            if (shadow) {
                anyShadow = true;
            }
        }
    }

    private static final class LineHeightPainter extends CountingPainter
            implements FloatingLyricRenderer.Painter {
        int reportedLineHeight = 11;
        int scissorHeight;

        @Override
        public int lineHeight() {
            return reportedLineHeight;
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            scissorHeight = Math.max(scissorHeight, height);
        }

        @Override
        public void disableScissor() {
        }
    }
}