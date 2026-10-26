package com.linklyric.common.hud;

import com.linklyric.common.hud.effect.EffectParam;
import com.linklyric.common.hud.effect.EffectUnit;
import com.linklyric.common.hud.effect.LyricEffectEngine;
import com.linklyric.common.hud.effect.LyricUnitEffect;
import com.linklyric.common.hud.effect.UnitTransform;
import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.common.style.LyricStyleConfig.Weight;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UnitTransform} 的呈现字段必须真的影响绘制。
 *
 * <h2>为什么值得单独钉住</h2>
 *
 * <p>{@code alpha} 与 {@code scale} 曾经是<b>死字段</b>：没有任何特效写入，
 * 也没有任何绘制代码读取。字段在、注释在、编译不报错、测试全绿，
 * 只是调了等于没调。这类缺陷不违反任何已有契约，只能靠一条专门盯着
 * 「参数是否落到绘制调用上」的测试来发现。
 *
 * <p>所以判据不是「字段等于多少」，而是<b>替身绘制层收到了什么</b>：
 * 颜色里的 alpha、姿态栈的调用序列、裁剪框的参数。字段可以存在，
 * 但没人读它，那就还是死的。
 */
class UnitTransformRenderTest {

    /** 夹具那一行的字数；每个字一次绘制，计数断言都按它算。 */
    private static final int GLYPHS = 4;

    // ——— 字段本身 ———

    /**
     * reset 必须把全部呈现字段恢复默认，一个不漏。
     *
     * <p>用反射遍历而不是逐个手写：{@code UnitTransform} 是复用对象
     * （引擎每帧每个单元 reset 一次以免分配），任何「新增字段忘了重置」
     * 都会带着上一帧、上一个字的值继续被绘制层读取，表现为某个字突然
     * 保持旋转，且只在特定特效开启时复现——极难归因。
     *
     * <p>手写的断言列表在新增字段时不会失败，反射会。这正是要的效果。
     */
    @Test
    void reset把全部字段恢复默认() throws Exception {
        UnitTransform t = new UnitTransform();

        // 先写一个与所有默认值都不同的探测值。
        for (Field f : floatFields()) {
            f.setFloat(t, 0.5f);
        }
        t.reset();

        for (Field f : floatFields()) {
            float expected = ("alpha".equals(f.getName()) || "scale".equals(f.getName()))
                    ? 1f : 0f;
            assertEquals(expected, f.getFloat(t), 1e-6f,
                    f.getName() + " 没有被 reset 恢复默认，会跨帧残留");
        }
    }

    /**
     * 字段清单必须恰好是这八个。
     *
     * <p>上一条对新增字段有效，但对「删字段或改名」无效：那种改动会让
     * 上一条自动缩小覆盖面而无人察觉。这里把清单钉死。
     */
    @Test
    void 呈现字段清单固定() {
        List<String> names = new ArrayList<>();
        for (Field f : floatFields()) {
            names.add(f.getName());
        }
        names.sort(String::compareTo);
        assertEquals(List.of("alpha", "bloom", "dx", "dy", "glowAlpha",
                        "glowRadius", "rotate", "scale"),
                names,
                "呈现字段清单变了；新增字段必须同时进 reset 与绘制消费点");
    }

    /** 新建对象的默认值：位移与角度为 0，缩放与透明度为 1。 */
    @Test
    void 新建对象即恒等() {
        UnitTransform t = new UnitTransform();
        assertEquals(0f, t.dx);
        assertEquals(0f, t.dy);
        assertEquals(0f, t.rotate);
        assertEquals(0f, t.bloom);
        assertEquals(0f, t.glowRadius);
        assertEquals(0f, t.glowAlpha);
        assertEquals(1f, t.alpha);
        assertEquals(1f, t.scale);
    }

    /** 复用的 {@code IDENTITY} 也必须带着恒等值，否则没特效时会画歪。 */
    @Test
    void 恒等常量确实是恒等() {
        assertEquals(0f, UnitTransform.IDENTITY.dx);
        assertEquals(0f, UnitTransform.IDENTITY.rotate);
        assertEquals(1f, UnitTransform.IDENTITY.alpha);
        assertEquals(1f, UnitTransform.IDENTITY.scale);
    }

    // ——— 绘制层是否真的消费了这些字段 ———

    /**
     * alpha 必须乘进最终绘制的颜色。
     *
     * <p>判据取「有特效」与「无特效」两次绘制的 alpha 之比，而不是写死数值：
     * 段颜色里的 alpha 已经带上了整行不透明度与未唱浓度，写死数字等于把
     * 另外两层也算进这条测试的期望里，那两层一动这条就误报。
     */
    @Test
    void alpha乘进字形颜色() {
        int base = render(s -> {
        }).firstAlpha();
        int faded = render(s -> s.alpha = 0.5f).firstAlpha();

        assertTrue(base > 0, "基准不应完全透明，实际=" + base);
        assertEquals(Math.round(base * 0.5f), faded,
                "alpha=0.5 应把颜色 alpha 减半：基准=" + base + " 实际=" + faded);
    }

    /**
     * 单元透明度与整行不透明度必须<b>相乘</b>，不能互相覆盖。
     *
     * <p>若实现是「用 transform.alpha 直接赋进颜色」，调了单元透明度之后
     * 用户设的整行不透明度就失效——两个旋钮互相抢同一个通道。
     */
    @Test
    void alpha与整行不透明度相乘() {
        int full = render(s -> s.alpha = 0.5f, 1f).firstAlpha();
        int half = render(s -> s.alpha = 0.5f, 0.5f).firstAlpha();

        assertEquals(Math.round(full * 0.5f), half,
                "两层透明度必须相乘：整行不透明时=" + full + "，半透明时=" + half);
    }

    /** 默认值下颜色一个通道都不该被改写。 */
    @Test
    void 默认值不改写颜色() {
        Spy spy = render(s -> {
        });
        List<Integer> alphas = spy.alphas();
        assertTrue(alphas.contains(255),
                "已唱段应保持满 alpha 255，实际=" + alphas);
        assertTrue(alphas.contains(Math.round(255f * LyricGeometry.UNSUNG_ALPHA)),
                "未唱段应保持 255×未唱浓度，两者都不该被改写，实际=" + alphas);
    }

    /**
     * 恒等变换不得压栈。
     *
     * <p>一句 30 字、每帧一次，无变换时白做 60 次矩阵栈操作，而平台层
     * 每次压栈都要复制一份 4×4 矩阵。这条把「不该做的事」也变成判据：
     * 只测「该做的做了」的话，无条件的 push 也能全绿。
     */
    @Test
    void 恒等变换不压栈() {
        Spy spy = render(s -> {
        });
        assertEquals(0, spy.pushes,
                "scale=1、rotate=0 时不应触碰姿态栈，实际压栈 " + spy.pushes + " 次");
        assertEquals(GLYPHS, spy.draws, "仍须每个字各绘制一次");
    }

    /**
     * scale 必须真的缩放，且绕字形中心。
     *
     * <p>中心对不对决定「字距是否保持不变」：绕屏幕原点缩放会让整行沿
     * 对角线甩出去，绕左边缘缩放则字距被放大。因此同时校验压栈次数
     * 与前后两次平移互为逆运算且以字心为锚。
     */
    @Test
    void scale绕字形中心缩放() {
        Spy spy = render(s -> s.scale = 1.5f);

        assertEquals(GLYPHS, spy.pushes, "每个字应各压一次栈");
        assertEquals(spy.pushes, spy.pops, "压栈必须配平");
        assertEquals(1.5f, spy.scaleX, 1e-6f, "缩放倍数应原样压入姿态栈");
        assertEquals(1.5f, spy.scaleY, 1e-6f);
        assertFalse(spy.rotateCalled, "只有缩放时不应旋转");
        assertCenterAnchored(spy);
    }

    /** rotate 必须真的旋转，并同样绕字形中心、不取反。 */
    @Test
    void rotate绕字形中心旋转() {
        Spy spy = render(s -> s.rotate = 30f);

        assertEquals(GLYPHS, spy.pushes, "每个字应各压一次栈");
        assertEquals(spy.pushes, spy.pops, "压栈必须配平");
        assertEquals(30f, spy.degrees, 1e-6f,
                "角度应原样传入，不取反（正角顺时针由 Pose2D 的契约保证）");
        assertFalse(spy.scaleCalled, "只有旋转时不应缩放");
        assertCenterAnchored(spy);
    }

    /** 缩放与旋转可叠加，两步进同一个姿态栈。 */
    @Test
    void 缩放与旋转叠加() {
        Spy spy = render(s -> {
            s.scale = 2f;
            s.rotate = -15f;
        });

        assertEquals(GLYPHS, spy.pushes);
        assertEquals(spy.pushes, spy.pops);
        assertEquals(2f, spy.scaleX, 1e-6f);
        assertEquals(-15f, spy.degrees, 1e-6f);
        assertCenterAnchored(spy);
    }

    /** 低于阈值的缩放与角度不得触发姿态变换。 */
    @Test
    void 低于阈值不压栈() {
        Spy spy = render(s -> {
            s.scale = 1.0005f;
            s.rotate = 0.005f;
        });
        assertEquals(0, spy.pushes,
                "阈值内的扰动不应压栈，否则默认路径无法保证逐字节等价");
    }

    /**
     * 旋转后裁剪边界必须按外接矩形放宽。
     *
     * <p>这是最容易漏的一步：字形几何变了、裁剪框没变，现象是
     * 「转起来的字被斜切成直边」。判据取裁剪框宽度——转 45 度时
     * 外接矩形必然比字本身宽，裁剪框也应跟着变宽。
     */
    @Test
    void 旋转后裁剪框按外接矩形放宽() {
        // 视口比这一行窄，右侧的字必然越界而进裁剪分支。
        int flat = render(s -> {
        }, 1f, 40).maxScissorWidth();
        int turned = render(s -> s.rotate = 45f, 1f, 40).maxScissorWidth();

        assertTrue(flat > 0, "视口过窄时应有字被裁");
        // 横向裁剪框**被夹在视口里**，所以它永远达不到外接矩形的完整宽度：
        // 触发裁剪的前提就是这个字有一部分在视口外，那部分本来就画不到。
        // 因此这里只能验「变宽」这个方向，不能验「宽到等于一个字」——
        // 后者要求墨迹左缘与视口右缘的距离超过字宽，而 fixtures 里
        // 末字恰好跨在视口边界上，是个必然不成立的期望。
        assertTrue(turned > flat,
                "旋转后裁剪框应比未旋转更宽：未旋转=" + flat + " 旋转=" + turned
                        + "（裁剪框没放宽就会把字切成直边）");
        // 45 度时外接矩形的两个角各向外多伸约 0.207 倍字宽，
        // 落进视口的那部分至少应多出两像素。
        assertTrue(turned - flat >= 2,
                "45 度旋转至少应让可见裁剪范围多出两个像素：未旋转=" + flat
                        + " 旋转=" + turned);
    }

    /** 缩放同理：放大后的墨迹需要按比例更宽的裁剪框。 */
    @Test
    void 放大后裁剪框按比例放宽() {
        int flat = render(s -> {
        }, 1f, 40).maxScissorWidth();
        int big = render(s -> s.scale = 2f, 1f, 40).maxScissorWidth();

        assertTrue(flat < Spy.CHAR_WIDTH * 2, "未缩放时裁剪框只是视口内那一小段");
        assertTrue(big >= Spy.CHAR_WIDTH * 2,
                "放大 2 倍后裁剪框至少应容得下两倍字宽：未缩放=" + flat
                        + " 放大=" + big);
    }

    /**
     * 旋转后裁剪框的纵向也要放宽。
     *
     * <p>只放宽横向不够：转 45 度会把字向上顶出原来那圈抬升留白，
     * 而纵向裁剪框的高度是固定的「行高 + 上下余量」。
     */
    @Test
    void 旋转后裁剪框纵向放宽() {
        Spy flat = render(s -> {
        }, 1f, 40);
        Spy turned = render(s -> s.rotate = 45f, 1f, 40);

        assertTrue(turned.firstScissorTop < flat.firstScissorTop
                        || turned.maxScissorHeight() > flat.maxScissorHeight(),
                "旋转后裁剪框应向上或向下扩展：未旋转 top=" + flat.firstScissorTop
                        + " 高=" + flat.maxScissorHeight()
                        + "；旋转后 top=" + turned.firstScissorTop
                        + " 高=" + turned.maxScissorHeight());
    }

    // ——— 夹具 ———

    private static List<Field> floatFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : UnitTransform.class.getDeclaredFields()) {
            if (f.getType() != float.class || !Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /**
     * 绕中心的变换必须是「translate(c) → [rotate] → [scale] → translate(-c)」。
     *
     * <p>逐字都做一遍，所以只校验第一个字的完整一组，再要求平移成对出现。
     * 不能按「固定四步」取：只缩放时没有 rotate 调用，只旋转时没有 scale。
     */
    private static void assertCenterAnchored(Spy spy) {
        assertTrue(!spy.ops.isEmpty(), "应有姿态变换");
        List<Integer> translates = new ArrayList<>();
        for (int i = 0; i < spy.ops.size(); i++) {
            if (spy.ops.get(i).startsWith("translate(")) {
                translates.add(i);
            }
        }
        assertEquals(0, translates.size() % 2,
                "每次变换都应有一去一回两次平移，实际=" + spy.ops);
        assertEquals(0, translates.get(0),
                "第一步应先平移到中心，实际第一条是=" + spy.ops.get(0));

        float[] toCenter = numbers(spy.ops.get(translates.get(0)));
        float[] back = numbers(spy.ops.get(translates.get(1)));
        assertEquals(-toCenter[0], back[0], 1e-4f,
                "末尾应平移回原处，实际=" + back[0]);
        assertEquals(-toCenter[1], back[1], 1e-4f);

        // 锚点横坐标必须是字心：charX + 半字宽。
        assertEquals(spy.firstCharX + Spy.CHAR_WIDTH / 2f, toCenter[0], 1e-4f,
                "横向锚点应是字形中心，实际=" + toCenter[0]
                        + "，字左边=" + spy.firstCharX);

        // 锚点纵坐标同理：textY - lift + 字高/2。本夹具里 lift 全为 0。
        assertEquals(100f + LyricGeometry.LINE_HEIGHT / 2f, toCenter[1], 1e-4f,
                "纵向锚点应是文字盒中心（盒顶=textY=100，盒高=lineHeight），实际="
                        + toCenter[1]);
    }

    private static float[] numbers(String op) {
        String inner = op.substring(op.indexOf('(') + 1, op.indexOf(')'));
        String[] parts = inner.split(",");
        return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1])};
    }

    private static Spy render(Tweak tweak) {
        return render(tweak, 1f);
    }

    private static Spy render(Tweak tweak, float opacity) {
        return render(tweak, opacity, Integer.MAX_VALUE);
    }

    /**
     * 走完整的绘制链路画一行，返回记录到的替身。
     *
     * <p>刻意从 {@link LyricPainter#karaoke} 进去而不是直接改 {@code glyph}：
     * 后者是私有的，绕开就得反射，而反射恰恰会掩盖「调用点没把字段传下去」
     * 这类真正要防的问题。这里特效给值、绘制层收值，中间任何一环漏掉
     * 都会在断言上体现。
     */
    private static Spy render(Tweak tweak, float opacity, int maxWidth) {
        Spy spy = new Spy(maxWidth);
        // 用一个只写值、不读任何输入的特效，把 tweak 直接注入变换对象。
        // 走真实引擎而不是自己构造 UnitTransform：引擎负责 reset，
        // 这样测到的就是「引擎每帧给什么、绘制层就收到什么」。
        LyricEffectEngine engine = new LyricEffectEngine(List.of(new LyricUnitEffect() {
            @Override
            public String name() {
                return "test_tweak";
            }

            @Override
            public void apply(EffectUnit unit, EffectParam param, UnitTransform out) {
                tweak.apply(out);
            }
        }));
        LyricPainter painter = new LyricPainter(spy, Weight.REGULAR, false, engine,
                0f, 0, true);

        String text = "一二三四";
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)), i * 1000L,
                    (i + 1) * 1000L));
        }
        LyricLine line = new LyricLine(text, "", 0L, text.length() * 1000L,
                List.copyOf(words), "");

        // 抬升全为 0：本测试只关心呈现场，不掺入抬升曲线的既有行为。
        float[] lifts = new float[text.length()];
        painter.karaoke(text, 10, 100, 18f, 0, spy.screenWidth(), lifts,
                opacity, 0xFFFFFFFF, 0xFF808080, line, 2000L);
        return spy;
    }

    /** 对变换对象做一处修改。 */
    private interface Tweak {
        void apply(UnitTransform transform);
    }

    /**
     * 记录绘制结果的替身画布。
     *
     * <p>同时记三样东西：颜色（验 alpha）、姿态栈调用序列（验中心与阈值）、
     * 裁剪框参数（验外接矩形放宽）。三者合起来才是「字段有没有真的生效」。
     */
    private static final class Spy implements FloatingLyricRenderer.Painter {

        static final int CHAR_WIDTH = 9;

        final List<Integer> colors = new ArrayList<>();
        final List<String> ops = new ArrayList<>();
        final List<Integer> scissorWidths = new ArrayList<>();
        final List<Integer> scissorHeights = new ArrayList<>();
        final int maxWidth;
        int pushes;
        int pops;
        int draws;
        int firstCharX = -1;
        int firstScissorTop = -1;
        float scaleX = Float.NaN;
        float scaleY = Float.NaN;
        float degrees = Float.NaN;
        boolean scaleCalled;
        boolean rotateCalled;

        Spy(int maxWidth) {
            this.maxWidth = maxWidth;
        }

        /** 第一个绘制调用的颜色，即第一个已唱字的颜色。 */
        int firstAlpha() {
            return colors.isEmpty() ? -1 : (colors.get(0) >>> 24) & 0xFF;
        }

        /**
         * 全部绘制调用的 alpha 通道。
         *
         * <p>取整行的集合而不是逐字写死：一行里已唱段与未唱段的 alpha
         * 本来就不同（后者还乘了未唱浓度），而这条测试要验的正是
         * 「两个值都没被单元变换改写」——集合恰好能同时覆盖两者。
         */
        List<Integer> alphas() {
            List<Integer> out = new ArrayList<>(colors.size());
            for (int color : colors) {
                out.add((color >>> 24) & 0xFF);
            }
            return out;
        }

        int maxScissorWidth() {
            return max(scissorWidths);
        }

        int maxScissorHeight() {
            return max(scissorHeights);
        }

        private static int max(List<Integer> values) {
            int m = Integer.MIN_VALUE;
            for (int v : values) {
                m = Math.max(m, v);
            }
            return m == Integer.MIN_VALUE ? 0 : m;
        }

        private final Pose2D pose = new Pose2D() {
            @Override
            public void pushPose() {
                pushes++;
            }

            @Override
            public void popPose() {
                pops++;
            }

            @Override
            public void translate(float x, float y) {
                ops.add("translate(" + x + "," + y + ")");
            }

            @Override
            public void scale(float x, float y) {
                scaleX = x;
                scaleY = y;
                scaleCalled = true;
                ops.add("scale(" + x + "," + y + ")");
            }

            @Override
            public void rotate(float deg) {
                degrees = deg;
                rotateCalled = true;
                ops.add("rotate(" + deg + ")");
            }
        };

        @Override
        public Pose2D pose() {
            return pose;
        }

        @Override
        public int screenWidth() {
            return Math.min(480, maxWidth);
        }

        @Override
        public int screenHeight() {
            return 270;
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
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            scissorWidths.add(width);
            scissorHeights.add(height);
            if (firstScissorTop < 0) {
                firstScissorTop = y;
            }
        }

        @Override
        public void disableScissor() {
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            draws++;
            colors.add(color);
        }

        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                   GlyphSpan[] spans, int originX, boolean shadow) {
            draws++;
            note(glyph, x);
            colors.add(spans.length == 0 ? 0 : spans[0].startColor());
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                      GlyphColors colors, boolean shadow) {
            draws++;
            note(glyph, x);
            this.colors.add(colors.left());
        }

        @Override
        public void drawGlyphSpans(String glyph, float x, int y, float lift,
                                   GlyphSpan[] spans, int originX, boolean shadow,
                                   float glowRadius, float glowAlpha,
                                   float strokeWidth, int strokeArgb) {
            drawGlyphSpans(glyph, x, y, lift, spans, originX, shadow);
        }

        @Override
        public void drawGlyphGradient(String glyph, float x, int y, float lift,
                                      GlyphColors colors, boolean shadow,
                                      float glowRadius, float glowAlpha,
                                      float strokeWidth, int strokeArgb) {
            drawGlyphGradient(glyph, x, y, lift, colors, shadow);
        }

        /** 记下第一个单字绘制的横坐标，作为「字心应落在哪儿」的基准。 */
        private void note(String glyph, float x) {
            if (firstCharX < 0 && glyph.length() == 1) {
                firstCharX = Math.round(x);
            }
        }
    }
}
