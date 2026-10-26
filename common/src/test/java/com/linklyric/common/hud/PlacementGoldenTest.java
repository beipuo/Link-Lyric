package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LinearStackPlacement} 的逐字段快照。
 *
 * <h2>为什么要有这份快照</h2>
 *
 * <p>{@code PlacedLine} 的坐标从 int 改成 float 时，「默认策略观感零变化」
 * 是唯一的判据，但它是**没有任何界面能直接告诉你**的那类判据：
 * 差半个像素不会报错、不会崩、也不会被任何算术断言抓到，
 * 只会在把新旧截图叠起来时露出来。
 *
 * <p>因此这里在改动之前先把每一字段的实际取值抓下来钉成常量。
 * 快照里的数字全部来自改造前的整数实现；改造后必须逐个字符一致。
 * 断言写成 {@code Float.toString((float) value)}，于是同一个用例
 * 在 int 与 float 两种字段类型下都能编译——否则这份快照在改造那一刻
 * 就会因为「编译不过」而失去意义。
 *
 * <h2>为什么还要断言「全为整数值」</h2>
 *
 * <p>快照只能证明**这一组样本**没变。渲染器把 {@code x}/{@code y}
 * 交给只收 int 的绘制原语时会过一次 {@code Math.round}，
 * 那条路径不在快照覆盖范围内。补一条「所有坐标恰好是整数值」的断言，
 * 就把「取整是恒等变换」这件事变成可验证的前提：
 * 只要它是整数，{@code Math.round} 与直接取整逐位相同，
 * 默认策略的每个像素都由构造保证不变，而不依赖样本覆盖得够不够全。
 *
 * <p>样本刻意覆盖：对齐三档、有无预览区、有无用户偏移、折行段数、
 * 滚动、行切换位移。这几项是放置算式里全部的分支。
 */
class PlacementGoldenTest {

    private static final PlacementStrategy LINEAR = new LinearStackPlacement();

    /** 改造前的实际取值。每一个字符都对应一次真实渲染的像素位置。 */
    private static final String GOLDEN = """
            == 单行居中全屏
            blockTop=415
            blockHeight=9
            blockBottom=430
            centerX=427
            centerY=422
            viewportLeft=367
            viewportRight=487
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=367
            footprint=361,415,132,15
              line 0 x=367.0 y=418.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
            == 三行居中全屏
            blockTop=406
            blockHeight=27
            blockBottom=439
            centerX=427
            centerY=422
            viewportLeft=367
            viewportRight=487
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=367
            footprint=361,406,132,33
              line 0 x=367.0 y=409.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=380.0 y=420.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
              line 2 x=377.0 y=429.0 w=100 scale=0.8 primary=false orient=0.0 text=[副行第2段]
            == 三行左对齐带偏移
            blockTop=387
            blockHeight=27
            blockBottom=420
            centerX=464
            centerY=403
            viewportLeft=157
            viewportRight=277
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=157
            footprint=151,387,132,33
              line 0 x=157.0 y=390.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=157.0 y=401.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
              line 2 x=157.0 y=410.0 w=100 scale=0.8 primary=false orient=0.0 text=[副行第2段]
            == 三行右对齐带偏移
            blockTop=425
            blockHeight=27
            blockBottom=458
            centerX=390
            centerY=441
            viewportLeft=577
            viewportRight=697
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=577
            footprint=571,425,132,33
              line 0 x=577.0 y=428.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=602.0 y=439.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
              line 2 x=597.0 y=448.0 w=100 scale=0.8 primary=false orient=0.0 text=[副行第2段]
            == 折行四行三段
            blockTop=399
            blockHeight=40
            blockBottom=445
            centerX=427
            centerY=422
            viewportLeft=367
            viewportRight=487
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=367
            footprint=361,399,132,46
              line 0 x=367.0 y=402.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=362.0 y=413.0 w=130 scale=1.0 primary=true orient=0.0 text=[主行第1段]
              line 2 x=357.0 y=424.0 w=140 scale=1.0 primary=true orient=0.0 text=[主行第2段]
              line 3 x=375.0 y=435.0 w=105 scale=0.8 primary=false orient=0.0 text=[副行第3段]
            == 滚动长句
            blockTop=410
            blockHeight=18
            blockBottom=434
            centerX=427
            centerY=422
            viewportLeft=120
            viewportRight=734
            viewWidth=614
            maxWidth=614
            padX=6
            padY=3
            scroll=-12
            textX=108
            footprint=114,410,626,24
              line 0 x=108.0 y=413.0 w=760 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=380.0 y=424.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
            == 行切换位移
            blockTop=406
            blockHeight=27
            blockBottom=439
            centerX=427
            centerY=422
            viewportLeft=367
            viewportRight=487
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=367
            footprint=361,406,132,33
              line 0 x=367.0 y=416.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=380.0 y=427.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
              line 2 x=377.0 y=436.0 w=100 scale=0.8 primary=false orient=0.0 text=[副行第2段]
            == 预览区内三行
            blockTop=69
            blockHeight=27
            blockBottom=102
            centerX=420
            centerY=85
            viewportLeft=360
            viewportRight=480
            viewWidth=120
            maxWidth=172
            padX=6
            padY=3
            scroll=0
            textX=360
            footprint=354,69,132,33
              line 0 x=360.0 y=72.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=373.0 y=83.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
              line 2 x=370.0 y=92.0 w=100 scale=0.8 primary=false orient=0.0 text=[副行第2段]
            == 预览区内单行右对齐
            blockTop=78
            blockHeight=9
            blockBottom=93
            centerX=420
            centerY=85
            viewportLeft=386
            viewportRight=506
            viewWidth=120
            maxWidth=172
            padX=6
            padY=3
            scroll=0
            textX=386
            footprint=380,78,132,15
              line 0 x=386.0 y=81.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
            == 大行距五行
            blockTop=376
            blockHeight=86
            blockBottom=468
            centerX=427
            centerY=422
            viewportLeft=367
            viewportRight=487
            viewWidth=120
            maxWidth=614
            padX=6
            padY=3
            scroll=0
            textX=367
            footprint=361,376,132,92
              line 0 x=367.0 y=379.0 w=120 scale=1.0 primary=true orient=0.0 text=[主行第0段]
              line 1 x=380.0 y=400.0 w=95 scale=0.8 primary=false orient=0.0 text=[副行第1段]
              line 2 x=377.0 y=419.0 w=100 scale=0.8 primary=false orient=0.0 text=[副行第2段]
              line 3 x=375.0 y=438.0 w=105 scale=0.8 primary=false orient=0.0 text=[副行第3段]
              line 4 x=372.0 y=458.0 w=110 scale=0.8 primary=false orient=0.0 text=[副行第4段]
            """;

    @Test
    void 默认策略逐字段快照不变() throws Exception {
        StringBuilder actual = new StringBuilder();
        for (Case c : cases()) {
            actual.append("== ").append(c.name).append('\n');
            appendPlacement(actual, LINEAR.place(c.input));
        }
        String text = actual.toString();

        // 同时落盘：断言失败时不必再从 Gradle 的差异输出里手工抄。
        java.nio.file.Path out = java.nio.file.Path.of(
                "build", "reports", "placement-golden.txt");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, text);

        assertEquals(GOLDEN.strip(), text.strip(),
                "默认策略的放置结果必须逐字段不变；实际结果已写入 " + out);
    }

    /**
     * 所有落位坐标必须恰好是整数。
     *
     * <p>这是「取整不改变任何像素」的前提。一旦某次改动让默认策略吐出
     * 100.5 这样的坐标，渲染器那次 {@code Math.round} 就不再是恒等变换，
     * 于是本项目所有既有像素快照同时失效——而它们全都不会因此失败。
     */
    @Test
    void 默认策略的坐标全为整数值() {
        for (Case c : cases()) {
            LyricPlacement placed = LINEAR.place(c.input);
            for (LyricPlacement.PlacedLine line : placed.lines()) {
                assertIntegral(c.name, "x", line.x());
                assertIntegral(c.name, "y", line.y());
            }
        }
    }

    private static void assertIntegral(String caseName, String field, float value) {
        assertEquals((float) Math.round(value), value,
                caseName + " 的 " + field + " 不是整数值：" + value
                        + "；默认策略必须逐像素沿用整数算式");
    }

    private static void appendPlacement(StringBuilder sb, LyricPlacement p) {
        sb.append("blockTop=").append(p.blockTop()).append('\n');
        sb.append("blockHeight=").append(p.blockHeight()).append('\n');
        sb.append("blockBottom=").append(p.blockBottom()).append('\n');
        sb.append("centerX=").append(p.centerX()).append('\n');
        sb.append("centerY=").append(p.centerY()).append('\n');
        sb.append("viewportLeft=").append(p.viewportLeft()).append('\n');
        sb.append("viewportRight=").append(p.viewportRight()).append('\n');
        sb.append("viewWidth=").append(p.viewWidth()).append('\n');
        sb.append("maxWidth=").append(p.maxWidth()).append('\n');
        sb.append("padX=").append(p.padX()).append('\n');
        sb.append("padY=").append(p.padY()).append('\n');
        sb.append("scroll=").append(p.scroll()).append('\n');
        sb.append("textX=").append(p.textX()).append('\n');
        FloatingLyricRenderer.Bounds f = p.footprint();
        sb.append("footprint=").append(f.x()).append(',').append(f.y())
                .append(',').append(f.width()).append(',').append(f.height()).append('\n');
        for (LyricPlacement.PlacedLine line : p.lines()) {
            sb.append("  line ").append(line.index())
                    .append(" x=").append(Float.toString((float) line.x()))
                    .append(" y=").append(Float.toString((float) line.y()))
                    .append(" w=").append(line.width())
                    .append(" scale=").append(Float.toString(line.scale()))
                    .append(" primary=").append(line.isPrimary())
                    .append(" orient=").append(Float.toString(line.orientationDegrees()))
                    .append(" text=").append('[').append(line.text()).append(']')
                    .append('\n');
        }
    }

    private record Case(String name, PlacementInput input) {
    }

    /**
     * 需要覆盖的分支。
     *
     * <p>屏幕固定 854×480（720p 下 GUI 缩放 1 档的实际像素），
     * 这样快照里的坐标与实机分辨率对得上，出现偏差时容易判断严重程度。
     */
    private static List<Case> cases() {
        List<Case> list = new ArrayList<>();
        LyricStyleConfig d = LyricStyleConfig.DEFAULT;

        list.add(new Case("单行居中全屏",
                mk(854, 480, null, 0, 0, d, 1, 1, false, 0)));
        list.add(new Case("三行居中全屏",
                mk(854, 480, null, 0, 0, d, 3, 1, false, 0)));
        list.add(new Case("三行左对齐带偏移",
                mk(854, 480, null, 37, -19, d.withAlign(LyricStyleConfig.Align.LEFT),
                        3, 1, false, 0)));
        list.add(new Case("三行右对齐带偏移",
                mk(854, 480, null, -37, 19, d.withAlign(LyricStyleConfig.Align.RIGHT),
                        3, 1, false, 0)));
        list.add(new Case("折行四行三段",
                mk(854, 480, null, 0, 0, d.withWrap(true), 4, 3, false, 0)));
        list.add(new Case("滚动长句",
                mk(854, 480, null, 0, 0, d, 2, 1, true, 0)));
        list.add(new Case("行切换位移",
                mk(854, 480, null, 0, 0, d, 3, 1, false, 7)));
        list.add(new Case("预览区内三行",
                mk(854, 480, new FloatingLyricRenderer.Bounds(300, 40, 240, 90),
                        0, 0, d, 3, 1, false, 0)));
        list.add(new Case("预览区内单行右对齐",
                mk(854, 480, new FloatingLyricRenderer.Bounds(300, 40, 240, 90),
                        0, 0, d.withAlign(LyricStyleConfig.Align.RIGHT), 1, 1, false, 0)));
        list.add(new Case("大行距五行",
                mk(854, 480, null, 0, 0, d.withLineGapPx(12), 5, 1, false, 0)));
        return list;
    }

    /**
     * 造一份放置输入。
     *
     * <p>宽度刻意互不相同：若各行同宽，「按各自宽度居中」与「按可用宽度居中」
     * 会给出同样的结果，快照就测不出这一层。
     */
    private static PlacementInput mk(int screenW, int screenH,
                                     FloatingLyricRenderer.Bounds preview,
                                     int offsetX, int offsetY,
                                     LyricStyleConfig style,
                                     int lines, int span,
                                     boolean scrolling, int blockShift) {
        List<String> texts = new ArrayList<>();
        int[] widths = new int[lines];
        int[] colors = new int[lines];
        float[] scales = new float[lines];
        int widest = 0;
        for (int i = 0; i < lines; i++) {
            boolean fullSize = i < span;
            texts.add(fullSize ? "主行第" + i + "段" : "副行第" + i + "段");
            widths[i] = fullSize ? 120 + 10 * i : 90 + 5 * i;
            colors[i] = 0xFF00FF00 | i;
            scales[i] = fullSize ? 1f : 0.8f;
            widest = Math.max(widest, widths[i]);
        }
        // 滚动时主行宽度是**未截断**的整行宽度，必须比任何一段都宽。
        int primaryWidth = scrolling ? widest + 640 : widths[0];
        return new PlacementInput(screenW, screenH, preview, offsetX, offsetY, style,
                texts, widths, colors, scales, primaryWidth,
                Math.max(0f, primaryWidth * 0.42f), scrolling, blockShift, span);
    }

    /**
     * 样本本身不得被删空。
     *
     * <p>快照断言只比字符串：「零个样本」与「零个样本」当然相等。
     * 这条把样本数量也变成判据的一部分，否则删掉几个分支覆盖完美的用例
     * 反而能让断言更容易通过。
     */
    @Test
    void 样本覆盖不少于十个分支() {
        assertTrue(cases().size() >= 10, "快照样本不得少于 10 个");
    }
}
