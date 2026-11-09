# Windows 系统媒体作为歌词来源

在 Windows 上，Link Lyric 可以读取发布了 GSMTC 会话的桌面应用或浏览器。Fabric 与 NeoForge 的客户端都支持这一路来源，默认不启用。Link-MC 有同步歌词时继续优先使用它；缺少同步歌词时才显示 Windows 来源匹配到的本地歌词。

1. 在装有 .NET 10 SDK 的 Windows 电脑编译桥接程序：

   ```bash
   dotnet publish native/windows-media -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o build/windows-media
   ```

2. 在游戏启动器的 JVM 参数追加 `-Dlinklyric.windowsBridge=<shb-media-bridge.exe 的绝对路径>`。路径含空格时给整个参数加引号。上面的构建命令已将运行时打包到桥接程序中。ARM64 用 `win-arm64`。

3. 在游戏实例下创建 `config/linklyric-lyrics/`，放入 UTF-8 的 `歌手 - 曲名.ttml` 或 `歌手 - 曲名.lrc`。没有歌手时只用曲名。同名 `.translation.lrc` 可为 LRC 提供译文。文件名中的控制字符及 Windows 不允许的符号替换为下划线。若应用没有公布位置或没有匹配的歌词，HUD 不显示该来源。

新增、修改或删除当前曲目的歌词后，打开 `/linklyric config`，在「操作」中点击「重新读取本地歌词」。Link-MC 和 Windows 来源都会在后台重新读取本地文件，暂停时也可操作。Link-MC 提供的歌词优先。

Windows 会话通常只包含歌曲及播放状态。桥只从系统读取快照，通过子进程的标准输入输出通信，不开启端口，不查询网络歌词，也不控制播放。只有一个后台轮询服务；游戏退出时关闭桥。播放器是否提供位置、时长或媒体会话取决于该播放器及其设置。
