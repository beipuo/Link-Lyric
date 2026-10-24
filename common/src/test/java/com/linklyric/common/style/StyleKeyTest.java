package com.linklyric.common.style;

import com.linklyric.common.i18n.LangKeys;
import com.linklyric.core.style.LyricStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样式预设的语言键。
 *
 * <p>键名重复登记在两处：{@link LyricStyle} 在 {@code core} 层，
 * 不能引用 {@code common.i18n}；{@link LangKeys} 在 {@code common} 层，
 * 是界面代码引用的入口。两边必须指向同一批键，否则配置界面会显示原始键名。
 */
class StyleKeyTest {

    @Test
    void 两处样式键一致() {
        assertEquals(LangKeys.STYLE_SUBTITLE, LyricStyle.KEY_SUBTITLE);
        assertEquals(LangKeys.STYLE_ISLAND, LyricStyle.KEY_ISLAND);
        assertEquals(LangKeys.STYLE_SIDEBAR, LyricStyle.KEY_SIDEBAR);
        assertEquals(LangKeys.STYLE_HOTBAR, LyricStyle.KEY_HOTBAR);
        assertEquals(LangKeys.STYLE_FLOATING, LyricStyle.KEY_FLOATING);
    }

    /** 展示名必须是键而非可直接显示的文案。 */
    @Test
    void 展示名是语言键() {
        for (LyricStyle s : new LyricStyle[]{
                LyricStyle.SUBTITLE, LyricStyle.ISLAND, LyricStyle.SIDEBAR,
                LyricStyle.HOTBAR, LyricStyle.FLOATING}) {
            assertTrue(s.displayName().startsWith("linklyric."),
                    s.id() + " 的展示名应为语言键，实际=" + s.displayName());
        }
    }
}
