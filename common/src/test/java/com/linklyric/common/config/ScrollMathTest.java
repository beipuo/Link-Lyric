package com.linklyric.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滚动区几何。
 *
 * <p>抽成纯计算放在 common，是因为这里的错法都很隐蔽：
 * 滚动量累加、内容比视口矮却允许滚、滚动条缩略块跑到轨道外。
 * 在界面里靠眼睛看不出来，只能靠数算。
 */
class ScrollMathTest {

    /** 内容比视口高时，滚动量应夹在 [0, total-visible]。 */
    @Test
    void 滚动量被夹紧() {
        LyricOptions.ScrollState s = new LyricOptions.ScrollState(400, 100);

        s.scrollBy(-9999);
        assertEquals(0, s.offset(), "向上滚到底");

        s.scrollBy(9999);
        assertEquals(300, s.offset(), "向下滚到底应停在 total-visible");
    }

    /** 内容装得下时不允许滚动。 */
    @Test
    void 内容装得下时不滚动() {
        LyricOptions.ScrollState s = new LyricOptions.ScrollState(80, 100);
        assertFalse(s.canScroll(), "内容比视口矮，不应可滚");

        s.scrollBy(100);
        assertEquals(0, s.offset(), "不可滚时滚动量必须为 0");
    }

    /** 反复滚动不能累加出偏差。 */
    @Test
    void 反复滚动不累加() {
        LyricOptions.ScrollState s = new LyricOptions.ScrollState(400, 100);

        s.scrollBy(50);
        int once = s.offset();
        s.scrollBy(-50);
        assertEquals(0, s.offset(), "滚回去应回到起点，实际=" + s.offset());

        s.scrollBy(50);
        assertEquals(once, s.offset(), "同样的滚动量应得到同样的位置");
    }

    /** 滚动条缩略块必须落在轨道内。 */
    @Test
    void 缩略块不越轨() {
        int trackTop = 100;
        int trackHeight = 200;
        int visible = 100;
        int total = 400;

        // 顶部
        int[] top = LyricOptions.ScrollState.thumb(trackTop, trackHeight, visible, total, 0);
        assertEquals(trackTop, top[0], "滚到顶时缩略块贴顶");

        // 底部
        int[] bottom = LyricOptions.ScrollState.thumb(
                trackTop, trackHeight, visible, total, total - visible);
        assertEquals(trackTop + trackHeight, bottom[0] + bottom[1],
                "滚到底时缩略块贴底");

        assertTrue(bottom[1] > 0, "缩略块高度必须为正");
        assertTrue(top[1] <= trackHeight, "缩略块不应比轨道还高");
    }

    /** 视口为 0 或负时不能除零。 */
    @Test
    void 退化视口不崩() {
        LyricOptions.ScrollState s = new LyricOptions.ScrollState(400, 0);
        assertFalse(s.canScroll(), "视口为空时不应可滚");
        s.scrollBy(100);
        assertEquals(0, s.offset());
    }
}
