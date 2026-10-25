package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.lyric.LyricWord;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字形墨迹向文本盒之外伸出时，裁剪不能把它切掉。
 *
 * <h2>这个缺陷的实际表现</h2>
 *
 * <p>每句歌词的<b>最后一个字没有阴影</b>，倒数第二个正常。
 *
 * <p>根因不是阴影本身，而是裁剪判定：拖尾字的横坐标带着特效的
 * 亚像素位移（实测 {@code 468.10135}），右缘因此比整数
 * {@code viewRight=477} 大了不到一像素，被判为「越界」而进入裁剪分支。
 * 而阴影是向<b>右</b>偏 1 像素画的，裁剪框右边界一压，
 * 切掉的正好是它自己的阴影。其余字都在框内，所以只有末字有问题。
 *
 * <p>光晕与描边同样画在 {@code charWidth} 之外，属于同一类问题：
 * 只要一个字被判越界，它向右伸出的那圈墨迹就会被切。
 */
class GlyphClipTest {

    /** 亚像素位移导致的「几乎不越界」，不该触发裁剪。 */
    @Test
    void 亚像素位移不触发裁剪() {
        Rec rec = runLine("abcdefghi", atPercent(0.99f));
        assertFalse(rec.anyClipped(),
                "坐标只多出不到 1 像素时不应裁剪，否则末字的阴影会被切掉");
    }

    /** 整句都完整可见时，一个字都不该被裁。 */
    @Test
    void 完整可见时不裁剪() {
        Rec rec = runLine("abcdef", atPercent(0.5f));
        assertFalse(rec.anyClipped(), "整句在视口内时不该有任何裁剪");
    }

    /** 越界裁剪时，裁剪框必须含住向右伸出的墨迹。 */
    @Test
    void 裁剪框含住向外墨迹() {
        // 视口比整句窄，末尾的字必然越界。
        Rec rec = runLine("abcdefghijklmnop", atPercent(0.5f), 40);
        assertTrue(rec.anyClipped(), "视口过窄时应有字被裁");
        // 右端越界的字，裁剪框右缘必须覆盖到视口右缘。
        //
        // 左端越界不适用这个断言：它本来就该被左边界截断，
        // 裁剪框停在字形右缘是正确的。用户报的故障只发生在右端。
        int viewRight = rec.screenWidth();
        boolean sawRight = false;
        for (Rec.Call c : rec.clipped()) {
            if (c.x() + c.charWidth() <= viewRight) {
                continue;
            }
            sawRight = true;
            assertTrue(c.clipX() + c.clipW() >= viewRight,
                    "右端越界字的裁剪框没覆盖到视口右缘，边缘墨迹会被切："
                            + " clip=" + c.clipX() + "+" + c.clipW()
                            + " viewRight=" + viewRight);
        }
        assertTrue(sawRight, "本例应该产生右端越界的字");
    }

    /** 每个字都拿到了阴影开关为 true 的绘制调用。 */
    @Test
    void 逐字都带阴影() {
        Rec rec = runLine("abcdefghi", atPercent(0.99f));
        List<Rec.Call> chars = rec.singleChars();
        assertFalse(chars.isEmpty(), "应当有逐字绘制记录");
        for (Rec.Call c : chars) {
            assertTrue(c.shadow(), "每个字都应带阴影：" + c.text());
        }
    }

    /** 行尾那个字与其它字一样带阴影。 */
    @Test
    void 末字与其它字同等对待() {
        Rec rec = runLine("abcdefghi", atPercent(0.99f));
        List<Rec.Call> chars = rec.singleChars();
        assertTrue(chars.size() >= 3, "至少应记录到 3 个字");
        Rec.Call last = chars.get(chars.size() - 1);
        Rec.Call prev = chars.get(chars.size() - 2);
        assertEquals(prev.shadow(), last.shadow(),
                "末字的阴影状态必须与倒数第二个字一致");
        assertEquals(prev.clipped(), last.clipped(),
                "末字不该比前一个字多出裁剪");
    }

    // ——— 夹具 ———

    private static long atPercent(float ratio) {
        return (long) (4000 * ratio);
    }

    private static Rec runLine(String text, long atMs) {
        return runLine(text, atMs, Integer.MAX_VALUE);
    }

    private static Rec runLine(String text, long atMs, int maxWidth) {
        LyricLine line = new LyricLine(text, "", 0L, 4000L, words(text), "");
        LyricTimeline tl = new LyricTimeline(List.of(line));
        LyricFeed feed = new LyricFeed() {
            @Override public boolean isAlive() { return true; }
            @Override public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("t", TrackIdentity.of("t", "a", 0L),
                        atMs, true, 1L, 0L, CapabilitySet.empty());
            }
            @Override public LyricTimeline lyric() { return tl; }
            @Override public long lyricSignature() { return 1L; }
        };
        FloatingLyricRenderer r = new FloatingLyricRenderer(feed);
        Rec rec = new Rec(maxWidth);
        // 渲染到稳定为止，而不是固定跑 N 帧。
        //
        // 滚动位置与抬升都经过一阶平滑（SMOOTH_TAU_SECONDS = 0.05），
        // 收敛到稳态需要约 5τ = 250ms。此前固定 10 帧 × 16ms = 160ms，
        // 只走到 3.2τ，本来就停在收敛曲线上——机器快时勉强过，
        // 一旦并行编译抢占 CPU、真实帧间隔拉长，断言就会在
        // 「本例应该产生右端越界的字」上随机失败。
        //
        // 判据用「连续两帧调用序列完全相同」：稳态下坐标不再变化。
        // 上限 300 帧只是防死循环，正常几帧就退出，反而比固定 sleep 更快。
        String previous = null;
        for (int i = 0; i < 300; i++) {
            rec.calls.clear();
            r.render(rec);
            String current = rec.signature();
            if (current.equals(previous)) {
                break;
            }
            previous = current;
            try {
                Thread.sleep(4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return rec;
    }

    private static List<LyricWord> words(String text) {
        List<LyricWord> out = new ArrayList<>();
        int len = text.length();
        long step = 4000L / Math.max(1, len);
        for (int i = 0; i < len; i++) {
            out.add(new LyricWord(String.valueOf(text.charAt(i)),
                    i * step, (i + 1) * step));
        }
        return out;
    }

    private static final class Rec implements FloatingLyricRenderer.Painter {
        final List<Call> calls = new ArrayList<>();
        final int maxWidth;
        int curX;
        int curW;

        Rec(int maxWidth) {
            this.maxWidth = maxWidth;
        }

        record Call(String text, float x, boolean shadow, int clipX, int clipW,
                    int charWidth) {
            boolean clipped() {
                return clipW > 0;
            }
        }

        List<Call> singleChars() {
            List<Call> out = new ArrayList<>();
            for (Call c : calls) {
                if (c.text.length() == 1 && c.text.charAt(0) != ' ') {
                    out.add(c);
                }
            }
            return out;
        }

        boolean anyClipped() {
            return !clipped().isEmpty();
        }

        /** 本帧全部绘制调用的指纹，用于判断是否已收敛到稳态。 */
        String signature() {
            StringBuilder sb = new StringBuilder(calls.size() * 24);
            for (Call c : calls) {
                sb.append(c.text).append('|').append(c.x).append('|')
                        .append(c.shadow).append('|').append(c.clipX).append('|')
                        .append(c.clipW).append(';');
            }
            return sb.toString();
        }

        List<Call> clipped() {
            List<Call> out = new ArrayList<>();
            for (Call c : singleChars()) {
                if (c.clipped()) {
                    out.add(c);
                }
            }
            return out;
        }

        private static final int CHAR_WIDTH = 9;

        @Override public int screenWidth() { return Math.min(854, maxWidth); }
        @Override public int screenHeight() { return 480; }
        @Override public int textWidth(String t) {
            return t == null ? 0 : t.length() * CHAR_WIDTH;
        }
        @Override public void fillRect(int x, int y, int w, int h, int c) { }
        @Override public void enableScissor(int x, int y, int w, int h) {
            curX = x;
            curW = w;
        }
        @Override public void disableScissor() { curX = 0; curW = 0; }
        @Override public boolean supportsScissor() { return true; }

        @Override public void drawText(String t, int x, int y, int c) {
            calls.add(new Call(t, x, true, curX, curW, textWidth(t)));
        }
        @Override public void drawText(String t, int x, int y, int c, boolean s) {
            calls.add(new Call(t, x, s, curX, curW, textWidth(t)));
        }
        @Override public void drawGlyphGradient(String g, float x, int y, float lift,
                com.linklyric.common.style.GlyphColors c, boolean shadow) {
            calls.add(new Call(g, x, shadow, curX, curW, textWidth(g)));
        }
        @Override public void drawGlyphSpans(String g, float x, int y, float lift,
                com.linklyric.common.style.GlyphSpan[] sp, int ox, boolean shadow) {
            calls.add(new Call(g, x, shadow, curX, curW, textWidth(g)));
        }
    }
}
