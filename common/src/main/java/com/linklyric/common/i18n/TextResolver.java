package com.linklyric.common.i18n;

/**
 * 语言键解析器。
 *
 * <p>{@code :common} 不依赖任何游戏的文本组件，因此把翻译动作
 * 通过这个极窄接口交给平台层。未注入实现时原样返回键名，
 * 便于测试与无界面环境。
 */
@FunctionalInterface
public interface TextResolver {

    /** 原样返回键名的默认实现。 */
    TextResolver IDENTITY = key -> key;

    /** 把语言键解析为当前语言下的文本。 */
    String resolve(String key);
}
