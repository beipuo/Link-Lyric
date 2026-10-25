package com.linklyric.common.hud.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 缓动与特效：曲线形状、浮位保持、脉冲提前、辉光强度。 */
class LyricEffectTest {

    private static EffectUnit unit(long begin, long end, String text) {
        EffectUnit u = new EffectUnit();
        u.beginMs = begin;
        u.endMs = end;
        u.textLength = text.length();
        return u;
    }

    /** 中文按单字成词：一句拆成逐字单元，才是真实数据形态。 */
    private static java.util.List<com.linklyric.core.lyric.LyricWord> chars(
            String text, long startMs, long stepMs) {
        java.util.List<com.linklyric.core.lyric.LyricWord> out = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            out.add(new com.linklyric.core.lyric.LyricWord(
                    text.substring(i, i + 1), startMs + stepMs * i, startMs + stepMs * (i + 1)));
        }
        return out;
    }

    private static EffectParam param(long positionMs) {
        EffectParam p = new EffectParam();
        p.textSize = 10f;
        p.lineWidth = 100f;
        p.positionMs = positionMs;
        return p;
    }

    @Test
    void 缓动是脉冲形_起于零峰在中点终于零() {
        assertEquals(0f, EmpEasing.at(0f), 1e-3f);
        assertEquals(1f, EmpEasing.at(0.5f), 1e-3f);
        assertEquals(0f, EmpEasing.at(1f), 1e-3f);
    }

    @Test
    void 缓动前半程上升后半程回落() {
        float previous = -1f;
        for (int i = 0; i <= 50; i++) {
            float v = EmpEasing.at(i / 100f);
            assertTrue(v >= previous - 1e-4f, "前半程应单调上升：" + i);
            previous = v;
        }
        previous = 2f;
        for (int i = 50; i <= 100; i++) {
            float v = EmpEasing.at(i / 100f);
            assertTrue(v <= previous + 1e-4f, "后半程应单调回落：" + i);
            previous = v;
        }
    }

    @Test
    void 缓动越界自动夹紧() {
        assertEquals(EmpEasing.at(0f), EmpEasing.at(-5f), 1e-6f);
        assertEquals(EmpEasing.at(1f), EmpEasing.at(5f), 1e-6f);
    }

    @Test
    void 唱到后保持浮位不回落() {
        WaveLiftEffect effect = new WaveLiftEffect();
        UnitTransform out = new UnitTransform();
        // 脉冲早已结束，只剩浮位。
        effect.apply(unit(0, 1000, "字"), param(9000), out);
        assertTrue(out.dy > 0f, "唱过的字应停在浮位");
        assertEquals(10f * 0.05f, out.dy, 1e-4f);
    }

    @Test
    void 未唱到不上浮() {
        WaveLiftEffect effect = new WaveLiftEffect();
        UnitTransform out = new UnitTransform();
        effect.apply(unit(5000, 6000, "字"), param(4000), out);
        assertEquals(0f, out.dy, 1e-4f);
    }

    @Test
    void 脉冲在染色到达之前就已起跳() {
        WaveLiftEffect effect = new WaveLiftEffect();
        // 单元 1000..2000，提前 400ms，因此在 700ms 时已经在上升段。
        UnitTransform before = new UnitTransform();
        effect.apply(unit(1000, 2000, "字"), param(700), before);
        assertTrue(before.dy > 0f, "到点前应已在上升");
    }

    @Test
    void 强度为零时特效静止() {
        WaveLiftEffect effect = new WaveLiftEffect();
        UnitTransform out = new UnitTransform();
        EffectParam p = param(1500);
        p.strength = 0f;
        effect.apply(unit(1000, 2000, "字"), p, out);
        assertEquals(0f, out.dy, 1e-5f);
    }

    @Test
    void 强调时上浮并提亮() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        EffectUnit u = unit(0, 500, "字");
        u.emphasisGroup = group;
        u.emphasisCharIndex = 1;

        // 缓动是脉冲形：峰值在中点，终点归零。取组内中点才有幅度。
        UnitTransform out = new UnitTransform();
        effect.apply(u, param(group.durationMs / 2), out);
        assertTrue(out.dy > 0f, "应上浮");
        assertTrue(out.bloom > 0f, "应提亮");
    }

    @Test
    void 强调不做缩放以免位图字体发糊() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        EffectUnit u = unit(0, 500, "字");
        u.emphasisGroup = group;
        u.emphasisCharIndex = 1;

        UnitTransform out = new UnitTransform();
        effect.apply(u, param(group.durationMs / 2), out);
        assertEquals(1f, out.scale, 1e-6f,
                "位图字体只能整像素平移，缩放会让笔画发虚");
    }

    @Test
    void 无强调组时不留任何变换() {
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        UnitTransform out = new UnitTransform();
        effect.apply(unit(0, 2000, "字"), param(500), out);
        assertEquals(0f, out.dx, 1e-6f);
        assertEquals(1f, out.scale, 1e-6f);
        assertEquals(0f, out.bloom, 1e-6f);
    }

    /**
     * 强调动画不产生任何水平位移。
     *
     * <p>这是「歌词左右乱动」的回归测试。
     *
     * <p>上游的左右展开是为矢量字形设计的：位移由平滑值驱动、
     * 渲染器按浮点定位，1px 级差异在几十像素的字号上只表现为疏密。
     * 本项目是 9px 位图字形，坐标必须取整，位移幅度也只有约 1px，
     * 于是取整结果在动画中途整根笔画瞬移一格，观感就是字在乱跳；
     * 而这点幅度又不足以让人看出「展开」。
     */
    @Test
    void 强调动画不产生水平位移() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();

        // 扫过整个动画过程：任何时刻都不允许出现水平位移。
        for (long t = 0; t <= group.durationMs * 2; t += Math.max(1L, group.durationMs / 16)) {
            for (int index = 0; index < 4; index++) {
                EffectUnit u = unit(0, 500, "字");
                u.emphasisGroup = group;
                u.emphasisCharIndex = index;
                UnitTransform out = new UnitTransform();
                effect.apply(u, param(t), out);
                assertEquals(0f, out.dx, 1e-6f,
                        "强调动画不得产生水平位移，t=" + t + " index=" + index);
            }
        }
    }

    /** 上浮仍然保留：强调的「顶起来」观感靠它。 */
    @Test
    void 强调动画仍有上浮() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        EffectUnit u = unit(0, 500, "字");
        u.emphasisGroup = group;
        u.emphasisCharIndex = 0;
        UnitTransform out = new UnitTransform();
        effect.apply(u, param(group.durationMs / 2), out);
        // dy 是「抬起量」：加到 lifts 上再传给绘制层，正值表示上浮。
        assertTrue(out.dy > 0f, "应有上浮（dy 为正表示抬起），实际=" + out.dy);
    }

    @Test
    void 引擎按注册顺序叠加且可增删() {
        LyricEffectEngine engine = LyricEffectEngine.defaults();
        assertTrue(engine.has("emphasize_glow"), "默认启用强调辉光");
        assertTrue(!engine.has("wave_lift"), "浮动上浮默认关闭，避免与既有抬升重复");

        engine.add(new WaveLiftEffect());
        assertTrue(engine.has("wave_lift"));

        engine.remove("wave_lift");
        assertTrue(!engine.has("wave_lift"), "移除后不应再存在");
    }

    @Test
    void 默认链不叠加额外上浮() {
        LyricEffectEngine engine = LyricEffectEngine.defaults();
        engine.beginFrame(100f, 100f, 10f, 9000, 1f);
        // 无强调组时默认链不应产生任何位移：抬升由既有 lifts 负责。
        UnitTransform transform = engine.prepare(0, 1, 0f, 10f, true, 0, 1000, null, 0);
        assertEquals(0f, transform.dy, 1e-6f);
    }

    @Test
    void 启用浮动特效后唱过的字带浮位() {
        LyricEffectEngine engine = new LyricEffectEngine(
                java.util.List.of(new WaveLiftEffect()));
        engine.beginFrame(100f, 100f, 10f, 9000, 1f);
        UnitTransform transform = engine.prepare(0, 1, 0f, 10f, true, 0, 1000, null, 0);
        assertTrue(transform.dy > 0f, "浮动上浮应给出浮位");
    }
}
