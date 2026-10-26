package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 朝向角是否真的被绘制链路消费。
 *
 * <h2>为什么这条不能只靠「策略算出了角度」来证明</h2>
 *
 * <p>策略给出 {@code orientationDegrees = 30}，与「屏幕上的字真的转了 30 度」
 * 之间隔着整条绘制链路。中间任何一环把它丢掉——行级判断写错、pose 没压栈、
 * 平台层 rotate 退化成空实现——画面都和横排一模一样，而没有一处会报错。
 * 这正是 {@link Pose2D#rotate} 注释里记下的那种坑：
 * 默认实现看着像实现了，实际把参数丢掉。
 *
 * <p>因此这里用一个记录型绘制器直接读 pose 栈的调用序列：
 * 非零朝向必须出现 rotate，零朝向必须一次都不出现。
 */
class OrientationPaintTest {

    @Test
    void 默认策略不产生任何旋转() {
        RecordingPainter painter = new RecordingPainter();
        renderer(LyricStyleConfig.DEFAULT).render(painter);

        assertTrue(painter.rotations.isEmpty(),
                "横排堆叠不该压任何旋转，实际记录：" + painter.rotations);
        assertTrue(painter.draws > 0, "至少应该画出一行");
    }

    @Test
    void 弧形策略真的向绘制端提交了旋转() {
        RecordingPainter painter = new RecordingPainter();
        renderer(LyricStyleConfig.DEFAULT.withLayout(LayoutMode.ARC)).render(painter);

        assertFalse(painter.rotations.isEmpty(),
                "弧形排布必须把朝向交给绘制端，否则浮点化与接朝向等于没做");
        assertTrue(painter.draws > 0, "旋转之外仍要真的画出字");
    }

    @Test
    void 环形策略真的向绘制端提交了旋转() {
        RecordingPainter painter = new RecordingPainter();
        renderer(LyricStyleConfig.DEFAULT.withLayout(LayoutMode.RING)).render(painter);

        assertFalse(painter.rotations.isEmpty(),
                "环形排布必须把朝向交给绘制端");
    }

    /**
     * 旋转必须围绕该行自身，而不是屏幕原点。
     *
     * <p>判据：行级旋转那一段必须是「两次 translate + 一次 rotate」，
     * 且两次 translate 互为相反数。省掉这个三段式会让字沿一个很大的圆弧
     * 甩出屏幕，而画面只是「不见了」，看不出是旋转写错。
     *
     * <p>按「恰有一次 rotate 且恰有两次 translate」筛出目标段，
     * 而不是遍历全部：逐字绘制内部也会压栈（缩放/旋转单个字形），
     * 那些段有自己的 translate/rotate，混进来比会把判据弄反。
     */
    @Test
    void 旋转围绕行中心且成对平移() {
        RecordingPainter painter = new RecordingPainter();
        renderer(LyricStyleConfig.DEFAULT.withLayout(LayoutMode.ARC)).render(painter);

        int matched = 0;
        for (PoseRecord pose : painter.poses) {
            if (pose.rotationCount != 1 || pose.translations.size() != 2) {
                continue;
            }
            matched++;
            float[] in = pose.translations.get(0);
            float[] out = pose.translations.get(1);
            assertEquals(-in[0], out[0], 1e-3f, "第二次 translate 必须抵消第一次的 x");
            assertEquals(-in[1], out[1], 1e-3f, "第二次 translate 必须抵消第一次的 y");
            // 中心离开屏幕原点才有意义：若 in 恒为 (0,0)，
            // 说明旋转发生在左上角，字会整块甩走。
            assertTrue(Math.abs(in[0]) > 1f || Math.abs(in[1]) > 1f,
                    "旋转中心不该是屏幕原点，实际 " + in[0] + "," + in[1]);
        }
        assertTrue(matched > 0, "至少应有一段行级旋转，实际记录：" + painter.describe());
    }

    @Test
    void 策略由样式自动选择且覆盖优先() {
        FloatingLyricRenderer r = renderer(LyricStyleConfig.DEFAULT);
        assertTrue(r.placementStrategy() instanceof LinearStackPlacement,
                "默认样式必须走横排");

        r.setStyle(LyricStyleConfig.DEFAULT.withLayout(LayoutMode.ARC));
        assertTrue(r.placementStrategy() instanceof ArcPlacement,
                "改成弧形后策略应随之切换");

        r.setStyle(LyricStyleConfig.DEFAULT.withLayout(LayoutMode.RING));
        assertTrue(r.placementStrategy() instanceof RingPlacement);

        ArcPlacement forced = new ArcPlacement();
        r.setPlacementStrategy(forced);
        assertEquals(forced, r.placementStrategy(), "显式覆盖必须优先于样式");

        r.clearPlacementOverride();
        assertTrue(r.placementStrategy() instanceof RingPlacement,
                "清除覆盖后应回到按样式选择");
    }

    private static FloatingLyricRenderer renderer(LyricStyleConfig style) {
        FloatingLyricRenderer r = new FloatingLyricRenderer(new MultiLineFeed());
        r.setStyle(style);
        r.setPreviewArea(null);
        return r;
    }

    /**
     * 三行内容的数据源。
     *
     * <p>不能用 {@link TestFeeds.Simple}：它只有一行，而默认 {@code lineCount=2}
     * 意味着第二行是空槽位、不出画。于是弧形排布下只剩一行，
     * 而单行落在弧的正中、朝向恰好是 0——几何完全正确，
     * 却会让「必须提交旋转」的断言失败。
     *
     * <p>要验「朝向被消费」，样本本身必须有多行处在弧的不同位置。
     */
    private static final class MultiLineFeed implements LyricFeed {

        private static final LyricTimeline THREE_LINES = build();

        private static LyricTimeline build() {
            List<LyricLine> lines = new ArrayList<>();
            String[] texts = {"一二三四", "五六七八", "九十十一"};
            long step = 500L;
            for (int n = 0; n < texts.length; n++) {
                String text = texts[n];
                List<LyricWord> words = new ArrayList<>();
                for (int i = 0; i < text.length(); i++) {
                    words.add(new LyricWord(String.valueOf(text.charAt(i)),
                            step * i, step * (i + 1)));
                }
                long start = step * text.length() * n;
                lines.add(new LyricLine(text, "", start, start + step * text.length(),
                        List.copyOf(words), ""));
            }
            return new LyricTimeline(List.copyOf(lines));
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            return TestFeeds.snapshot(600L);
        }

        @Override
        public LyricTimeline lyric() {
            return THREE_LINES;
        }

        @Override
        public long lyricSignature() {
            return 3L;
        }
    }

    /** 记录 pose 调用序列的绘制器。 */
    private static final class RecordingPainter implements FloatingLyricRenderer.Painter {

        final List<Float> rotations = new ArrayList<>();
        final List<PoseRecord> poses = new ArrayList<>();
        int draws;
        /**
         * 整个渲染过程共用同一个 pose 实例。
         *
         * <p>不能每次 {@code pose()} 新建：栈状态是跨调用累加的，
         * 每次给新实例等于每次从空栈开始——push 与 pop 落在不同实例上，
         * 一条记录都收不到。平台层的实现是一个包装对象持同一份
         * {@code PoseStack}，行为与此一致。
         */
        private final RecordingPose pose = new RecordingPose(this);

        @Override
        public FloatingLyricRenderer.Pose2D pose() {
            return pose;
        }

        @Override
        public int screenWidth() {
            return 854;
        }

        @Override
        public int screenHeight() {
            return 480;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 9;
        }

        @Override
        public int charWidth(char c) {
            return 9;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            draws++;
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

        /** 把记录摊成一行字，供断言失败时直接读出结构。 */
        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("poses=").append(poses.size())
                    .append(" rotations=").append(rotations.size());
            for (PoseRecord p : poses) {
                sb.append(" [t=").append(p.translations.size())
                        .append(" r=").append(p.rotationCount)
                        .append(" s=").append(p.scaleCount).append(']');
            }
            return sb.toString();
        }
    }

    /** 把 push/pop 之间的调用收进一条记录。 */
    private static final class RecordingPose implements FloatingLyricRenderer.Pose2D {

        private final RecordingPainter owner;
        private final List<PoseRecord> stack = new ArrayList<>();

        RecordingPose(RecordingPainter owner) {
            this.owner = owner;
        }

        @Override
        public void pushPose() {
            // 用栈而不是单个字段：行级旋转的 push 会包住逐字绘制，
            // 而逐字绘制自己也会 push（缩放/旋转字形）。单字段会被内层覆盖，
            // 出头时把内层当外层，外层那条记录就永远丢不到 owner 手里。
            stack.add(new PoseRecord());
        }

        @Override
        public void popPose() {
            if (!stack.isEmpty()) {
                owner.poses.add(stack.remove(stack.size() - 1));
            }
        }

        @Override
        public void translate(float x, float y) {
            PoseRecord top = top();
            if (top != null) {
                top.translations.add(new float[]{x, y});
            }
        }

        @Override
        public void scale(float x, float y) {
            PoseRecord top = top();
            if (top != null) {
                top.scaleCount++;
            }
        }

        @Override
        public void rotate(float degrees) {
            owner.rotations.add(degrees);
            PoseRecord top = top();
            if (top != null) {
                top.rotationCount++;
            }
        }

        private PoseRecord top() {
            return stack.isEmpty() ? null : stack.get(stack.size() - 1);
        }
    }

    /** 一次 push/pop 之间的调用记录。 */
    private static final class PoseRecord {
        final List<float[]> translations = new ArrayList<>();
        int rotationCount;
        int scaleCount;
    }
}
