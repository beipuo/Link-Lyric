package com.linklyric.core.lyric;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 歌词缓存。容量受限的 LRU，按 {@link com.linklyric.core.sync.TrackIdentity#cacheKey()} 索引。
 *
 * <p>歌词是按曲目稳定的数据，一次查询后整首都会反复读取，缓存收益极高。
 * 但一首歌的逐字时间轴可达数百 KB，无界缓存会在长时间游戏中累积成泄漏，
 * 因此必须有容量上限。
 *
 * <p>非线程安全：仅由渲染/主线程持有。异步加载完成后由主线程投递进来。
 */
public final class LyricCache {

    private static final int DEFAULT_CAPACITY = 32;

    private final int capacity;
    private final Map<String, LyricTimeline> entries;

    public LyricCache() {
        this(DEFAULT_CAPACITY);
    }

    public LyricCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new LinkedHashMap<>(this.capacity * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LyricTimeline> eldest) {
                return size() > LyricCache.this.capacity;
            }
        };
    }

    /** 命中返回时间轴，未命中返回 null（调用方据此发起异步查询）。 */
    public LyricTimeline get(String cacheKey) {
        return entries.get(cacheKey);
    }

    /** 只在拿到非空时间轴时写入，避免用「查不到」污染缓存。 */
    public void put(String cacheKey, LyricTimeline timeline) {
        if (cacheKey == null || timeline == null || timeline.isEmpty()) {
            return;
        }
        entries.put(cacheKey, timeline);
    }

    public boolean has(String cacheKey) {
        return entries.containsKey(cacheKey);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
