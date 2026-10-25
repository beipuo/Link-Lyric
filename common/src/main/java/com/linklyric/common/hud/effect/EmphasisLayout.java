/*
 * 派生自 Lyricon (https://github.com/tomakino/lyricon)
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * 上游声明其移植自 applemusic-like-lyrics (AGPL-3.0)
 *
 * 本项目修改：连续同类聚合成组并加入 5 字、2.4 秒上限；跳过无逐字时间的整行合成单元。
 */
package com.linklyric.common.hud.effect;

import com.linklyric.core.lyric.LyricWord;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一行歌词切成绘制单元，并为其中够长的词组算出强调参数。
 *
 * <p>分组规则：连续的非空白、非 CJK 词合并为一组，CJK 词各自成组。
 * 空白是组边界——前一词以空白结尾或后一词以空白开头就断开。
 *
 * <p>强调条件：组时长至少 1 秒；CJK 不限字数，非 CJK 还需 2 到 7 个字符。
 * 短词做强调会显得抽动，长句做强调会拖沓，这两条界正好框住「值得强调」的范围。
 *
 * <p>幅度按组时长推导，行末那一组再放大一次，让句子收尾有落点。
 */
public final class EmphasisLayout {

    /** 强调所需的最短组时长。 */
    private static final long MIN_EMPHASIS_MS = 1000L;

    /** 非 CJK 组允许的字数上限。 */
    private static final int MAX_LATIN_CHARS = 7;

    /**
     * CJK 组允许的字数上限。
     *
     * <p>本项目的词就是单字，连续 CJK 若不设上限，整句会聚成一个巨型组，
     * 结果是整句同时放大发亮、动画拖到十几秒。上游靠「词」天然限制在几个字，
     * 这里用同量级的窗口把节奏拉回来。
     */
    private static final int MAX_CJK_CHARS = 5;

    /**
     * 组动画时长上限，毫秒。
     *
     * <p>慢歌单字可能持续一秒以上，按时长推导的动画会拖得过长；
     * 强调是一次收束，不该占据整个乐句。
     */
    private static final long MAX_GROUP_MS = 2400L;

    /** 行末组的放大倍数。 */
    private static final float LAST_AMOUNT_SCALE = 1.6f;
    private static final float LAST_BLUR_SCALE = 1.5f;
    private static final float LAST_DURATION_SCALE = 1.2f;

    private static final float MAX_AMOUNT = 1.2f;
    private static final float MAX_BLUR = 0.8f;

    private EmphasisLayout() {
    }

    /**
     * 为整行算出每个字的强调组。
     *
     * @param words 行内词序列
     * @return 与 {@code words} 等长的数组，未参与强调的位置为 null
     */
    public static EmphasisGroup[] groups(List<LyricWord> words) {
        EmphasisGroup[] out = new EmphasisGroup[words.size()];
        if (words.isEmpty()) {
            return out;
        }

        int lastTextIndex = -1;
        for (int i = words.size() - 1; i >= 0; i--) {
            if (!words.get(i).text().isBlank()) {
                lastTextIndex = i;
                break;
            }
        }

        // 本项目的中文按单字成词，因此「一个含多个汉字的词」只可能来自
        // 没有逐字时间的整行合成单元。强调是给字与词做的，套在整行上
        // 只会让整句一起发亮，故整行不参与。拉丁词不受此限：
        // 一个含数个字母的词本就是正常数据。
        if (words.size() == 1) {
            String only = words.get(0).text();
            if (isCjk(only) && only.trim().length() > 1) {
                return out;
            }
        }

        int i = 0;
        while (i < words.size()) {
            LyricWord first = words.get(i);
            if (first.text().isBlank()) {
                i++;
                continue;
            }

            List<Integer> group = new ArrayList<>(4);
            group.add(i);
            boolean cjk = isCjk(first.text());
            int limit = cjk ? MAX_CJK_CHARS : Integer.MAX_VALUE;
            int j = i + 1;
            while (j < words.size() && group.size() < limit) {
                LyricWord next = words.get(j);
                if (next.text().isBlank() || isCjk(next.text()) != cjk) {
                    break;
                }
                String prev = words.get(group.get(group.size() - 1)).text();
                if (prev.isEmpty() || next.text().isEmpty()) {
                    break;
                }
                // 空白是组边界：前一词尾随空白或后一词前导空白即断开。
                if (Character.isWhitespace(prev.charAt(prev.length() - 1))
                        || Character.isWhitespace(next.text().charAt(0))) {
                    break;
                }
                group.add(j);
                j++;
            }
            i += group.size();

            long groupBegin = Long.MAX_VALUE;
            long groupEnd = Long.MIN_VALUE;
            StringBuilder merged = new StringBuilder();
            for (int index : group) {
                LyricWord w = words.get(index);
                groupBegin = Math.min(groupBegin, w.startMs());
                groupEnd = Math.max(groupEnd, w.endMs());
                merged.append(w.text());
            }

            boolean singleCjk = group.size() == 1 && isCjk(words.get(group.get(0)).text());
            boolean anyEmphasized = false;
            for (int index : group) {
                LyricWord w = words.get(index);
                if (shouldEmphasize(isCjk(w.text()), w.text().trim(), w.durationMs())) {
                    anyEmphasized = true;
                    break;
                }
            }
            boolean mergedEmphasized = !singleCjk
                    && shouldEmphasize(false, merged.toString().trim(), groupEnd - groupBegin);
            if (!anyEmphasized && !mergedEmphasized) {
                continue;
            }

            boolean isLast = group.get(group.size() - 1) == lastTextIndex;

            long duration = Math.min(MAX_GROUP_MS,
                    Math.max(MIN_EMPHASIS_MS, groupEnd - groupBegin));
            float amount = powerCurve(duration / 2000f) * 0.6f;
            float blur = powerCurve(duration / 3000f) * 0.5f;
            if (isLast) {
                amount *= LAST_AMOUNT_SCALE;
                blur *= LAST_BLUR_SCALE;
                duration = (long) (duration * LAST_DURATION_SCALE);
            }
            amount = Math.min(MAX_AMOUNT, amount);
            blur = Math.min(MAX_BLUR, blur);

            int charCount = 0;
            for (int index : group) {
                charCount += words.get(index).text().trim().length();
            }
            if (charCount <= 0) {
                continue;
            }

            EmphasisGroup emphasis = new EmphasisGroup(duration, groupBegin, amount, blur, charCount);
            int offset = 0;
            for (int index : group) {
                out[index] = emphasis;
                offset += words.get(index).text().trim().length();
            }
        }
        return out;
    }

    /** 与上游一致的强调判定。 */
    static boolean shouldEmphasize(boolean cjk, String trimmed, long durationMs) {
        if (durationMs < MIN_EMPHASIS_MS) {
            return false;
        }
        if (cjk) {
            return true;
        }
        int length = trimmed.length();
        return length > 1 && length <= MAX_LATIN_CHARS;
    }

    /** 时长曲线：超过 1 秒后开方放缓增长，未到 1 秒时立方压低。 */
    private static float powerCurve(float v) {
        return v > 1f ? (float) Math.sqrt(v) : v * v * v;
    }

    /** 是否 CJK 或日韩文字。 */
    public static boolean isCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) {
                return true;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
            if (c >= 0x3400 && c <= 0x4DBF) {
                return true;
            }
            if (c >= 0xAC00 && c <= 0xD7AF) {
                return true;
            }
            if (c >= 0xF900 && c <= 0xFAFF) {
                return true;
            }
        }
        return false;
    }
}
