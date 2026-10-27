# 设计目标与仓库结构

返回 [README](../README.md)。

## 设计目标

把任意播放器的歌词、歌单与播放状态同步到 Minecraft 客户端。

不绑定单一播放器。Salt Player、Windows SMTC、MPD、本地歌词文件都只是**数据源**的实现；歌词可由播放器提供，也可由 LRCLIB 等**歌词源**补齐。不传输音频，Mod 不发声。

当前只接入 Salt Player 桥接。本地与在线歌词源尚未实现，缺少 `lyric` 时不会自动查询外部歌词库。

## 为什么这样分层

为每个播放器各写一个适配器不可持续：适配器全部要自己维护，每个都得逆向。

通用化放在协议层：模组只认一个开放的本机协议，谁想接入谁写适配器。你维护协议与渲染端，社区维护适配器。

同时把**元数据源**与**歌词源**拆开：

```
播放器 ──► 元数据源 ──┐
                      ├──► 快照 + 时间轴 ──► 渲染层
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

`common` 的硬性规则：**不得出现任何 Minecraft 或加载器 API 的 import**。这条规则区分了「加第二个加载器只要几百行」与「两份平行代码库」。

代价是依赖 Mojang 类型的渲染层代码（`RenderType`、`GlyphProvider`、`VertexFormat`）无法收进 `common`，只能在两个加载器模块各放一份。这 8 个文件（约 2100 行）是**结构性重复**；合并会让 `common` 失去零依赖性质，500 余个单测全部需要启动游戏运行时。

重复本身不危险，**静默分叉**才危险。`:common:verifyMirroredRender` 把「除包名与平台名外必须逐字一致」变成构建检查，挂在 `check` 上：

```
渲染层已分叉: render/sdf/SdfAtlas.java，首个差异在第 6 行
```

`check` 上共挂 7 道静态守卫，每道对应一类「编译通过、运行不报错」的失效：

| 任务 | 检查目标 |
| --- | --- |
| `verifyMirroredRender` | 两端渲染层副本与三个界面静默分叉 |
| `verifyLangKeys` | `LangKeys` 常量与四份 lang json 键不一致 |
| `verifyPainterParity` | 两端 `GuiPainter` 少实现一个重载 |
| `verifyNoDroppedParams` | 签名正确但形参整段丢弃 |
| `verifyConstantsReferenced` | 常量定义后无人引用 |
| `verifyPreviewGeometry` | 界面层写死预览高度，与行数脱钩 |
| `verifyShaderUniforms` | `.fsh` 用到的 uniform 未在 json 声明 |
