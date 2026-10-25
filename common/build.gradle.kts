plugins {
    `java-library`
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()

java {
    // common 必须能被两个分支同时消费：26.1.2 与 1.21.1。
    // 固定编译到 Java 21，class 版本才不会被 1.21.1（Java 21）拒绝。
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// 刻意保持零依赖：本模块不得出现任何 Minecraft 或加载器 API。
// JSON 编解码由各加载器模块用宿主自带的 Gson 完成。
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// 双平台渲染层的漂移守卫
//
// fabric/render 与 neoforge/render 是同一份代码的两份副本：它们直接操作
// Mojang 的 RenderType / GlyphProvider，而本模块被硬性约束为零依赖
// （见上方 dependencies 的注释），无法收纳这些类型。所以重复是结构性的，
// 不能靠「合并到 common」消除。
//
// 结构性重复真正的危险不是重复，而是**静默分叉**：改了 fabric 忘了改
// neoforge，两个平台开始行为不同，而编译器一声不响。这个任务把
// 「除包名与平台名外必须逐字一致」变成构建检查，漂移当场失败。
// ---------------------------------------------------------------------------
// 平台之间**允许**不一致的文件，必须在此显式登记并写明理由。
// 除此之外，两侧的文件集与内容都必须完全一致。
val platformOnlyRenderFiles = mapOf(
    "sdf/SdfShaderRegistration.java" to
        "装载时机不同：Fabric 挂资源重载，NeoForge 必须用 RegisterShadersEvent",
)

/**
 * 在 render 目录下自动发现所有副本文件。
 *
 * <p>此前这里是手工维护的清单，于是有个明显的洞：新增一个两侧都有的文件、
 * 却忘了往清单里加一行，它就永远不受检查，静默分叉也不报错。
 * 现在以目录实际内容为准，清单只用来登记「允许不同」的例外。
 */
fun discoverRenderSources(dir: File): Set<String> {
    if (!dir.isDirectory) {
        return emptySet()
    }
    return dir.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .map { it.relativeTo(dir).invariantSeparatorsPath }
        .toSet()
}

/** 归一化：抹掉平台名差异，只比较语义内容。 */
fun normalizedMirror(file: File): List<String> =
    file.readLines().map { line ->
        line.replace("fabric", "@P@").replace("neoforge", "@P@")
            .replace("Fabric", "@P@").replace("NeoForge", "@P@")
    }

val verifyMirroredRender = tasks.register("verifyMirroredRender") {
    group = "verification"
    description = "校验 fabric 与 neoforge 的渲染层副本没有静默分叉"

    val root = project.rootDir
    val fabricDir = File(root, "fabric/src/main/java/com/linklyric/fabric/render")
    val neoforgeDir = File(root, "neoforge/src/main/java/com/linklyric/neoforge/render")

    doLast {
        val problems = mutableListOf<String>()

        // 先比文件集：少一侧、多一侧都是分叉，且能指名道姓。
        val fabSet = discoverRenderSources(fabricDir)
        val neoSet = discoverRenderSources(neoforgeDir)
        for (rel in (fabSet - neoSet - platformOnlyRenderFiles.keys).sorted()) {
            problems += "neoforge 缺少副本: render/" + rel
        }
        for (rel in (neoSet - fabSet - platformOnlyRenderFiles.keys).sorted()) {
            problems += "fabric 缺少副本: render/" + rel
        }
        // 登记为例外却两侧都有，说明例外已失效，应当删掉这行登记。
        for (rel in platformOnlyRenderFiles.keys.sorted()) {
            if (fabSet.contains(rel) && neoSet.contains(rel)) {
                problems += "render/" + rel + " 已登记为平台独有，但两侧都存在；请从例外清单移除"
            }
        }

        val pairs = (fabSet intersect neoSet).sorted().map { rel ->
            rel to (File(fabricDir, rel) to File(neoforgeDir, rel))
        }
        for ((rel, files) in pairs) {
            val (fab, neo) = files
            if (!fab.isFile) {
                problems += "缺少 fabric 副本: render/" + rel
                continue
            }
            if (!neo.isFile) {
                problems += "缺少 neoforge 副本: render/" + rel
                continue
            }
            val a = normalizedMirror(fab)
            val b = normalizedMirror(neo)
            if (a != b) {
                val at = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: b.size
                problems += buildString {
                    append("渲染层已分叉: render/").append(rel)
                    append("，首个差异在第 ").append(at + 1).append(" 行")
                    append("\n  fabric  : ").append(a.getOrNull(at)?.trim())
                    append("\n  neoforge: ").append(b.getOrNull(at)?.trim())
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "fabric 与 neoforge 的渲染层副本必须逐字一致（包名与平台名除外）：\n"
                    + problems.joinToString("\n")
            )
        }
        logger.lifecycle(
            "渲染层副本一致：共 " + pairs.size + " 个文件，平台独有 "
                + platformOnlyRenderFiles.size + " 个"
        )
    }
}

tasks.named("check") {
    dependsOn(verifyMirroredRender)
}
