# 发布与分支约定

本仓库每个 Minecraft 版本一条分支，各自独立发布同一份 CurseForge 项目。

返回 [README](../README.md)。

## 分支与版本矩阵

| 分支 | Minecraft | Java | SDF 渲染 |
|---|---|---|---|
| `1.21.1` | 1.21.1 | 21 | 保留 |
| `1.21.2` | 1.21.2 | 21 | 保留（着色器改声明式） |
| `1.21.3` | 1.21.3 | 21 | 保留 |
| `1.21.4` | 1.21.4 | 21 | 保留 |
| `1.21.5` | 1.21.5 | 21 | 保留（管线改 RenderPipeline） |
| `1.21.6` | 1.21.6 | 21 | 保留（GUI 改保留模型、uniform 改 std140 块） |
| `1.21.7` | 1.21.7 | 21 | 保留 |
| `1.21.8` | 1.21.8 | 21 | 保留 |
| `1.21.9` | 1.21.9 | 21 | 保留（字形拆 UnbakedGlyph/GlyphBitmap、输入事件对象化） |
| `1.21.10` | 1.21.10 | 21 | 保留 |
| `1.21.11` | 1.21.11 | 21 | 保留（Identifier 改名、采样器独立） |
| `26.1.2` | 26.1.2 | 25 | 骨架 |

1.21.x 全线到 1.21.11 已就位。后续计划展开至 26.1.1–26.3，每条 Minecraft 版本一条分支。

`1.21.2` 起三次渲染管线重写都在各自分支内消化，没有降级为原版位图字形。每条的适配要点见该分支的提交说明。

分支名即 Minecraft 版本号。切分支后必须同步修改 `gradle.properties` 的 `minecraft_version`、`fabric_api_version`、`neoforge_version`、`loader_version` 与 `java_release`，详见 [构建与环境](BUILD.md)。

## 发布流程

在任意分支上发布 GitHub Release，即触发该 tag 对应代码的构建与 CurseForge 上传。

```powershell
# 1. 改版本号
#    gradle.properties: mod_version=0.1.1

git add -A
git commit -m "更新0.1.1版本"
git push origin 1.21.1

# 2. 打 tag（必须带 MC 版本后缀，见下节）
git tag v0.1.1-mc1.21.1
git push origin v0.1.1-mc1.21.1

# 3. 到 GitHub Releases 页面基于该 tag 发布 Release
#    正文会直接作为 CurseForge 的版本说明
```

## tag 命名

git 的 tag 是全仓库共享的，不同分支不能用同名 tag。命名必须携带 Minecraft 版本：

```
v<mod_version>-mc<minecraft_version>
```

例如 `v0.1.1-mc1.21.1`、`v0.1.1-mc26.1.2`、`v0.1.2-mc1.21.5`。

tag 里的版本与 `gradle.properties` 的 `mod_version` 不一致时，工作流只打 warning 并继续发布；后接的 `-mc...` 后缀不参与比对。

## CurseForge 版本号

十个分支会共用同一个 `mod_version`，而 CurseForge 要求同一项目内版本号唯一。工作流因此把 MC 版本拼进上传版本号：

| 项目 | 取值 |
| --- | --- |
| 上传版本 `version` | `0.1.1-mc1.21.1` |
| 显示名称 `name` | `Link Lyric 0.1.1 [Fabric + NeoForge 1.21.1]` |
| 支持的游戏版本 | 取自该分支 `gradle.properties` 的 `minecraft_version` |

## 工作流如何选分支

工作流触发时不看分支名，只看 Release 的 tag：

1. 取 `github.event.release.tag_name` 作为构建目标。
2. `actions/checkout` 用该 ref 检出代码。
3. 从检出的 `gradle.properties` 读取 `mod_version`、`minecraft_version`、`archives_base_name`。
4. 产物路径按 `<archives_base_name>-<mc>-<platform>-<ver>.jar` 拼接。

因此新增分支时，工作流本身不需要任何改动。

## 工作流文件必须在默认分支

GitHub 只会从仓库**默认分支**加载 `.github/workflows/` 下的定义。非默认分支上的同名文件不会被执行，也不会被读取。

新增分支后，必须确保默认分支上也存在最新版 `.github/workflows/publish-curseforge.yml`，否则在该分支发 Release 不会触发任何构建。

## 各分支必须维护的键

每条分支的 `gradle.properties` 需要独立维护：

| 键 | 说明 |
| --- | --- |
| `minecraft_version` | 决定产物文件名与 CurseForge 的 game-versions |
| `mod_version` | 决定上传版本号前缀 |
| `java_release` | 决定 CI 使用哪个 JDK |
| `fabric_api_version` | 对应 Minecraft 版本的 Fabric API |
| `neoforge_version` | 对应 Minecraft 版本的 NeoForge |
| `loader_version` | Fabric Loader 版本 |

## 手动补发

构建失败或需要重跑时，用 `workflow_dispatch` 触发并填写：

- `ref`：分支名或 tag，例如 `1.21.1` 或 `v0.1.1-mc1.21.1`
- `version_type`：`release` / `beta` / `alpha`

Release 触发时版本类型由 tag 推断：含 `-alpha` 判 alpha，含 `-beta` 判 beta，其余 release。

## 需要配置的仓库项

| 名称 | 位置 | 用途 |
| --- | --- | --- |
| `CURSEFORGE_PROJECT_ID` | Variables | CurseForge 项目数字 ID |
| `CURSEFORGE_TOKEN` | Secrets | CurseForge API Token |
| `publish` | Environments | 人工审批闸门，可省略 |

