# 离屏 Framebuffer 可行性探针（spike）

> **状态：已编译通过，未在真实游戏中运行过。**
> 本文每条结论都标注了置信度。标「需实机验证」的，就是**没有**运行验证过的。
> 编写环境无法启动 Minecraft，这个限制不会因为代码写得漂亮而消失。

## 这个探针在回答什么

一个问题：**HUD 渲染阶段能不能切离屏 framebuffer、画东西、再切回来采样？**

能，则「整屏弥漫光晕」（离屏累积 → 多级降采样模糊 → 加色合成）有实现基础。
不能，则那条路线要放弃或降级。探针不追求画面，只验证链路。

## 文件清单

| 文件 | 作用 |
| --- | --- |
| `fabric/src/main/java/com/linklyric/fabric/spike/OffscreenGlowProbe.java` | 探针主体：建 FBO、切目标、画测试图案、切回、采样合成 |
| `fabric/src/main/java/com/linklyric/fabric/spike/package-info.java` | 包的边界声明：不被生产代码依赖、运行时行为未经实机验证 |

> **目录位置与任务书的偏差**：任务书要求放在 `render/spike/`，实际放在
> `fabric/spike/`。原因是项目有一条硬守卫（`common/build.gradle.kts` 的
> `verifyMirroredRender`）强制 `fabric/render` 与 `neoforge/render` 逐字镜像，
> 未登记的文件会直接让构建失败。任务书同时禁止改 `common/`，两条约束冲突。
> 我选择守守卫、挪目录——**这一点需要你确认是否可以接受**。
> 详见文末「约束冲突」一节。

## 触发方式

```
/linklyric spike glow
```

切换式开关，默认关闭。打开后屏幕**右侧垂直居中**出现一个方块，
里面是蓝色色块（占离屏缓冲中间一半、四周留边）。
关掉后探针完全不执行任何 GL 操作。

色块刻意不是满屏纯色：**留边才能看出上下是否颠倒、边缘是否被裁、采样是否越界**。
满屏纯色这四件事全都看不出来。

---

## 五个问题的判定

### 1. HUD 回调期间切 framebuffer，会不会打断当帧未刷完的批次？

**判定：代码推断可确定 —— 会打断，且必须规避。**

依据（全部来自对 1.21.1 官方映射 jar 的反汇编）：

- `HudRenderCallback` 由 `fabric-rendering-v1` 的 `InGameHudMixin` 注入在
  **`Gui.render(GuiGraphics, DeltaTracker)` 的 `@At("TAIL")`**。
  （证据：`InGameHudMixin` 的常量池里 `At.value` = `TAIL`，`method` 指向 `render`。）
- `Gui.render` 的方法体只有三件事：
  `RenderSystem.enableDepthTest()` → `LayeredDraw.render(...)` → `disableDepthTest()`。
  **它自己不 flush。**
- 真正 flush 的位置在上游 `GameRenderer.render` 里，而且**在 `Gui.render` 之后**
  （字节码顺序：偏移 602 调 `Gui.render`，偏移 974 才调 `GuiGraphics.flush`），
  以及 `GuiGraphics.flush()` 的实现是 `disableDepthTest → bufferSource.endBatch() → enableDepthTest`。

所以 HUD 回调执行时，**本帧 GUI 的顶点还压在 `BufferSource` 里没落屏**，
它们的目标是主屏。此时若直接 `bindWrite` 到自己的 FBO，那批顶点会被画进离屏纹理，
表现是「屏幕上 HUD 少了一块，离屏纹理里凭空多了一块」。

**规避方式（已在代码里实现）**：换目标前先
`minecraft.renderBuffers().bufferSource().endBatch()`。
注意是 `BufferSource.endBatch()`，不是 `GuiGraphics.flush()`——后者会顺带
`disableDepthTest`，而 `Gui.render` 已经显式 `enableDepthTest` 了，重复切换没必要。

> 需实机验证的部分：`endBatch` 之后本帧后续还有没有别人往同一个
> `BufferSource` 里写顶点（若下游还有写，那批仍会落进离屏）。
> 代码推断倾向于「`Gui.render` 是 GUI 本帧最后一段」，但下游还有
> `Screen.renderWithTooltip`、`renderSavingIndicator`、`renderFpsMeter`，
> 它们都在 `Gui.render` **之后**。也就是说：**我们 swap 回去之后它们才画，
> 因此它们不受影响**——但这条顺序必须有实机确认，因为它依赖 `Gui.render`
> 在 `GameRenderer` 里被调用的确切位置。

**这一条如果实机发现仍有内容串进离屏，正确改法不是继续猜，而是把探针
改成「只在自己那一笔之前 endBatch」，或直接改用独立的 `BufferSource`。**

---

### 2. 切回来之后，`RenderSystem` 哪些状态会变脏？要恢复什么？

**判定：代码推断可确定（状态清单）+ 需实机验证（视觉是否真的受影响）。**

逐项对照 `RenderTarget._bindWrite` / `unbindWrite` 的实际动作：

| 状态 | 切换时是否被改 | 是否需要手动恢复 | 依据 |
| --- | --- | --- | --- |
| **framebuffer 绑定** | 是（`_glBindFramebuffer`） | 是，`rt.unbindWrite()` 绑回 0 | `_bindWrite` 只绑不存旧值 |
| **viewport** | 是（`bindWrite(true)` 会 `_viewport(0,0,viewWidth,viewHeight)`） | **必须**。`unbindWrite` **不还原视口** | `unbindWrite` 只有一次 `_glBindFramebuffer` |
| **投影矩阵** | 我们主动改（离屏用像素正交） | **必须**。`RenderSystem` 里是静态字段，改了就全局生效 | `setProjectionMatrix` 写 `projectionMatrix` 静态字段 |
| **VertexSorting** | 同上，随投影一起改 | **必须**，随投影一起存/取 | `setProjectionMatrix(m, sorting)` 两个字段一起写 |
| **shader** | 我们主动 `setShader` | 建议恢复。`RenderSystem` 只有一个静态 `shader` 字段 | `setShader` 直接 `putstatic` |
| **纹理绑定（shaderTextures）** | 我们 `setShaderTexture(0, texId)` | 建议清回 0 | 静态 `shaderTextures` 数组 |
| **混合（blend）** | 我们 `enableBlend` + `defaultBlendFunc` | 是。`GlStateManager` 内部有 `BLEND` 状态对象 | `BLEND` 是静态字段 |
| **深度测试** | 我们 `disableDepthTest` | 是。`Gui.render` 结束时是 `disableDepthTest`，但中途由各绘制自己管 | `DEPTH` 静态字段 |
| **scissor** | **没有改** | 见问题 4 | 探针全程不碰 scissor |
| **颜色/深度掩码** | 没有改（只有 `_blitToScreen` 会动，我们没用它） | 否 | `RenderTarget.blitToScreen` 里才有 `_colorMask` |

实现上，`OffscreenGlowProbe` 用 `try/finally` 保证即使抛异常也会：
`unbindWrite` → 恢复视口 → 恢复投影矩阵与 sorting → 清 shaderTexture → 恢复视口。
**异常路径也走 finally**，否则一次失败会把后续所有帧的视口带偏，
症状是「整屏只画左上角四分之一」这类看起来完全不相关的问题。

> **最容易被漏掉的一项：viewport。** `bindWrite(true)` 改了视口，
> `unbindWrite()` 只绑回 framebuffer 0，**不还原视口**。
> 如果只绑回去不恢复视口，后续所有绘制都会用离屏那 256×256 的视口，
> 而 GL 不会报错。这是本探针里唯一一个「一定会错、且只能靠肉眼发现」的点。

---

### 3. 1.21.1 是否强制必须走 `RenderTarget`，还是裸 GL 也行？

**判定：代码推断可确定 —— 两者都能编译，裸 GL 也能跑，但 `RenderTarget` 是正确选择。**

依据：`GlStateManager` 在 1.21.1 暴露了完整的裸 GL 入口，全部 public：
`glGenFramebuffers()`、`_glBindFramebuffer(int,int)`、`_glFramebufferTexture2D(...)`、
`glCheckFramebufferStatus(int)`、`_genTexture()`、`_texImage2D(...)`、`_glDeleteFramebuffers(int)`。
所以**技术上不存在「强制必须用 RenderTarget」的机制**。

选 `RenderTarget` 的实际理由（不是风格偏好，是它会替你做三件事）：

- **视口**：`bindWrite(true)` 顺带设视口。裸 GL 忘了设视口会照样画、照样不报错。
- **尺寸校验**：`createBuffers` 用 `maxSupportedTextureSize` 卡住非法尺寸并抛异常，
  而不是留一个永远 incomplete 的 FBO 让你在别处追查黑屏。
- **线程转发**：`resize` / `bindWrite` 在非渲染线程会转成 `recordRenderCall` 排队。
  探针全在渲染线程，用不上，但它让调用点不必自己判断线程。

**一个反直觉的坑**：`RenderTarget.clear(boolean)` 的实现是
`bindWrite → glClear → unbindWrite`。它**顺手解绑**。
所以 `rt.clear(true); 画东西;` 的写法**画不到离屏上**，会落回主屏。
正确写法是先 `bindWrite(true)`，再 `RenderSystem.clear(...)`。
代码里就是这么做的，注释也标了。

---

### 4. 视口和 scissor 在切换时怎么处理？

**视口：必须手动管，且有两套坐标要区分。判定：代码推断可确定。**

- **离屏期间**：`bindWrite(true)` 会设成 `(0, 0, viewWidth, viewHeight)`，即离屏自己的物理像素尺寸。
- **切回主屏**：必须显式 `RenderSystem.viewport(0, 0, window.getWidth(), window.getHeight())`。
  注意用的是 **`Window.getWidth()/getHeight()`（物理像素）**，
  不是 `guiScaledWidth/Height`（逻辑像素）。这两个值在有 GUI 缩放时差整数倍，
  用错会让画面缩到屏幕一角。
- **无法"保存后恢复"**：1.21.1 的 `GlStateManager` 没有暴露视口读取接口，
  只能按窗口尺寸反推。这是实现上的一个真实限制，不是偷懒。

**Scissor：探针全程不碰，但这里有个真实风险。判定：需实机验证。**

已知事实（代码可确定）：`GuiGraphics` 的 scissor 是**自己的一个栈**
（`GuiGraphics$ScissorStack`），`enableScissor` 会 push 并把逻辑坐标换算成物理坐标后
调 `applyScissor`；`applyScissor` 开头会 `flushIfManaged()`。

推断的风险（**需实机验证**）：scissor 矩形一旦在 `GuiGraphics` 里启用，
就是全局 GL 状态，与 framebuffer 无关。如果本帧有 GUI 元素在**我们执行期间**
留下了启用的 scissor，我们画进离屏的内容会被那个 scissor 裁掉。
歌词渲染本身用 `graphics.enableScissor/disableScissor` 成对调用，
理论上出栈后已恢复，但**探针与歌词在同一帧同一 `GuiGraphics` 上，顺序是探针在前**。

**实机验证方法**：打开探针的同时让歌词滚动（会走 scissor 路径），
看右侧方块是否被切成条带。若被裁，正确修法是探针进场前
`RenderSystem.disableScissor()`，出场后不恢复（让 `GuiGraphics` 自己的栈去管）。
探针代码目前**没有**加这一步，因为无法确认是否需要——这是有意留白。

---

### 5. 已知的坑：驱动 / Iris / OptiFine 下会失效吗？

**判定：需实机验证。这一条我不做任何猜测性断言。**

能确定的部分（代码层面，与光影无关）：

- 探针用的是 `RenderTarget` + 原版 `position_tex_color` 着色器，
  **没有自己的 shader 程序**，所以不存在「着色器编译被光影拦截」的问题。
- `RenderTarget` 是不透明 FBO，不依赖主 framebuffer 的采样格式。

**必须实机验证的怀疑点**（列出推理依据，但不下结论）：

- **Iris/OptiFine 的 HUD 阶段处理**。光影包通常接管
  `WorldRenderer` 级别的主渲染目标（它们的 `gcolor`/`gdepth` 等）。
  我们的 FBO 与那套无关，但**光影是否在 HUD 之前/之后做最终合成、
  是否会重置 `RenderSystem` 状态**，取决于具体光影与版本。
  如果光影在 HUD 后还有一次全屏后处理，它读的是主 framebuffer，
  我们的内容可能被它整体覆盖或二次处理。
- **`GlStateManager` 的状态缓存与外部 GL 调用的不一致**。
  `GlStateManager` 缓存 blend/depth 等状态以避免冗余 GL 调用。
  若某个模组绕过它直接调 GL 改了状态，缓存就脏了，我们的恢复动作会基于错误假设。
- **macOS**：`TextureTarget` 的第四个构造参数在原版里传的是 `Minecraft.ON_OSX`
  （`PostChain` 与 `MainTarget` 都是 `(w, h, true, ON_OSX)` 这个形状），
  探针照抄了。这个参数在 macOS 上有实际含义（与 framebuffer 的 clear/格式处理有关）。
  **Windows 上预计无影响，但我没在 macOS 上验证过。**

**验证清单（实机）**：

1. 原版（无光影）→ 右侧方块出现，蓝色，边缘不裁切；
2. 打开 Iris + 任一光影 → 方块是否仍在、颜色是否被改；
3. 改 GUI 缩放（1x/2x/3x/auto）→ 方块位置与大小是否随之正确；
4. 改窗口大小 / 全屏切换 → 是否错位（视口与投影都依赖窗口尺寸）；
5. F1 隐藏 HUD → 探针是否也跟着消失（当前实现**不会**，
   因为接入点在 `hideGui` 判断**之后**——见下条）；
6. 打开任意 GUI（物品栏等）→ 是否互相干扰。

> **第 5 条我需要主动说明**：探针接入点放在
> `onHudRender` 里 `client.options.hideGui` 判断**之后**，
> 所以 F1 时探针不绘制。这是刻意的——探针属于 HUD 内容，应遵守 F1。

---

## 一句话结论

**值得投入，但有前置条件。**

理由是链路本身**不需要任何 hack**：`RenderTarget` 是一等公民 API，
HUD 阶段切 FBO、上层有 `PostChain` 在用同一套机制，我们做的事与
原版「世界模糊」后处理在结构上同类。真正的工作量不在「能不能切」，
而在**状态恢复的正确性**（视口、投影、混合）与**多级降采样模糊的着色器**。

前置条件是：**先做完上面 6 条实机验证清单**。
其中第 3、4 条（GUI 缩放 / 窗口变化）如果不过，路线本身仍然成立，
但要额外处理尺寸变化；第 2 条（Iris）如果不过，只能在无光影下提供该特效，
需要降级为「关闭光影时启用」。

**不建议现在就投入实现**，建议先实机跑通本探针。

---

## 我改了哪些现有文件

**两处，共 14 行，全部是追加，无一行修改或删除原有逻辑。**

### `fabric/src/main/java/com/linklyric/fabric/LinkLyricFabric.java`（+4 行）

在 `onHudRender` 开头（`client == null || client.font == null` 判断之后、
`flushPendingScreen(client)` 之前）追加 4 行：

```java
// 【spike】离屏探针的唯一接入点。关闭态下 current() 是一个空实现，
// 开销为一次虚调用；探针自身出错会自我熔断，不会波及下面的歌词渲染。
com.linklyric.fabric.spike.OffscreenGlowProbe.current()
        .render(graphics, deltaTracker.getGameTimeDeltaPartialTick(false));
```

**为什么必须改这里**：HUD 回调是探针唯一的执行时机，而这个回调的注册点
（第 71 行 `HudRenderCallback.EVENT.register(...)`）指向 `onHudRender`。
不改这里就没有任何办法在 HUD 阶段执行代码。

**为什么放在 `hideGui` 判断之后**：探针属于 HUD 内容，F1 时应一起消失。
放在 `flushPendingScreen` 之前是为了让探针在歌词之前绘制——
若放在歌词之后，探针换 framebuffer 时歌词顶点已经在批次里了。

**关闭态开销**：一次虚调用 + 一次空方法，可忽略。
探针内部任何异常都会 `current = NOOP` 自动熔断，不会影响歌词渲染。

### `fabric/src/main/java/com/linklyric/fabric/FabricLyricCommand.java`（+10 行）

在 `root` 的 builder 链末尾追加 `spike glow` 子命令（在原有最后一个
`.then(literal("location")...)` 之后、`dispatcher.register(root)` 之前）：

```java
// 【spike】离屏 framebuffer 可行性探针。只在排查渲染路线时手动打开，
// 打开后屏幕右侧会出现一个采样自离屏缓冲的方块。
.then(literal("spike")
        .then(literal("glow").executes(ctx -> {
            boolean on = com.linklyric.fabric.spike.OffscreenGlowProbe.toggle();
            ctx.getSource().sendFeedback(Component.literal(
                    on ? "离屏探针：开（屏幕右侧出现离屏采样方块）"
                       : "离屏探针：关"));
            return 1;
        })));
```

**为什么必须改这里**：需要一个手动触发入口，而现有命令注册就在这个类里。
追加在链尾不影响任何既有子命令的解析。

### `common/` 改动：零

一行未碰。`git diff --name-only -- common/` 只列出你之前就有的
`FloatingLyricRenderer.java` / `LyricGeometry.java` 以及四个未跟踪新文件，
这些**全部是你原本的工作树状态，不是我改的**。

---

## 验证结果（真实执行记录）

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :fabric:compileJava --console=plain --offline` | **BUILD SUCCESSFUL** |
| `.\gradlew.bat :common:test --console=plain --offline` | **65 测试类 / 417 用例 / 0 失败 / 1 跳过** |
| `:common:verifyMirroredRender` | 渲染层副本一致：8 个文件，平台独有 1 个 |
| `:common:verifyLangKeys` | 语言键一致：99 个键 × 4 份文案 |
| `:common:verifyPainterParity` | 两端适配一致：21 个绘制方法 × 2 端 |
| `:common:verifyNoDroppedParams` | 形参无静默丢弃：4 个文件 |
| `:common:verifyConstantsReferenced` | 常量均有消费者 |
| `:common:verifyPreviewGeometry` | 预览高度统一：4 个界面均由 previewHeight 计算 |

测试基线与你给的完全一致。

> 过程中我一度看到 34 个测试失败（`NoClassDefFoundError`）。
> 根因是我为了强制重跑而删掉了 `common/build`，导致主源集与测试源集的
> classpath 产物状态不一致，**不是代码问题**。重新编译主源集后恢复全绿。
> 记录在这里是因为：如果你之后也遇到这种失败，先怀疑构建产物，别怀疑代码。

**运行时行为：全部未经实机验证。** 本环境无法启动 Minecraft。

---

## 约束冲突说明（需要你决策）

任务书要求探针放在 `fabric/.../render/spike/`，同时要求「只有入口文件可改」
并且 `common/` 一行不能碰。但项目里有一条既有守卫
（`common/build.gradle.kts` 的 `verifyMirroredRender`）会：

1. 扫描 `fabric/src/main/java/com/linklyric/fabric/render/` 下**所有** `.java`；
2. 要求 `neoforge/.../render/` 有同名同内容的副本；
3. 缺副本即 `GradleException`，`check` 任务直接失败。

我实测验证过（不是推测）：

```
> fabric 与 neoforge 的渲染层副本必须逐字一致（包名与平台名除外）：
  neoforge 缺少副本: render/spike/OffscreenGlowProbe.java
  neoforge 缺少副本: render/spike/package-info.java
```

三条出路：

- **A（我选的）**：把探针放到 `fabric/spike/`，在镜像区之外。守卫不受影响，
  代价是目录与任务书不一致。
- **B**：放进 `render/spike/`，并在 `common/build.gradle.kts` 的
  `platformOnlyRenderFiles` 里登记这两个文件为「平台独有」。这要改 `common/`，
  违反硬约束 3。
- **C**：放进 `render/spike/` 并给 neoforge 也复制一份。探针本来就是 Fabric 验证用，
  给 neoforge 复制一份无意义的副本是纯负担。

**我选 A，因为它同时满足「不碰 common」和「不破坏守卫」两条硬约束。**
如果你更看重目录位置，改成 B 只需在 `platformOnlyRenderFiles` 加两行。

---

## 代码里几个刻意的设计选择

写这几处的理由不是显然的，都踩到了具体问题：

- **用 `position_tex_color` 而不是 `blit_screen` 做合成**。
  `blit_screen` 看着更对症，但它的顶点着色器是
  `screenPos = Position.xy * 2.0 - 1.0`，**绕开投影矩阵**，
  只在「视口等于整个窗口」时成立。探针要的是「像普通 GUI 元素一样贴上去」，
  带矩阵更贴近真实用法。
- **离屏用固定 256×256，而不是屏幕尺寸**。除了开销小，更重要的是
  逼合成阶段真的做一次**缩放采样**。后续多级降采样模糊每一级都是这种采样；
  若这里只能 1:1 才通，那条路线要到更后期才暴露问题。
- **合成用独立的手动 draw call，不塞进 `GuiGraphics` 批次**。
  塞进批次的话，落屏时机由后面还有谁攒批决定，
  「离屏内容是否真的进了屏幕」这件事就不可信了。
- **失败即熔断**（`failed` 标志）。没有这道闸门，一次 FBO 创建失败会每帧重试，
  日志刷屏到看不出别的问题。
- **`drawWithShader` 会自动 `setDefaultUniforms`**（含 `ModelViewMat` / `ProjMat` /
  `ColorModulator`），并在结束时 `shader.clear()`。所以不需要手动设
  `ModelViewMat`——但**必须在调用前把 `RenderSystem` 的投影设成想要的那套**，
  因为它读的是 `RenderSystem.getProjectionMatrix()`。
