# 内核结构

`common` 模块的 `core` 包只放纯数据与纯计算，可在没有游戏运行时的 JVM 里直接测试。

返回 [README](../README.md)。

## 包结构

```
common/src/main/java/com/linklyric/core/
├── capability/   Capability, CapabilitySet     能力声明与集合运算
├── lyric/        LyricTimeline, LyricLine, LyricWord, LyricTimingFix
├── sync/         PlaybackClock, PlayerSnapshot, TrackIdentity
├── style/        LyricStyle                    多样式几何预设（尚未接入渲染）
└── protocol/     Protocol                      跨进程契约常量
```

`core` 不引用任何 Minecraft 或加载器类型。编排与渲染在 `common`，平台适配在 `fabric` / `neoforge`。

这条界线的实际用途是保证测试可跑：`common:test` 里 500 余个用例全部不需要启动游戏。一旦有 Minecraft 类型漏进 `core`，整条测试链立刻失效。

## 能力模型

**能力探测是架构核心。** 没有能力声明，代码会散落在无数个 `if (source == ...)` 里。

| 数据源 | 元数据 | 进度 | 逐字 | 队列 | 按索引播放 | 成本 |
|---|---|---|---|---|---|---|
| Salt Player 插件 | ✓ | ✓ | ✓ TTML | ✗ | ✗ | 低，纯 Java |
| Windows SMTC | ✓ | ✓ 可外推 | ✗ | ✗ | ✗ | 中，需非 Java 组件 |
| MPD / foobar | ✓ | ✓ | ✗ | ✓ | ✓ | 中，纯 Java |
| 本地 LRC/TTML | — | ✗ | 部分 | ✗ | ✗ | 极低 |

Salt Player 的「队列 ✗ / 按索引播放 ✗」是实测定论，不是待办：插件 API 的 `WorkshopApi.Playback` 只暴露 `play/pause/previous/next/seekTo` 与当前曲目，**没有队列访问入口**。

能力声明已贯通到快照（`SnapshotParser` 解析 → `PlayerSnapshot.capabilities()`）。渲染层目前只读取 `PLAYBACK_POSITION`——没有它无法逐字推进，只能整行显示。`QUEUE` 等其余能力尚无消费方，因为依赖它们的界面还不存在。
