package com.linklyric.common.hud.effect;

import java.util.ArrayList;
import java.util.List;

/**
 * 特效引擎：持有一组特效，按注册顺序叠加。
 *
 * <p>内部对象全部复用，逐帧零分配。特效链可整体替换，也可按名字增删。
 */
public final class LyricEffectEngine {

    private List<LyricUnitEffect> effects;

    private final EffectUnit unit = new EffectUnit();
    private final EffectParam param = new EffectParam();
    private final UnitTransform transform = new UnitTransform();

    public LyricEffectEngine(List<LyricUnitEffect> effects) {
        this.effects = new ArrayList<>(effects);
    }

    /**
     * 默认特效链：仅强调辉光。
     *
     * <p>浮动上浮（{@link WaveLiftEffect}）默认不启用：本项目的逐字抬升已经由
     * 「染色进度 → 抬升量」给出，语义同样是「唱到即抬起」。两套串在一起会让
     * 峰值幅度翻倍并出现二次抬升。需要换成脉冲式上浮时，用
     * {@code effects().setEffects(List.of(new WaveLiftEffect(), new EmphasizeGlowEffect()))}
     * 并同时关闭旧抬升。
     */
    public static LyricEffectEngine defaults() {
        return new LyricEffectEngine(List.of(
                new EmphasizeGlowEffect()));
    }

    public void setEffects(List<LyricUnitEffect> newEffects) {
        this.effects = new ArrayList<>(newEffects);
    }

    public void add(LyricUnitEffect effect) {
        List<LyricUnitEffect> next = new ArrayList<>(effects);
        next.add(effect);
        this.effects = next;
    }

    public void remove(String name) {
        List<LyricUnitEffect> next = new ArrayList<>();
        for (LyricUnitEffect e : effects) {
            if (!e.name().equals(name)) {
                next.add(e);
            }
        }
        this.effects = next;
    }

    public boolean has(String name) {
        for (LyricUnitEffect e : effects) {
            if (e.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 更新本帧全局参数。 */
    public void beginFrame(float lineWidth, float highlightWidth, float textSize,
                           long positionMs, float strength) {
        param.lineWidth = lineWidth;
        param.highlightWidth = highlightWidth;
        param.textSize = textSize;
        param.positionMs = positionMs;
        param.strength = strength;
    }

    /** 计算单元的最终变换；返回的对象只在本帧有效。 */
    public UnitTransform prepare(int textStart, int textLength, float x0, float x1,
                                 boolean cjk, long beginMs, long endMs,
                                 EmphasisGroup group, int charIndex) {
        unit.textStart = textStart;
        unit.textLength = textLength;
        unit.x0 = x0;
        unit.x1 = x1;
        unit.cjk = cjk;
        unit.beginMs = beginMs;
        unit.endMs = endMs;
        unit.emphasisGroup = group;
        unit.emphasisCharIndex = charIndex;
        transform.reset();
        for (LyricUnitEffect effect : effects) {
            effect.apply(unit, param, transform);
        }
        return transform;
    }
}
