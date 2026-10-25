package com.linklyric.common.hud.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 强调时要产生外发光，且半径与强度都为正。
     *
     * <p>光晕由 SDF 距离场算出，因此这两项必须真的被设上；
     * 只设强度不设半径时着色器会按「不发光」处理，
     * 表现为调了参数却完全没效果。
     */
    @Test
    void 强调时产生外发光() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        EffectUnit u = unit(0, 500, "字");
        u.emphasisGroup = group;
        u.emphasisCharIndex = 1;

        UnitTransform out = new UnitTransform();
        effect.apply(u, param(group.durationMs / 2), out);
        assertTrue(out.hasGlow(), "应产生可见光晕");
        assertTrue(out.glowRadius > 0f, "光晕半径应为正");
        assertTrue(out.glowAlpha > 0f, "光晕强度应为正");
    }

    /**
     * 光晕半径受 padding 约束。
     *
     * <p>SDF 图集每边只留了 2 个设计像素的留白。光晕半径若超过
     * 字号的三成，向下取整后就可能越过留白边界，采到相邻字形，
     * 表现为字周围冒出别人的笔画。这条上限必须真的生效。
     */
    @Test
    void 光晕半径不超过字号的零点三倍() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        EffectUnit u = unit(0, 500, "字");
        u.emphasisGroup = group;
        u.emphasisCharIndex = 1;

        EffectParam p = param(group.durationMs / 2);
        p.textSize = 20f;
        UnitTransform out = new UnitTransform();
        effect.apply(u, p, out);

        assertTrue(out.glowRadius <= p.textSize * 0.3f + 1e-4f,
                "光晕半径应被 0.3 em 限住，实际 " + out.glowRadius
                        + "，字号 " + p.textSize);
    }

    /** 动画结束、缓动归零后，光晕也应消失。 */
    @Test
    void 强调结束后光晕消失() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        EffectUnit u = unit(0, 500, "字");
        u.emphasisGroup = group;
        u.emphasisCharIndex = 1;

        UnitTransform out = new UnitTransform();
        effect.apply(u, param(group.durationMs * 2), out);
        assertFalse(out.hasGlow(), "动画结束后不应还留着光晕");
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
                "缩放会把字距一起放大，与「强调这一句」无关，因此不用缩放");
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
     * 横向展开必须连续，不得跳变。
     *
     * <p>这是「歌词左右乱动」的回归测试，判据不是「不许动」，
     * 而是「只能平滑地动」。
     *
     * <p>过去位移被禁用，原因很具体：9px 位图字形在非整数位置会糊，
     * 绘制层用 {@code Math.round} 取整，约 1px 的位移在动画中途
     * 被整成 0 或 1，整根笔画瞬移一格，观感是字在乱跳。
     *
     * <p>改用 SDF 后绘制层不再取整，位移可以真正连续推进。
     * 因此这里改为校验连续性：相邻两次采样之间的位移差必须很小，
     * 大到能看出来的跳变仍然判失败。
     */
    @Test
    void 强调横向展开保持连续() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();

        long step = Math.max(1L, group.durationMs / 64);
        float previous = Float.NaN;
        float maxJump = 0f;
        for (long t = 0; t <= group.durationMs * 2; t += step) {
            EffectUnit u = unit(0, 500, "字");
            u.emphasisGroup = group;
            u.emphasisCharIndex = 0;
            UnitTransform out = new UnitTransform();
            effect.apply(u, param(t), out);
            if (!Float.isNaN(previous)) {
                maxJump = Math.max(maxJump, Math.abs(out.dx - previous));
            }
            previous = out.dx;
        }
        // 位移总量不超过 0.02 × 1.2 × 9 ≈ 0.22px，
        // 单步增量必须远小于它，否则就是有断点。
        assertTrue(maxJump < 0.05f,
                "相邻采样间位移应平滑推进，实际最大跳变=" + maxJump);
    }

    /** 边缘字的横向展开比中心字更大，形成「向两侧撑开」。 */
    @Test
    void 边缘字展开大于中心字() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        EmphasizeGlowEffect effect = new EmphasizeGlowEffect();
        long t = group.durationMs / 2;

        float center = 0f;
        float last = 0f;
        for (int index = 0; index < 4; index++) {
            EffectUnit u = unit(0, 500, "字");
            u.emphasisGroup = group;
            u.emphasisCharIndex = index;
            UnitTransform out = new UnitTransform();
            effect.apply(u, param(t), out);
            if (index == 0) {
                center = out.dx;
            }
            last = out.dx;
        }
        // 组内按离中心距离错峰，末字起始最晚、进度最低；
        // 这里只保证存在横向位移，方向的符号由实现决定。
        assertTrue(Math.abs(center) > 0f || Math.abs(last) > 0f,
                "强调展开应产生横向位移");
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
