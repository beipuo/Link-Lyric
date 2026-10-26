package com.linklyric.common.hud;

/**
 * 歌词放置策略：把「本帧要画什么」映射成「每行画在哪」。
 *
 * <h2>接缝的位置</h2>
 *
 * <p>这是整个渲染流程里唯一知道「歌词以什么形状占据屏幕」的地方。
 * 下游（绘制、包围盒、底板、命中检测）只读 {@link LyricPlacement}，
 * 不关心它怎么算出来的。因此换一种排布——横排堆叠、弧形、环形——
 * 只需要换一个实现，不必碰绘制链路。
 *
 * <h2>为什么输入是 {@link PlacementInput} 而不是渲染器</h2>
 *
 * <p>策略若拿到渲染器，就能读写它的任何状态：偏移、包围盒、换行索引、
 * 平滑宽度。那等于把「几何」与「状态机」重新搅在一起，只是换了个文件名。
 * 输入摊成不可变 record 后，策略只能做纯计算，因而可以脱离游戏运行时验证
 * ——{@code common} 里带平台依赖的代码一行都进不来。
 *
 * <h2>实现方必须守的三条</h2>
 *
 * <p>一、**纯函数**。同样的输入必须给同样的输出，不得缓存跨帧状态；
 * 时序相关的量（行切换偏移、已唱宽度）由调用方算好后作为输入传进来。
 *
 * <p>二、**浮点计算、边界取整一次**。这一条改自「整数落位」。
 * 原先要求策略直接吐出整数像素，理由是「改动取整时机就会破坏逐像素快照」。
 * 加弧形与圆环之后这条站不住了：圆弧上的坐标是 {@code r·sin θ}，
 * 钟面上的方位角更是无理数，任何先取整的做法都会在策略内部丢一次精度，
 * 而丢掉的精度无法在绘制端还原。
 *
 * <p>新的契约是：策略内部全程浮点，**只在交给绘制原语的那一刻取整**。
 * 取整由调用方（渲染器）在调用点做，因为只有它知道下一个原语要 int 还是 float。
 * 默认策略不受影响——{@link LinearStackPlacement} 仍走
 * {@link LineLayout} 的整数算式，浮点化只是放宽了字段类型，
 * 「默认观感零变化」是构造上成立的（见 {@link LyricPlacement} 类注释）。
 *
 * <p>块信息（{@code blockTop / blockBottom / viewportLeft / viewWidth / textX}）
 * 仍必须是整数：它们要喂给 {@link FloatingLyricRenderer.Bounds}、
 * {@code fillRect} 与 {@code enableScissor}，全是 int 原语。
 *
 * <p>三、**不得为空**。有内容可画时必须给出至少一行，且第 0 项必须是
 * 主行（{@link LyricPlacement.PlacedLine#isPrimary()} 为 true）。
 * 绘制链路按这个约定找主行、算包围盒，缺了就是一片空白而没有任何报错。
 */
public interface PlacementStrategy {

    /**
     * 算出本帧的放置。
     *
     * @param in 只读输入，非 null
     * @return 放置结果，{@code lines} 非空
     */
    LyricPlacement place(PlacementInput in);
}
