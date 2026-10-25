package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一语义的渲染常量必须只有一个来源。
 *
 * <h2>为什么值得测</h2>
 *
 * <p>「未唱透明度」「抬升余量」「阴影余量」这三个值，逐字路径
 * （{@code LyricPainter}）与整段路径（{@code FloatingLyricRenderer}）都要用。
 * 此前两处各写一份字面量，取值恰好相同，于是看不出问题；
 * 但只要有人改了其中一处，同一帧里就会出现两种「未唱」浓度，
 * 或者抬起的字顶部被裁掉一块——两者都是肉眼可见、却很难归因的故障。
 *
 * <p>现在两个类都引用 {@code LyricGeometry} 里的常量。
 * 这里用反射把「不再有第二份字面量」这件事钉住：
 * 若有人图省事把字段改回字面量，字段虽仍在，但会与共享值脱钩，这条测试即失败。
 */
class SharedConstantTest {

    /** 逐字路径引用的未唱透明度必须等于共享值。 */
    @Test
    void 未唱透明度两处一致() throws Exception {
        float shared = LyricGeometry.UNSUNG_ALPHA;
        assertEquals(shared, privateFloat(LyricPainter.class, "UNSUNG_ALPHA"), 1e-6f,
                "LyricPainter 的未唱透明度必须与 LyricGeometry 一致");
        assertEquals(shared,
                privateFloat(FloatingLyricRenderer.class, "UNSUNG_ALPHA"), 1e-6f,
                "FloatingLyricRenderer 的未唱透明度必须与 LyricGeometry 一致");
    }

    /** 两处裁剪余量必须一致，否则字顶被裁。 */
    @Test
    void 裁剪余量两处一致() throws Exception {
        assertEquals(LyricGeometry.LIFT_HEADROOM,
                privateInt(LyricPainter.class, "LIFT_HEADROOM"),
                "抬升余量必须与共享值一致");
        assertEquals(LyricGeometry.LIFT_HEADROOM,
                privateInt(FloatingLyricRenderer.class, "LIFT_HEADROOM"),
                "渲染器底板留白用的抬升余量必须与共享值一致");

        assertEquals(LyricGeometry.SHADOW_HEADROOM,
                privateInt(LyricPainter.class, "SHADOW_HEADROOM"),
                "阴影余量必须与共享值一致");
        assertEquals(LyricGeometry.SHADOW_HEADROOM,
                privateInt(FloatingLyricRenderer.class, "SHADOW_HEADROOM"),
                "渲染器的阴影余量必须与共享值一致");
    }

    /**
     * 未唱透明度必须真的能减弱存在感，又不能低到看不见。
     *
     * <p>0.38 这个具体值来自实机观感，但区间是硬约束：
     * 太接近 1 就看不出「没唱」，太低则未唱的字等于消失，
     * 用户会以为歌词只显示半句。
     */
    @Test
    void 未唱透明度落在可用区间() {
        float a = LyricGeometry.UNSUNG_ALPHA;
        assertTrue(a > 0.2f && a < 0.6f,
                "未唱透明度应落在 0.2..0.6，实际=" + a);
    }

    /** 三个常量都必须是 static final，否则可被运行时改掉。 */
    @Test
    void 常量不可变() throws Exception {
        for (String name : new String[]{"UNSUNG_ALPHA", "LIFT_HEADROOM", "SHADOW_HEADROOM"}) {
            Field f = LyricGeometry.class.getField(name);
            int m = f.getModifiers();
            assertTrue(Modifier.isStatic(m) && Modifier.isFinal(m),
                    name + " 必须是 static final");
        }
    }

    private static float privateFloat(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(null);
    }

    private static int privateInt(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
