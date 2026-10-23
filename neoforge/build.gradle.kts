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
            sourceSet(sourceSets.main.get())
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
