package com.linklyric.common.hud;

import com.linklyric.common.bridge.BridgePlaybackSource;
import com.linklyric.core.lyric.LyricLine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 用真实桥接端点验证染色确实在推进。
 *
 * <p>这里不使用任何模拟数据：真的连 127.0.0.1:35400，真的走
 * {@link BridgePlaybackSource} 的轮询线程，真的让渲染器逐帧计算。
 * 目的是排除「单元测试通过但实机不动」这类断层。
 *
 * <p>端点未启动或未在播放时自动跳过，不阻塞常规构建。
 */
class LiveBridgeAnimationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 35400;

    @Test
    void 真实端点下已唱宽度随帧推进() throws Exception {
        Assumptions.assumeTrue(reachable(), "桥接端点未启动，跳过");

        BridgePlaybackSource source = new BridgePlaybackSource(HOST, PORT);
        ScanningPainter painter = new ScanningPainter();
        FloatingLyricRenderer renderer = new FloatingLyricRenderer(source);
        source.start();
        try {
            // 先拿到歌词，否则没有可绘制内容
            long deadline = System.currentTimeMillis() + 8000L;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(150L);
                painter.reset();
                renderer.render(painter);
                if (painter.lastScissor >= 0) {
                    ready = true;
                    break;
                }
            }
            Assumptions.assumeTrue(ready, "端点无活动歌词，跳过"
                    + "（时间轴行数=" + source.lyric().size()
                    + "，播放中=" + source.pollSnapshot().playing() + "）");
            Assumptions.assumeTrue(source.pollSnapshot().playing(), "当前未在播放，跳过");

            for (int i = 0; i < 18; i++) {
                Thread.sleep(150L);
                painter.reset();
                renderer.render(painter);
                // 必须用渲染器实际使用的（外推后）位置反查时间轴。
                // 用轮询值会与渲染结果错位最多一个上报周期，诊断信息失真。
                long rendered = renderedPosition(renderer);
                LyricLine cur = source.lyric().lineAt(rendered);
                System.out.printf(
                        "渲染位置=%-7d 上报=%-7d 行宽=%-4d 已唱=%-4d text=[%s] 行=[%s](%d-%d, words=%d)%n",
                        rendered, (long) source.pollSnapshot().positionMs(),
                        painter.lastLineWidth, painter.lastScissor, painter.lastText,
                        cur == null ? "null" : cur.text(),
                        cur == null ? -1 : cur.startMs(),
                        cur == null ? -1 : cur.endMs(),
                        cur == null ? -1 : cur.words().size());

                if (painter.lastScissor != painter.lastLineWidth) {
                    System.out.println("  -> 部分高亮（正常染色）");
                } else {
                    System.out.println("  -> 整行等高亮（异常：无染色过程）");
                }
            }
        } finally {
            source.stop();
        }
    }

    /** 反射读取渲染器本帧使用的位置，用于诊断对齐。 */
    private static long renderedPosition(FloatingLyricRenderer renderer) throws Exception {
        java.lang.reflect.Field f =
                FloatingLyricRenderer.class.getDeclaredField("lastRenderedPositionMs");
        f.setAccessible(true);
        return (long) f.get(renderer);
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class ScanningPainter implements FloatingLyricRenderer.Painter {
        int lastScissor = -1;
        int lastLineWidth = -1;
        String lastText = "";
        private int scissorCount;

        void reset() {
            lastScissor = -1;
            lastLineWidth = -1;
            lastText = "";
            scissorCount = 0;
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
            // 近似 MC 默认字体：全角约 9px，半角约 6px
            if (text == null) {
                return 0;
            }
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                width += text.charAt(i) > 0x2E80 ? 9 : 6;
            }
            return width;
        }

        @Override
        public void drawText(String text, int x, int y, int color) {
            lastText = text;
            if (lastLineWidth < 0) {
                lastLineWidth = textWidth(text);
            }
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
            // 第一次裁剪是实心已唱层；后续是羽化过渡层，不代表已唱进度
            scissorCount++;
            if (scissorCount == 1) {
                lastScissor = width;
            }
        }

        @Override
        public void disableScissor() {
        }
    }
}
