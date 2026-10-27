# 当前进度与待办

返回 [README](../README.md)。

## 已完成

内核、能力模型、协议 v2、Salt Player 桥接插件（`Salt Music/Link MC`）、悬浮歌词在 Fabric 与 NeoForge 双端可用。

配置界面已可用：颜色、笔画加粗、对齐、行数、间奏、阴影、透明度、行切换时长、最大宽度、字号缩放、行间距、时间偏移与复位、描边开关与颜色、位置调整与复位。全部使用原版控件，文案走 `lang/*.json`。

两个平台的配置界面**共用同一份** `LangKeys` 与同一批 lang 文件；分组标题、选项悬浮说明、描边预设色名也全部走语言键，英文界面不会露出中文。

## 待做

按优先级：

1. 灵动岛 / 侧边栏 / 物品栏三种产品样式。当前悬浮歌词已有横排、弧形、环形排布与可选折行，不能等同于这些产品样式。
2. 歌单界面。**当前做不了**：Salt Player 插件 API 无队列访问。
3. 本地 LRC / TTML 歌词源，让 SMTC 这类无歌词数据源也能显示逐字。
4. 26.1.2 分支的渲染层适配，方案见下节。

## 待办：26.1.2 的渲染架构

26.1.2 把文字渲染重构成公开的管线 API，1.21.1 那套 `@Accessor` + 反射的做法在新版本属于绕远路。移植时改用下面这条路径。

**公开能力。** `TextRenderable` 的签名是

```java
void render(Matrix4fc pose, VertexConsumer buffer, int packedLightCoords, boolean flat);
```

它同时提供 `textureView()`、`guiPipeline()` 与 `left/top/right/bottom()`。字形位置边界、纹理视图与管线都是公开可读的，1.21.1 里需要 `VarHandle` 读取的 `u0/u1/v0/v1/left/right/up/down` 在这一版不需要了。

**渲染管线可公开构造。** `GlyphRenderTypes.createForIntensityTexture` 与 `createForColorTexture` 都是 public static，返回的 record 直接带 `RenderPipeline`。自建带颜色插值的管线是正常用法。

**顶点格式要确认。** `DefaultVertexFormat.POSITION_TEX_COLOR` 含 COLOR 分量，这是逐顶点配色成立的前提。移植第一步应当确认 `RenderPipelines.GUI_TEXT` 的实际 `withVertexFormat` 取值；若该管线不带 COLOR，就需要按上面的 record 自建一条。

**移植后的结构可以简化。** 平台层不再需要：

- 反射取字段（`GradientGlyphWriter` 的 VarHandle 部分整个删掉）
- 手算 UV 与斜体补偿（`TextRenderable` 已给出边界）
- 降级判定（字段不会因改名而失配）

保留 `GradientColorizer` 与 `GlyphColors`。这两个在 common 层、纯计算、与 MC 版本无关，可以直接复用。

**验证顺序。**

1. 确认 `GUI_TEXT` 的顶点格式是否含 COLOR。
2. 写一个只画单个字形、左右端点色明显不同的最小用例。
3. 接入 `FloatingLyricRenderer`。

## 已知待验证项

- NeoForge 26.1.2 的 `loaderVersion` 范围（当前 `[4,)`，需按实机日志校准）
- 26.1.2 上 Fabric 与 NeoForge 的 HUD 注册 API 形态（是否已全面转向 RenderState 模型）
- SMTC 的 `Position` 实际精度：部分播放器恒报 0，需运行时判断而非依赖能力声明

## 桌面端适配器顺序

第二个适配器**不要选 SMTC**。它覆盖最广，但需要引入非 Java 的 .NET sidecar；出问题时无法区分是协议设计错了还是 helper 写错了。先用纯 Java 的 MPD 或本地 LRC 把协议验证干净，再上 SMTC。
