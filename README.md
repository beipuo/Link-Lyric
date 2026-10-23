# Link Lyric

把任意播放器的歌词、歌单与播放状态同步到 Minecraft 的客户端模组。

不绑定单一播放器。Salt Player、Windows SMTC、MPD、本地歌词文件都只是**数据源**的一种实现；歌词可由播放器提供，也可由 LRCLIB 等**歌词源**外部补齐。不传输音频，Mod 不发声。

## 为什么这样分层

支持多个播放器（为 Spotify、网易云、foobar 各写适配器）是陷阱：适配器全要自己维护，每个都得逆向，永远追不完。

正确的通用化在协议层：模组只认一个开放的本机协议，谁想接入谁写适配器。你维护协议与渲染端，社区维护适配器。

更关键的是把**元数据源**与**歌词源**拆开：

```
播放器 ──► 元数据源 ──┐
                      ├──► LyricSession ──► 渲染层
歌词库 ──► 歌词源 ────┘
```

播放器不必自带歌词。这条拆分让「Spotify 不提供逐字歌词」不再等于「模组做不到逐字歌词」。

## 仓库结构

两个维度正交：**分支管 Minecraft 版本，模块管加载器**。

```
Link Lyric/
├── common/     纯 Java 内核，零 Minecraft / 加载器依赖
├── fabric/     Fabric 接线层
└── neoforge/   NeoForge 接线层
```

`common` 的硬性规则：**不得出现任何 Minecraft 或加载器 API 的 import**。它是「加第二个加载器只要几百行」和「两份平行代码库」的分界。

## 内核结构

```
common/src/main/java/com/linklyric/core/
├── capability/   Capability, CapabilitySet     能力声明与集合运算
├── source/       PlaybackSource, SourceProfiles 数据源抽象与各源能力预设
├── lyric/        LyricProvider, LyricTimeline, LyricCache, LyricLine, LyricWord
├── sync/         PlaybackClock, PlayerSnapshot, TrackIdentity
├── session/      LyricSession                  编排层，渲染层唯一入口
├── style/        LyricStyle                    四维正交的样式模型
├── ui/           ScrollModel                   三种滚动语义
└── protocol/     Protocol                      跨进程契约常量
```

**能力探测是架构核心。** 不做能力声明，代码会烂在无数个 `if (source == ...)` 里。

| 数据源 | 元数据 | 进度 | 逐字 | 队列 | 按索引播放 | 成本 |
|---|---|---|---|---|---|---|
| Salt Player 插件 | ✓ | ✓ | ✓ TTML | ✓ | 待验证 | 低，纯 Java |
| Windows SMTC | ✓ | ✓ 可外推 | ✗ | ✗ | ✗ | 中，需非 Java 组件 |
| MPD / foobar | ✓ | ✓ | ✗ | ✓ | ✓ | 中，纯 Java |
| 本地 LRC/TTML | — | ✗ | 部分 | ✗ | ✗ | 极低 |

UI 按能力降级：数据源没声明 `QUEUE` 时歌单列表**整体隐藏**，而不是显示空列表。

## 分支与版本矩阵

| 项目 | `26.1.2` 分支 | `1.21.1` 分支 |
|---|---|---|
| Minecraft | 26.1.2 | 1.21.1 |
| 编译 Java | 25 | 21 |
| Fabric Loom 插件 | `net.fabricmc.fabric-loom` | `net.fabricmc.fabric-loom-remap` |
| Mappings | 无需声明 | `loom.officialMojangMappings()` |
| Fabric 依赖 | `implementation` | `modImplementation` |
| Fabric API | 0.155.3+26.1.2 | 0.116.17+1.21.1 |
| NeoForge | 26.1.2.122 | 21.1.264 |
| ModDevGradle | 2.0.148 | 2.0.148 |
| Gradle | 9.7.1 | 9.7.1 |

Minecraft 26.1 起不再混淆，Fabric 亦停止维护 Yarn（止步于 1.21.11）。所以 26.1.2 走无混淆路径，1.21.1 仍走 remap。切分支时 `fabric/build.gradle.kts` 需要对齐的就是上表后三行，两分支已各自配置完成。

## 本地环境

- JDK 25：26.1.2 分支与 Gradle 运行时
- JDK 21：1.21.1 分支
- 路径已在 `gradle.properties` 中配置

## 网络与代理

**JVM 不读取 Windows 系统代理设置。** 直连 `maven.neoforged.net` 时 Cloudflare 会直接断开 JVM 的 TLS 握手：

```
SSLHandshakeException: Remote host terminated the handshake
```

同一个 URL 用 PowerShell（走系统代理）请求却返回 200，极易误判成仓库地址写错。实测调整 `jdk.tls.client.protocols`、禁用 status_request 扩展均无效——这是握手指纹层面的拦截。

解决办法是在**用户级** `~/.gradle/gradle.properties` 显式配置代理，而不是写进仓库：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.http.nonProxyHosts=localhost|127.0.0.1
systemProp.https.nonProxyHosts=localhost|127.0.0.1
```

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

产物命名携带平台与 MC 版本：

```
fabric/build/libs/linklyric-<mc>-fabric-<ver>.jar
neoforge/build/libs/linklyric-<mc>-neoforge-<ver>.jar
```

## 当前进度

已完成（两分支均已实际构建通过）：

- 三模块骨架与两加载器构建配置
- 通用内核：能力探测（`Capability` / `CapabilitySet` / `SourceProfiles`）、数据源抽象 `PlaybackSource`、歌词源抽象 `LyricProvider`、编排层 `LyricSession`
- 基座：`PlaybackClock` 锚点外推、`LyricTimeline` 二分行索引、`TrackIdentity` 时长感知匹配、`LyricCache` LRU、`ScrollModel` 三种滚动语义、`LyricStyle` 五套样式预设
- 内核单测 17 项全部通过，覆盖能力降级、迟到歌词丢弃、乱序快照丢弃、无进度漂移
- 26.1.2 分支：Fabric + NeoForge 构建通过
- 1.21.1 分支：Fabric（remap）+ NeoForge 构建通过

待办：

- 第一个数据源适配器：Salt Player 插件（`.spmod`）
- 第二个数据源适配器：MPD 或本地 LRC（纯 Java，用于验证协议真的通用）
- 歌词源：LRCLIB（`/api/search` 与 `/api/get` 均支持 duration 精确匹配）
- HUD 歌词层与歌单 Screen
- 中文描述改用 `assets/linklyric/lang/zh_cn.json`（`gradle.properties` 走 ISO-8859-1 解析，直接写中文会乱码）

## 已知待验证项

- `WorkshopApi` 是否暴露**当前播放队列**，以及是否存在**按索引播放**接口；无则 `SourceProfiles.SALT_PLAYER` 需移除 `QUEUE`
- NeoForge 26.1.2 的 `loaderVersion` 范围（当前 `[4,)`，需按实机日志校准）
- 26.1.2 上 Fabric 与 NeoForge 的 HUD 注册 API 形态（是否已全面转向 RenderState 模型）
- SMTC 的 `Position` 实际精度：部分播放器恒报 0，需运行时判断而非盲信能力声明

## 桌面端适配器顺序（重要）

第二个适配器**不要选 SMTC**。它覆盖最广，但唯一需要引入非 Java 的 .NET sidecar；若第二个就做它，出问题时无法区分是协议设计错了还是 helper 写错了。先用纯 Java 的 MPD 或本地 LRC 把协议验证干净，再上 SMTC。
