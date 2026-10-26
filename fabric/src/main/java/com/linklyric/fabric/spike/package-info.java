/**
 * 一次性可行性探针（spike）。**不参与生产路径。**
 *
 * <h2>这里在回答什么</h2>
 *
 * <p>只有一个问题：在 {@code HudRenderCallback} 期间把渲染目标切到离屏
 * framebuffer、画东西、再切回来采样，这条链路在 1.21.1 上是否成立。
 *
 * <p>成立则「整屏弥漫光晕」（离屏累积 → 多级降采样模糊 → 加色合成）才有实现基础；
 * 不成立则整条路线要降级。所以探针只验证链路，不追求画面。
 *
 * <h2>边界</h2>
 *
 * <p>除 {@code LinkLyricFabric.onHudRender} 与 {@code FabricLyricCommand} 两处
 * 各一行转发之外，没有任何代码依赖本包。
 * 触发入口是 {@code /linklyric spike glow}，默认关闭；删掉本包与那两行，
 * 项目行为立刻回到原样。
 *
 * <h2>为什么不在 {@code render/} 下</h2>
 *
 * <p>本包最初放在 {@code com.linklyric.fabric.render.spike}，实测会让构建失败：
 * {@code common/build.gradle.kts} 的 {@code verifyMirroredRender} 要求
 * {@code fabric/render} 与 {@code neoforge/render} 逐字镜像，
 * 未登记的文件会被判为「neoforge 缺少副本」并抛异常。
 *
 * <p>三条出路（放在镜像区外 / 改 {@code common/} 登记例外 / 给 neoforge 复制一份）
 * 里，只有第一条同时满足「不碰 common」与「不破坏守卫」。取舍写在 {@code README.md}。
 *
 * <h2>警告</h2>
 *
 * <p>本包代码**从未在真实游戏里运行过**（编写环境无法启动 Minecraft）。
 * 编译通过与运行时正确是两件事。每条结论的置信度都写在 {@code README.md} 里，
 * 那里明确标注了哪些属于「代码推断可确定」、哪些属于「需实机验证」。
 */
package com.linklyric.fabric.spike;
