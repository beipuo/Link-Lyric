package com.linklyric.common.hud.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强调特效的外发光参数。
 *
 * <h2>为什么光晕要拆成半径与强度两个量</h2>
 *
 * <p>光晕的形状（半径）与亮度（强度）若绑成一个量，动画里淡出时会
 * 看到光晕「缩一下」——那是形状在变，不是亮度在降。拆开后
 * 只淡亮度，形状保持，观感是自然散掉。
 *
 * <p>半径上限也不是随手取的：SDF 图集每边只留 2 个设计像素的 padding，
 * 光晕超出这个范围会采到相邻字形，表现为字周围冒出别人的笔画。
 */
class GlowTransformTest {

    /** 默认不发光。 */
    @Test
    void 默认不发光() {
        UnitTransform t = new UnitTransform();
        assertFalse(t.hasGlow(), "新建的变换不应带光晕");
        assertEquals(0f, t.glowRadius);
        assertEquals(0f, t.glowAlpha);
    }

    /** reset 会清掉光晕。 */
    @Test
    void reset清掉光晕() {
        UnitTransform t = new UnitTransform();
        t.glowRadius = 3f;
        t.glowAlpha = 0.8f;
        assertTrue(t.hasGlow());
        t.reset();
        assertFalse(t.hasGlow(), "reset 后不应残留光晕");
    }

    /** 只有强度没有半径，不算发光。 */
    @Test
    void 半径为零不发辉() {
        UnitTransform t = new UnitTransform();
        t.glowAlpha = 1f;
        assertFalse(t.hasGlow(), "半径为 0 时不应视为发光");
    }

    /** 只有半径没有强度，同样不算发光。 */
    @Test
    void 强度为零不发辉() {
        UnitTransform t = new UnitTransform();
        t.glowRadius = 4f;
        assertFalse(t.hasGlow(), "强度为 0 时不应视为发光");
    }

    /** 半径与强度都设上才算发光。 */
    @Test
    void 半径与强度齐备才发光() {
        UnitTransform t = new UnitTransform();
        t.glowRadius = 2f;
        t.glowAlpha = 0.5f;
        assertTrue(t.hasGlow());
    }

    /** 光晕与提亮是两条独立的通路。 */
    @Test
    void 提亮与光晕互不影响() {
        UnitTransform t = new UnitTransform();
        t.bloom = 1f;
        assertFalse(t.hasGlow(), "只有提亮时不应视为发光");
        t.glowRadius = 2f;
        t.glowAlpha = 0.5f;
        assertEquals(1f, t.bloom, "加光晕不应改动提亮量");
    }
}
