package com.linklyric.common.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件请求（歌词子树 ETag）的两条不变式。
 *
 * <h2>为什么必须钉住</h2>
 *
 * <p>服务端在歌词未变时不再下发子树，只回 {@code lyricOmitted: true}。
 * 客户端原先靠 {@code lyricContentSignature} 判断「歌词变没变」——
 * 而那个方法找不到 {@code "lyric":{ 时会返回 0}。于是省略一次，
 * 指纹就从旧值跳到 0，被当成「歌词变了」，进而 {@code parseLyric}
 * 解出空时间轴，把正在显示的歌词当场清掉。
 *
 * <p>这不是理论风险：整首歌除了换曲那一刻，每一轮轮询都走这条路径。
 */
class ConditionalLyricTest {

    private static final String WITH_LYRIC = "{\"v\":2,\"seq\":7,\"positionMs\":1000,"
            + "\"lyricOmitted\":false,"
            + "\"lyric\":{\"wordLevel\":true,\"lines\":["
            + "{\"text\":\"甲\",\"startMs\":0,\"endMs\":1000,\"words\":[]}]}}";

    private static final String OMITTED = "{\"v\":2,\"seq\":7,\"positionMs\":1000,"
            + "\"lyricOmitted\":true}";

    /**
     * 省略歌词时，指纹会退回 0——所以**不能**用指纹判断变没变。
     *
     * <p>这条固定住「坑确实存在」，防止将来有人把判定改回指纹比较。
     */
    @Test
    void 省略时指纹退化为零() {
        assertNotEquals(0, BridgePlaybackSource.lyricContentSignature(WITH_LYRIC),
                "正常载荷应有非零指纹");
        assertEquals(0, BridgePlaybackSource.lyricContentSignature(OMITTED),
                "歌词被省略时指纹必然退化为 0，这正是不能拿它做判定的原因");
    }

    /** 省略标志必须能被可靠读出，且不被歌词正文里的同名字段干扰。 */
    @Test
    void 省略标志可读取() {
        assertTrue(BridgePlaybackSource.lyricOmitted(OMITTED), "应读到 true");
        assertTrue(!BridgePlaybackSource.lyricOmitted(WITH_LYRIC), "应读到 false");
        assertTrue(!BridgePlaybackSource.lyricOmitted(null), "null 视为未省略");
        assertTrue(!BridgePlaybackSource.lyricOmitted("{}"), "字段缺失视为未省略");
    }

    /**
     * 歌词正文里出现同名文本时，不得被误判为省略标记。
     *
     * <p>手写解析器最容易被值里的同名键骗到。这里把
     * {@code lyricOmitted} 塞进歌词文本，确认判定仍取外层字段。
     */
    @Test
    void 正文里的同名字段不干扰() {
        String tricky = "{\"lyricOmitted\":false,\"lyric\":{\"lines\":["
                + "{\"text\":\"lyricOmitted\",\"startMs\":0,\"endMs\":1,\"words\":[]}]}}";
        assertTrue(!BridgePlaybackSource.lyricOmitted(tricky),
                "应以外层字段为准，不被歌词文本里的同名字符串骗到");
    }

    /**
     * 决策函数：省略时必须不重建，哪怕指纹算出来是 0。
     *
     * <p>这是整个条件请求的核心。若把这里改回「比较指纹」，
     * 省略时指纹从 4096 掉到 0，会立刻触发一次重建，
     * 把正在显示的歌词清空。下面这条测试就是那一刻的画像。
     */
    @Test
    void 省略时不重建歌词() {
        int lastSignature = 4096;
        int omittedSignature = BridgePlaybackSource.lyricContentSignature(OMITTED);
        assertEquals(0, omittedSignature, "前提：省略时指纹为 0");

        assertTrue(!BridgePlaybackSource.needsReparse(OMITTED, omittedSignature, lastSignature),
                "服务端说歌词没变，就不该重建——0 不等于「变了」");
    }

    /** 歌词确实下发且指纹变化时必须重建。 */
    @Test
    void 指纹变化时重建() {
        int sig = BridgePlaybackSource.lyricContentSignature(WITH_LYRIC);
        assertTrue(BridgePlaybackSource.needsReparse(WITH_LYRIC, sig, 0),
                "首次拿到歌词应重建");
        assertTrue(!BridgePlaybackSource.needsReparse(WITH_LYRIC, sig, sig),
                "同一份歌词不应重复重建");
    }

    /** 旧适配器：从不发省略标记，行为必须与改动前完全一致。 */
    @Test
    void 旧适配器不受影响() {
        String legacy = "{\"seq\":1,\"lyric\":{\"lines\":["
                + "{\"text\":\"甲\",\"startMs\":0,\"endMs\":9,\"words\":[]}]}}";
        int sig = BridgePlaybackSource.lyricContentSignature(legacy);
        assertTrue(!BridgePlaybackSource.lyricOmitted(legacy), "没有该字段即为未省略");
        assertTrue(BridgePlaybackSource.needsReparse(legacy, sig, 0), "内容变化仍应重建");
    }

    /** ETag 的引号与弱校验前缀都应被剥离，裸指纹才能直接比较。 */
    @Test
    void etag引号被剥离() {
        assertEquals("abc", BridgePlaybackSource.stripEtag("\"abc\""));
        assertEquals("abc", BridgePlaybackSource.stripEtag("W/\"abc\""));
        assertEquals("abc", BridgePlaybackSource.stripEtag("abc"));
        assertEquals(null, BridgePlaybackSource.stripEtag(null));
        assertEquals(null, BridgePlaybackSource.stripEtag("\"\""));
    }
}
