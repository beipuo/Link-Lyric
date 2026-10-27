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
//
// 覆盖范围：render/ 目录，以及下面 uiMirrorFiles 登记的三个界面。
//
// 三个界面**必须**逐字镜像。它们此前各自实现，结果是同一张配置页在两端
// 长得不一样：Fabric 继承原版 OptionsSubScreen（原版负责滚动、标题、页脚、
// 键盘导航与朗读），NeoForge 从 Screen 手搓两栏 + 自绘滚动条 + 手写滑条，
// 于是主开关、描边颜色、行数三处的控件形态互不相同，"取消"在一端根本不回滚。
// 现在 NeoForge 直接复用 Fabric 的实现，差异只剩包名与平台类名。
//
// 平台入口（LinkLyricFabric / LinkLyricNeoForge）与指令类**不在**保护范围内，
// 它们本就按各自的加载器 API 书写。其中 Painter 适配层的「方法集必须相同」
// 由 verifyPainterParity 负责。
/**
 * 必须两端逐字镜像的界面文件（相对各平台包目录）。
 *
 * <p>这三个才是"用户看到的那张界面"，任何一端单独改动都会让两端体验分叉，
 * 而这种分叉不会被编译器发现：两端各自都能编译、都能运行。
 */
val uiMirrorFiles = listOf(
    "LyricConfigScreen.java",
    "ColorPickerScreen.java",
    "LyricLocationScreen.java",
)

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

        // 三个界面：固定清单，缺一侧即失败。它们不是 render/ 下的副本，
        // 所以单独比一次，规则与下面完全相同（归一化后逐字一致）。
        val fabUi = File(root, "fabric/src/main/java/com/linklyric/fabric")
        val neoUi = File(root, "neoforge/src/main/java/com/linklyric/neoforge")
        for (name in uiMirrorFiles) {
            val fab = File(fabUi, name)
            val neo = File(neoUi, name)
            if (!fab.isFile) {
                problems += "fabric 缺少界面: " + name
            }
            if (!neo.isFile) {
                problems += "neoforge 缺少界面: " + name
            }
            if (fab.isFile && neo.isFile) {
                val a = normalizedMirror(fab)
                val b = normalizedMirror(neo)
                if (a != b) {
                    val at = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: b.size
                    problems += buildString {
                        append("界面已分叉: ").append(name)
                        append("，首个差异在第 ").append(at + 1).append(" 行")
                        append("\n  fabric  : ").append(a.getOrNull(at)?.trim())
                        append("\n  neoforge: ").append(b.getOrNull(at)?.trim())
                    }
                }
            }
        }

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
                + platformOnlyRenderFiles.size + " 个；界面镜像 "
                + uiMirrorFiles.size + " 个"
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

/**
 * 严格的 JSON 语法体检，返回问题列表；空表示通过。
 *
 * <p>为什么必须自己写一遍：上面抽键用的是正则，它对语法错误完全免疫。
 * 四份 lang 文件曾经都在最后一个条目后留了尾随逗号——严格 JSON 不接受，
 * 而这些**键名全对**，于是 verifyLangKeys 一路绿灯。
 *
 * <p>MC 自带的解析器是宽松的（Gson 允许尾随逗号），所以运行时也不报错；
 * 但任何按标准 JSON 读这些文件的东西（数据包校验、别的工具、
 * 我们自己后来加的脚本）都会当场失败。这正是"守卫看起来覆盖了、
 * 实际上没有"的典型：检查了内容，没检查是不是合法容器。
 *
 * <p>只做结构判定的最小实现：跟踪字符串与转义，认括号配对，
 * 并单独抓「逗号后紧跟右括号」。不追求完整 JSON 语义（不需要）。
 */
fun jsonSyntaxProblems(file: File, text: String): List<String> {
    val problems = mutableListOf<String>()
    val stack = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    // 上一个「有效字符」，用来判断逗号是否紧贴右括号。
    var lastMeaningful = ' '
    var line = 1
    for (ch in text) {
        if (ch == '\n') {
            line++
        }
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> {
                    inString = false
                    lastMeaningful = ch
                }
            }
            continue
        }
        // 字符串之外的空白一律跳过，且不改变 lastMeaningful。
        //
        // 这一行是必需的：逗号与右括号之间几乎总有换行，
        // 若把换行记成「上一个有效字符」，尾随逗号永远抓不到——
        // 检查看起来在跑，实际恒过。
        if (ch.isWhitespace()) {
            continue
        }
        when (ch) {
            '"' -> {
                inString = true
                lastMeaningful = ch
            }
            '{', '[' -> {
                stack.addLast(ch)
                lastMeaningful = ch
            }
            '}', ']' -> {
                val want = if (ch == '}') '{' else '['
                val got = stack.removeLastOrNull()
                if (got != want) {
                    problems += file.name + " 第 " + line + " 行：括号不配对（遇到 " + ch +
                        "，栈顶是 " + (got ?: "空") + "）"
                }
                if (lastMeaningful == ',') {
                    problems += file.name + " 第 " + line + " 行：逗号后直接跟 " + ch +
                        "，这是尾随逗号，严格 JSON 解析会失败"
                }
                lastMeaningful = ch
            }
            ',', ':' -> lastMeaningful = ch
            else -> lastMeaningful = ch
        }
    }
    if (inString) {
        problems += file.name + "：字符串未闭合"
    }
    if (stack.isNotEmpty()) {
        problems += file.name + "：仍有 " + stack.size + " 个未闭合的括号"
    }
    return problems
}

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
            // 先验容器合法性：键名再对，文件本身不合法也白搭。
            problems += jsonSyntaxProblems(file, file.readText())

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

// ---------------------------------------------------------------------------
// 参数丢弃检查
//
// verifyPainterParity 只比方法签名，所以「签名对、但形参被整段丢掉」它抓不到。
// 上一轮 NeoForge 那两个带特效的重载正是这种形态：签名与 Fabric 完全一致，
// 方法体却只转调无特效版本，glow 与 stroke 两个参数从未被读取。
//
// 这里把「实现了却从不读取某个形参」变成构建检查。
// 白名单只登记确有理由的：接口为兼容保留的参数、平台回调的固定签名。
// ---------------------------------------------------------------------------
val intentionallyUnusedParams = mapOf(
    // manager 供 BakedGlyph 字形通道使用；1.21.2 起着色器改为声明式，
    // 不再需要用它手动编译，接口签名仍必须保留。
    "LinkLyricFabric.java" to setOf("deltaTracker", "manager"),
    "LinkLyricNeoForge.java" to setOf("event"),
    "*" to setOf("ignoredBold"),
)

/** 解析出「方法名 -> 未被读取的形参名」。 */
fun unusedParameters(file: File): List<String> {
    val src = file.readText()
    val problems = mutableListOf<String>()
    val pattern = Regex(
        """(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?""" +
            """[\w.<>\[\], ]+?\s+(\w+)\s*\(([^)]*)\)\s*\{"""
    )
    for (m in pattern.findAll(src)) {
        val name = m.groupValues[1]
        val raw = m.groupValues[2].trim()
        if (raw.isEmpty()) {
            continue
        }
        val params = raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("""\s+""")).last().removeSuffix("[]") }
            .filter { Regex("""^[a-zA-Z_]\w*$""").matches(it) }
        if (params.isEmpty()) {
            continue
        }
        // 取方法体：从开括号起做配平
        var i = m.range.last
        var depth = 0
        var end = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
            i++
        }
        val body = src.substring(m.range.last, end)
        for (p in params) {
            if (!Regex("""\b""" + Regex.escape(p) + """\b""").containsMatchIn(body)) {
                problems += name + " 丢弃了形参 " + p
            }
        }
    }
    return problems
}

val verifyNoDroppedParams = tasks.register("verifyNoDroppedParams") {
    group = "verification"
    description = "校验实现方没有整段丢弃形参（签名对但参数不读，形同虚设）"

    val targets = listOf(
        File(rootDir, "fabric/src/main/java/com/linklyric/fabric/LinkLyricFabric.java"),
        File(rootDir, "neoforge/src/main/java/com/linklyric/neoforge/LinkLyricNeoForge.java"),
        File(rootDir, "common/src/main/java/com/linklyric/common/hud/LyricPainter.java"),
        File(rootDir, "common/src/main/java/com/linklyric/common/hud/FloatingLyricRenderer.java"),
    )

    doLast {
        val problems = mutableListOf<String>()
        for (file in targets) {
            if (!file.isFile) {
                problems += "找不到源文件: " + file.path
                continue
            }
            val allowed = intentionallyUnusedParams[file.name].orEmpty() +
                intentionallyUnusedParams["*"].orEmpty()
            for (issue in unusedParameters(file)) {
                if (allowed.any { issue.endsWith(" " + it) }) {
                    continue
                }
                problems += file.name + ": " + issue
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "以下形参实现了却从未读取，很可能是特效/配置被静默丢弃：\n"
                    + problems.joinToString("\n")
            )
        }
        logger.lifecycle("形参无静默丢弃：" + targets.size + " 个文件")
    }
}

// ---------------------------------------------------------------------------
// 常量必须有消费者
//
// Protocol 里曾有四个常量一个消费者都没有：WS_PATH、DEFAULT_PORT、
// STALE_AFTER_MS、COMMAND_TIMEOUT_MS。实际值在别处硬编码成了字面量，
// 于是「改协议常量」这个最自然的动作完全不生效；其中两个还在注释里
// 承诺了根本不存在的防御机制（陈旧隐藏、指令回执超时）。
//
// 两侧入口那两份 BRIDGE_PORT = 35400 与协议常量三次并存，
// 就是这套结构最典型的结果。这里把「定义了就至少被引用一次」变成检查。
// ---------------------------------------------------------------------------
// 目前没有豁免项。真有需要时在此登记常量名并写明理由。
val constantsAllowedUnreferenced = emptySet<String>()

val verifyConstantsReferenced = tasks.register("verifyConstantsReferenced") {
    group = "verification"
    description = "校验协议与几何常量都至少有一个消费者"

    val sources = listOf(
        File(rootDir, "common/src/main/java/com/linklyric/core/protocol/Protocol.java"),
        File(rootDir, "common/src/main/java/com/linklyric/common/hud/LyricGeometry.java"),
    )
    val searchRoots = listOf(
        File(rootDir, "common/src/main/java"),
        File(rootDir, "common/src/test/java"),
        File(rootDir, "fabric/src/main/java"),
        File(rootDir, "neoforge/src/main/java"),
    )

    doLast {
        val problems = mutableListOf<String>()
        // 把所有源文件读成一份大文本，逐个常量数引用次数
        val corpus = searchRoots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "java" }.toList() }
            .joinToString("\n") { it.readText() }

        for (file in sources) {
            if (!file.isFile) {
                problems += "找不到源文件: " + file.path
                continue
            }
            val decls = Regex(
                """public static final [\w.<>\[\]]+ ([A-Z][A-Z0-9_]*)\s*="""
            ).findAll(file.readText()).map { it.groupValues[1] }.toList()

            if (decls.isEmpty()) {
                problems += file.name + ": 没有解析出任何常量，正则可能已失效"
            }
            for (name in decls) {
                if (name in constantsAllowedUnreferenced) {
                    continue
                }
                // 减去声明自身那一处
                val uses = Regex("""\b""" + Regex.escape(name) + """\b""")
                    .findAll(corpus).count() - 1
                if (uses <= 0) {
                    problems += file.name + ": 常量 " + name + " 没有任何消费者"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "以下常量定义了却无人引用，实际值很可能在别处被硬编码：\n"
                    + problems.joinToString("\n")
            )
        }
        logger.lifecycle("常量均有消费者")
    }
}

// ---------------------------------------------------------------------------
// 预览几何守卫
//
// 四个界面（两个 LyricConfigScreen、两个 ColorPickerScreen）此前各写一个
// "PREVIEW_HEIGHT = 56"。那是按两行手算的数字，于是：
//   - 行数调到 3 以上，副行被裁掉一半；
//   - 调到 1 行，上下空一大截；
//   - 调「行数」滑条时预览毫无反应，正好把这个选项的效果藏住。
//
// 现在高度一律由 LyricGeometry.previewHeight 现算。这道守卫禁止界面层
// 再出现写死的预览高度——那是最容易被复制粘贴回四个文件的一类常量。
// ---------------------------------------------------------------------------
val verifyPreviewGeometry = tasks.register("verifyPreviewGeometry") {
    group = "verification"
    description = "校验预览高度由 LyricGeometry.previewHeight 统一计算"

    val screens = listOf(
        File(rootDir, "fabric/src/main/java/com/linklyric/fabric/LyricConfigScreen.java"),
        File(rootDir, "neoforge/src/main/java/com/linklyric/neoforge/LyricConfigScreen.java"),
        File(rootDir, "fabric/src/main/java/com/linklyric/fabric/ColorPickerScreen.java"),
        File(rootDir, "neoforge/src/main/java/com/linklyric/neoforge/ColorPickerScreen.java"),
    )

    doLast {
        val problems = mutableListOf<String>()
        // Kotlin 的原始字符串里 \s 会被 gradle 的插值处理吃掉反斜杠，
        // 用字符类绕开：空白字符直接写出，避免反斜杠转义问题。
        val hardcoded = Regex("PREVIEW_HEIGHT[ ]*=[^=]")
        for (file in screens) {
            if (!file.isFile) {
                problems += "找不到界面源文件: " + file.path
                continue
            }
            val text = file.readText()
            if (hardcoded.containsMatchIn(text)) {
                problems += file.name + ": 预览高度被写死；应调用 LyricGeometry.previewHeight(...)"
            }
            if (!text.contains("previewHeight(")) {
                problems += file.name + ": 没有调用 previewHeight(...)，预览高度可能与行数脱钩"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "预览几何不统一：\n" + problems.joinToString("\n")
            )
        }
        logger.lifecycle("预览高度统一：4 个界面均由 previewHeight 计算")
    }
}

// ---------------------------------------------------------------------------
// 着色器 uniform 声明守卫
//
// 着色器 JSON 的 uniforms 数组是 GLSL 与 Java 之间的唯一契约，但它是一个
// 独立文件，编译器、verifyMirroredRender、verifyNoDroppedParams 都看不见它。
//
// 已经踩过：StrokeWidth / StrokeColor 两个 uniform 在 .fsh 与 SdfRenderType
// 里都用上了，json 里却从未声明。safeGetUniform 对缺失 uniform 返回空实现、
// 不抛异常，于是描边宽度恒为 0——功能整体失效而构建全绿。
//
// 这类缺陷的共同形态是「两端都在，只有声明漏了」，因此这里比三方：
//   .fsh 里声明的 uniform、json 里声明的 uniform、其中采样器除外。
// 两端的 .fsh 本身还要求逐字一致（同 verifyMirroredRender 的理由）。
// ---------------------------------------------------------------------------
val shaderNames = listOf("linklyric_sdf_text")

val verifyShaderUniforms = tasks.register("verifyShaderUniforms") {
    group = "verification"
    description = "校验着色器 .fsh 用到的 uniform 都在 RenderPipeline 里声明了"

    doLast {
        val problems = mutableListOf<String>()
        for (name in shaderNames) {
            // 1.21.5 起着色器 json 被废弃，uniform 由 RenderPipeline.withUniform
            // 声明，资源放在模组自己的命名空间下。
            val fsh = listOf("fabric", "neoforge").map {
                it to File(rootDir, it + "/src/main/resources/assets/linklyric/shaders/core/" + name + ".fsh")
            }
            for ((platform, file) in fsh) {
                if (!file.isFile) {
                    problems += platform + ": 缺少着色器 " + name + ".fsh"
                }
            }
            if (fsh.any { !it.second.isFile }) {
                continue
            }
            // 两端 .fsh 必须逐字一致：着色器是同一份代码的两个副本。
            if (fsh[0].second.readText() != fsh[1].second.readText()) {
                problems += name + ".fsh 两端不一致"
            }

            // 抽出 GLSL 里声明的 uniform。采樣器另算：
            // 它由 RenderPipeline.withSampler 绑定，不参与 uniform 比对。
            val glslSource = fsh[0].second.readText()
            val glslUniforms = Regex("""^uniform\s+(?!sampler)\w+\s+(\w+)\s*;""", RegexOption.MULTILINE)
                .findAll(glslSource)
                .map { it.groupValues[1] }
                .toSet()
            val glslSamplers = Regex("""^uniform\s+sampler\w+\s+(\w+)\s*;""", RegexOption.MULTILINE)
                .findAll(glslSource)
                .map { it.groupValues[1] }
                .toSet()

            // 矩阵由管线的 uniform 块注入，GLSL 里不必再声明。
            val injected = setOf("ModelViewMat", "ProjMat")

            for (platform in listOf("fabric", "neoforge")) {
                val javaFile = File(
                    rootDir,
                    platform + "/src/main/java/com/linklyric/" + platform + "/render/sdf/SdfRenderType.java"
                )
                if (!javaFile.isFile) {
                    problems += platform + ": 缺少 SdfRenderType.java"
                    continue
                }
                val declared = Regex("""\.withUniform\("([^"]+)"""")
                    .findAll(javaFile.readText())
                    .map { it.groupValues[1] }
                    .toSet()
                val missing = (glslUniforms - declared - injected).sorted()
                if (missing.isNotEmpty()) {
                    problems += platform + ": RenderPipeline 缺少 uniform: " + missing.joinToString(", ")
                }
                val stale = (declared - glslUniforms - injected).sorted()
                if (stale.isNotEmpty()) {
                    problems.add(
                        platform + ": RenderPipeline 声明了 GLSL 未使用的 uniform: "
                            + stale.joinToString(", ")
                    )
                }
                val samplers = Regex("""\.withSampler\("([^"]+)"""")
                    .findAll(javaFile.readText())
                    .map { it.groupValues[1] }
                    .toSet()
                val missingSamplers = (glslSamplers - samplers).sorted()
                if (missingSamplers.isNotEmpty()) {
                    problems.add(
                        platform + ": RenderPipeline 缺少采样器: "
                            + missingSamplers.joinToString(", ")
                    )
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "着色器 uniform 声明与 GLSL 不一致（缺失会让对应功能静默失效）：\n"
                    + problems.joinToString("\n")
            )
        }
        logger.lifecycle("着色器 uniform 一致：" + shaderNames.size + " 个着色器 × 2 端")
    }
}

tasks.named("check") {
    dependsOn(
        verifyMirroredRender, verifyLangKeys, verifyPainterParity,
        verifyNoDroppedParams, verifyConstantsReferenced, verifyPreviewGeometry,
        verifyShaderUniforms
    )
}
