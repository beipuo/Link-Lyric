# Link Salt Music

把 Salt Player for Windows（SPW）的歌词、歌单与播放状态同步到 Minecraft 的客户端模组。

- SPW 侧桥接插件（`.spmod`）从 Workshop API 取数据，经本机回环通道推送。
- Minecraft 侧只做消费与渲染：歌词、歌单列表、播放顺序切换、歌词样式。
- 不传输音频，Mod 不发声；声音始终由 SPW 播放。

## 仓库结构

两个维度正交：**分支管 Minecraft 版本，模块管加载器**。

```
Link Salt Music/
├── common/     纯 Java 逻辑，零 Minecraft / 加载器依赖
├── fabric/     Fabric 接线层
└── neoforge/   NeoForge 接线层
```

`common` 的硬性规则：**不得出现任何 Minecraft 或加载器 API 的 import**。它只放时钟外推、行索引、滚动状态机、样式模型、协议常量。这条规则是"加第二个加载器只要几百行"和"两份平行代码库"的分界。

## 分支与版本矩阵

| 项目 | `26.1.2` 分支 | `1.21.1` 分支 |
|---|---|---|
| Minecraft | 26.1.2 | 1.21.1 |
| 编译 Java | 25 | 21 |
| Fabric Loom 插件 | `net.fabricmc.fabric-loom` | `net.fabricmc.fabric-loom-remap` |
| Mappings | 无需声明 | `loom.officialMojangMappings()` |
| Fabric 依赖 | `implementation` | `modImplementation` |
| Fabric Loader | 0.19.5 | 0.19.5 |
| Fabric API | 0.155.3+26.1.2 | 0.116.17+1.21.1 |
| NeoForge | 26.1.2.122 | 21.1.264 |
| ModDevGradle | 2.0.148 | 2.0.148 |
| Gradle | 9.7.1 | 9.7.1 |

### 为什么两个分支的 Gradle 写法不同

Minecraft 26.1 起不再混淆，Fabric 亦停止维护 Yarn（Yarn 止步于 1.21.11）。因此：

- `26.1.2` 分支走无混淆路径：不需要 `mappings`，依赖用 `implementation`，Loom 用非 remap 变体。
- `1.21.1` 分支仍需重映射：必须声明 `loom.officialMojangMappings()`，依赖用 `modImplementation`，Loom 用 remap 变体。

切分支时 `fabric/build.gradle.kts` 需要对齐的三处就是上表加粗的后三行。两个分支已各自完成配置，切换后无需手工改动。

## 本地环境

- JDK 25：用于 26.1.2 分支与 Gradle 运行时
- JDK 21：用于 1.21.1 分支
- 路径已在 `gradle.properties` 的 `org.gradle.java.home` 与 `org.gradle.java.installations.paths` 中配置

## 网络与代理

**JVM 不读取 Windows 系统代理设置。** 直连 `maven.neoforged.net` 时 Cloudflare 会直接断开 JVM 的 TLS 握手，表现为：

```
SSLHandshakeException: Remote host terminated the handshake
```

而同一个 URL 用 PowerShell（走系统代理）请求却返回 200，很容易误判成仓库地址写错。实测调整 `jdk.tls.client.protocols`、禁用 status_request 扩展均无效——这是握手指纹层面的拦截。

解决办法是在**用户级** `~/.gradle/gradle.properties` 中显式配置代理，而不是写进仓库：

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
# 只跑共享逻辑的单测（不需要下载 Minecraft）
.\gradlew.bat :common:test

# 完整构建
.\gradlew.bat build

# 启动客户端
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
```

产物命名携带平台与 MC 版本，便于多目标发布时区分：

```
fabric/build/libs/linksaltmusic-<mc>-fabric-<ver>.jar
neoforge/build/libs/linksaltmusic-<mc>-neoforge-<ver>.jar
```

## 当前进度

已完成（两个分支均已实际构建通过）：

- 三模块骨架与两加载器构建配置
- `common` 基座：`PlaybackClock`（锚点外推）、`LyricTimeline`（行索引二分）、`LyricWord` / `LyricLine`、`ScrollModel`（三种滚动语义）、`LyricStyle`（五套样式预设）、`Protocol` 常量
- `common` 单测 8 项全部通过
- 26.1.2 分支：Fabric + NeoForge 构建通过
- 1.21.1 分支：Fabric（remap）+ NeoForge 构建通过

待办：

- SPW 侧桥接插件仓库（`.spmod`）
- 回环通道与命令回执（见 `docs/PROTOCOL.md`）
- HUD 歌词层与歌单 Screen
- 中文描述改用 `assets/linksaltmusic/lang/zh_cn.json`（`gradle.properties` 走 ISO-8859-1 解析，直接写中文会乱码）

## 已知待验证项

- `WorkshopApi` 是否暴露**当前播放队列**（而非仅曲库）
- 是否存在**按索引播放**的接口；若无，需在插件侧用队列索引模拟
- NeoForge 26.1.2 的 `loaderVersion` 范围（当前写 `[4,)`，需按实机日志校准）
- 26.1.2 上 Fabric 与 NeoForge 的 HUD 注册 API 具体形态（是否已全面转向 RenderState 模型）
