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

// ---------------------------------------------------------------------------
// 语言键三向校验
//
// 键名登记在 LangKeys 常量里，文案分散在四个 json（两平台 × 两语言）。
// 这两边一旦不一致，症状**不会报错**：缺键的位置会直接把
// "linklyric.config.foo" 这种原始键名画到界面上，混在中文里很难被发现，
// 且只影响其中一种语言时更隐蔽。
//
// 因此把「常量集 == 每份 json 的键集」做成构建检查。
// ---------------------------------------------------------------------------
val langKeysSource = File(
    rootDir,
    "common/src/main/java/com/linklyric/common/i18n/LangKeys.java"
)

val langJsonFiles = listOf("fabric", "neoforge").flatMap { platform ->
    listOf("zh_cn", "en_us").map { lang ->
        "$platform/$lang" to File(
            rootDir,
            "$platform/src/main/resources/assets/linklyric/lang/$lang.json"
        )
    }
}

/** 从 LangKeys 里抽出所有字符串字面量。只认 public static final String 行。 */
fun declaredLangKeys(file: File): Set<String> =
    Regex("""public static final String [A-Z0-9_]+\s*=\s*"([^"]+)"""")
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()

/** 抽出 json 的顶层键。用正则而非 json 库，避免给构建脚本加依赖。 */
fun langJsonKeys(file: File): Set<String> =
    Regex("""^\s*"([^"]+)"\s*:""", RegexOption.MULTILINE)
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()

val verifyLangKeys = tasks.register("verifyLangKeys") {
    group = "verification"
    description = "校验 LangKeys 常量与四个 lang json 的键完全一致"

    doLast {
        val declared = declaredLangKeys(langKeysSource)
        val problems = mutableListOf<String>()

        if (declared.isEmpty()) {
            problems += "没有从 LangKeys 里解析出任何键，正则可能已失效"
        }
        // 常量值重复意味着两份文案共用一个键，改一处会影响两处。
        //
        // 必须从**未去重**的序列里统计：declared 是 toSet() 的结果，
        // 用它对重复值计数永远是 1，这条检查会静默失效。
        val duplicated = Regex("""public static final String ([A-Z0-9_]+)\s*=\s*"([^"]+)"""")
            .findAll(langKeysSource.readText())
            .map { it.groupValues[2] }
            .toList()
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
        for ((key, count) in duplicated) {
            problems += "LangKeys 里键值重复（" + count + " 处）: " + key
        }

        for ((label, file) in langJsonFiles) {
            if (!file.isFile) {
                problems += label + ": 缺少文件 " + file.path
                continue
            }
            val present = langJsonKeys(file)
            val missing = (declared - present).sorted()
            val extra = (present - declared).sorted()
            if (missing.isNotEmpty()) {
                problems += label + ": json 缺少 " + missing.size + " 个键 -> " + missing.joinToString(", ")
            }
            if (extra.isNotEmpty()) {
                problems += label + ": json 多出 " + extra.size + " 个键 -> " + extra.joinToString(", ")
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "语言键必须与 LangKeys 完全一致：\n" + problems.joinToString("\n")
            )
        }
        logger.lifecycle(
            "语言键一致：" + declared.size + " 个键 × " + langJsonFiles.size + " 份文案"
        )
    }
}

// ---------------------------------------------------------------------------
// 两端 Painter 适配一致性
//
// GuiPainter 是两个平台各自手写的一份适配层，不受 verifyMirroredRender 保护
// （它在平台包里，不是 render/ 下的副本）。它实现的是同一套 Painter 接口，
// 而接口里带发光/描边的方法都有 **default 降级实现**——
// 于是某端漏写一个重载，编译通过、运行正常，只是那几项特效凭空消失。
//
// 已经踩过：NeoForge 把带特效的重载实现成转调无特效版本，
// 描边与发光在那边整体失效，既不报错也不退回原版字形。
// 这里把「两端实现的方法集合必须相同」变成构建检查。
// ---------------------------------------------------------------------------
val painterAdapters = listOf(
    "fabric" to File(
        rootDir,
        "fabric/src/main/java/com/linklyric/fabric/LinkLyricFabric.java"
    ),
    "neoforge" to File(
        rootDir,
        "neoforge/src/main/java/com/linklyric/neoforge/LinkLyricNeoForge.java"
    ),
)

/** 引导入口与平台特有回调，按平台本就不同，比较时排除。 */
val platformSpecificMethods = setOf(
    "onInitializeClient",
    "onResourceManagerReload",
)

/** 抽出 public 方法签名「名(参数类型,...)」。参数只取类型，忽略形参名。 */
fun publicMethodSignatures(file: File): Set<String> =
    Regex("""public\s+(?:int|void|boolean|float|FloatingLyricRenderer\.Pose2D)\s+(\w+)\s*\(([^)]*)\)""")
        .findAll(file.readText())
        .map { m ->
            val params = m.groupValues[2]
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.split(Regex("""\s+""")).dropLast(1).joinToString(" ") }
            m.groupValues[1] + "(" + params.joinToString(",") + ")"
        }
        .filterNot { sig -> platformSpecificMethods.any { sig.startsWith(it + "(") } }
        .toSet()

val verifyPainterParity = tasks.register("verifyPainterParity") {
    group = "verification"
    description = "校验两端的 Painter 适配层实现了同一组方法"

    doLast {
        val problems = mutableListOf<String>()
        val byName = painterAdapters.map { (name, file) ->
            if (!file.isFile) {
                problems += name + ": 找不到源文件 " + file.path
            }
            name to publicMethodSignatures(file)
        }.toMap()

        val fab = byName["fabric"].orEmpty()
        val neo = byName["neoforge"].orEmpty()

        if (fab.isEmpty() || neo.isEmpty()) {
            problems += "没有解析出方法签名，正则可能已失效"
        }

        // 只比 Painter 接口里的方法：其余是各自的辅助方法，允许不同。
        val paintMethods = fab.filter { it.startsWith("draw") || it in setOf(
            "screenWidth()", "screenHeight()", "textWidth(String)",
            "textWidthScaled(String,float)", "charWidth(char)",
            "lineHeight()", "pose()", "fillRect(int,int,int,int,int)",
            "supportsScissor()", "enableScissor(int,int,int,int)",
            "disableScissor()",
        ) }

        for (sig in (fab - neo).sorted()) {
            problems += "neoforge 缺少实现: " + sig
        }
        for (sig in (neo - fab).sorted()) {
            problems += "fabric 缺少实现: " + sig
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "两端 Painter 适配层必须实现同一组方法：\n" + problems.joinToString("\n")
            )
        }
        logger.lifecycle(
            "两端适配一致：" + paintMethods.size + " 个绘制方法 × 2 端"
        )
    }
}

tasks.named("check") {
    dependsOn(verifyMirroredRender, verifyLangKeys, verifyPainterParity)
}
