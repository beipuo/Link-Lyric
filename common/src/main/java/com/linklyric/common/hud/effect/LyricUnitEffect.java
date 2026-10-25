package com.linklyric.common.hud.effect;

/**
 * 逐单元特效契约。
 *
 * <p>渲染器把一行切成单元，每个单元依次经过已注册的特效，得到最终呈现变换。
 * 新增特效只需实现本接口并注册，不必改动绘制内核。
 *
 * <p>约定：特效无外部副作用，同参数下输出确定。
 */
public interface LyricUnitEffect {

    /** 特效名，用于增删与调试。 */
    String name();

    /**
     * 施加本特效。
     *
     * @param unit  单元描述，只读
     * @param param 本帧全局参数，只读
     * @param out   待叠加的变换，特效直接写入
     */
    void apply(EffectUnit unit, EffectParam param, UnitTransform out);
}
