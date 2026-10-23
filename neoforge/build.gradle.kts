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

dependencies {
    implementation(project(":common"))
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
