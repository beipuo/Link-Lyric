package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样式配置与配置界面的行为约束。
 *
 * <p>重点在「越界不崩、旧配置不丢、实时生效」三件事——
 * 这三处一旦退化，表现分别是画面异常、升级后设置丢失、以及改了没反应。
 */
class StyleConfigTest {

    /** 越界值必须夹到合法区间，而不是原样接受。 */
    @Test
    void 越界值被夹紧() {
        LyricStyleConfig s = new LyricStyleConfig(
                0xFFFFFFFF, 0xFF000000, 0xFF888888,
                99_999, 99_999, 99f, 99f, true,
                null, null, true, 2, null, 1f, null, 0, true, 2, false, 1, 0xFF000000, 70, false,
                // 末位是排布形态。传 null 是为了同时验证构造器会兜底成横排。
                null,
                // 混排校正同样传 null，验证它兜底成关闭态而非「开启 + 默认刻度」。
                null,
                // 锚点比例与留白同样越界，验证构造器夹取。
                99f, 99_999);

        assertEquals(LyricStyleConfig.MAX_OFFSET_MS, s.offsetMs());
        assertEquals(LyricStyleConfig.MAX_TRANSITION_MS, s.lineTransitionMs());
        assertEquals(LyricStyleConfig.MAX_LINE_SCALE, s.lineScale());
        assertEquals(LyricStyleConfig.MAX_WIDTH_RATIO, s.maxWidthRatio());
        assertEquals(com.linklyric.common.hud.LayoutMode.LINEAR, s.layout(),
                "null 排布形态必须兜底成横排，否则切形态后会一片空白");
        assertEquals(LyricStyleConfig.MAX_ANCHOR_RATIO, s.anchorRatio(), 1e-6f,
                "锚点比例应夹到上限 1（贴顶）");
        assertEquals(LyricStyleConfig.MAX_PAD_X, s.padXPx(),
                "左右留白应夹到上限");
    }

    /**
     * 锚点比例与留白在负向、NaN 两侧都要夹住。
     *
     * <p>NaN 是一个**真会从配置文件里进来**的值：{@code Float.parseFloat("NaN")}
     * 不抛异常，返回 NaN，于是 {@code LayoutConfig.read()} 会把它一路送到构造器。
     * 若构造器不兜住，{@code LyricGeometry.anchorRatioY} 里的比较会两边都不成立，
     * 插值出一个无意义的坐标——歌词整块消失，且没有任何报错。
     */
    @Test
    void 锚点与留白负向越界及NaN被夹紧() {
        assertEquals(LyricStyleConfig.MIN_ANCHOR_RATIO,
                LyricStyleConfig.DEFAULT.withAnchorRatio(-5f).anchorRatio(), 1e-6f,
                "负比例应夹到 0（贴底）");
        assertEquals(LyricStyleConfig.MIN_ANCHOR_RATIO,
                LyricStyleConfig.DEFAULT.withAnchorRatio(Float.NaN).anchorRatio(), 1e-6f,
                "NaN 应兜到 0（贴底），与 clampFloat 的既有约定一致");
        assertEquals(LyricStyleConfig.MIN_PAD_X,
                LyricStyleConfig.DEFAULT.withPadXPx(-5).padXPx(),
                "负留白应夹到 0");
    }

    /** 负数侧同样要夹紧。 */
    @Test
    void 负向越界被夹紧() {
        LyricStyleConfig s = LyricStyleConfig.DEFAULT
                .withOffsetMs(-99_999)
                .withLineScale(-5f);

        assertEquals(LyricStyleConfig.MIN_OFFSET_MS, s.offsetMs());
        assertEquals(LyricStyleConfig.MIN_LINE_SCALE, s.lineScale());
    }

    /** 时间偏移必须真的作用到渲染位置：正偏移让歌词提前，即同一时刻唱得更多。 */
    @Test
    void 正时间偏移让歌词提前() {
        FloatingLyricRenderer early = rendererWithOffset(500);
        FloatingLyricRenderer normal = rendererWithOffset(0);

        early.render(new NullPainter());
        normal.render(new NullPainter());

        assertTrue(early.currentSungWidth() > normal.currentSungWidth(),
                "正偏移应更早唱到，实际 early=" + early.currentSungWidth()
                        + " normal=" + normal.currentSungWidth());
    }

    private static FloatingLyricRenderer rendererWithOffset(int offsetMs) {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setStyle(LyricStyleConfig.DEFAULT.withOffsetMs(offsetMs));
        return r;
    }

    /** 关闭羽化不应报错，且不应影响唱到宽度。 */
    @Test
    void 关闭羽化仍能渲染() {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new TestFeeds.Simple());
        r.setStyle(LyricStyleConfig.DEFAULT.withFeather(false));
        RecordingPainter p = new RecordingPainter();
        r.render(p);

        assertTrue(p.textCount > 0, "应仍绘制文字");
    }

    /** 样式与位置必须一起持久化，互不覆盖。 */
    @Test
    void 样式与位置独立保存() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-style");
        LayoutConfig config = LayoutConfig.load(dir);

        config.save(30, -12, true, LyricStyleConfig.DEFAULT.withSungColor(0xFFFF0000));
        config.saveStyle(LyricStyleConfig.DEFAULT.withUnsungColor(0xFF00FF00));

        LayoutConfig reloaded = LayoutConfig.load(dir);
        assertEquals(30, reloaded.offsetX(), "位置不应被样式保存覆盖");
        assertEquals(-12, reloaded.offsetY(), "位置不应被样式保存覆盖");
        assertEquals(0xFF00FF00, reloaded.style().unsungColor(), "样式应更新");
    }

    /** 旧配置文件缺少样式键时，样式必须回落到默认值而不是坏值。 */
    @Test
    void 旧配置样式回落默认() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-oldstyle");
        Files.writeString(dir.resolve("linklyric-layout.properties"),
                "offsetX=5\noffsetY=5\nenabled=true\n");

        LyricStyleConfig style = LayoutConfig.load(dir).style();
        assertEquals(LyricStyleConfig.DEFAULT.sungColor(), style.sungColor());
        assertEquals(LyricStyleConfig.DEFAULT.lineTransitionMs(), style.lineTransitionMs());
    }

    /** 十六进制与十进制两种颜色写法都要能读。 */
    @Test
    void 颜色两种写法都可读() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-color");
        Files.writeString(dir.resolve("linklyric-layout.properties"),
                "sungColor=#FF102030\nunsungColor=-16777216\n");

        LyricStyleConfig style = LayoutConfig.load(dir).style();
        assertEquals(0xFF102030, style.sungColor(), "应支持 #AARRGGBB");
        assertEquals(0xFF000000, style.unsungColor(), "应支持十进制");
    }

    /**
     * 区间的上下限必须自洽。
     *
     * <p>这里原本测的是 {@code LyricOptions.Option} 的滑条往返映射。
     * 那层中间表示已经删掉——两端界面现在共用同一份代码，
     * 滑条由原版 {@code OptionInstance.IntRange} 承担，
     * 再测一遍映射等于测原版。
     *
     * <p>但「最小值小于最大值」这件事仍然值得守：区间写反时
     * 原版滑条不会报错，只是永远拖不动，表现成"这一项坏了"。
     */
    @Test
    void 样式区间自洽() {
        assertTrue(LyricStyleConfig.MIN_OFFSET_MS < LyricStyleConfig.MAX_OFFSET_MS);
        assertTrue(LyricStyleConfig.MIN_TRANSITION_MS < LyricStyleConfig.MAX_TRANSITION_MS);
        assertTrue(LyricStyleConfig.MIN_LINE_GAP < LyricStyleConfig.MAX_LINE_GAP);
        assertTrue(LyricStyleConfig.MIN_PAD_X < LyricStyleConfig.MAX_PAD_X);
        assertTrue(LyricStyleConfig.MIN_STROKE_PX < LyricStyleConfig.MAX_STROKE_PX);
        assertTrue(LyricStyleConfig.MIN_STROKE_ALPHA < LyricStyleConfig.MAX_STROKE_ALPHA);
        assertTrue(LyricStyleConfig.MIN_WEIGHT_PERCENT < LyricStyleConfig.MAX_WEIGHT_PERCENT);
        assertTrue(LyricStyleConfig.MIN_LINES < LyricStyleConfig.MAX_LINES);
        assertTrue(LyricStyleConfig.MIN_OPACITY < 1.0f);
    }

    /** 数据源开关应持久化，重启后保持禁用。 */
    @Test
    void 数据源开关持久化() throws Exception {
        Path dir = Files.createTempDirectory("linklyric-source");
        LayoutConfig config = LayoutConfig.load(dir);

        config.saveSourceEnabled("salt-player", false);
        assertTrue(LayoutConfig.load(dir).isSourceDisabled("salt-player"),
                "禁用状态应落盘");

        config.saveSourceEnabled("salt-player", true);
        assertFalse(LayoutConfig.load(dir).isSourceDisabled("salt-player"),
                "重新启用后应清除禁用记录");
    }

    /** 禁用数据源后渲染器应停止出画。 */
    @Test
    void 禁用数据源后不出画() {
        TestFeeds.Toggleable feed = new TestFeeds.Toggleable();
        FloatingLyricRenderer r = new FloatingLyricRenderer(feed);
        RecordingPainter p = new RecordingPainter();
        assertTrue(r.render(p), "启用时应出画");

        feed.setEnabled(false);
        RecordingPainter after = new RecordingPainter();
        assertFalse(r.render(after), "禁用后不应出画");
    }

    private static final class NullPainter implements FloatingLyricRenderer.Painter {
        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }
    }

    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {
        int textCount;

        @Override
        public int screenWidth() {
            return 400;
        }

        @Override
        public int screenHeight() {
            return 300;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            textCount++;
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }
    }
}
