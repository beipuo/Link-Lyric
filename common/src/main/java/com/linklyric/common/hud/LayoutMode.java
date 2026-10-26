package com.linklyric.common.hud;

import com.linklyric.common.i18n.LangKeys;

/**
 * 可选的排布形态。
 *
 * <h2>为什么是一个枚举而不是三个策略类引用</h2>
 *
 * <p>这个枚举要出现在 {@link com.linklyric.common.style.LyricStyleConfig} 里，
 * 也就是要**写进用户配置文件、并在两端配置界面上显示**。
 * 那两处只认可序列化的稳定标识：字符串或枚举名。若把策略实例直接存进样式，
 * 配置持久化就得存类名，而在混淆过的发布包里类名会被改写，
 * 用户升级后配置直接失效。
 *
 * <p>枚举名还有一个实际好处：配置文件里看到的是
 * {@code layout=ARC} 这样的明文，出问题时能直接判断用户选了什么，
 * 不必反查类名到实现的映射。
 *
 * <h2>为什么策略实例在这里分配</h2>
 *
 * <p>放在这里而不是让渲染器 switch：渲染器只该按枚举取一个实例，
 * 多一行都不该知道。三个实现都是无状态的（纯函数契约见
 * {@link PlacementStrategy}），因此可以安全地共享单例——
 * 每次新建只会带来 GC 压力，不带来任何隔离性。
 */
public enum LayoutMode {

    /** 横排堆叠：默认，逐字染色、滚动、折行全部可用。 */
    LINEAR(LangKeys.LAYOUT_LINEAR, "linear", new LinearStackPlacement()),

    /** 弧形：沿一段圆弧铺开，每行按切线方向倾斜；不滚动。 */
    ARC(LangKeys.LAYOUT_ARC, "arc", new ArcPlacement()),

    /** 环形：均布在圆周上，朝向为各点切线；不滚动，适合短句少行。 */
    RING(LangKeys.LAYOUT_RING, "ring", new RingPlacement());

    /** 界面显示名的语言键；与 {@link Align} 的 {@code labelKey} 同一约定。 */
    public final String labelKey;

    private final String id;
    private final PlacementStrategy strategy;

    LayoutMode(String labelKey, String id, PlacementStrategy strategy) {
        this.labelKey = labelKey;
        this.id = id;
        this.strategy = strategy;
    }

    /**
     * 配置持久化用的标识。
     *
     * <p>与枚举名分开是有意的：枚举名是代码里的标识，改一次重命名
     * 就会让所有老配置失效；而这个字符串一旦写进用户文件就不能再动，
     * 因此它必须独立于代码标识存在，且只允许新增、不允许修改。
     */
    public String id() {
        return id;
    }

    /**
     * 对应的放置策略实例；无状态，可安全共享。
     */
    public PlacementStrategy strategy() {
        return strategy;
    }

    /**
     * 由持久化标识反查；无法识别时退回 {@link #LINEAR}。
     *
     * <p>退回默认而不是抛异常：配置文件里出现未知值的最常见原因是
     * 用户从更新的版本退回了旧版本，或手动改坏了文件。
     * 这两种情形都不该让歌词整个不显示——最差的结果是回到横排，
     * 而那恰好是所有版本都支持的形态。
     */
    public static LayoutMode fromId(String value) {
        if (value != null) {
            for (LayoutMode mode : values()) {
                if (mode.id.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
        }
        return LINEAR;
    }

    /**
     * 该形态是否支持横向滚动。
     *
     * <p>弧形与环形把视口设成「完整覆盖整块」以绕开屏幕空间的轴对齐裁剪框
     * （理由见 {@link ArcPlacement} 类注释）。既然视口已经覆盖全部，
     * 再叠加滚动偏移就只会把字推出视口，于是这两种形态必须关闭滚动。
     *
     * <p>把这个判断集中在这里，而不是让每个策略各自在 {@code place} 里
     * 忽略 {@code in.scrolling()}：调用方也要知道——它决定要不要为这一帧
     * 维护滚动状态、要不要把 {@code scrolling} 传成 false。
     * 两处各判一次就会出现「渲染器以为不滚、策略以为滚」的错配。
     */
    public boolean scrolls() {
        return this == LINEAR;
    }
}
