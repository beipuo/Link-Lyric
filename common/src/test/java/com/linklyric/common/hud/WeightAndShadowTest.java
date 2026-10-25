package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.config.LyricOptions;
import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字重与软阴影的配置链路。
 *
 * <h2>为什么这两项容易接错</h2>
 *
 * <p>它们的值不参与排版，只喂给着色器 uniform。接错了不会崩、
 * 不会报错，只是「调滑条没反应」——这类问题只能靠测配置往返来钉住，
 * 靠肉眼要在游戏里反复试才看得出来。
 */
class WeightAndShadowTest {

    /** 默认不加粗、阴影柔和。 */
    @Test
    void 默认值() {
        LyricStyleConfig d = LyricStyleConfig.DEFAULT;
        assertEquals(0, d.weightPercent(), "默认不应加粗");
        assertTrue(d.softShadow(), "默认使用柔和阴影");
    }

    /** 字重百分比被夹到合法区间。 */
    @Test
    void 字重越界被夹紧() {
        assertEquals(LyricStyleConfig.MAX_WEIGHT_PERCENT,
                LyricStyleConfig.DEFAULT.withWeightPercent(999).weightPercent());
        assertEquals(LyricStyleConfig.MIN_WEIGHT_PERCENT,
                LyricStyleConfig.DEFAULT.withWeightPercent(-50).weightPercent());
    }

    /** 软阴影开关只改自己。 */
    @Test
    void 软阴影不影响其它字段() {
        LyricStyleConfig before = LyricStyleConfig.DEFAULT;
        LyricStyleConfig after = before.withSoftShadow(false);
        assertFalse(after.softShadow());
        assertEquals(before.weightPercent(), after.weightPercent());
        assertEquals(before.opacity(), after.opacity());
        assertNotEquals(before, after);
    }

    /** 字重写盘再读回来保持。 */
    @Test
    void 字重能存能读(@TempDir Path dir) throws IOException {
        LayoutConfig config = LayoutConfig.load(dir);
        config.saveStyle(config.style().withWeightPercent(40)
                .withSoftShadow(false));

        LayoutConfig reloaded = LayoutConfig.load(dir);
        assertEquals(40, reloaded.style().weightPercent());
        assertFalse(reloaded.style().softShadow(), "软阴影开关也应保持关闭");
    }

    /** 界面滑条读到的值与写入的一致。 */
    @Test
    void 滑条往返一致() {
        LyricStyleConfig style = LyricStyleConfig.DEFAULT.withWeightPercent(60);
        LyricOptions.Option option = LyricOptions.Option.WEIGHT_PERCENT;
        assertEquals(60, option.readRaw(style), "滑条应读出 60");

        // 滑条按 0..1 归一化，60 对应 0.6。
        LyricStyleConfig written = option.write(LyricStyleConfig.DEFAULT, 0.6);
        assertEquals(60, written.weightPercent(), "滑条写回应得到 60");
    }

    /** 滑条两侧端点分别对应不加粗与最大加粗。 */
    @Test
    void 滑条端点() {
        LyricOptions.Option option = LyricOptions.Option.WEIGHT_PERCENT;
        assertEquals(0, option.write(LyricStyleConfig.DEFAULT, 0.0).weightPercent());
        assertEquals(100, option.write(LyricStyleConfig.DEFAULT, 1.0).weightPercent());
    }
}
