package com.linklyric.common.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 歌词指纹的稳定性与正确性。
 *
 * <p>指纹唯一的用途是「判断歌词是否变化」，因此必须同时满足两条：
 * 歌词不变时恒定，歌词一变就不同。任何一条失效都会让渲染层出可见故障——
 * 前者导致时间轴被反复重建（高亮每帧直接赋成目标值、跳过平滑推进），
 * 后者导致换曲后仍显示上一首的歌词。
 */
class LyricSignatureTest {

    /** 逐字符累加必须与 {@code String.hashCode()} 完全一致。 */
    @Test
    void 区间哈希与子串哈希等价() {
        String s = "{\"lyric\":{\"lines\":[{\"text\":\"春江潮水连海平\",\"startMs\":12000}]}}";
        for (int from = 0; from < s.length(); from += 7) {
            for (int to = from; to <= s.length(); to += 11) {
                assertEquals(s.substring(from, to).hashCode(), BridgePlaybackSource.hashRange(s, from, to),
                        "区间 [" + from + ", " + to + ") 的哈希与子串版本不一致");
            }
        }
    }

    /** 位置、序号这些每轮都变的字段不得影响指纹。 */
    @Test
    void 位置变化不改变指纹() {
        String a = "{\"positionMs\":12340,\"seq\":42,\"lyric\":{\"lines\":[{\"text\":\"原文\"}]}}";
        String b = "{\"positionMs\":98321,\"seq\":97,\"lyric\":{\"lines\":[{\"text\":\"原文\"}]}}";
        assertEquals(BridgePlaybackSource.lyricContentSignature(a),
                BridgePlaybackSource.lyricContentSignature(b),
                "位置与序号不该参与指纹，否则时间轴会被每轮重建");
    }

    /** 歌词内容变化必须改变指纹。 */
    @Test
    void 歌词变化改变指纹() {
        String a = "{\"positionMs\":1,\"lyric\":{\"lines\":[{\"text\":\"第一句\"}]}}";
        String b = "{\"positionMs\":1,\"lyric\":{\"lines\":[{\"text\":\"第二句\"}]}}";
        assertNotEquals(BridgePlaybackSource.lyricContentSignature(a),
                BridgePlaybackSource.lyricContentSignature(b));
    }

    /** 同一个歌词对象反复调用的结果必须恒定。 */
    @Test
    void 指纹可重复() {
        String body = "{\"lyric\":{\"lines\":[{\"text\":\"海上明月共潮生\",\"words\":[]}]},\"seq\":7}";
        int first = BridgePlaybackSource.lyricContentSignature(body);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, BridgePlaybackSource.lyricContentSignature(body));
        }
    }

    /** 没有歌词字段时返回 0，调用方据此走「无歌词」分支而不是崩溃。 */
    @Test
    void 缺失歌词返回零() {
        assertEquals(0, BridgePlaybackSource.lyricContentSignature("{\"positionMs\":1}"));
        assertEquals(0, BridgePlaybackSource.lyricContentSignature(null));
        assertEquals(0, BridgePlaybackSource.lyricContentSignature(""));
    }

    /** 值里出现同名字符串不得被误当成键。 */
    @Test
    void 忽略字符串内的同名文本() {
        String body = "{\"note\":\"这里提到 \\\"lyric\\\" 但只是个值\","
                + "\"lyric\":{\"lines\":[{\"text\":\"真正的歌词\"}]}}";
        int sig = BridgePlaybackSource.lyricContentSignature(body);
        assertNotEquals(0, sig, "应定位到真正的 lyric 键");
        assertEquals(sig, BridgePlaybackSource.lyricContentSignature(
                "{\"lyric\":{\"lines\":[{\"text\":\"真正的歌词\"}]}}"),
                "指纹只应取决于 lyric 的值，与前面无关字段无关");
    }
}
