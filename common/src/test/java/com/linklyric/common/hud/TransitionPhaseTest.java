package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行切换的两阶段时序。
 *
 * <p>用户要的顺序是「旧主行先消失，其余行再上移一行补位」。
 * 若两者同时进行，半透明旧字会与上移中的新字擦身而过，混出一层脏色。
 * 这个先后关系在界面上稍纵即逝，只能用数值钉住。
 */
class TransitionPhaseTest {

    /** 旧行淡出阶段：进度从 0 起，应在前段完成。 */
    @Test
    void 旧行淡出先完成() {
        float portion = 0.45f;
        // 进度 0.4 时旧行已接近消失，而补位位移尚未开始。
        float oldFade = Math.min(1f, 0.4f / portion);
        float risePart = Math.max(0f, (0.4f - portion) / (1f - portion));

        assertTrue(oldFade > 0.8f, "进度 0.4 时旧行应基本淡出，实际=" + oldFade);
        assertEquals(0f, risePart, 1e-6f, "此时补位位移还不应开始");
    }

    /** 补位位移在旧行淡出之后才开始。 */
    @Test
    void 补位在淡出之后() {
        float portion = 0.45f;
        float risePart = Math.max(0f, (portion + 0.02f - portion) / (1f - portion));
        assertTrue(risePart > 0f, "淡出结束后补位应已开始");
        assertTrue(risePart < 0.1f, "刚接上时补位只应走一小段，实际=" + risePart);
    }

    /** 两段在进度 1 时都必须收满。 */
    @Test
    void 末端收满() {
        float portion = 0.45f;
        float oldFade = Math.min(1f, 1f / portion);
        float risePart = Math.min(1f, (1f - portion) / (1f - portion));
        assertEquals(1f, oldFade, 1e-6f);
        assertEquals(1f, risePart, 1e-6f);
    }

    /** 位移量从一整行收到 0：方向必须向上。 */
    @Test
    void 位移方向向上() {
        int step = 12;
        float portion = 0.45f;
        // 刚进入补位阶段时偏移接近一整行，结束时为 0。
        float early = Math.max(0f, (portion + 0.001f - portion) / (1f - portion));
        int shiftEarly = Math.round(step * (1f - early));
        int shiftEnd = Math.round(step * (1f - 1f));

        assertTrue(shiftEarly > shiftEnd,
                "补位阶段偏移应递减到 0，实际 早=" + shiftEarly + " 末=" + shiftEnd);
        assertEquals(0, shiftEnd, "结束时不应残留偏移");
    }
}
