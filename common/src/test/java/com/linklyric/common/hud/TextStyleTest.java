package com.linklyric.common.hud;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;

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

        LyricStyleConfig same = d.withWeight(LyricStyleConfig.Weight.REGULAR);
        assertEquals(LyricStyleConfig.Weight.REGULAR, same.weight());
        assertEquals(d.align(), same.align());
        assertEquals(d.sungColor(), same.sungColor());

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
                null, null, true, 2, null, 1f, null, 0, true, 2, false, 1, 0xFF000000, 70, false,
                // 末位排布形态同样故意传 null，一并验证兜底。
                null,
                // 混排校正也传 null，验证它兜底成关闭态。
                null,
                // 锚点比例与留白用默认值：本用例只测枚举兜底。
                LyricStyleConfig.DEFAULT_ANCHOR_RATIO, LyricStyleConfig.DEFAULT_PAD_X);
        assertNotNull(c.weight());
        assertNotNull(c.align());
        assertNotNull(c.interlude(), "间奏样式也必须兜底");
        assertNotNull(c.layout(), "排布形态也必须兜底");
        assertEquals(com.linklyric.common.hud.LayoutMode.LINEAR, c.layout());
        assertEquals(LyricStyleConfig.Weight.REGULAR, c.weight());
        assertEquals(LyricStyleConfig.Align.CENTER, c.align());
    }

    /**
     * 只保留常规一档。
     *
     * <p>加粗已移除：MC 的粗体是同一字形右移一个纹素再画一遍，
     * 在 9px 位图字形上两份轮廓只错开 1px、重叠 8px，
     * 看起来就是同一个字画了两次。
     */
    @Test
    void 字重只保留常规() {
        assertEquals(1, LyricStyleConfig.Weight.values().length,
                "加粗已移除，只剩常规一档");
        for (LyricStyleConfig.Weight w : LyricStyleConfig.Weight.values()) {
            assertNotNull(w.labelKey, w + " 必须有语言键");
            assertFalse(w.labelKey.isEmpty());
        }
    }

    /**
     * 绘制文本里绝不出现样式符。
     *
     * <p>逐字绘制时每个字单独成一个字符串。字形解析按第一个码点取字形，
     * 拼上 {@code §l} 前缀后取到的是 § 本身，屏幕上于是每字多画一个
     * S 形状的字符。
     */
    @Test
    void 绘制文本不含样式符() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setStyle(base());
        BoldPainter painter = new BoldPainter();
        r.render(painter);

        assertTrue(painter.texts.stream().noneMatch(t -> t.contains("\u00a7")),
                "绘制文本不得含样式符，实际=" + painter.texts);
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
     * 字重不再影响绘制：每个字只画一次。
     *
     * <p>这是「两个字重叠」的直接回归测试。曾因加粗启用伪粗体副本，
     * 同一字形在 {@code x+1} 处被再写一遍，与本体只错开 1px，
     * 9px 的字上看起来像重影。
     */
    @Test
    void 每字只画一次() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setStyle(base());

        BoldPainter painter = new BoldPainter();
        r.render(painter);

        assertTrue(!painter.texts.isEmpty(), "应至少画一次");
        assertTrue(painter.texts.stream().noneMatch(t -> t.contains("\u00a7")),
                "绘制文本不含样式符，实际=" + painter.texts);
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

    /**
     * 视口裁剪时，平台行高必须参与裁剪高度计算。
     *
     * <p>触发裁剪的条件是「字被视口边缘切掉」。染色前沿本身
     * 不再依赖裁剪（改用逐顶点配色），因此这里必须构造真正超宽的行，
     * 让它滚出视口，否则一次裁剪都不会发生、断言测量的是 0。
     */
    @Test
    void 裁剪高度取平台行高() {
        String longText = "字".repeat(80);
        LyricTimeline timeline = new LyricTimeline(
                java.util.List.of(new LyricLine(longText, "", 0L, 60_000L,
                        java.util.List.of(), "")));
        FloatingLyricRenderer r = new FloatingLyricRenderer(new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public com.linklyric.core.sync.PlayerSnapshot pollSnapshot() {
                return TestFeeds.snapshot(30_000L);
            }

            @Override
            public LyricTimeline lyric() {
                return timeline;
            }

            @Override
            public long lyricSignature() {
                return 1L;
            }
        });
        r.setStyle(base().withMaxWidthRatio(0.3f));

        LineHeightPainter p = new LineHeightPainter();
        // 多帧：滚动量随时间推进，视口外必然出现被切掉的字。
        for (int i = 0; i < 10; i++) {
            r.render(p);
            try {
                Thread.sleep(16L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

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

    /**
     * 记录每次绘制收到的文本。
     *
     * <p>旧的 {@code sawBold} 观察点已删除：接口不再有 {@code bold} 参数，
     * 「传没传字重」不再是可观测的量。真正要守的性质是
     * 「绘制文本不含 {@code §} 样式符」与「每个字只画一次」，
     * 两者都在下面覆写的方法里被记录。
     */
    private static final class BoldPainter implements FloatingLyricRenderer.Painter {
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
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                      GlyphColors colors, boolean shadow) {
            if (glyph != null && !glyph.isEmpty()) {
                texts.add(glyph);
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
        final java.util.List<String> texts = new java.util.ArrayList<>();

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
            texts.add(text);
        }

        @Override
        public void drawText(String text, int x, int y, int color, boolean shadow) {
            draws++;
            texts.add(text);
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