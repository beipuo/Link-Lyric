package com.linklyric.common.hud.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linklyric.core.lyric.LyricWord;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 强调分组：判定条件、行末放大、逐字错峰。 */
class EmphasisLayoutTest {

    private static LyricWord w(String text, long start, long end) {
        return new LyricWord(text, start, end);
    }

    @Test
    void 短词不强调() {
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(w("a", 0, 900)));
        assertNull(groups[0]);
    }

    @Test
    void 长CJK字强调且不限字数() {
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(w("永", 0, 1200)));
        assertNotNull(groups[0]);
    }

    @Test
    void 拉丁词超过七字不强调() {
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(w("abcdefgh", 0, 2000)));
        assertNull(groups[0]);
    }

    @Test
    void 拉丁词两到七字强调() {
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(w("hello", 0, 2000)));
        assertNotNull(groups[0]);
    }

    @Test
    void 单词本身太短但合并后够长仍强调() {
        List<LyricWord> words = List.of(
                w("Car", 0, 400),
                w("ds", 400, 1600));
        EmphasisGroup[] groups = EmphasisLayout.groups(words);
        assertNotNull(groups[0], "合并后时长与字数都达标");
        assertNotNull(groups[1]);
        assertEquals(groups[0], groups[1], "同一组共享同一个参数对象");
    }

    @Test
    void 空白是组边界_相邻拉丁词不合并() {
        List<LyricWord> words = List.of(
                w("aa", 0, 800),
                w("bb", 900, 2700));
        EmphasisGroup[] merged = EmphasisLayout.groups(words);
        // 两词各自时长都不足 1 秒，合并后跨度 2700ms 才达标。
        assertNotNull(merged[0], "合并后应达标");
        assertEquals(merged[0], merged[1], "相邻拉丁词合并为一组");
        assertNull(EmphasisLayout.groups(List.of(w("aa", 0, 800)))[0],
                "单独一个短词不达标");
    }

    @Test
    void 任一邻词以空白收尾则断开() {
        List<LyricWord> words = List.of(
                w("hello ", 0, 1000),
                w("world", 1100, 2100));
        EmphasisGroup[] groups = EmphasisLayout.groups(words);
        assertNotNull(groups[0]);
        assertNotNull(groups[1]);
        assertTrue(groups[0] != groups[1], "尾随空白使两词各成一组");
    }

    @Test
    void 行末组幅度更大() {
        // 中间用空白断开，才有「非行末」与「行末」两组可比。
        LyricWord first = w("字", 0, 1500);
        LyricWord spacer = w("  ", 1500, 1500);
        LyricWord last = w("句", 1600, 3100);
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(first, spacer, last));
        assertNotNull(groups[0]);
        assertNull(groups[1], "空白词不参与分组");
        assertNotNull(groups[2]);
        assertTrue(groups[2].amount > groups[0].amount, "行末组的放大系数应更大");
        assertTrue(groups[2].blur > groups[0].blur, "行末组的辉光应更强");
        assertTrue(groups[2].durationMs > groups[0].durationMs, "行末组时长应更长");
    }

    @Test
    void 幅度受上限约束() {
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(w("字", 0, 20000)));
        assertTrue(groups[0].amount <= 1.2f);
        assertTrue(groups[0].blur <= 0.8f);
    }

    /** 中文按单字成词：把一句拆成逐字单元，才是真实数据形态。 */
    private static List<LyricWord> chars(String text, long startMs, long stepMs) {
        List<LyricWord> out = new java.util.ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.add(w(text.substring(i, i + 1), startMs + stepMs * i, startMs + stepMs * (i + 1)));
        }
        return out;
    }

    @Test
    void 逐字错峰首字立即开始且单调递增() {
        EmphasisGroup group = EmphasisLayout.groups(chars("四个汉字", 0, 500))[0];
        assertNotNull(group);
        long previous = -1L;
        for (int i = 0; i < 4; i++) {
            long start = group.charStartMs(i);
            assertTrue(start > previous, "错峰时间必须严格递增");
            previous = start;
        }
        assertEquals(group.startMs, group.charStartMs(0));
    }

    @Test
    void 空行不产生分组() {
        assertEquals(0, EmphasisLayout.groups(List.of()).length);
    }

    @Test
    void 空白词不参与分组() {
        EmphasisGroup[] groups = EmphasisLayout.groups(List.of(w("   ", 0, 2000)));
        assertNull(groups[0]);
    }
}
