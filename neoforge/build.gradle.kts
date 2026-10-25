plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

val modVersion = providers.gradleProperty("mod_version").get()
val modId = providers.gradleProperty("mod_id").get()
group = providers.gradleProperty("maven_group").get()
version = modVersion

base {
    // 产物名携带平台与 MC 版本，便于在 CurseForge / Modrinth 上区分多目标构建
    archivesName = "${providers.gradleProperty("archives_base_name").get()}-${providers.gradleProperty("minecraft_version").get()}-neoforge"
}

neoForge {
    version = providers.gradleProperty("neoforge_version").get()

    runs {
        create("client") {
            client()
            gameDirectory = file("run")
            // 抬升坐标采集：仅 gradle -PdiagLift 时打开，
            // 平时不写日志，避免每帧刷屏。
            if (providers.gradleProperty("diagLift").isPresent) {
                jvmArgument("-Dlinklyric.diagLift=true")
            }
        }
    }

    mods {
        create(modId) {
            // common 的 class 必须一并算作本 mod 的文件。
            // dev 环境下 FML 把 mod 文件当作独立 classloader 单元，
            // 只写 implementation(project(":common")) 时 common 不在其中，
            // 结果就是启动即 NoClassDefFoundError: com/linklyric/common/hud/LyricFeed。
            // 打包进 jar（见下方 tasks.jar）只解决发布形态，救不了 dev 运行。
            sourceSet(sourceSets.main.get())
            sourceSet(project(":common").extensions.getByType<SourceSetContainer>()["main"])
        }
    }
}

/**
 * ModDev 同样可能声明 project 级仓库，使 settings 中的声明被忽略。
 * CurseForge 制品在这里再声明一次，两处都覆盖才稳妥。
 */
repositories {
    maven("https://www.cursemaven.com") {
        name = "CurseMaven"
        content {
            includeGroup("curse.maven")
        }
    }
}

dependencies {
    implementation(project(":common"))

    // ModernUI：提供高分辨率字体与文本排版。仅 NeoForge 且仅 1.21.x 有官方构建，
    // 因此不放进 :common，也不进 :fabric。
    //
    // 该制品已在本地核对过：只含 META-INF/neoforge.mods.toml（无 fabric.mod.json），
    // modLoader="javafml"，minecraft versionRange="[1.21, 1.21.2)"。
    // 它是已编译模组，dev 环境由 moddev 负责把它放进运行时 classpath。
    // 文件 ID 写在 gradle.properties，与 Fabric 用同一套约定。
//    compileOnly("curse.maven:modern-ui-352491:${providers.gradleProperty("modernui_neoforge_file").get()}")
//    runtimeOnly("curse.maven:modern-ui-352491:${providers.gradleProperty("modernui_neoforge_file").get()}")
}

/**
 * 把 :common 的类直接并入模组 jar。
 *
 * <p>只写 {@code implementation(project(":common"))} 是不够的：Gradle 会把它当作
 * 外部依赖，编译期可见但**不会**进入制品，装进游戏后立刻
 * {@code NoClassDefFoundError}。这里显式展开依赖的 class 输出。
 */
tasks.jar {
    from(project(":common").sourceSets.main.get().output)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = providers.gradleProperty("java_release").get().toInt()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java_release").get().toInt())
    }
    withSourcesJar()
}

tasks.processResources {
    val props = mapOf(
        "version" to modVersion,
        "mod_id" to modId,
        "mod_name" to providers.gradleProperty("mod_name").get(),
        "mod_description" to providers.gradleProperty("mod_description").get(),
        "mod_author" to providers.gradleProperty("mod_author").get(),
        "minecraft_version" to providers.gradleProperty("minecraft_version").get(),
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(props)
    }
}
