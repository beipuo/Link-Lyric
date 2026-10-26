package com.linklyric.common.hud;

/**
 * 极简二维变换栈，避免在 common 里依赖任何渲染库。
 *
 * <p>坐标系与游戏内 HUD 一致：原点在左上，<b>x 向右、y 向下</b>。
 * 角度单位为度。后加的 {@link #rotate}、{@link #shear} 都作用在
 * <b>当前原点</b>上，不额外提供中心点参数——原因见各自注释。
 */
public interface Pose2D {

    /** 恒等变换：压栈、出栈与变换都不做任何事。 */
    Pose2D IDENTITY = new Pose2D() {
        @Override
        public void pushPose() {
        }

        @Override
        public void popPose() {
        }

        @Override
        public void translate(float x, float y) {
        }

        @Override
        public void scale(float x, float y) {
        }
    };

    void pushPose();

    void popPose();

    void translate(float x, float y);

    void scale(float x, float y);

    /**
     * 绕<b>当前原点</b>旋转，单位为度，<b>顺时针为正</b>（与屏幕 y 向下配套）。
     *
     * <h2>正方向的依据</h2>
     *
     * <p>底层走 {@code com.mojang.math.Axis.ZP.rotationDegrees(degrees)}，
     * 它等价于 JOML 的 {@code new Quaternionf().rotationZ(toRadians(degrees))}
     * （这条等价关系已由 {@code javap -c} 反汇编 {@code Axis} 的静态初始化块确认，
     * 不是推测）。而 {@code rotationZ(θ)} 把单位基映射为
     * {@code (1,0) -> (cos θ, sin θ)}：正角把 +X 轴推向 +Y 轴。
     *
     * <p>游戏内 HUD 的正交投影是
     * {@code Matrix4f.setOrtho(0, guiWidth, guiHeight, 0, ...)}
     * （见 {@code GameRenderer} 的 GUI 渲染分支），第四个参数是 bottom=0、
     * 第三个是 top=guiHeight，所以 <b>+Y 在屏幕上是向下</b>。
     * 于是「右方向转下方向」就是肉眼看到的顺时针。
     *
     * <p>实测数据（绕 (200,200) 旋转，屏幕像素坐标）：
     * {@code +90°} 把中心右侧的点 (300,200) 送到 (200,300)，
     * 即从 3 点钟转到 6 点钟——顺时针；{@code -90°} 送到 (200,100)，
     * 逆时针。两端平台层因此<b>直接用正号转调，不需要取负</b>。
     *
     * <h2>为什么只绕原点，不提供中心点参数</h2>
     *
     * <p>变换栈的语义必须与 {@link #translate}、{@link #scale} 对齐：
     * 它们都作用在当前坐标系上，调用方自己决定原点在哪。若这里额外加一个
     * 「绕某点旋转」的便利方法，栈上就会出现两套原点概念（栈原点与世界点），
     * 而世界点那一套在前一层变换不是恒等时会算错——嵌套时几乎必然如此。
     *
     * <p>绕任意中心点旋转的标准写法是三步，项目里已经用了三次同一模式：
     * {@code FloatingLyricRenderer} 第 697-700 行（整块按 {@code lineScale} 缩放，
     * 以块心为原点）与第 835-839 行（间奏提示同样处理），
     * 以及 {@link LyricCanvas#drawTextScaled} 第 165-169 行（以 {@code (x,y)} 为原点）：
     * <pre>{@code
     * pose.pushPose();
     * pose.translate(cx, cy);   // 移到中心，此时原点在中心
     * pose.rotate(degrees);     // 于是这一步自然绕中心
     * pose.translate(-cx, -cy); // 移回去
     * ... 绘制 ...
     * pose.popPose();
     * }</pre>
     *
     * <p>照此写法即可。反过来，若省掉前后两次 translate 直接调用本方法，
     * 旋转会绕屏幕左上角发生，字块会沿一个很大的圆弧甩到别处——
     * 表现为「字号/角度一变，歌词就游走」，且因为不抛异常，很难归因。
     *
     * <h2>为什么这里有默认空实现</h2>
     *
     * <p>本接口有 {@link #IDENTITY} 常量、可能被第三方实现与测试替身实现。
     * 新增抽象方法会让这些实现全部编译失败，而它们中的多数根本不需要旋转。
     * 这与 {@link LyricCanvas} 里那批 {@code default} 方法（见该文件第 20-35 行
     * {@code charWidth} 的注释）是同一约定：<b>默认实现必须退化，不许报错</b>。
     *
     * <p>但退化只保证「不崩」，不保证「生效」。两端的真实实现
     * （{@code LinkLyricFabric} 与 {@code LinkLyricNeoForge} 的 {@code pose()}）
     * 必须逐个覆写，否则旋转会静默失效——这正是
     * {@link LyricCanvas#drawTextScaled} 注释（第 147-159 行）记下的那种坑：
     * 默认实现看着像实现了，实际把参数丢掉，只有一端漏覆写就变成两端表现不一致。
     *
     * @param degrees 角度，顺时针为正
     */
    default void rotate(float degrees) {
    }

    /**
     * 以<b>当前原点</b>为基准斜切。
     *
     * <p>参数语义：{@code x} 表示「y 每增加 1，x 平移多少倍」，
     * {@code y} 表示「x 每增加 1，y 平移多少倍」，即变换后
     * <pre>{@code
     * x' = x + shear.x * y
     * y' = y + shear.y * x
     * }</pre>
     * 两者都为 0 时是恒等变换。正负号随屏幕坐标走：{@code shear.x = 1}
     * 会让原点<b>下方</b>的内容向右推，上方的内容向左推。
     *
     * <h2>与 rotate 的关系</h2>
     *
     * <p>斜切不保角，常用来做「斜体」或环形排布时的透视压缩。
     * 它与 {@link #rotate} 一样只绕原点，绕任意中心的写法同样是
     * translate → shear → translate 回来。
     *
     * <h2>为什么是 default</h2>
     *
     * <p>同 {@link #rotate}：保持 {@link #IDENTITY} 与既有实现可编译，
     * 默认退化而不是抛异常；两端平台层各自覆写。
     *
     * @param x y 每增加 1 时 x 的平移倍数
     * @param y x 每增加 1 时 y 的平移倍数
     */
    default void shear(float x, float y) {
    }
}
