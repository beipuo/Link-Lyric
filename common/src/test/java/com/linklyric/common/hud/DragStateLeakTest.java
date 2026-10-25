package com.linklyric.common.hud;

import com.linklyric.core.capability.CapabilitySet;
import com.linklyric.core.lyric.LyricTimeline;
import com.linklyric.core.sync.PlayerSnapshot;
import com.linklyric.core.sync.TrackIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拖动状态机不得跨会话残留标志。
 *
 * <h2>踩到的具体形态</h2>
 *
 * <p>{@code beginFromOffset()} 会把 {@code waitingForCursor} 置为 true，
 * 表示「第一次移动只用来确定基准点」。{@code end()}、{@code beginSession()}
 * 都会清掉它，唯独 {@code cancel()} 没有。
 *
 * <p>触发路径是「指令进入 → 还没动就 Esc 取消 → 再正常按住歌词拖」。
 * 关键在于取消发生在任何一次移动**之前**：标志还没被 {@code drag()}
 * 消费掉，于是被原样带进下一次会话。新会话的首次移动会被当成
 * 「确定基准点」而整个吞掉——玩家按住拖了两三像素，画面纹丝不动，
 * 必须松手重拖。这种「第一下没反应」的故障极难归因到状态残留上。
 */
class DragStateLeakTest {

    /** 取消之后，下一次拖动必须立刻跟手。 */
    @Test
    void 取消后不残留等待光标标志() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        assertTrue(c.begin(0, 0));
        c.cancel();

        // 新会话：首次移动必须立即生效
        assertTrue(c.begin(0, 0));
        c.drag(30, 20);
        assertEquals(30, renderer.offsetX(),
                "取消后的首次移动被吞掉了");
        assertEquals(20, renderer.offsetY());
    }

    /** 正常情况下 cancel 仍须回到会话起点。 */
    @Test
    void 取消仍回到原位() {
        FloatingLyricRenderer renderer = renderer();
        LyricDragController c = new LyricDragController(renderer, null);

        c.begin(0, 0);
        c.drag(80, 80);
        c.cancel();
        assertEquals(0, renderer.offsetX(), "取消后应回到原位");
        assertFalse(renderer.isDragging(), "取消后应退出拖动模式");
    }

    private static FloatingLyricRenderer renderer() {
        return new FloatingLyricRenderer(new LyricFeed() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public PlayerSnapshot pollSnapshot() {
                return new PlayerSnapshot("test", TrackIdentity.of("曲目", "", 0L),
                        0L, false, 0L, System.nanoTime(), CapabilitySet.empty());
            }

            @Override
            public LyricTimeline lyric() {
                return LyricTimeline.empty();
            }

            @Override
            public long lyricSignature() {
                return 1L;
            }
        });
    }
}
