plugins {
    // 26.1 起 Minecraft 不再混淆，Fabric 亦停止维护 Yarn。
    // 因此本分支使用「非 remap」变体的 Loom，且无需 mappings 行。
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

val modVersion = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()
version = modVersion

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")

    // 无混淆版本下 Fabric 依赖走 implementation，而非 modImplementation。
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

    implementation(project(":common"))
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
