package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LyricCanvas#drawTextScaled} 的默认实现不得丢弃缩放。
 *
 * <h2>为什么这条必须单独测</h2>
 *
 * <p>默认实现原先是 {@code drawText(text, x, y, color)}，{@code scale}
 * 被原样丢掉。副行缩到 0.8 倍全靠两端平台各自覆写，一旦某个平台漏覆写，
 * 副行会静默变成主行字号——不报错、不告警，只是看起来「两行一样大」。
 *
 * <p>更要命的是它**测不出来**：渲染器侧的测试用的是自己覆写了
 * {@code drawTextScaled} 的替身，永远走不到默认实现。
 *
 * <h2>修正时踩的坑</h2>
 *
 * <p>第一版把 scale 折算成纵向位移（{@code y - (1-scale)*lineHeight}）。
 * 那是错的：副行的纵向位置已由 {@link LineLayout} 算好，
 * 再叠一次位移等于把布局算了两遍，位置随 scale 漂移。
 * 现行为走姿态栈以 {@code (x, y)} 为原点缩放。
 */
class ScaledDefaultTest {

    /** 缩放必须作用在姿态栈上，而不是被忽略。 */
    @Test
    void 默认实现真的缩放() {
        ScaleSpy spy = new ScaleSpy();
        spy.drawTextScaled("副行", 40, 60, 0.5f, 0xFFFFFFFF);

        assertTrue(spy.pushed, "缩放应压栈");
        assertTrue(spy.popped, "缩放必须出栈，否则后续绘制全部被缩小");
        assertEquals(0.5f, spy.scaleX, 1e-6f, "缩放倍数应原样压入姿态栈");
        assertEquals(0.5f, spy.scaleY, 1e-6f);
    }

    /** 缩放必须以 (x, y) 为原点，否则文字会整体偏移。 */
    @Test
    void 以绘制原点为锚() {
        ScaleSpy spy = new ScaleSpy();
        spy.drawTextScaled("副行", 40, 60, 0.8f, 0xFFFFFFFF);

        // 压栈后应先平移到 (x, y)、缩放、再平移回去。
        assertTrue(spy.ops.contains("translate(40.0,60.0)"),
                "应先平移到绘制原点，实际=" + spy.ops);
        assertTrue(spy.ops.contains("translate(-40.0,-60.0)"),
                "缩放后应平移回来，实际=" + spy.ops);
    }

    /** 缩放为 1 时不必压栈，省掉无谓的状态切换。 */
    @Test
    void 缩放为一不压栈() {
        ScaleSpy spy = new ScaleSpy();
        spy.drawTextScaled("副行", 10, 20, 1f, 0xFFFFFFFF);

        assertTrue(!spy.pushed, "scale=1 不应压栈");
        assertEquals(1, spy.draws, "仍须绘制一次");
    }

    /** 记录姿态栈操作与绘制次数的画布。 */
    private static final class ScaleSpy implements LyricCanvas {
        final java.util.List<String> ops = new java.util.ArrayList<>();
        boolean pushed;
        boolean popped;
        float scaleX = 1f;
        float scaleY = 1f;
        int draws;

        private final Pose2D pose = new Pose2D() {
            @Override
            public void pushPose() {
                pushed = true;
            }

            @Override
            public void popPose() {
                popped = true;
            }

            @Override
            public void translate(float x, float y) {
                ops.add("translate(" + x + "," + y + ")");
            }

            @Override
            public void scale(float x, float y) {
                scaleX = x;
                scaleY = y;
                ops.add("scale(" + x + "," + y + ")");
            }
        };

        @Override
        public Pose2D pose() {
            return pose;
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
        public void drawText(String text, int x, int y, int color) {
            draws++;
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }
    }
}
