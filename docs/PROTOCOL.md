# Link Lyric 进程间协议（草案 v2）

适配器与 Minecraft 模组运行在两个独立进程中，必须走进程间通信。本文定义最小契约。

协议**不假设任何具体播放器**。Salt Player、SMTC、MPD 的适配器都只是同一个协议的实现。

## 传输

**当前实现是 HTTP 轮询，不是 WebSocket。**

- 地址：`GET http://127.0.0.1:35400/lyric`
- 角色：**适配器起服务端，模组作为客户端**。适配器生命周期短于游戏，
  它退出后模组只是拿不到数据，不需要任何探测或重连协议。
- 模组侧每 200ms 拉一次（`BridgePlaybackSource.POLL_INTERVAL_MS`），
  请求在独立后台线程执行，渲染帧内只有一次 volatile 读，零网络调用。
- 单次响应就是一份完整的 `snapshot` 对象（见下节），不承载消息序列。
- 连续失败 6 次（约 1.2 秒）才判定断连并清空显示，避免插件重载时闪烁。
- 响应体上限 4 MiB，超限按一次失败处理（`Protocol.MAX_PAYLOAD_CHARS`）。
- **不要绑定 `0.0.0.0`**。回环以外的任何地址都需要先加鉴权。
- 读取用 `readBounded` 而非 `readAllBytes`：端口任何本机进程都能监听，
  无上限读取等于把游戏堆交给对端决定。

早先的设计是 WebSocket 推送多消息。改成轮询的原因是：一首歌的位置只需
每秒级时效，200ms 轮询在回环上的开销可以忽略，却省掉了握手、心跳、
断线重连、背压这一整套状态机——而那套状态机是这类模组最容易出 bug 的地方。

原始推送设计里的四类消息（握手、状态、指令、回执）**不在本协议内**，
本文不描述它们。适配器不得依赖任何双向通道：模组只发 `GET`，
没有回执、没有订阅、没有连接状态。若将来需要双向控制，再按当时的
实际需要重新设计，比提前把一套状态机写进文档、再让代码永远追不上要诚实。

## 设计纪律

**只同步锚点和事件，不同步推导结果。**

当前行索引、逐字进度、滚动位置全部由模组侧从「锚点 + 播放位置」算出。任何「再推一个当前行索引」的设计都会制造两份可能不一致的真相。

**歌词不必来自播放器。** 快照里的 `lyric` 可整体省略，模组会另行向歌词源查询。

但**播放位置不能省**。`positionMs` 是驱动逐字染色与换行的唯一时间锚点，
缺失时会被当成 `0`，画面停在第一行不动。只提供歌词、不提供位置的软件
无法让本模组工作——它需要能报出「唱到哪一秒」，而不只是「唱什么」。

## 能力声明

能力随每份快照一起下发，不需要单独的握手消息：

```json
{
  "v": 2,
  "capabilities": ["TRACK_METADATA", "PLAYBACK_POSITION", "TRACK_DURATION", "TRANSPORT"]
}
```

取值见 `Capability` 枚举：`TRACK_METADATA`、`PLAYBACK_POSITION`、
`TRACK_DURATION`、`INLINE_LYRIC`、`WORD_LEVEL_LYRIC`、`QUEUE`、
`PLAY_BY_INDEX`、`SEEK`、`TRANSPORT`、`ARTWORK`。

模组对未知能力名**忽略而非报错**（`SnapshotParser.parseCapabilities` 里
`valueOf` 抛出的 `IllegalArgumentException` 被吞掉），旧模组因此能兼容新适配器。

能力在会话内应保持稳定。中途改变会让渲染层反复重建布局，虽然不会崩，
但会看到一次明显的跳动。

## 载荷

### 快照

每次 `GET /lyric` 的完整响应体。换曲、换歌词、播放态变化都体现为
同一份结构的字段变化——模组不需要区分「事件」与「状态」。

```json
{
  "v": 2,
  "type": "snapshot",
  "seq": 42,
  "track": {
    "title": "曲名",
    "artist": "艺术家",
    "album": "专辑",
    "durationMs": 243000
  },
  "positionMs": 12340,
  "playing": true,
  "lyric": {
    "wordLevel": true,
    "lines": [
      {
        "text": "原文",
        "translation": "翻译",
        "startMs": 12000,
        "endMs": 15000,
        "speaker": "",
        "words": [
          { "text": "原", "startMs": 12000, "endMs": 12400 }
        ]
      }
    ]
  }
}
```

`lyric` 可整体省略（适配器无歌词能力时）。`words` 为空数组时按整行一个单元处理，
渲染层无需分支。

`durationMs` 是歌词匹配的一等参数，不是可选项。同一首歌的录音室版与 Live 版
标题完全相同，只有时长能区分。`TrackIdentity.cacheKey()` 因此把时长量化成桶
（容忍 1 秒误差）后一并参与键值，避免不同版本共用同一份缓存。

### 歌词单独取一次

歌词体积远大于快照，且只在换曲时变化。模组每轮只对响应体里 `"lyric"`
的值做一次哈希当作指纹（`BridgePlaybackSource.lyricContentSignature`），
指纹变化才真正解析时间轴。

这里必须用**歌词内容的哈希**，不能用 `seq` 或整份响应：`seq` 每次位置上报
都会递增，用它会让时间轴每秒重建一次，渲染层的换行状态被反复重置，
高亮于是每帧直接赋成目标值、跳过平滑推进——观感就是「整行瞬间变色」。

## 版本兼容

- 字段只增不删；删除或改变语义必须递增 `v`。
- 模组收到不认识的 `v` 时**降级为仅显示整行歌词与翻译**，放弃逐字与样式，不得崩溃。
- v1 → v2 是破坏性变更：快照从扁平字段改为嵌套 `track` 对象，并新增 `sourceId` / `capabilities`。

## 尺寸与性能

- 单条载荷上限 4 MiB（`Protocol.MAX_PAYLOAD_CHARS`）。
- 封面不传原图，适配器压到 128×128 后按 `coverId` 引用。
- 当前**没有**「陈旧数据」判定。唯一的时间闸是断连阈值：连续失败 6 次
  （约 1.2 秒）即清空显示。曾有一个 `STALE_AFTER_MS` 常量承诺「超过 30 秒
  未响应则静默隐藏」，但对应机制从未实现，常量已删除——不要按那个行为设计适配器。

## 歌词源接口

**尚未实现。** 下面是设计的取数口径，不是现有代码。

计划中的内置歌词源将以 LRCLIB 为首个实现，例如：

- 精确查询：`GET /api/get?artist_name=&track_name=&album_name=&duration=`，`duration` 用于区分版本
- 模糊搜索：`GET /api/search?q=`
- 返回字段含 `syncedLyrics`（LRC 格式）与 `plainLyrics`，均无则为纯音乐

LRC 是行级格式，需要转成 `LyricTimeline`。逐字时间轴应优先取 TTML（AMLL 歌词库）而非 LRC。
