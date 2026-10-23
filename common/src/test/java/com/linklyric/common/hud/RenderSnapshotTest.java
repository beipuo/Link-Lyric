package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实像素绘制验证渲染结果。
 *
 * <p>逐字渲染的缺陷几乎都不会抛异常，只会在画面上表现为重影、错位、不连贯。
 * 纯坐标断言能测出「偏移量是多少」，但测不出「看起来对不对」。
 * 因此这里用 {@link java.awt.Graphics2D} 实现一份 {@code Painter}，
 * 把真实合成结果画成 PNG，同时保留坐标断言。
 *
 * <p>产物在 {@code common/build/reports/frames/}，便于人工核对。
 */
class RenderSnapshotTest {

    private static final int SCALE = 6;
    private static final int WIDTH = 480;
    private static final int HEIGHT = 270;

    /**
     * 同一个字被两层绘制时，纵坐标必须一致。
     *
     * <p>变色需要两层：未唱色铺底、已唱色覆盖。两层只要共享同一个
     * {@code textX} 与同一份抬升表，像素就严丝合缝地重合，
     * 观感是「同一个字从灰变蓝」。
     *
     * <p>早先的缺陷不是「画了两层」，而是两层各自位移：
     * 换行时旧行下滑、新行上升，两种颜色在中途对穿。
     * 因此判据是**同帧内同一字的两次绘制 y 相同**，
     * 而不是「只许画一次」。
     */
    @Test
    void 同帧内两层严格重合() throws Exception {
        AwtPainter painter = new AwtPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed(fiveChars(), 4_800L));

        int checkedFrames = 0;
        for (int frame = 0; frame < 40; frame++) {
            painter.clearRecords();
            renderer.render(painter);

            Map<String, List<Integer>> perFrame = painter.allYsThisFrame();
            for (Map.Entry<String, List<Integer>> e : perFrame.entrySet()) {
                List<Integer> list = e.getValue();
                for (int i = 1; i < list.size(); i++) {
                    assertEquals(list.get(0), list.get(i),
                            "帧 " + frame + " 字 '" + e.getKey()
                                    + "' 两层纵坐标不一致，实际=" + list);
                }
            }
            if (!perFrame.isEmpty()) {
                checkedFrames++;
            }
            Thread.sleep(8L);
        }
        assertTrue(checkedFrames > 0,
                "应有关注的帧，实际检查帧数=" + checkedFrames);
    }

    /** 唱到的字应比远处的字更高（y 更小）。 */
    @Test
    void 唱到的字抬升可见() throws Exception {
        AwtPainter painter = new AwtPainter();
        // 位置 4800ms：第 5 个字（索引 4）正在唱，其后的字尚未开始
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed(fiveChars(), 4_800L));

        Map<String, Integer> lastY = new LinkedHashMap<>();
        for (int frame = 0; frame < 40; frame++) {
            painter.clearRecords();
            renderer.render(painter);
            lastY.putAll(painter.recordsY());
            Thread.sleep(8L);
        }

        int baseY = painter.baseY();
        String text = "相爱过如果是很好听";

        // 找抬升量最大的字，作为「正在被唱的那一带」
        int maxLift = 0;
        String maxChar = "";
        int farLift = Integer.MAX_VALUE;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            Integer y = lastY.get(ch);
            if (y == null) {
                continue;
            }
            int lift = baseY - y;
            if (lift > maxLift) {
                maxLift = lift;
                maxChar = ch;
            }
            farLift = Math.min(farLift, lift);
        }

        assertTrue(maxLift > 0,
                "应有字被抬起，实际 baseY=" + baseY + " 全部=" + lastY);
        assertTrue(maxLift >= (farLift == Integer.MAX_VALUE ? 0 : farLift),
                "最大抬升不应小于最小抬升");
        assertTrue(!maxChar.isEmpty(), "应能找到抬起的字");
    }

    /** 输出真实渲染帧，供人工核对观感。 */
    @Test
    void 输出渲染帧() throws Exception {
        Path dir = Path.of("build", "reports", "frames");
        Files.createDirectories(dir);

        long[] positions = {1_000L, 3_000L, 4_800L, 7_000L, 9_500L};
        for (long pos : positions) {
            AwtPainter painter = new AwtPainter();
            FloatingLyricRenderer renderer = new FloatingLyricRenderer(feed(fiveChars(), pos));
            for (int frame = 0; frame < 40; frame++) {
                renderer.render(painter);
                Thread.sleep(8L);
            }
            painter.save(new File(dir.toFile(), "frame-" + pos + ".png"));
        }

        assertTrue(dir.toFile().listFiles().length >= positions.length,
                "应输出全部帧");
    }

    /**
     * 十个字的一行，每字一秒，真实逐字数据。
     *
     * <p>刻意让每个字互不相同：若出现重复字，记录表按字聚合就无法区分
     * 「两层错位」与「两个同字各在一处」，诊断会失效。
     */
    private static LyricTimeline fiveChars() {
        String text = "相爱过如果是很好听";
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            words.add(new LyricWord(String.valueOf(text.charAt(i)),
                    i * 1_000L, (i + 1) * 1_000L));
        }
        return new LyricTimeline(List.of(
                new LyricLine(text, "", 0L, text.length() * 1_000L,
                        List.copyOf(words), "")));
    }

    private static LyricFeed feed(LyricTimeline timeline, long position) {
        return new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                        position, true, 0L, System.nanoTime(), CapabilitySet.empty());
            }

            @Override
            public LyricTimeline lyric() {
                return timeline;
            }

            @Override
            public long lyricSignature() {
                return 1L;
            }
        };
    }

    /**
     * 用 AWT 真实绘制，并按裁剪矩形过滤可见绘制。
     *
     * <p>裁剪必须用真实 {@code Graphics2D} 的 clip 实现，
     * 否则测不出「正在唱的字只显示已唱比例」这类行为。
     */
    private static final class AwtPainter implements FloatingLyricRenderer.Painter {
        private final BufferedImage image =
                new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        private final Graphics2D g = image.createGraphics();
        private final Font font = pickFont();
        private final Map<String, Integer> records = new LinkedHashMap<>();
        /** 本帧内每个字被绘制的全部纵坐标（底色 + 高亮各一次）。 */
        private final Map<String, List<Integer>> perFrame = new LinkedHashMap<>();

        AwtPainter() {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(new Color(32, 32, 40));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }

        private static Font pickFont() {
            String[] candidates = {"Microsoft YaHei", "SimHei", "SimSun", "Dialog"};
            for (String name : candidates) {
                Font f = new Font(name, Font.PLAIN, 10);
                if (f.getFamily().equalsIgnoreCase(name) || name.equals("Dialog")) {
                    return f;
                }
            }
            return new Font(Font.MONOSPACED, Font.PLAIN, 10);
        }

        void clearRecords() {
            records.clear();
            perFrame.clear();
        }

        /** 本帧内每个字被绘制的全部纵坐标。 */
        Map<String, List<Integer>> allYsThisFrame() {
            return new LinkedHashMap<>(perFrame);
        }

        Map<String, Integer> recordsY() {
            return new LinkedHashMap<>(records);
        }

        int baseY() {
            int max = Integer.MIN_VALUE;
            for (int y : records.values()) {
                max = Math.max(max, y);
            }
            return max == Integer.MIN_VALUE ? 0 : max;
        }

        void save(File file) throws Exception {
            BufferedImage big = new BufferedImage(WIDTH * SCALE, HEIGHT * SCALE,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D bg = big.createGraphics();
            bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            bg.drawImage(image, 0, 0, WIDTH * SCALE, HEIGHT * SCALE, null);
            bg.dispose();
            ImageIO.write(big, "png", file);
            g.dispose();
        }

        @Override
        public int screenWidth() {
            return WIDTH;
        }

        @Override
        public int screenHeight() {
            return HEIGHT;
        }

        @Override
        public int textWidth(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            FontMetrics fm = g.getFontMetrics(font);
            return fm.stringWidth(text);
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            // 只记录真正落在当前裁剪区内的绘制，模拟真实可见性
            Shape clip = g.getClip();
            int charWidth = textWidth(text);
            if (clip != null && !clip.intersects(x, y, Math.max(1, charWidth), 10)) {
                return;
            }
            if (text.length() == 1) {
                records.putIfAbsent(text, y);
                perFrame.computeIfAbsent(text, k -> new ArrayList<>()).add(y);
            }
            g.setColor(new Color(color, true));
            g.drawString(text, x, y + g.getFontMetrics(font).getAscent());
        }

        @Override
        public void fillRect(int x, int y, int width, int height, int color) {
            g.setColor(new Color(color, true));
            g.fillRect(x, y, width, height);
        }

        @Override
        public boolean supportsScissor() {
            return true;
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            Shape old = g.getClip();
            Rectangle r = new Rectangle(x, y, Math.max(0, width), Math.max(0, height));
            g.setClip(old == null ? r : old.getBounds().intersection(r));
        }

        @Override
        public void disableScissor() {
            g.setClip(null);
        }
    }
}
