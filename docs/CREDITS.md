# 参考源码、致谢与许可证

返回 [README](../README.md)。

## 参考源码与致谢

强调动画的算法、参数与缓动曲线来自下列项目。只列**代码、设计或数据确实进了本仓库**的项目，每条写明取用了什么。名称保持各作者自己的写法，不译。

### applemusic-like-lyrics（AMLL）

<https://github.com/amll-dev/applemusic-like-lyrics>

逐字 emphasize 动画的原始出处。取用其参数体系：

| 参数 | 取值 |
| --- | --- |
| 组时长门槛 | ≥1s |
| 组内字数 | CJK 不限；非 CJK 限 2–7 字 |
| 幅度 | `amount = powerCurve(du/2000) × 0.6`，`blur = powerCurve(du/3000) × 0.5`，上限 1.2 / 0.8 |
| 行末组放大 | 1.6 / 1.5 / 1.2 倍 |
| 逐字错峰跨度 | `du / 2.5 / 字符数` |
| 向两侧展开 | `0.03em × (n/2 - i)` |
| 上浮 | `0.025em` |
| 缓动 | 分段三次贝塞尔：前段 `cubic-bezier(0.2, 0.4, 0.58, 1)`，后段 `1 - cubic-bezier(0.3, 0, 0.58, 1)`，中点 0.5 |

### Lyricon（Tomakino / Proify）

<https://github.com/tomakino/lyricon>

本仓库移植时的直接参照实现（Apache-2.0），AMLL 的参数体系经其转写为 Android Canvas 版本。取用的工程细节：

- 贝塞尔结果预计算为 512 项查找表。逐字特效每帧调用上百次，直接求解是 CPU 热路径。
- 缓动的**脉冲形**语义：0 起、中点达峰、终点归零。这是本项目动画语义的直接来源。
- `WaveLiftEffect` 的脉冲参数：唱到后保持 `0.05em` 浮位，另叠提前 400ms、时长 `max(1s, 词时长) × 1.4` 的 `sin(πx)` 脉冲。
- 动画强度按「视图可用高度 / 完整动画所需高度」收缩，下限 0.2。

### HyperMusicCover

<https://github.com/zyl6932/HyperMusicCover>

提供通往 Lyricon 与上述致谢清单的线索。其歌词**渲染**由 Lyricon 的 `LyricView` 承担，本仓库未从中取用代码。其 `LyricParse` 的歌词数据归一化思路（译文按时间就近匹配、缺失时间的尾巴闭合）在对比中作为参照。

### 未取用代码的项目

**Cloth Config**、**owo-lib**、**Mekanism** 是颜色选择器设计阶段的参考。三者都强绑各自的 UI 框架，可借鉴的是交互细节而非实现。

## 与上游的差异

以下几处**刻意不照搬** AMLL：

- 不做缩放（AMLL 用 1.1 倍）。原版字体是位图图集，非整数倍缩放会让 9px 的字重新采样而发虚。
- 发光用原位提亮，不用光晕。四向偏移在 9px 字形上等于四份错位副本互相覆盖。
- CJK 组加 5 字、2.4 秒双上限。AMLL 的「词」天然限制在几个字，本项目按单字切分，必须自己设窗口。

## 许可证

本仓库**按文件分许可**，不是单一许可证：

| 范围 | 许可证 |
| --- | --- |
| 除下表所列文件外的全部代码 | MIT（见 `LICENSE`） |
| `effect/EmphasizeGlowEffect`、`EmphasisGroup`、`EmphasisLayout`、`WaveLiftEffect`、`EmpEasing` | Apache-2.0（派生自 Lyricon） |

派生文件在 `NOTICE` 中逐个点名，头部另注出处与修改内容（Apache-2.0 第 4 条要求）。Apache-2.0 全文在 `third_party/lyricon/LICENSE`。

上游许可证：

| 项目 | 许可证 |
| --- | --- |
| applemusic-like-lyrics | AGPL-3.0 |
| Lyricon | Apache-2.0 |
| HyperMusicCover | AGPL-3.0 |

Lyricon 的每个源文件都带 `Copyright 2026 Proify, Tomakino / Apache-2.0` 头，并在 `EmphasizeGlowEffect.kt` 中声明移植自 AMLL。本项目据此沿 Lyricon 的授权链行事。

**为什么不能整仓 MIT。** 派生文件的标识符（去关键字后）与上游重合度：`EmphasizeGlowEffect` 79%、`EmphasisGroup` 62%、`WaveLiftEffect` 52%，其中 `first_frame_x`、`spread_factor`、`lift_factor` 等并非通用命名。这些文件按「自有代码」处理会与事实不符。
