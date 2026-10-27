# Link Lyric

一款在游戏内显示音乐歌词的模组。

## 支持的数据源

| 数据源 | 提供形式 | 进度 | 仓库链接 | 安装链接 |
|---|---|---|---|---|
| Salt Player | 插件 | ✅ | [Link-MC](https://github.com/beipuo/Link-MC) | [Link-MC](https://github.com/beipuo/Link-MC) |
| Windows SMTC | — | ⬛️ | ⬛️ | ⬛️ |
| MPD / foobar | — | ⬛️ | ⬛️ | ⬛️ |
| 本地 LRC/TTML | — | ⬛️ | ⬛️ | ⬛️ |

⬛️ = 尚未实现。当前唯一可用的数据源是 Salt Player 插件桥接。

## 构建

```powershell
# 只跑内核单测（不需要下载 Minecraft）
.\gradlew.bat :common:test

# 完整构建
.\gradlew.bat build

# 启动客户端
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
```

详细说明见 [构建与环境](docs/BUILD.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [设计目标](docs/DESIGN.md) | 分层理由、仓库结构、七道构建守卫 |
| [内核结构](docs/KERNEL.md) | `core` 包职责与能力模型 |
| [渲染层](docs/RENDERING.md) | 悬浮歌词排版、逐字染色、字内渐变、强调动画、拖动交互 |
| [协议](docs/PROTOCOL.md) | 适配器与模组之间的进程间契约（v2） |
| [构建与环境](docs/BUILD.md) | 分支版本矩阵、本地 JDK、代理、两个易踩的坑 |
| [进度与待办](docs/ROADMAP.md) | 已完成项、26.1.2 渲染架构迁移方案、适配器顺序建议 |
| [致谢与许可证](docs/CREDITS.md) | 上游项目、与上游的差异、按文件分许可说明 |

## 参考源码与致谢

**applemusic-like-lyrics（AMLL）** — <https://github.com/amll-dev/applemusic-like-lyrics>
**Lyricon**（Tomakino / Proify）— <https://github.com/tomakino/lyricon>
**HyperMusicCover** — <https://github.com/zyl6932/HyperMusicCover>

取用的具体参数与代码见 [致谢与许可证](docs/CREDITS.md)。

### 许可证

本仓库**按文件分许可**：

| 范围 | 许可证 |
| --- | --- |
| 除下表所列文件外的全部代码 | MIT（见 `LICENSE`） |
| `effect/EmphasizeGlowEffect`、`EmphasisGroup`、`EmphasisLayout`、`WaveLiftEffect`、`EmpEasing` | Apache-2.0（派生自 Lyricon） |

上游许可证：

| 项目 | 许可证 |
| --- | --- |
| applemusic-like-lyrics | AGPL-3.0 |
| Lyricon | Apache-2.0 |
| HyperMusicCover | AGPL-3.0 |

派生文件在 `NOTICE` 中逐个点名，Apache-2.0 全文在 `third_party/lyricon/LICENSE`。
