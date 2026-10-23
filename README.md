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

## 已实现：悬浮歌词

当前可用链路是**最小闭环**，只做一件事：在屏幕底部居中显示当前歌词行。

```
Salt Player ──(spmod 插件)──► 127.0.0.1:35400 ──► BridgePlaybackSource
                                                        │
                                          FloatingLyricRenderer ──► HUD
```

| 模块 | 职责 |
|---|---|
| `common/bridge/SnapshotParser` | 手写 JSON 解析，零第三方依赖 |
| `common/bridge/BridgePlaybackSource` | 后台线程轮询，渲染帧内零网络请求 |
| `common/hud/FloatingLyricRenderer` | 单行居中排版，逐单元染色、羽化推进、底板 |
| `common/hud/LyricFeed` | 渲染层依赖的最小接口，便于单测替身 |
| `core/sync/PlaybackClock` | 位置外推，把秒级上报插值成帧级连续位置 |

Fabric 与 NeoForge 各自只有约一百行接线代码，排版与状态计算完全共用。

### 逐字染色为什么不能「直接算宽度就画」

染色要连续，靠的不是算得准，而是**跨帧保持状态**。这里踩过四个坑，
每一个都会让高亮停在原地或整行瞬亮，症状几乎一样，排查时极易混淆：

**歌词指纹不能取 `seq`。** 桥接端的 `seq` 每次位置上报都会递增，
用它做歌词缓存键，时间轴会每秒被重建一次，渲染层的换行状态被反复重置，
结果是每帧都跳过平滑、直接把已唱宽度赋成目标值——表现为整字跳变。
指纹必须只对 `lyric` 对象本身做哈希。

**帧间隔要在入口统一算。** 若只在「同一行」分支里取 `nanoTime`，
换行那一帧不会记录时间戳，下一帧间隔被算成 0，平滑推进失效。

**外推必须限幅且单调。** 轮询滞后时外推会无界前冲，位置越过整行后
`indexAt` 停在末行并判定已唱完，整行恒亮——看起来像「没有染色」。
限幅之外还要保证同一次播放中位置不倒退，否则轮询抖动会让高亮闪烁；
但跨行、seek、换曲这类大跨度回退必须放行，不然会永久卡在旧行。

**上报周期是 1 秒，不是轮询间隔。** 轮询每 200ms 发一次，但宿主每秒才更新
一次位置，同一份位置会连续返回 5 次。外推余量若小于这个周期，高亮走完余量
就停住等下一条上报——现象正是「一秒一跳、中间明显停顿」。
余量必须按实测周期自适应，且上限要随本地时间一起推进；若写成
`reportedMs + lead` 这种固定上限，位置追上上限后就再无前进余地。

**行级歌词要按行内比例插值。** 宿主并非总提供逐字时间：换到一首没有逐字
数据的歌，`lyricsCells` 就只有 1 个元素。此时若按「到点全亮」处理，
高亮会整块跳变。按行内时间比例做线性推进，观感接近卡拉OK，
且不伪造原数据里并不存在的字级时间。

**字级上浮的比例不能照抄手机。** HyperLyric 的 `liftFactor = 0.12` 是以
手机大字号为基准的；我们的行高只有 10px，按 0.12 算得 1px，
肉眼几乎看不出位移，等于没做。这里取 0.35（约 3px），
刚好明显可见而又不跑出底板内边距。裁剪预留空间必须由同一个常量算出，
否则抬起的字顶部会被削平。
**长行要滚动，不能截断。** 截断丢掉的是副歌后半句，而歌词恰恰是「想看下一句」
需求最强的地方。滚动规则取自 `ScrollStepper`：演唱点越过视口中线才推动文本，
推动量恰为超出中线的距离，并夹在 `[-(行宽-视口宽), 0]`。
这样演唱中的字始终停在中线附近；若改成按时间匀速滚，速度会与演唱脱节，更难跟。
滚动时所有裁剪区都必须再夹一次视口，否则已唱段会画出面板、压到别的 HUD 上。
**同色裁剪画不出中间态。** 等宽汉字无法靠整字累加表达「唱到一半」，
必须用像素裁剪；裁剪区前后再叠两级半透明羽化，推进端才不生硬。

对应的回归测试在 `common/src/test/.../hud/`：
`ProgressCurveTest` 用真实桥接数据校验曲线单调与收敛，
`LiveBridgeAnimationTest` 直接连 `127.0.0.1:35400` 实跑，端点未启动时自动跳过。

### 两个必须知道的坑

**映射名不是 Yarn。** 1.21.1 分支用 Mojang 官方映射：`GuiGraphics`、`Minecraft`、
`DeltaTracker`。写成 `DrawContext`、`MinecraftClient`（Yarn 名）会直接编译失败。
26.1.2 分支的类名又不同，不能照抄。

**`implementation(project(":common"))` 不进制品。** 它只影响编译期，
jar 里不会有 common 的类，装进游戏立刻 `NoClassDefFoundError`。
必须在 `jar` 任务里显式 `from(project(":common").sourceSets.main.get().output)`。
另外不要同时用 `include(project(":common"))`，那会生成嵌套 jar 与展开的 class
形成重复。

### 渲染性能约定

轮询在独立线程每 200ms 一次；`pollSnapshot()` 只做一次 volatile 读，绝不发网络请求。
断连要连续失败 6 次（约 1.2 秒）才清空，避免插件重载时歌词闪烁。
不为歌词文本构造 `Component`——中文歌词每帧重建会产生大量短命对象。

### 测试

```powershell
.\gradlew.bat :common:test
```

32 项。其中 `LiveSampleTest` 使用**真实抓取的 `/lyric` 响应**（`live-sample.json`）
做端到端验证——手写样例永远会漏掉真实数据里的格式细节，这次就靠它抓出了
两个解析 bug：切片截断导致括号不配平、数组拆分没跳过换行导致字段名被当成元素。
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
| Salt Player 插件 | ✓ | ✓ | ✓ TTML | ✗ | ✗ | 低，纯 Java |
| Windows SMTC | ✓ | ✓ 可外推 | ✗ | ✗ | ✗ | 中，需非 Java 组件 |
| MPD / foobar | ✓ | ✓ | ✗ | ✓ | ✓ | 中，纯 Java |
| 本地 LRC/TTML | — | ✗ | 部分 | ✗ | ✗ | 极低 |

Salt Player 的「队列 ✗ / 按索引播放 ✗」不是待办，而是实测定论：插件 API 的
`WorkshopApi.Playback` 只暴露 `play/pause/previous/next/seekTo` 与当前曲目，
**没有任何队列访问入口**。虚报这两项会得到一个永远为空的歌单列表。

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

已完成：内核、能力模型、协议 v2、Salt Player 桥接插件（`Salt Music/Link MC`）、
悬浮歌词在 Fabric 与 NeoForge 双端可用。

待做，按优先级：

1. 灵动岛 / 侧边栏 / 物品栏三种样式（`LyricStyle` 已有预设，缺渲染实现）
2. 歌单界面——**当前做不了**，Salt Player 插件 API 无队列访问
3. 本地 LRC / TTML 歌词源（让 SMTC 这类无歌词数据源也能显示逐字）
4. 26.1.2 分支的渲染层适配（类名不同，需另写）
5. 配置文件（端口、样式、位置可调）
## 已知待验证项

- `WorkshopApi` 是否暴露**当前播放队列**，以及是否存在**按索引播放**接口；无则 `SourceProfiles.SALT_PLAYER` 需移除 `QUEUE`
- NeoForge 26.1.2 的 `loaderVersion` 范围（当前 `[4,)`，需按实机日志校准）
- 26.1.2 上 Fabric 与 NeoForge 的 HUD 注册 API 形态（是否已全面转向 RenderState 模型）
- SMTC 的 `Position` 实际精度：部分播放器恒报 0，需运行时判断而非盲信能力声明

## 桌面端适配器顺序（重要）

第二个适配器**不要选 SMTC**。它覆盖最广，但唯一需要引入非 Java 的 .NET sidecar；若第二个就做它，出问题时无法区分是协议设计错了还是 helper 写错了。先用纯 Java 的 MPD 或本地 LRC 把协议验证干净，再上 SMTC。
