package com.linklyric.common.hud;

import com.linklyric.common.style.GlyphColors;
import com.linklyric.common.style.GlyphSpan;
import com.linklyric.common.style.GradientColorizer;
import com.linklyric.common.style.LyricStyleConfig.Weight;
import com.linklyric.common.hud.effect.EmphasisGroup;
import com.linklyric.common.hud.effect.EmphasisLayout;
import com.linklyric.common.hud.effect.LyricEffectEngine;
import com.linklyric.common.hud.effect.UnitTransform;
import com.linklyric.core.lyric.LyricLine;
import com.linklyric.core.lyric.LyricWord;

import java.util.List;

/**
 * 歌词文本的绘制。
 *
 * <p>两层真实颜色：整行先以未唱色铺满，再把已唱段用已唱色重画。
 * 两层共用同一份坐标与抬升表，只有裁剪区不同，因此像素严格重合，
 * 得到的是真正的变色而非「蒙一层灰」。
 *
 * <p>逐字绘制时前沿交给顶点插值：一个字画一次，左右两端给不同颜色，
 * GPU 逐像素插值，因此前沿可以落在任意亚像素位置，
 * 不受裁剪只能整像素量化的限制。
 */
final class LyricPainter {

    /**
     * 抬升与阴影所需的裁剪余量。
     *
     * <p>引用共享常量而非各写一份：底板留白（渲染器）与这里的裁剪框
     * 必须用同一个值，否则抬起的字顶部会被裁掉一块。
     */
    private static final int LIFT_HEADROOM = LyricGeometry.LIFT_HEADROOM;
    private static final int SHADOW_HEADROOM = LyricGeometry.SHADOW_HEADROOM;

    /** 阴影相对正文的水平偏移，与 SDF 渲染侧一致。 */
    private static final float SHADOW_OFFSET_PX = 1f;

    /**
     * 裁剪判定的容差（像素）。
     *
     * <p>特效给出的位移是亚像素的，字形右缘因此可能比视口多出不到 1 像素。
     * 若不容忍这一丁点，那个字会被当成越界而裁剪，
     * 裁掉的正是它自己向外偏移的阴影。
     */
    private static final float CLIP_TOLERANCE = 1f;

    /**
     * 单字缩放的生效阈值。
     *
     * <p>与 {@code FloatingLyricRenderer} 里整块 {@code lineScale} 的判定
     * 用同一个数（0.001）。两处必须一致：块已经按 1.0004 缩放了、
     * 字却因为阈值更小而没缩，切换动画的边界帧上会看到字块内部错位。
     *
     * <p>阈值本身的作用是让「没有特效动缩放」这个最常见的情形稳定走
     * 无变换分支——那条分支与加缩放之前逐字节等价，不能被浮点噪声打破。
     */
    private static final float SCALE_EPSILON = 0.001f;

    /**
     * 单字旋转的生效阈值，单位度。
     *
     * <p>9 像素的字转 0.01 度，最远角点的位移约
     * {@code (9/2)·sin(0.01°) ≈ 0.0008} 像素——比一个像素小三个数量级，
     * 光栅化与肉眼都分辨不出。取这个量级既能漏掉无意义的浮点残值，
     * 又不会吞掉真实的微小旋转。
     */
    private static final float ROTATE_EPSILON_DEGREES = 0.01f;

    /**
     * 透明度改写的生效阈值。
     *
     * <p>差小于千分之一时换算到 8 位 alpha 上不足 0.26 个色阶，
     * 取整后与不透明完全相同。因此这个区间内直接跳过改写，
     * 让默认值走「颜色数组原样传递」的路径。
     */
    private static final float ALPHA_EPSILON = 0.001f;

    /**
     * 未唱文字的透明度。
     *
     * <p>引用共享常量：整段路径（渲染器）与本类必须一致，
     * 否则同一帧里「未唱」会出现两种浓度。
     */
    private static final float UNSUNG_ALPHA = LyricGeometry.UNSUNG_ALPHA;

    /**
     * 强调上浮幅度，相对字号。
     *
     * <p>用于估算动画需要的垂直空间；实际幅度由特效按组参数缩放。
     */
    private static final float EMPHASIS_LIFT_EM = 0.03f;

    /** 动画强度下限：空间再紧也保留一点动感。 */
    private static final float MIN_STRENGTH = 0.2f;

    /**
     * 单字字符串缓存。
     *
     * <p>绘制原语收的是 {@code String}，而逐字循环手上只有 {@code char}。
     * 若每帧每字 {@code substring(i, i+1)}，一句 30 字在 60fps 下就是
     * 每秒 1800 个短命字符串，全部产生在渲染热路径上。
     *
     * <p>ASCII 走定长数组（覆盖绝大多数正文），其余码点按需建并入表。
     * 歌词用字高度收敛，实际条目数很少，缓存不会无限增长。
     */
    private static final String[] ASCII_GLYPHS = new String[128];

    private static final java.util.Map<Character, String> OTHER_GLYPHS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String glyphOf(char c) {
        if (c < 128) {
            String cached = ASCII_GLYPHS[c];
            if (cached == null) {
                cached = String.valueOf(c);
                ASCII_GLYPHS[c] = cached;
            }
            return cached;
        }
        return OTHER_GLYPHS.computeIfAbsent(c, String::valueOf);
    }

    private final FloatingLyricRenderer.Painter painter;
    private final Weight weight;
    private final boolean shadow;
    private final LyricEffectEngine effects;
    /** 描边宽度，像素；0 表示不描边。 */
    private final float strokeWidth;
    /** 描边颜色，含 alpha。 */
    private final int strokeArgb;
    /** 是否启用交界羽化；false 时前沿为硬切。 */
    private final boolean feather;

    /**
     * 中英混排校正参数。
     *
     * <p>存整个 {@code MixedScript} 而不是拆成三个字段，理由与
     * {@code LyricStyleConfig} 里收成一个值对象相同：这三项永远
     * 一起生效或一起不生效，而 {@link #mixedActive} 是它们唯一的判据。
     *
     * <p>构造时**只读一次** {@code active()} 并缓存成布尔量，
     * 不让逐字循环每字再问一遍。这个量在本对象的生命周期内不会变
     * （{@code LyricPainter} 每帧新建，见 {@code textPainter()}），
     * 因此缓存不引入任何陈旧风险。
     */
    private final boolean mixedActive;

    /** CJK 字形缩放系数；{@link #mixedActive} 为 false 时恒为 1。 */
    private final float cjkScale;

    /** CJK 字形的基线上抬量，像素；{@link #mixedActive} 为 false 时恒为 0。 */
    private final float cjkLift;

    /**
     * 施加混排校正时的合并载体。
     *
     * <p>**为什么不能就地改 {@code UnitTransform}**：特效层返回的是
     * {@link LyricEffectEngine} 内部复用的单例，而 {@link UnitTransform#IDENTITY}
     * 更是全局共享常量。若把 CJK 缩放直接乘进那个对象，
     * 下一个拉丁字拿到的是同一个已被改写的实例——表现为"英文也被缩小了"，
     * 且只在混排行上出现，极难归因。
     *
     * <p>因此这里备一个本对象私有的临时对象，每个需要校正的字
     * 把特效给的值抄进来、乘上系数，再交给绘制。抄写有成本，但只在
     * 混排行上发生，且省掉的是一次错误的共享写入。
     */
    private final UnitTransform mixedScratch = new UnitTransform();

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow) {
        this(painter, weight, shadow, null);
    }

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow,
                 LyricEffectEngine effects) {
        this(painter, weight, shadow, effects, 0f, 0);
    }

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow,
                 LyricEffectEngine effects, float strokeWidth, int strokeArgb) {
        this(painter, weight, shadow, effects, strokeWidth, strokeArgb, true);
    }

    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow,
                 LyricEffectEngine effects, float strokeWidth, int strokeArgb,
                 boolean feather) {
        this(painter, weight, shadow, effects, strokeWidth, strokeArgb, feather,
                com.linklyric.common.style.MixedScript.OFF);
    }

    /**
     * 全参形态。
     *
     * <p>老的七参构造器保留并转调这里、传 {@code MixedScript.OFF}：
     * 既有测试与诊断代码不必跟着改，而那些调用点本就只关心
     * 字重/阴影/描边，不该被混排参数牵连。
     */
    LyricPainter(FloatingLyricRenderer.Painter painter, Weight weight, boolean shadow,
                 LyricEffectEngine effects, float strokeWidth, int strokeArgb,
                 boolean feather, com.linklyric.common.style.MixedScript mixed) {
        this.painter = painter;
        this.weight = weight;
        this.shadow = shadow;
        this.effects = effects;
        this.strokeWidth = strokeWidth;
        this.strokeArgb = strokeArgb;
        this.feather = feather;
        // mixed 为 null 时按关闭处理：这条路径的调用方可能来自旧代码，
        // 不该因为少传一个参数就抛 NPE 让整帧歌词消失。
        boolean active = mixed != null && mixed.active();
        this.mixedActive = active;
        // 不启用时把两个量钉成恒等值，而不是保留 mixed 里的数：
        // 这样绘制路径上没有任何"虽然为 false 但值不是 1"的残余状态，
        // 阅读代码时不必回头确认那个布尔量。
        this.cjkScale = active ? mixed.scale() : 1f;
        this.cjkLift = active ? mixed.baselineLift() : 0f;
    }

    /**
     * 绘制一行卡拉 OK 歌词。
     *
     * @param lifts 逐字抬升量；null 表示退回整段绘制
     */
    void karaoke(String visible, int textX, int textY, float sungWidth,
                 int viewportLeft, int viewRight, float[] lifts,
                 float opacity, int sungColor, int unsungColor,
                 LyricLine line, long positionMs) {
        int alpha = Math.round(255f * opacity);
        int sung = withAlpha(sungColor, alpha);
        int unsung = withAlpha(unsungColor, Math.round(alpha * UNSUNG_ALPHA));

        float edge = textX + sungWidth;

        if (lifts == null) {
            wholeLine(visible, textX, textY, sungWidth,
                    viewportLeft, viewRight, sung, unsung);
            return;
        }

        List<LyricWord> words = line.words();
        EmphasisGroup[] groups = emphasisGroups(words);
        int lineWidth = painter.textWidth(visible);

        // 每帧只算一次全局参数；强度按可用行高收缩，避免抬升把字挤出裁剪区。
        if (effects != null) {
            effects.beginFrame(lineWidth, sungWidth, textSizePx(), positionMs,
                    motionStrength(textY));
        }

        int[] charWord = mapCharsToWords(words, visible.length());
        int cursor = 0;
        int wordCursor = 0;
        int currentWord = -1;
        for (int i = 0; i < visible.length(); i++) {
            char codeUnit = visible.charAt(i);
            String ch = glyphOf(codeUnit);
            int charX = textX + cursor;
            // 用单字步进而不是重新度量整段前缀；结果与 textWidth(ch) 等价。
            int charWidth = painter.charWidth(codeUnit);
            cursor += charWidth;
            int charRight = charX + charWidth;

            int word = charWord[i];
            if (word != currentWord) {
                currentWord = word;
                wordCursor = 0;
            }

            UnitTransform transform = UnitTransform.IDENTITY;
            if (effects != null && word >= 0) {
                LyricWord source = words.get(word);
                EmphasisGroup group = groups[word];
                transform = effects.prepare(
                        i, 1, charX - textX, charRight - textX,
                        EmphasisLayout.isCjk(ch), source.startMs(), source.endMs(),
                        group, wordCursor);
            }
            wordCursor++;

            if (charRight <= viewportLeft || charX >= viewRight) {
                continue;
            }

            // 传入整行宽度：过渡宽度按行宽比例推算，
            // 使长句与短句的羽化观感一致。
            // 段坐标保持**绝对**（相对行首）：染色计算必须用统一坐标系，
            // 否则每个字各自归零会让前沿函数在每个字边界处跳变。
            // 转成字形局部坐标由绘制层负责，这里额外给出行锚点。
            GlyphSpan[] spans = GradientColorizer.spansForChar(
                    charX, charRight, edge, sung, unsung, lineWidth, feather);
            glyph(ch, charWidth, charX, textY, lifts[i] + transform.dy,
                    viewportLeft, viewRight, spans, transform);
        }
    }

    /** 整行绘制并按已唱宽度分两段上色，用于没有逐字抬升的情况。 */
    private void wholeLine(String visible, int textX, int textY, float sungWidth,
                           int viewportLeft, int viewRight,
                           int sungColor, int unsungColor) {
        int lineWidth = painter.textWidth(visible);
        int solidWidth = Math.max(0,
                Math.min((int) Math.floor(sungWidth), lineWidth));
        colorLayer(visible, textX, textY,
                viewportLeft, viewRight, textX, lineWidth, unsungColor);
        if (solidWidth > 0) {
            colorLayer(visible, textX, textY,
                    viewportLeft, viewRight, textX, solidWidth, sungColor);
        }
    }

    /**
     * 绘制单个字，左右两端可不同色，可带位移、提亮、光晕、缩放与旋转。
     *
     * <p>分工：位移（{@code dx}/{@code dy}）进坐标、渐变由染色给出、
     * 缩放与旋转走姿态栈。位移与姿态都动字形，但层次不同：
     * 位移是屏幕空间的平移，不改变字形朝向；缩放与旋转以字形中心为原点，
     * 不影响相邻字的位置。颜色一侧则完全由染色负责，两条链路互不干扰。
     *
     * <p>缩放必须走姿态栈而不能改字号：字号一变就要重新取字形，
     * 而图集是按原字号烘焙的，改字号等于换一套字形，
     * 要么糊、要么得重烘焙整批。姿态变换只是改顶点矩阵，字形始终取自图集。
     *
     * @param lift      抬升量，像素；绘制语义为 {@code y - lift}
     * @param spans     染色段，坐标以 {@code charX} 为原点
     * @param transform 本单元的呈现变换；{@code dx} 在此消费，{@code dy} 已由调用方并入 {@code lift}
     */
    private void glyph(String ch, int charWidth, int charX, int textY, float lift,
                       int viewportLeft, int viewRight, GlyphSpan[] spans,
                       UnitTransform transform) {

        float drawX = charX + transform.dx;

        // ——— 中英混排校正：先与特效变换合成，再一起进姿态栈 ———
        //
        // 这里不能"再压一层栈"来单独缩放 CJK：两层缩放都以字形中心为原点时
        // 数学上等价于一次相乘，但会多做 2 次矩阵复制（一句 30 字每帧 60 次），
        // 而且嵌套的旋转中心会随第一层的缩放漂移——外层先缩放后，
        // 内层算出的 centerX/centerY 已经是缩放前的坐标，旋转就会偏。
        // 因此合成到一个 {@code UnitTransform} 里，只压一次栈。
        //
        // <h2>关键取舍：只缩墨迹，不动步进</h2>
        //
        // 缩放走姿态栈，因此**只改变字形墨迹的大小，不改变它的格子宽度**。
        // 这是有意的，也是本实现与"改字号"路线的分水岭：
        //
        // <ul>
        //   <li>{@code sungWidth} 是一个**行内像素坐标**，它由
        //   {@code targetWidthAt} 逐字累加 {@code painter.textWidth(part)} 得到；</li>
        //   <li>{@code LyricWrap.wrap} 的 {@code startPx} 与
        //   {@code Segment.localSung(globalSung) = clamp(globalSung - startPx, 0, width)}
        //   建立在同一套未缩放的度量上；</li>
        //   <li>{@code LyricProgress.charStarts} 也是。</li>
        // </ul>
        //
        // 一旦让 CJK 的**步进**也缩 4%，上面三条的坐标系就与实际绘制脱节：
        // 第 20 个字处会累计偏差约 7 像素，染色前沿会跑到字外面，
        // 折行断点也会落在错误的位置。而这两个症状都在**开启校正后**才出现，
        // 与"校正本身在改几何"极易混淆。
        //
        // 因此这里刻意只缩墨迹：字距仍按原步进排，中文与相邻英文之间
        // 会出现每边约 0.18 像素的额外空隙（96% 下），一行 20 字累计约 7 像素
        // 的"视觉右缩"——但**每个字的落点是准的**，`sungWidth`、折行、
        // 逐字抬升全部不需要改动。这是拿一点缝隙换全部坐标系的不变性。
        //
        // 只有在 mixedActive 为真**且**这个字是 CJK 时才合成。
        // 关闭态下 mixedActive 恒为 false，整段合成逻辑不被进入，
        // 传给下面的仍是特效原样返回的那个对象引用——这是"结构性零变化"
        // 的落点：不是参数恰好等于 1，而是代码根本没走这里。
        UnitTransform effective = transform;
        float effectiveLift = lift;
        // 是否真的改了基线（不只是缩放）。裁剪框的纵向公式只在这时才切换，
        // 理由见下面未变换分支里的注释。
        boolean baselineAdjusted = false;
        if (mixedActive && ScriptRuns.needsCorrection(ch.charAt(0))) {
            // 复制而不是就地改：transform 可能是特效引擎内部复用的单例，
            // 也可能是全局共享的 UnitTransform.IDENTITY。参见 mixedScratch 的注释。
            mixedScratch.dx = transform.dx;
            mixedScratch.dy = transform.dy;
            mixedScratch.alpha = transform.alpha;
            mixedScratch.scale = transform.scale * cjkScale;
            mixedScratch.rotate = transform.rotate;
            mixedScratch.bloom = transform.bloom;
            mixedScratch.glowRadius = transform.glowRadius;
            mixedScratch.glowAlpha = transform.glowAlpha;
            effective = mixedScratch;
            // 基线校正并入 lift：绘制语义是 y - lift，正 lift 上抬。
            // 并入而不是另开一个 translate，理由同上——lift 已经参与了
            // 旋转中心与裁剪框的计算，另开一条位移会让那两处看不到它。
            effectiveLift = lift + cjkLift;
            baselineAdjusted = cjkLift != 0f;
        }

        float scale = effective.scale;
        float rotate = effective.rotate;
        boolean scaling = Math.abs(scale - 1f) > SCALE_EPSILON;
        boolean rotating = Math.abs(rotate) > ROTATE_EPSILON_DEGREES;
        boolean transformed = scaling || rotating;

        // 变换中心 = 字形盒中心。
        //
        // 横向取**步进宽度**的中点，不是墨迹中点：步进宽度是这个字的格子，
        // 逐字围绕格子中心缩放时字距保持不变；改用墨迹中点则每个字的重心
        // 各不相同，同一行放大后会显得疏密不均。
        //
        // 纵向取**文字盒**中心，盒顶就是传入的 textY。依据有两条：
        // 一是 {@code drawTextLifted} 的语义就是 {@code y - lift}，
        // 二是一旦进入裁剪分支，本类的裁剪框一直以 textY 作为文字上缘
        // （见下方 enableScissor 的参数）——若 textY 是基线，抬起的字顶部
        // 早就被那个框切掉了。盒高取平台真实行高：本类已经把它当文字盒高度用，
        // 这里沿用同一个定义，旋转中心才不会与裁剪框错开。
        //
        // lift 必须计入：平台层是在自身的姿态栈里 translate(0, -lift) 之后
        // 才绘制的，字形最终落在 textY - lift 处，而外层变换作用在**最终屏幕坐标**上。
        // 漏掉这一项，字被抬起时就会绕自己下方那一点旋转，观感是「边转边飘」。
        //
        // 这里用 effectiveLift 而不是原始 lift：混排校正把 CJK 的基线偏移
        // 并进了抬升量（见上），若中心仍按未校正的 lift 算，
        // 校正后的字会绕自己下方那一点缩放，观感是「缩的时候往下沉」。
        float boxHeight = textSizePx();
        float centerX = drawX + charWidth * 0.5f;
        float centerY = textY - effectiveLift + boxHeight * 0.5f;
        // 保留浮点坐标，不取整。
        //
        // 9px 位图字形在非整数位置会糊，所以过去这里必须 Math.round；
        // 代价是 1px 以内的位移会被整成 0 或 1，动画看起来一跳一跳。
        // 改用 SDF 后字形在任意小数位置都锐利，取整只带来跳变，因此去掉。
        // 整数平移仍由下面的 scissor 判断保证不越界。
        //
        // 裁剪边界分两条路径算。
        //
        // 未变换时**严格沿用原式**：默认值（scale=1、rotate=0）下像素结果
        // 必须与加变换之前逐字节一致，而原式里的 left/right 是夹过视口的整数、
        // 纵向裁剪框另有固定留白。换成统一公式哪怕只差半个像素，
        // 也会平移所有既有画面——这条判据没法靠「看起来差不多」验收。
        //
        // 变换时代之以**变换后的外接矩形**。这一步不能省：裁剪框若仍按未变换的
        // charWidth 给，旋转过去的那两个角就落在框外，
        // 表现为「转起来的字被斜切成直边」。
        boolean full;
        int scissorLeft;
        int scissorTop;
        int scissorWidth;
        int scissorHeight;
        if (transformed) {
            // 矩形绕自身中心旋转，外接矩形半宽 = (w·|cos| + h·|sin|)/2，
            // 半高把两项对调。缩放先作用于盒，所以宽高先乘 scale。
            float radians = (float) Math.toRadians(rotate);
            float cos = Math.abs((float) Math.cos(radians));
            float sin = Math.abs((float) Math.sin(radians));
            float grow = Math.max(0f, scale);
            float margin = outwardMargin();
            float boxW = (charWidth + 2f * margin) * grow;
            float boxH = (boxHeight + 2f * margin) * grow;
            float halfW = (boxW * cos + boxH * sin) * 0.5f;
            float halfH = (boxW * sin + boxH * cos) * 0.5f;

            int inkLeft = (int) Math.floor(centerX - halfW);
            int inkRight = (int) Math.ceil(centerX + halfW);
            int left = Math.max(inkLeft, viewportLeft);
            int right = Math.min(inkRight, viewRight);
            if (right - left <= 0) {
                return;
            }
            full = inkLeft >= viewportLeft - CLIP_TOLERANCE
                    && inkRight <= viewRight + CLIP_TOLERANCE;
            scissorLeft = left;
            scissorWidth = right - left;
            // 纵向同样要放宽：旋转把字向上顶出原来那圈留白，
            // 缩放则按比例放大墨迹。裁剪框取外接矩形的上下缘。
            scissorTop = (int) Math.floor(centerY - halfH);
            scissorHeight = (int) Math.ceil(centerY + halfH) - scissorTop;
        } else {
            int left = Math.max((int) Math.floor(drawX), viewportLeft);
            int right = Math.min((int) Math.ceil(drawX + charWidth), viewRight);
            if (right - left <= 0) {
                return;
            }

            // 「这个字完整在视口内吗」的判定必须带容差。
            //
            // 实测故障：每句的**最后一个字**没有阴影，倒数第二个正常。
            // 原因是末字坐标带着特效的亚像素位移（实测 468.10135），
            // 右缘 477.10135 比整数 viewRight=477 大了 0.1 像素，
            // 于是只有它被判为越界而进入裁剪分支；又因为阴影是向右偏
            // 1 像素画的，裁剪框右边界一压，被切掉的正好是阴影。
            //
            // 0.1 像素不是「越界」，是取整与浮动位移的噪声。留 1 像素容差
            // 即可覆盖亚像素位移，同时不影响真正越界的字被裁掉。
            full = drawX >= viewportLeft - CLIP_TOLERANCE
                    && drawX + charWidth <= viewRight + CLIP_TOLERANCE;
            // 真正越界的字仍要裁，但裁剪框要含住向外伸出的墨迹，
            // 否则滚动时边缘那圈阴影与描边会被切成直边。
            int margin = (int) Math.ceil(outwardMargin());
            scissorLeft = Math.max(left - margin, viewportLeft);
            scissorWidth = right - left + margin;
            if (baselineAdjusted) {
                // 纵向裁剪框的顶必须跟着基线校正走。
                //
                // 上抬量最大 3px，而这块框只有「行高 + 抬升余量(2) + 阴影余量(4)」，
                // 若顶仍按未校正的 textY 算，抬起的 CJK 墨迹上缘会落到框外——
                // 表现是中文的顶部（尤其是阴影）被切平，而拉丁不受影响。
                //
                // 这条**只在真的校正了基线时**才走：没有校正时必须严格沿用
                // 下面那条原式，否则光是 `Math.round(textY - lift)` 里的取整
                // 就会让所有带抬升的字平移一个像素，把"零变化"打破。
                int shift = Math.abs(Math.round(cjkLift));
                scissorTop = Math.round(textY - effectiveLift) - LIFT_HEADROOM - shift;
                scissorHeight = painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM
                        + shift * 2;
            } else {
                scissorTop = textY - LIFT_HEADROOM;
                scissorHeight = painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM;
            }
        }

        // 颜色侧的变换：提亮与透明度都在这一步落进段颜色。
        GlyphSpan[] shown = shade(spans, effective.bloom, effective.alpha);

        // 姿态只在真的需要时压栈。
        //
        // 一句 30 字、每帧一次，无变换时白做 60 次矩阵栈操作；而压栈/出栈
        // 本身不是免费的：平台层每次都要复制一份 4×4 矩阵。阈值与
        // {@code FloatingLyricRenderer} 里整块 lineScale 的判定一致（0.001），
        // 两处若各用一套阈值，同一帧里会出现「块已缩放、字还没跟上」的边界帧。
        Pose2D pose = null;
        if (transformed) {
            // 绕字形中心的变换：三步写法，与 Pose2D 注释里的标准模式一致。
            // 先到中心、再旋转缩放、最后移回来——直接调用 rotate 会绕屏幕左上角转，
            // 字会沿一个很大的圆弧甩出去。
            pose = painter.pose();
            pose.pushPose();
            pose.translate(centerX, centerY);
            if (rotating) {
                pose.rotate(rotate);
            }
            if (scaling) {
                pose.scale(scale, scale);
            }
            pose.translate(-centerX, -centerY);
        }

        try {
            if (!full) {
                painter.enableScissor(scissorLeft, scissorTop, scissorWidth, scissorHeight);
            }

            // 一律按常规字重绘制。
            //
            // 加粗已移除：MC 的粗体是同一字形右移一个纹素再画一遍，
            // 在 9px 位图字形上两份轮廓只错开 1px，叠出 8px，
            // 看起来就是同一个字重了一次。逐字绘制路径没有原版
            // drawString 那样能让两份合成一个观感的余地。
            if (shown.length == 1 && shown[0].isSolid()) {
                // 单色：走平台原有的整字绘制，省掉逐顶点写入。
                // 传浮点横坐标，与渐变路径保持同样的亚像素精度。
                painter.drawGlyphGradient(ch, drawX, textY, effectiveLift,
                        new GlyphColors(shown[0].startColor(), shown[0].endColor()), shadow,
                        effective.glowRadius, effective.glowAlpha, strokeWidth, strokeArgb);
            } else {
                // 段边界落在前沿函数的折点上，段内插值即精确。
                //
                // originX 必须传 charX：spans 是以 charX 为原点生成的
                // （spansForChar(charX, charRight, ...)），减去它正好得到
                // 字形局部坐标。若改传 textX（行首），每个字会多偏移
                // 自身之前的累计宽度，被推出裁剪区而看起来完全透明。
                painter.drawGlyphSpans(ch, drawX, textY, effectiveLift, shown,
                        Math.round(charX),
                        shadow, effective.glowRadius, effective.glowAlpha,
                        strokeWidth, strokeArgb);
            }
        } finally {
            // 出栈放在 finally：绘制抛异常时若漏了 popPose，
            // 之后所有字的矩阵都会带着这个变换，一帧之内画面全毁。
            if (!full) {
                painter.disableScissor();
            }
            if (pose != null) {
                pose.popPose();
            }
        }
    }

    /**
     * 把提亮与透明度一次性写进段颜色。
     *
     * <p>两件事合在一个循环里：它们都是「只改颜色、不动几何」，各走一遍
     * 就是多一次数组分配、多一轮对象创建，而这里在逐字热路径上，
     * 一句 30 字就是 30 次。提亮改 RGB、透明度改 A，通道互不重叠，
     * 因此先做哪一步都得到同一结果。
     *
     * <p>恒等时必须返回<b>原数组引用</b>，不能照抄一份值相同的副本：
     * 默认值（{@code bloom=0}、{@code alpha=1}）下像素结果必须与加这两个
     * 字段之前逐字节一致，而「返回同一引用」是这条判据唯一可机械验证的形态。
     */
    private static GlyphSpan[] shade(GlyphSpan[] spans, float bloomLevel, float alpha) {
        boolean lift = bloomLevel > 0f;
        boolean fade = alpha < 1f - ALPHA_EPSILON;
        if ((!lift && !fade) || spans.length == 0) {
            return spans;
        }
        float t = Math.min(1f, bloomLevel);
        GlyphSpan[] out = new GlyphSpan[spans.length];
        for (int i = 0; i < spans.length; i++) {
            GlyphSpan s = spans[i];
            int start = s.startColor();
            int end = s.endColor();
            if (lift) {
                start = liftTowardWhite(start, t);
                end = liftTowardWhite(end, t);
            }
            if (fade) {
                start = scaleAlpha(start, alpha);
                end = scaleAlpha(end, alpha);
            }
            out[i] = new GlyphSpan(s.startX(), s.endX(), start, end);
        }
        return out;
    }

    /**
     * 按比例缩放 ARGB 的 alpha 通道，RGB 不动。
     *
     * <p><b>乘</b>而不是覆盖：段颜色里的 alpha 已经带上了整行不透明度
     * （{@code karaoke} 生成段时乘过 {@code opacity} 与未唱浓度），
     * 直接赋值会让「整行半透明」与「这个字被特效淡出」互相抹掉——
     * 表现为调了单元透明度之后，整行的透明度设置失效。
     */
    private static int scaleAlpha(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * factor);
        a = a < 0 ? 0 : Math.min(a, 255);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /**
     * 保留旧的整字提亮入口，供整行绘制路径使用。
     *
     * <p>逐字路径已改走 {@link #shade}（提亮与透明度同一个循环），
     * 这里只剩 {@link GlyphColors} 这一种入参形态——整行路径的
     * {@code wholeLine} 与 {@code colorLayer} 不经过 {@code glyph()}，
     * 因此拿不到 {@code UnitTransform}，也没有逐单元透明度可言。
     *
     * <p>提亮只作用于字形本身的颜色：沿 RGB 三通道向 255 插值、保留原 alpha，
     * 字形边缘仍取自图集，所以字始终清晰。它与外发光分工不同：
     * 提亮让<b>笔画变白</b>，光晕让<b>轮廓外散开一圈</b>。
     * 只有提亮时看起来像字被漂白，配上光晕才是被点亮。
     */
    private static GlyphColors bloom(GlyphColors colors, float level) {
        if (level <= 0f) {
            return colors;
        }
        float t = Math.min(1f, level);
        return new GlyphColors(liftTowardWhite(colors.left(), t),
                liftTowardWhite(colors.right(), t));
    }

    private static int liftTowardWhite(int argb, float t) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        r = Math.round(r + (255 - r) * t);
        g = Math.round(g + (255 - g) * t);
        b = Math.round(b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 为整行算出强调分组；词序列为空时返回全 null。 */
    private static EmphasisGroup[] emphasisGroups(List<LyricWord> words) {
        if (words == null || words.isEmpty()) {
            return new EmphasisGroup[0];
        }
        return EmphasisLayout.groups(words);
    }

    /**
     * 把行的每个字符映射到它所属的词序号。
     *
     * <p>词按出现顺序铺在行文本上，与渲染时逐字遍历的顺序一致。
     * 对不上的位置填 -1，渲染端据此跳过特效。
     */
    private static int[] mapCharsToWords(List<LyricWord> words, int charCount) {
        int[] map = new int[charCount];
        java.util.Arrays.fill(map, -1);
        if (words == null) {
            return map;
        }
        int at = 0;
        for (int w = 0; w < words.size(); w++) {
            int length = words.get(w).text().length();
            for (int k = 0; k < length && at < charCount; k++) {
                map[at++] = w;
            }
        }
        return map;
    }

    /**
     * 动画强度。
     *
     * <p>HUD 的可用高度未必容得下完整的抬升：把可用高度与动画所需高度之比
     * 压到 0.2 以上，空间越小幅度越轻，字不会被推出边界，同时仍保留一点动感。
     */
    private float motionStrength(int textY) {
        int available = painter.screenHeight() - textY - LyricGeometry.LINE_HEIGHT;
        if (available <= 0) {
            return MIN_STRENGTH;
        }
        float needed = textSizePx() * (1f + EMPHASIS_LIFT_EM);
        if (needed <= 0f) {
            return 1f;
        }
        return Math.min(1f, Math.max(MIN_STRENGTH, available / needed));
    }

    /** 当前字号，像素；取不到时按行高估算。 */
    private float textSizePx() {
        return painter.lineHeight() > 0 ? painter.lineHeight() : LyricGeometry.LINE_HEIGHT;
    }

    /** 用指定颜色画一层文字，只显示 {@code [clipLeft, clipLeft + clipWidth)} 这一段。 */
    private void colorLayer(String visible, int textX, int textY,
                            int viewportLeft, int viewRight,
                            int clipLeft, int clipWidth, int color) {
        int left = Math.max(clipLeft, viewportLeft);
        int right = Math.min(clipLeft + clipWidth, viewRight);
        if (right - left <= 0) {
            return;
        }
        boolean needScissor = left > textX || right < textX + painter.textWidth(visible);
        if (needScissor) {
            enableScissor(left, textY, right - left);
        }
        painter.drawText(styled(visible), textX, textY, color, shadow);
        if (needScissor) {
            painter.disableScissor();
        }
    }

    /** 绘制一段文本，使用当前字重与阴影设置。 */
    void text(String text, int x, int y, int color) {
        painter.drawText(styled(text), x, y, color, shadow);
    }

    /** 绘制抬升后的文本；单字不加样式包裹，避免文本序列碎片化。 */
    void textLifted(String text, int x, int y, float lift, int color) {
        painter.drawTextLifted(styled(text), x, y, lift, color, shadow);
    }

    /** 按缩放比例绘制文本，用于副行。 */
    void textScaled(String text, int x, int y, float scale, int color) {
        painter.drawTextScaled(styled(text), x, y, scale, color, shadow);
    }

    /**
     * 字形向文本盒之外伸出的最大距离（像素）。
     *
     * <p>取阴影偏移、描边宽度、光晕半径三者之和：它们各自向外扩，
     * 没有互相包含的关系，所以要相加而不是取最大。
     */
    private float outwardMargin() {
        float m = SHADOW_OFFSET_PX;
        if (strokeWidth > 0f) {
            m += strokeWidth;
        }
        // 光晕半径属于特效的瞬时状态，不在这里体现：
        // 它每帧都变，而字形的裁剪决策若跟着闪变，
        // 滚动时边缘会一帧裁一帧不裁。宁可边缘光晕被裁掉一点，
        // 也不要让裁剪框本身抖动。
        return m;
    }

    private void enableScissor(int left, int textY, int width) {
        painter.enableScissor(left, textY - LIFT_HEADROOM, width,
                painter.lineHeight() + LIFT_HEADROOM + SHADOW_HEADROOM);
    }
    /**
     * 整行绘制的文本准备。
     *
     * <p>返回原文。曾经在这里按字重拼 {@code §l}/{@code §r}，
     * 加粗移除后不再有任何修饰；保留这个方法是因为调用点较多，
     * 且未来若需要加回样式，这里是唯一的插入点。
     */
    private String styled(String text) {
        return text;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}