# Link Salt Music 进程间协议（草案 v1）

SPW 插件与 Minecraft 模组运行在两个独立 JVM 中，必须走进程间通信。本文定义两者的最小契约。

## 传输

- 默认地址：`ws://127.0.0.1:35400/spw`
- **不要绑定 `0.0.0.0`**。跨机场景需另行加配对码鉴权，参考 `salt-remote-plugin`。
- 角色：Minecraft 侧起服务端，SPW 插件作为客户端连接并推送。理由是插件生命周期短于游戏，连不上就退避重连，游戏侧无需探测。
- 保底方案：插件每 100ms 覆盖写一个状态 JSON 文件，模组轮询。零端口、零权限，适合用来先把链路跑通。

## 设计纪律

**只同步锚点和事件，不同步推导结果。**

当前行索引、逐字进度、滚动位置全部由模组侧从「锚点 + 播放位置」算出。任何「再推一个当前行索引」的设计都会制造两份可能不一致的真相。

## 消息类型

### `snapshot` — 完整曲目快照

换曲时发送一次完整状态，收到即整体替换。

```json
{
  "v": 1,
  "type": "snapshot",
  "seq": 42,
  "track": {
    "title": "曲名",
    "artist": "艺术家",
    "album": "专辑",
    "durationMs": 243000,
    "coverId": "sha1-abbrev"
  },
  "positionMs": 12340,
  "playing": true,
  "lyric": {
    "source": "spw-lyrics",
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

`lyric.lines` 允许为空（无歌词）。`words` 为空数组时，模组侧按整行一个单元处理，渲染层无需分支。

### `state` — 状态事件

低频，仅三种情况发送。

```json
{ "v": 1, "type": "state", "seq": 43, "playing": false, "positionMs": 45120 }
```

`positionMs` 表示发送瞬间的真实位置。模组收到后无条件重锚。

### `command` — 模组下发控制

```json
{ "v": 1, "type": "command", "id": 7, "action": "playAt", "index": 8 }
```

| action | 参数 | 说明 |
|---|---|---|
| `playPause` | — | 播放/暂停切换 |
| `next` | — | 下一首 |
| `previous` | — | 上一首 |
| `seek` | `positionMs` | 跳转 |
| `playAt` | `index` | 播放队列第 N 首 |

插件侧必须做**幂等**：命令带递增 `id`，重复 `id` 直接丢弃。`playAt` 若宿主无按索引接口，由插件用队列索引循环 `next()` 兜底。

### `ack` — 命令回执

```json
{ "v": 1, "type": "ack", "id": 7, "ok": true }
```

模组侧在收到 `ack` 前应保持「选中态」但**不点亮播放态**，避免界面先跳、数据后跟的撕裂感。超过 2 秒未回执则回滚选中态。

## 版本兼容

- 字段只增不删；删除或改变语义必须递增 `v`。
- 模组收到不认识的 `v` 时**降级为仅显示当前行与翻译**，放弃逐字与样式，不得崩溃。
- 已知破坏性变更：SPW 1.18 → 1.19 的 Workshop API 断裂过一次（`SPW-Lyrics` 被迫保留旧分支）。插件侧需声明目标 SPW 版本。

## 尺寸与性能

- 单条载荷上限 4 MiB（`Protocol.MAX_PAYLOAD_CHARS`）。
- 封面不传原图，插件侧压到 128×128 后按 `coverId` 引用。
- 位置外推超过 30 秒未收到任何消息即视为陈旧，模组侧静默隐藏。
