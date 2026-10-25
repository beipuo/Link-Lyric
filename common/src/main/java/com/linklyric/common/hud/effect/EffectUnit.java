package com.linklyric.common.hud.effect;

import com.linklyric.common.hud.effect.EmphasisGroup;

/**
 * 一个绘制单元：字形在行内的位置与其自己的时间窗。
 *
 * <p>CJK 逐字成单元，拉丁文按词成单元；参与强调的词组内，拉丁词也逐字成单元，
 * 以便逐字错峰。
 */
public final class EffectUnit {

    /** 单元文本起始偏移，用于从行文本里截取。 */
    public int textStart;

    /** 单元文本长度，字符数。 */
    public int textLength = 1;

    /** 单元左边界，相对行首。 */
    public float x0;

    /** 单元右边界，相对行首。 */
    public float x1;

    /** 是否 CJK，决定逐字与整词策略。 */
    public boolean cjk;

    /** 单元开始时间，毫秒。 */
    public long beginMs;

    /** 单元结束时间，毫秒。 */
    public long endMs;

    /** 所属强调组；null 表示不参与强调动画。 */
    public EmphasisGroup emphasisGroup;

    /** 该字在强调组内的序号，用于逐字错峰。 */
    public int emphasisCharIndex;

    /** 单元宽度。 */
    public float width() {
        return x1 - x0;
    }

    /** 单元时长，毫秒。 */
    public long durationMs() {
        return Math.max(0L, endMs - beginMs);
    }
}
