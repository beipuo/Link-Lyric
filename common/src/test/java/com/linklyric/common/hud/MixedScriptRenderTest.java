package com.linklyric.common.hud;

import com.linklyric.common.hud.effect.UnitTransform;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.common.style.MixedScript;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混排校正是否真的落到了绘制调用上。
 *
 * <h2>这个类补的是哪个洞</h2>
 *
 * <p>{@code ScriptRunsTest} 证明了"分类与分段度量正确"，
 * 但那只说明喂进去的数据对。若 {@code LyricPainter} 收了 {@code MixedScript}
 * 之后没把它用出去（漏传、判据写反、只在某一条分支里用），
 * 上面那些测试依然全绿而功能是死的。
 *
 * <p>因此这里从 {@link LyricPainter#karaoke} 进去——与
 * {@code UnitTransformRenderTest} 同一个入口——让真实的绘制链跑一遍，
 * 再从替身画布上核对三件事：
 *
 * <ul>
 *   <li><b>关闭态下姿态栈一次都不压</b>。这是"零视觉变化"最硬的形态：
 *   不是"压了栈但缩放为 1"，而是根本没压。</li>
 *   <li><b>开启态下 CJK 字压栈、拉丁字不压</b>。证明缩放按字符分派，
 *   而不是整行统一。</li>
 *   <li><b>缩放系数确实是传进去的那个值</b>。从姿态栈的
 *   {@code scale(s, s)} 调用里读出来，与 {@code MixedScript.scale()} 对上。</li>
 * </ul>
 *
 * <h2>仍然测不到的</h2>
 *
 * <p>中英混排"看起来齐了没有"。{@link Spy} 记的是调用参数，
 * 不是像素。真正的墨迹包围盒只有平台层的
 * {@code SdfGlyphProvider} 拿得到，{@code common} 里没有这份数据。
 * 这一条必须进游戏验证，测试只能保证"交给平台的数字是对的"。
 */
class MixedScriptRenderTest {

    /** 拉丁 + CJK 混合，一次覆盖两类。 */
    private static final String MIXED_TEXT = "ab中cd文";

    /** 纯拉丁，用于验证拉丁字不被缩放。 */
    private static final String LATIN_TEXT = "abcd";

    /**
     * 关闭态：姿态栈必须一次都不压。
     *
     * <p>这是"默认零视觉变化"的核心断言。若有人把判据从
     * {@code mixedActive} 改成 {@code mixed.enabled()}，
     * 或干脆无条件走合成逻辑，这条立刻失败。
     */
    @Test
    void 关闭态不压任何姿态栈() {
        Spy spy = render(MIXED_TEXT, MixedScript.OFF);
        assertFalse(spy.pushCount() > 0,
                "关闭混排校正时不得压姿态栈，实际压了 " + spy.pushCount() + " 次");
        assertTrue(spy.ops.stream().noneMatch(op -> op.startsWith("scale(")),
                "关闭时不得出现任何缩放调用，实际=" + spy.ops);
    }

    /**
     * 开启但刻度停在中性位：同样不得压栈。
     *
     * <p>这条与上一条的区别在于 {@code enabled} 是 true。
     * 它证明"中性刻度等同关闭"不是靠参数恰好为 1 蒙过去的，
     * 而是判据本身把它挡在了外面。
     */
    @Test
    void 中性刻度等同关闭() {
        Spy spy = render(MIXED_TEXT, new MixedScript(true, 100, 0));
        assertEquals(0, spy.pushCount(),
                "开启但刻度为 100%/0 时，绘制路径必须与关闭完全一致");
        assertTrue(spy.ops.stream().noneMatch(op -> op.startsWith("scale(")),
                "中性刻度不得产生缩放调用");
    }

    /**
     * 开启且缩放偏离 1：CJK 字必须压栈并缩放，拉丁字不得缩放。
     *
     * <p>{@code "ab中cd文"} 里有两个 CJK 字、四个拉丁字，
     * 因此压栈次数应为 2。这个数字是硬的：多一个说明拉丁也被拖下水，
     * 少一个说明某个 CJK 被漏掉。
     */
    @Test
    void 只为CJK字压栈() {
        Spy spy = render(MIXED_TEXT, new MixedScript(true, 96, 0));
        assertEquals(2, spy.pushCount(),
                "两个 CJK 字应各压一次栈，实际 " + spy.pushCount()
                        + " 次；ops=" + spy.ops);
    }

    /** 纯拉丁行即便开启校正，也不得有任何缩放。 */
    @Test
    void 纯拉丁行不受影响() {
        Spy spy = render(LATIN_TEXT, new MixedScript(true, 96, 2));
        assertEquals(0, spy.pushCount(),
                "纯拉丁行没有需要校正的字，不得压栈");
    }

    /**
     * 压栈时传下去的缩放系数必须等于 {@code MixedScript.scale()}。
     *
     * <p>从 {@code scale(s, s)} 的实参里读回数值来核对。
     * 这比"有没有调用 scale"更严：若把系数写死成 0.9、
     * 或误用 {@code cjkScalePercent} 当系数（96 倍），这条会失败。
     */
    @Test
    void 缩放系数与配置一致() {
        Spy spy = render(MIXED_TEXT, new MixedScript(true, 96, 0));
        List<Float> scales = spy.scaleArgs();
        assertFalse(scales.isEmpty(), "应有缩放调用");
        for (float s : scales) {
            assertEquals(0.96f, s, 1e-6f,
                    "缩放系数必须等于 96/100，实际=" + s);
        }
    }

    /** 换一个刻度，系数必须跟着变——证明它是读配置而不是写死。 */
    @Test
    void 换刻度则系数跟着变() {
        Spy spy = render(MIXED_TEXT, new MixedScript(true, 88, 0));
        List<Float> scales = spy.scaleArgs();
        assertFalse(scales.isEmpty());
        for (float s : scales) {
            assertEquals(0.88f, s, 1e-6f, "缩放系数必须跟随配置，实际=" + s);
        }
    }

    /**
     * 基线校正必须真的进入绘制调用的 lift 参数。
     *
     * <p>绘制语义是 {@code y - lift}，因此正的上抬量应当让
     * 传给平台的 lift **变大**。这里抬升数组全为 0，
     * 于是 CJK 字的 lift 应当恰好等于校正量。
     */
    @Test
    void 基线校正进入lift参数() {
        Spy plain = render(MIXED_TEXT, new MixedScript(true, 100, 0));
        Spy lifted = render(MIXED_TEXT, new MixedScript(true, 100, 2));

        // 取每个 CJK 字（下标 2 与 5）对应的 lift
        float plainCjk = plain.liftOf("中");
        float liftedCjk = lifted.liftOf("中");
        assertEquals(0f, plainCjk, 1e-6f, "未校准时 lift 应为 0");
        assertEquals(2f, liftedCjk, 1e-6f,
                "校正量为 2 时，CJK 字的 lift 应为 2（正数上抬）");

        // 拉丁字必须一动不动
        assertEquals(plain.liftOf("a"), lifted.liftOf("a"), 1e-6f,
                "拉丁字的 lift 不得被混排校正影响");
    }

    /** 负的校正量必须让 CJK 字的 lift 变负（下沉）。 */
    @Test
    void 负校正量让CJK下沉() {
        Spy spy = render(MIXED_TEXT, new MixedScript(true, 100, -2));
        assertEquals(-2f, spy.liftOf("中"), 1e-6f,
                "校正量为 -2 时 lift 应为 -2");
    }

    /**
     * 已有的逐字抬升必须与混排校正**叠加**，而不是互相覆盖。
     *
     * <p>{@code lifts} 来自上浮曲线，是"这一句唱到哪个字"的动画；
     * 混排校正是"中文比英文高一点"的静态修正。两者若互相覆盖，
     * 表现是开启校正后动画消失、或动画期间校正失效——两种都只在
     * 开启后才出现，很容易被当成"这个开关有 bug"。
     */
    @Test
    void 逐字抬升与混排校正叠加() {
        Spy spy = renderWithLifts(MIXED_TEXT, new MixedScript(true, 100, 2),
                new float[]{1f, 1f, 3f, 1f, 1f, 4f});
        // 下标 2 的 CJK 字：曲线给 3，校正再给 2
        assertEquals(5f, spy.liftOf("中"), 1e-6f,
                "CJK 字的 lift 应为曲线值 3 加校正量 2");
        // 下标 5 的 CJK 字：曲线给 4，校正再给 2
        assertEquals(6f, spy.liftOf("文"), 1e-6f);
        // 拉丁字只拿曲线值
        assertEquals(1f, spy.liftOf("a"), 1e-6f,
                "拉丁字的 lift 只应来自曲线");
    }

    /**
     * 修正 CJK 时不得污染特效共享的变换对象。
     *
     * <p>{@link UnitTransform#IDENTITY} 是全局共享常量，
     * 而特效引擎每帧复用同一个实例。若把缩放直接乘进它，
     * 同一次绘制里后画的拉丁字会拿到已被改写的对象——
     * 表现为"开着校正时英文也被缩小了"，且只在混排行上出现。
     *
     * <p>这条用"拉丁字的 lift 与缩放都不变"来间接验证：
     * 若共享对象被改写，拉丁字会继承上一次的缩放。
     */
    @Test
    void 不污染共享变换对象() {
        float before = UnitTransform.IDENTITY.scale;
        render(MIXED_TEXT, new MixedScript(true, 96, 2));
        assertEquals(before, UnitTransform.IDENTITY.scale, 1e-6f,
                "全局 IDENTITY 的缩放不得被混排校正改写");
        assertEquals(0f, UnitTransform.IDENTITY.dy, 1e-6f,
                "全局 IDENTITY 的位移不得被改写");
    }

    /**
     * 样式默认值必须是关闭，且渲染器读的就是它。
     *
     * <p>这条把 {@code LyricStyleConfig.DEFAULT} 与绘制行为连起来：
     * 若某天有人把默认值改成"开启"，配置界面上的新用户
     * 会在不知情的情况下看到所有中文歌词位移。
     */
    @Test
    void 样式默认为关闭且渲染不压栈() {
        LyricStyleConfig d = LyricStyleConfig.DEFAULT;
        assertFalse(d.mixed().enabled(), "样式默认必须关闭混排校正");
        assertFalse(d.mixed().active(), "样式默认不得 active");
        Spy spy = render(MIXED_TEXT, d.mixed());
        assertEquals(0, spy.pushCount());
    }

    /**
     * 校正不得改变步进宽度，因此不得影响 sungWidth / 折行坐标系。
     *
     * <p>这条钉住的是本实现最重要的一个取舍：缩放只作用于**墨迹**，
     * 走姿态栈；步进（{@code charWidth} / {@code textWidth}）一律不动。
     *
     * <p>为什么必须这样：{@code sungWidth} 是行内像素坐标，由
     * {@code targetWidthAt} 逐字累加 {@code textWidth} 得到；
     * {@code LyricWrap} 的 {@code startPx} 与
     * {@code localSung(globalSung) = clamp(globalSung - startPx, 0, width)}
     * 建立在同一套度量上；{@code LyricProgress.charStarts} 也是。
     * 若步进也缩 4%，第 20 个字处累计偏差约 7 像素，
     * 染色前沿会跑到字外、折行断点会落在错误位置。
     *
     * <p>因此开启校正后，同一个字的绘制横坐标必须与关闭时**完全相同**。
     * 这条一旦失败，说明有人把缩放挪进了度量层。
     */
    @Test
    void 校正不改变步进宽度() {
        Spy off = render(MIXED_TEXT, MixedScript.OFF);
        Spy on = render(MIXED_TEXT, new MixedScript(true, 96, 0));

        List<Float> offXs = off.glyphXs();
        List<Float> onXs = on.glyphXs();
        assertEquals(offXs.size(), onXs.size(),
                "两次绘制的字形数必须相同");
        for (int i = 0; i < offXs.size(); i++) {
            assertEquals(offXs.get(i), onXs.get(i), 1e-6f,
                    "第 " + i + " 个字的绘制横坐标不得因校正而改变"
                            + "（否则 sungWidth 与折行坐标系全部失准）");
        }
    }

    // ———————————————— 测试脚手架 ————————————————

    private static Spy render(String text, MixedScript mixed) {
        float[] lifts = new float[text.length()];
        return renderWithLifts(text, mixed, lifts);
    }

    private static Spy renderWithLifts(String text, MixedScript mixed, float[] lifts) {
        Spy spy = new Spy();
        LyricPainter painter = new LyricPainter(spy, LyricStyleConfig.Weight.REGULAR,
                false, null, 0f, 0, true, mixed);

        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)), i * 1000L,
                    (i + 1) * 1000L));
        }
        LyricLine line = new LyricLine(text, "", 0L, text.length() * 1000L,
                List.copyOf(words), "");

        painter.karaoke(text, 10, 100, text.length() * 9f, 0, spy.screenWidth(),
                lifts, 1f, 0xFFFFFFFF, 0xFF808080, line, 2000L);
        return spy;
    }

    /**
     * 记录绘制调用的替身画布。
     *
     * <p>只记三类信息：姿态栈操作序列、每个字的 lift、以及缩放实参。
     * 不记颜色——颜色链路由 {@code UnitTransformRenderTest} 负责，
     * 混排校正不碰颜色，多记一份只会让断言更难读。
     */
    private static final class Spy implements FloatingLyricRenderer.Painter {

        /** 每个码元 9 像素，与真实 CJK 步进一致。 */
        private static final int CHAR_WIDTH = 9;

        final List<String> ops = new ArrayList<>();

        /** 字形 -> 传给平台的 lift。同一个字可能出现多次，取最后一次。 */
        private final List<String[]> glyphLifts = new ArrayList<>();

        /** 每个字形绘制时的横坐标，按调用顺序。 */
        private final List<Float> xs = new ArrayList<>();

        private final Pose2D pose = new Pose2D() {
            @Override
            public void pushPose() {
                ops.add("push");
            }

            @Override
            public void popPose() {
                ops.add("pop");
            }

            @Override
            public void translate(float dx, float dy) {
                ops.add("translate(" + dx + "," + dy + ")");
            }

            @Override
            public void scale(float sx, float sy) {
                ops.add("scale(" + sx + "," + sy + ")");
            }

            @Override
            public void rotate(float degrees) {
                ops.add("rotate(" + degrees + ")");
            }
        };

        @Override
        public Pose2D pose() {
            return pose;
        }

        @Override
        public int screenWidth() {
            return 320;
        }

        @Override
        public int screenHeight() {
            return 180;
        }

        @Override
        public int lineHeight() {
            return LyricGeometry.LINE_HEIGHT;
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public int charWidth(char c) {
            return CHAR_WIDTH;
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            // 背景底板与混排校正无关，忽略。
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            ops.add("scissor(" + x + "," + y + "," + width + "," + height + ")");
        }

        @Override
        public void disableScissor() {
            ops.add("unscissor");
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            notes(text, 0f, x);
        }

        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                   GlyphSpan[] spans, int originX, boolean shadow) {
            notes(glyph, lift, x);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                      GlyphColors colors, boolean shadow) {
            notes(glyph, lift, x);
        }

        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                   GlyphSpan[] spans, int originX, boolean shadow,
                                   float glowRadius, float glowAlpha,
                                   float strokeWidth, int strokeArgb) {
            notes(glyph, lift, x);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                      GlyphColors colors, boolean shadow,
                                      float glowRadius, float glowAlpha,
                                      float strokeWidth, int strokeArgb) {
            notes(glyph, lift, x);
        }

        private void notes(String glyph, float lift, float x) {
            glyphLifts.add(new String[]{glyph, Float.toString(lift)});
            xs.add(x);
        }

        /** 全部字形绘制时的横坐标，按调用顺序。 */
        List<Float> glyphXs() {
            return xs;
        }

        int pushCount() {
            return (int) ops.stream().filter("push"::equals).count();
        }

        /** 从 {@code scale(s,s)} 的实参里读回缩放系数。 */
        List<Float> scaleArgs() {
            List<Float> out = new ArrayList<>();
            for (String op : ops) {
                if (op.startsWith("scale(")) {
                    String inner = op.substring(6, op.length() - 1);
                    out.add(Float.parseFloat(inner.split(",")[0]));
                }
            }
            return out;
        }

        /** 取某个字最后一次绘制时用的 lift。 */
        float liftOf(String glyph) {
            float found = Float.NaN;
            for (String[] pair : glyphLifts) {
                if (pair[0].equals(glyph)) {
                    found = Float.parseFloat(pair[1]);
                }
            }
            assertFalse(Float.isNaN(found), "没有记录到字形「" + glyph + "」的绘制调用");
            return found;
        }
    }
}
