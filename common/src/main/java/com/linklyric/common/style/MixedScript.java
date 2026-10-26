package com.linklyric.common.style;

/**
 * 中英混排的度量与基线校正参数。
 *
 * <h2>要解决的是什么</h2>
 *
 * <p>Link Lyric 全部走 MC 字库，而 MC 的拉丁字母与 CJK 字符来自**不同的字体资源**：
 * 拉丁走 {@code BitmapProvider} 的位图字形（8px 高、x-height 约 5px），
 * 中文走 {@code UnihexProvider}（unifont，16 行位数据、按 0.5 缩到 8px）。
 * 两者的 {@code advance} 由各自 provider 给出（见 {@code SdfGlyphProvider.bake}），
 * 中文稳定 9px，拉丁窄字只有 3..6px，于是同一行里中文字形既更宽也更"顶格"。
 *
 * <p>度量本身**是准的**：{@code GlyphLookup.advance} 与 {@code SdfGlyphProvider}
 * 都取自同一个原版 {@code GlyphInfo}，所以宽度累加、折行断点、染色前沿都对得上。
 * 缺的是**视觉大小的统一**——中文墨迹几乎顶满 9px 行高，拉丁大写字母只占 7px 上下，
 * 中英混排时中文看起来就是"胖一圈"。
 *
 * <h2>为什么校正量是可配置的，而不是一个算出来的常量</h2>
 *
 * <p>过去这里本可以直接写死一个系数（例如借鉴 DangoMusic 的 {@code CJK_SCALE = 0.96f}），
 * 但那个值是在 Android 的 {@code Canvas.drawText} 上凭实机观感调出来的：
 * 它的字体、字号、栅格化路径与 MC 的位图 + SDF 管线**没有一处相同**。
 * 照抄一个在别的渲染栈上凑出来的数字，等于把别人的观感当成自己的事实。
 *
 * <p>本项目也没有能力在 {@code common} 层"算出"这个值：真正决定观感的是
 * 字形位图的墨迹包围盒，而那份像素只有平台层拿得到（见 {@code SdfGlyphProvider.bake}
 * 里 {@code BitmapProvider$Glyph} 与 {@code UnihexProvider$Glyph} 两条取像素路径）。
 * {@code common} 里能算的只有 {@code advance} 与 {@code GlyphInfo.getAdvance}，
 * 那是**格宽**，不是墨迹高度，推不出"看起来差多少"。
 *
 * <p>所以这里选择**第二种做法**：把校正量做成可配置参数，默认取中性值
 * （缩放 100%、基线位移 0），由用户在游戏里对着实际画面调。
 * 这比编一个"有依据"的数字诚实——依据若是错的，还不如明确说"这是旋钮"。
 *
 * <h2>默认值为什么是 96 而不是 100</h2>
 *
 * <p>{@code DEFAULT_SCALE_PERCENT = 96} 只是滑条的**起点**，不是"已验证正确的值"。
 * 取它的理由是可辩护的：9px 字号下缩 4% 等于墨迹四周各收约 0.18px，
 * 亚像素量级，SDF 管线在任意小数位置都锐利（见 {@code LyricPainter.glyph} 里
 * "保留浮点坐标，不取整"的注释），所以这个量级既不会糊，又足以改变
 * "谁看起来更大"的判断。但它**没有**经过实机验证——见 {@link #baselineShiftPx}。
 *
 * <h2>为什么开关与两个量放在一个 record 里</h2>
 *
 * <p>{@link LyricStyleConfig} 已经是 24 字段的 record，每个 {@code with*} 方法
 * 都要把全部字段原样列一遍。若把开关、缩放、基线拆成三个独立字段，就是
 * 24 处调用点各多三个实参、三处夹取、三份持久化键——而这三者语义上
 * **永远一起生效或一起不生效**（单独缩中文却不校基线，正是"改了但更难看"的形态）。
 * 收成一个值对象后，{@code LyricStyleConfig} 只多一个字段，
 * 构造点只多一个实参，且"这三项是一件事"在类型上就成立。
 *
 * @param enabled         是否启用混排校正。**默认 false**：开启会改变所有含中日韩字符的
 *                        歌词的像素位置，而既有 6 项守卫里 {@code verifyPreviewGeometry}
 *                        与逐像素快照类测试都依赖稳定几何
 * @param cjkScalePercent 中日韩字形的缩放百分比，85..100。
 *                        100 表示不缩放，与关闭在几何上等价
 * @param baselineShiftPx 中日韩字形的垂直校正，像素，-3..3，**正数表示上抬**。
 *                        0 表示不校正——这是默认值，因为 CJK 与拉丁在 MC 字库里
 *                        的**真实**基线差在本项目里无法测定（见类注释）
 */
public record MixedScript(boolean enabled, int cjkScalePercent, int baselineShiftPx) {

    /**
     * 缩放百分比下限。
     *
     * <p>压在 85：9px 字号缩到 85% 后墨迹高约 7.6px，
     * 与拉丁大写字母的 7px 已经接近，再往下中文就会明显小于英文，
     * 那不再是"校正大小差"，而是"把中文调小"。
     */
    public static final int MIN_SCALE_PERCENT = 85;

    /**
     * 缩放百分比上限。
     *
     * <p>100 是中性值，超过它就是在放大中文。留出这个值而不是把上限压到 99，
     * 是为了让滑条能回到"完全不缩"的位置——用户想验证开启前后的差别时，
     * 需要一个能退到恒等的刻度，而不是只能关整个开关再看。
     */
    public static final int MAX_SCALE_PERCENT = 100;

    /**
     * 默认缩放百分比，96。
     *
     * <p>起点值，未经实机验证，理由见类注释。刻意写在这里而不是散在
     * {@code LyricStyleConfig.DEFAULT} 的字面量里：滑条的默认刻度与
     * "恢复到默认"的行为必须读同一个常量。
     */
    public static final int DEFAULT_SCALE_PERCENT = 96;

    /**
     * 基线位移的对称范围，像素。
     *
     * <p>±3：9px 行高下位移 3px 已经是"这个字明显浮起来/沉下去"，
     * 再大就不是校正而是错位。取整数而非小数，是因为这个量本来就只能靠
     * 肉眼在游戏里对齐，半个像素的区别在实机上判断不出来，
     * 却会让配置文件里出现一串难读的小数。
     */
    public static final int MIN_BASELINE_SHIFT_PX = -3;
    public static final int MAX_BASELINE_SHIFT_PX = 3;

    /**
     * 默认基线位移，0 = 不做任何垂直校正。
     *
     * <h2>为什么这里是 0 而不是一个算出来的值</h2>
     *
     * <p>类注释里说过"算不出真实的墨迹差"，这里要说清**为什么连推导都不做**。
     *
     * <p>{@code common} 层手上能拿到的纵向量只有 {@code RawGlyph.top/bottom}，
     * 而那两个值由 {@code SdfGlyphBaker.bake} 用两条**不同**的公式产生：
     * 位图分支传的是 {@code (7f - sheet.getTop()) / pixelScale}，
     * unifont 分支传的是一个常量 {@code 16f}、{@code scale} 取 {@code 0.5f}。
     * 两条公式里都掺了 provider 自己的网格约定（位图的 oversample、unifont 的 16 行），
     * 而最终落笔位置还要经 {@code SdfGlyphWriter} 对 {@code box} 的解释。
     * 拿这条链条上的中间量去反推"基线下沉了几像素"，中间任何一环的理解偏差
     * 都会直接变成一个错误的常量，而且这个错误**不会报错**——只会让中文整体偏掉。
     *
     * <p>所以这里取 0：不校正时行为与不开启完全一致，用户实测后自己给值。
     */
    public static final int DEFAULT_BASELINE_SHIFT_PX = 0;

    /**
     * 关闭态。
     *
     * <p>两个校正量保留默认值而不是随手写 100/0：这样用户在界面上打开开关时，
     * 看到的是"建议起点"而不是"看起来没生效的中性值"。
     * 开关与量分离，才有"打开就看到效果"的体验。
     */
    public static final MixedScript OFF =
            new MixedScript(false, DEFAULT_SCALE_PERCENT, DEFAULT_BASELINE_SHIFT_PX);

    public MixedScript {
        // 夹取收口在构造器：配置文件是玩家可以手改的，界面滑条也可能因
        // 版本回退而越界。收在这里，就不必在绘制热路径上再判一次。
        // 这与 LyricStyleConfig 构造器里那批 clamp 是同一条约定。
        cjkScalePercent = clamp(cjkScalePercent, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
        baselineShiftPx = clamp(baselineShiftPx,
                MIN_BASELINE_SHIFT_PX, MAX_BASELINE_SHIFT_PX);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 缩放系数，1 表示不缩放。
     *
     * <p>返回 float 而不是让调用方每次除一遍 100：绘制在逐字循环里，
     * 一句 30 字、60fps 就是每秒 1800 次除法。更重要的是，
     * {@code 100 / 100f} 恰好等于 {@code 1.0f}，于是"百分比为 100"这件事
     * 在调用方那里可以直接用 {@code == 1f} 判断，不需要引入容差。
     */
    public float scale() {
        return cjkScalePercent / 100f;
    }

    /**
     * 基线校正的抬升量，像素；正值表示上抬。
     *
     * <p>返回"抬升量"而不是"位移量"，是为了与 {@code LyricCanvas.drawTextLifted}
     * 的 {@code y - lift} 语义直接对齐：调用方把它加进已有的 lift 即可，
     * 不需要在下游再取一次负号。符号在这里转一次，
     * 比让每个调用点各自记得"这个字段是反的"可靠。
     */
    public float baselineLift() {
        return baselineShiftPx;
    }

    /**
     * 校正是否真的会改变绘制。
     *
     * <p>这是**结构性零变化**的判据，不是断言。两种情况返回 false：
     * 开关关着，或者开关开着但两个校正量都停在中性刻度
     * （缩放 100% 且基线 0）。后者让用户可以把滑条拖回恒等位置，
     * 此时绘制路径与从未开启完全一致——代码不进那条分支，
     * 而不是"进了分支但参数恰好是 1"。
     *
     * <p>这个区分在浮点上是有意义的：绕字形中心缩放 1.0 倍虽然数学上是恒等，
     * 但仍会压一次姿态栈、仍会走 {@code LyricPainter.glyph} 里按外接矩形
     * 放宽裁剪框的分支。那条分支算出的裁剪框与未变换分支的公式不同
     * （见该方法的注释），两者在边界像素上可能差一个像素。
     * 因此"中性值也等同关闭"必须由这个布尔量提前挡掉。
     */
    public boolean active() {
        return enabled && (cjkScalePercent != MAX_SCALE_PERCENT || baselineShiftPx != 0);
    }

    public MixedScript withEnabled(boolean value) {
        return new MixedScript(value, cjkScalePercent, baselineShiftPx);
    }

    public MixedScript withCjkScalePercent(int value) {
        return new MixedScript(enabled, value, baselineShiftPx);
    }

    public MixedScript withBaselineShiftPx(int value) {
        return new MixedScript(enabled, cjkScalePercent, value);
    }
}
