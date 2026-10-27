# 构建与环境

返回 [README](../README.md)。

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

Minecraft 26.1 起不再混淆，Fabric 停止维护 Yarn（止步于 1.21.11）。26.1.2 走无混淆路径，1.21.1 仍走 remap。切分支时 `fabric/build.gradle.kts` 需要对齐的是上表后三行，两分支已各自配置完成。

## 本地环境

- JDK 25：26.1.2 分支与 Gradle 运行时
- JDK 21：1.21.1 分支
- 路径写在**用户级** `~/.gradle/gradle.properties`，不在仓库里。`org.gradle.java.home` 与 `org.gradle.java.installations.paths` 是机器相关绝对值，提交进仓库会让其他人 clone 后直接构建失败。各模块 toolchain 只声明需要的 Java 大版本，由 Gradle 自行挑选。

## 网络与代理

**JVM 不读取 Windows 系统代理设置。** 直连 `maven.neoforged.net` 时 Cloudflare 会断开 JVM 的 TLS 握手：

```
SSLHandshakeException: Remote host terminated the handshake
```

这是握手指纹层面的拦截，调整 `jdk.tls.client.protocols` 或禁用 status_request 扩展均无效。在**用户级** `~/.gradle/gradle.properties` 显式配置代理：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.http.nonProxyHosts=localhost|127.0.0.1
systemProp.https.nonProxyHosts=localhost|127.0.0.1
```

## 构建命令

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

## 两个必须知道的坑

**映射名不是 Yarn。** 1.21.1 分支用 Mojang 官方映射：`GuiGraphics`、`Minecraft`、`DeltaTracker`。写成 `DrawContext`、`MinecraftClient`（Yarn 名）会编译失败。26.1.2 分支的类名不同，不能照抄。

**`implementation(project(":common"))` 不进制品。** 它只影响编译期，jar 内不会有 common 的类，装进游戏立刻 `NoClassDefFoundError`。必须在 `jar` 任务里显式加入：

```kotlin
from(project(":common").sourceSets.main.get().output)
```

不要同时使用 `include(project(":common"))`，那会生成嵌套 jar 与展开的 class 形成重复。
