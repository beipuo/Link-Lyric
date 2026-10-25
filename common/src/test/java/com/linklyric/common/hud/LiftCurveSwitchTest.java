package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上浮曲线的切换与持久化。
 *
 * <p>切换若只改界面不落到样式，重开界面又回到默认；
 * 若只写样式不落盘，重启游戏后又回到默认。两条路都要钉住。
 */
class LiftCurveSwitchTest {

    /** 默认是余弦。 */
    @Test
    void 默认使用余弦() {
        assertEquals(LyricStyleConfig.LiftCurve.COSINE,
                LyricStyleConfig.DEFAULT.liftCurve());
    }

    /** 切换后样式确实变了，不是原样返回。 */
    @Test
    void 切换改变样式() {
        LyricStyleConfig style = LyricStyleConfig.DEFAULT.withLiftCurve(
                LyricStyleConfig.LiftCurve.LINEAR);
        assertEquals(LyricStyleConfig.LiftCurve.LINEAR, style.liftCurve());
        assertNotEquals(LyricStyleConfig.DEFAULT, style,
                "切换曲线应产生新的样式对象");
    }

    /** 切换不影响其它字段。 */
    @Test
    void 切换不波及其它字段() {
        LyricStyleConfig before = LyricStyleConfig.DEFAULT;
        LyricStyleConfig after = before.withLiftCurve(LyricStyleConfig.LiftCurve.LINEAR);
        assertEquals(before.sungColor(), after.sungColor());
        assertEquals(before.offsetMs(), after.offsetMs());
        assertEquals(before.opacity(), after.opacity());
        assertEquals(before.lineCount(), after.lineCount());
    }

    /** 写盘再读回来，仍是切换后的值。 */
    @Test
    void 曲线能存能读(@TempDir Path dir) throws IOException {
        LayoutConfig config = LayoutConfig.load(dir);
        config.saveStyle(config.style().withLiftCurve(LyricStyleConfig.LiftCurve.LINEAR));

        LayoutConfig reloaded = LayoutConfig.load(dir);
        assertEquals(LyricStyleConfig.LiftCurve.LINEAR, reloaded.style().liftCurve(),
                "重新读取后应保持线性");
    }

    /** 全新目录没有配置文件，回退到默认，不抛异常。 */
    @Test
    void 缺失配置回退默认(@TempDir Path dir) throws IOException {
        LayoutConfig config = LayoutConfig.load(dir);
        assertEquals(LyricStyleConfig.DEFAULT.liftCurve(), config.style().liftCurve());
    }

    /** 两种曲线都只产出非负抬升，不会把字往下压。 */
    @Test
    void 两种曲线都不向下压字() {
        int[] starts = {0, 10, 20, 30};
        int[] widths = {10, 10, 10, 10};
        float[] cosine = LyricProgress.cosineLifts(starts, widths, 25f, 10f);
        float[] linear = LyricProgress.lifts(4, 10f, new float[]{1f, 1f, 0.5f, 0f});
        for (float v : cosine) {
            assertTrue(v >= 0f, "余弦抬升不应为负：" + v);
        }
        for (float v : linear) {
            assertTrue(v >= 0f, "线性抬升不应为负：" + v);
        }
    }
}
