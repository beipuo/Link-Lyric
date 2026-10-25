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
val mirroredRenderSources = listOf(
    "GlyphLookup.java",
    "GradientGlyphWriter.java",
    "GradientPainter.java",
    "sdf/BoundTextureState.java",
    "sdf/SdfAtlas.java",
    "sdf/SdfGlyphProvider.java",
    "sdf/SdfGlyphWriter.java",
    "sdf/SdfRenderType.java",
)

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
    val pairs = mirroredRenderSources.map { rel ->
        rel to (File(root, "fabric/src/main/java/com/linklyric/fabric/render/" + rel)
                to File(root, "neoforge/src/main/java/com/linklyric/neoforge/render/" + rel))
    }

    doLast {
        val problems = mutableListOf<String>()
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
        logger.lifecycle("渲染层副本一致：共 " + pairs.size + " 个文件")
    }
}

tasks.named("check") {
    dependsOn(verifyMirroredRender)
}
