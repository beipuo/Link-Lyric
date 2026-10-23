package com.linklyric.common.hud;

import com.linklyric.common.bridge.SnapshotParser;
import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实桥接数据检验染色推进的观感指标。
 *
 * <p>关注三件事：是否单调、是否连续（不跳字）、是否覆盖整行。
 */
class ProgressCurveTest {

    @Test
    void 真实歌词的染色推进平滑且单调() throws Exception {
        String json = readResource();
        LyricTimeline timeline = SnapshotParser.parseLyric(json);
        assertTrue(!timeline.isEmpty(), "应解析出歌词");

        LyricLine target = longestLine(timeline);
        assertTrue(target != null, "应有一行可渲染歌词");

        System.out.println("=== 样本行 ===");
        System.out.println("text  = " + target.text());
        System.out.println("span  = " + target.startMs() + " - " + target.endMs());
        System.out.println("words = " + target.words().size() + "  chars = " + target.text().length());

        MeasuringFeed feed = new MeasuringFeed(timeline, target);
        WidthPainter painter = new WidthPainter(320, 240);
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed);

        int lineWidth = painter.textWidth(target.text());
        long span = target.endMs() - target.startMs();

        System.out.println("--- 采样（位置 : 已唱宽度 / 行宽）---");

        float previous = -1f;
        boolean monotonic = true;
        List<Float> samples = new ArrayList<>();

        // 每格推进 20 次渲染，模拟约 20 帧，检验平滑逼近行为
        int targetIndex = timeline.indexAt(target.startMs());
        for (int step = 0; step <= 20; step++) {
            // 采样覆盖到行尾前一毫秒，且不越界触发换行重置
            long position = target.startMs() + (span - 1L) * step / 20L;
            if (timeline.indexAt(position) != targetIndex) {
                break;
            }
            feed.position = position;

            float last = -1f;
            for (int frame = 0; frame < 5; frame++) {
                painter.reset();
                renderer.render(painter);
                last = painter.lastSolid;
                Thread.sleep(4L);
            }
            samples.add(last);

            if (previous >= 0f && last < previous - 0.5f) {
                monotonic = false;
            }
            previous = last;

            System.out.printf("t=%-7d sung=%-7.1f (%3.0f%%)%n",
                    position, last, lineWidth == 0 ? 0 : last * 100.0 / lineWidth);
        }

        // 推进必须单调：回退说明在正常播放中发生了错误重锚
        assertTrue(monotonic, "正常播放中已唱宽度不应回退");

        // 必须单调推进到接近整行：末次采样接近行宽（允许末字自身时长很长）
        float finalWidth = samples.get(samples.size() - 1);
        assertTrue(finalWidth >= lineWidth * 0.85f,
                "行末应大幅推进，实际=" + finalWidth + " 行宽=" + lineWidth
                        + " 采样=" + samples);

        // 单独校验：落在行结束后的间隙里（下一行尚未开始），
        // 该行应判定为唱完并收敛到全亮，形成自然的收尾。
        int nextIndex = targetIndex + 1;
        LyricLine next = timeline.line(nextIndex);
        long gapProbe = (next != null && next.startMs() > target.endMs())
                ? target.endMs() + (next.startMs() - target.endMs()) / 2
                : target.endMs();
        feed.position = gapProbe;
        for (int frame = 0; frame < 20; frame++) {
            painter.reset();
            renderer.render(painter);
            Thread.sleep(4L);
        }
        assertTrue(painter.lastSolid >= lineWidth * 0.99f,
                "行结束后应收敛到全亮，实际=" + painter.lastSolid + " 行宽=" + lineWidth);

        // 起点必须接近 0：一开始就大段发亮说明进度映射错了
        assertTrue(samples.get(0) <= lineWidth * 0.05f,
                "行首应几乎全暗，实际=" + samples.get(0));

        long distinct = samples.stream().map(Math::round).distinct().count();
        assertTrue(distinct >= 8, "应有足够多的中间档位，实际=" + distinct);
    }

    private static LyricLine longestLine(LyricTimeline timeline) {
        LyricLine target = null;
        for (int i = 0; i < timeline.size(); i++) {
            LyricLine line = timeline.line(i);
            if (line == null || line.text().isEmpty()) {
                continue;
            }
            if (target == null || line.text().length() > target.text().length()) {
                target = line;
            }
        }
        return target;
    }

    private static String readResource() throws IOException {
        try (InputStream in = ProgressCurveTest.class.getResourceAsStream("/live-sample.json")) {
            if (in == null) {
                throw new IOException("缺少 live-sample.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class MeasuringFeed implements LyricFeed {
        private final LyricTimeline timeline;
        private final LyricLine pinned;
        long position;

        MeasuringFeed(LyricTimeline timeline, LyricLine pinned) {
            this.timeline = timeline;
            this.pinned = pinned;
            this.position = pinned.startMs();
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public PlayerSnapshot pollSnapshot() {
            // playing=false 用绝对位置扫描，避免外推干扰采样点
            return new PlayerSnapshot("test", TrackIdentity.of("t", "a", 0L),
                    position, false, 1L, 0L, CapabilitySet.empty());
        }

        @Override
        public LyricTimeline lyric() {
            return timeline;
        }

        @Override
        public long lyricSignature() {
            return 1L;
        }
    }

    /** 固定 10 像素/字符，记录最后一次实心裁剪宽度。 */
    private static final class WidthPainter implements FloatingLyricRenderer.Painter {
        private final int width;
        private final int height;
        float lastSolid;
        int scissorCalls;

        WidthPainter(int width, int height) {
            this.width = width;
            this.height = height;
        }

        void reset() {
            lastSolid = 0f;
            scissorCalls = 0;
        }

        @Override
        public int screenWidth() {
            return width;
        }

        @Override
        public int screenHeight() {
            return height;
        }

        @Override
        public int textWidth(String text) {
            return text == null ? 0 : text.length() * 10;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
        }

        @Override
        public void fillRect(int x, int y, int w, int h, int color) {
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int w, int h) {
            scissorCalls++;
            // 第一次裁剪是实心已唱层，后续是羽化层
            if (scissorCalls == 1) {
                lastSolid = w;
            }
        }

        @Override
        public void disableScissor() {
        }
    }
}
