package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
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

    /**
     * 字重百分比必须原样存进样式、原样读出来。
     *
     * <p>这一项曾经由 {@code LyricOptions.Option} 做归一化往返，
     * 那层中间表示已删除（两端界面共用同一份代码，滑条直接用原版区间）。
     * 但「界面刻度 0..100 与样式字段一一对应」这条契约仍然要守：
     * 一旦有人在写入路径上加缩放，滑条拖到底就不再等于全量加粗，
     * 而画面上只是"加粗得不够"，不会报错。
     */
    @Test
    void 字重百分比直通样式() {
        LyricStyleConfig style = LyricStyleConfig.DEFAULT.withWeightPercent(60);
        assertEquals(60, style.weightPercent(), "写进去多少就该读出多少");
        assertEquals(0, LyricStyleConfig.DEFAULT.withWeightPercent(0).weightPercent(), "0 为不加粗");
        assertEquals(100, LyricStyleConfig.DEFAULT.withWeightPercent(100).weightPercent(), "100 为满刻度");
    }
}
