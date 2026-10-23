# Link Lyric 进程间协议（草案 v2）

适配器与 Minecraft 模组运行在两个独立进程中，必须走进程间通信。本文定义最小契约。

协议**不假设任何具体播放器**。Salt Player、SMTC、MPD 的适配器都只是同一个协议的实现。

## 传输

- 默认地址：`ws://127.0.0.1:35400/lyric`
- **不要绑定 `0.0.0.0`**。跨机场景需另加配对码鉴权。
- 角色：模组起服务端，适配器作为客户端接入。理由是适配器生命周期短于游戏，连不上就退避重连，游戏侧无需探测。
- 保底方案：适配器每 100ms 覆盖写状态 JSON 文件，模组轮询。零端口、零权限，用于先把链路跑通。

## 设计纪律

**只同步锚点和事件，不同步推导结果。**

当前行索引、逐字进度、滚动位置全部由模组侧从「锚点 + 播放位置」算出。任何「再推一个当前行索引」的设计都会制造两份可能不一致的真相。

**歌词不必来自播放器。** `inline_lyric` 缺失时，模组自行向歌词源查询。

## 握手

连接建立后适配器立即发送，声明身份与能力。能力在整个会话内不变。

```json
{
  "v": 2,
  "type": "hello",
  "sourceId": "windows-smtc",
  "displayName": "Windows 媒体控制",
  "capabilities": ["TRACK_METADATA", "PLAYBACK_POSITION", "TRACK_DURATION", "TRANSPORT"]
}
```

`capabilities` 取值见 `Capability` 枚举：`TRACK_METADATA`、`PLAYBACK_POSITION`、`TRACK_DURATION`、`INLINE_LYRIC`、`WORD_LEVEL_LYRIC`、`QUEUE`、`PLAY_BY_INDEX`、`SEEK`、`TRANSPORT`、`ARTWORK`。

模组对未知能力名**忽略而非报错**，以便旧模组兼容新适配器。

## 消息类型

### `snapshot` — 曲目快照

换曲时发送完整状态，收到即整体替换。

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

`lyric` 可整体省略（适配器无歌词能力时）。`words` 为空数组时按整行一个单元处理，渲染层无需分支。

`durationMs` 是歌词匹配的一等参数，不是可选项。同一首歌的录音室版与 Live 版标题完全相同，只有时长能区分。

### `state` — 状态事件

低频，仅在播放态变化或 seek 时发送。

```json
{ "v": 2, "type": "state", "seq": 43, "playing": false, "positionMs": 45120 }
```

`positionMs` 是发送瞬间的真实位置，模组收到后按漂移阈值决定是否重锚。

### `command` — 模组下发控制

```json
{ "v": 2, "type": "command", "id": 7, "action": "playAt", "index": 8 }
```

| action | 参数 | 所需能力 |
|---|---|---|
| `playPause` | — | `TRANSPORT` |
| `next` / `previous` | — | `TRANSPORT` |
| `seek` | `positionMs` | `SEEK` |
| `playAt` | `index` | `PLAY_BY_INDEX` |

模组**只对声明了对应能力的适配器下发改命令**。适配器必须幂等：命令带递增 `id`，重复 `id` 直接丢弃。

`playAt` 在适配器无原生按索引接口时，由适配器用队列索引循环 `next()` 兜底。

### `ack` — 回执

```json
{ "v": 2, "type": "ack", "id": 7, "ok": true }
```

模组在收到 `ack` 前保持「选中态」但**不点亮播放态**，避免界面先跳、数据后跟的撕裂感。超过 2 秒未回执则回滚选中态（`Protocol.COMMAND_TIMEOUT_MS`）。

## 版本兼容

- 字段只增不删；删除或改变语义必须递增 `v`。
- 模组收到不认识的 `v` 时**降级为仅显示整行歌词与翻译**，放弃逐字与样式，不得崩溃。
- v1 → v2 是破坏性变更：快照从扁平字段改为嵌套 `track` 对象，并新增 `sourceId` / `capabilities`。

## 尺寸与性能

- 单条载荷上限 4 MiB（`Protocol.MAX_PAYLOAD_CHARS`）。
- 封面不传原图，适配器压到 128×128 后按 `coverId` 引用。
- 位置外推超过 30 秒未收到任何消息即视为陈旧，模组静默隐藏。

## 歌词源接口

模组侧的内置歌词源走同一套 `LyricProvider` 抽象。以 LRCLIB 为例：

- 精确查询：`GET /api/get?artist_name=&track_name=&album_name=&duration=`，`duration` 用于区分版本
- 模糊搜索：`GET /api/search?q=`
- 返回字段含 `syncedLyrics`（LRC 格式）与 `plainLyrics`，均无则为纯音乐

LRC 是行级格式，需要转成 `LyricTimeline`。逐字时间轴应优先取 TTML（AMLL 歌词库）而非 LRC。
