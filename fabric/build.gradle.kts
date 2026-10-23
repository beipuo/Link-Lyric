plugins {
    // 1.21.1 仍是混淆版本：Yarn 虽已停止维护，但 Mojang 官方映射可用。
    // 因此本分支必须使用「remap」变体的 Loom，并显式声明 mappings。
    // 与 26.1.2 分支的差异共三处：插件 id、mappings 行、modImplementation。
    id("net.fabricmc.fabric-loom-remap") version "1.18-SNAPSHOT"
}

val modVersion = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()
version = modVersion

base {
    // 产物名携带平台与 MC 版本，便于在 CurseForge / Modrinth 上区分多目标构建
    archivesName = "${providers.gradleProperty("archives_base_name").get()}-${providers.gradleProperty("minecraft_version").get()}-fabric"
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    mappings(loom.officialMojangMappings())

    // 需要重映射的依赖走 modImplementation。
    modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

    // common 是纯 Java、零 Minecraft 依赖，不能被重映射。
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
        "mod_id" to providers.gradleProperty("mod_id").get(),
        "mod_name" to providers.gradleProperty("mod_name").get(),
        "mod_description" to providers.gradleProperty("mod_description").get(),
        "mod_author" to providers.gradleProperty("mod_author").get(),
        "minecraft_version" to providers.gradleProperty("minecraft_version").get(),
        "java_release" to providers.gradleProperty("java_release").get(),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}
