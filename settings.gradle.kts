pluginManagement {
    repositories {
        // 顺序即优先级：官方 portal / 中心仓库在前，专用仓库在后。
        // 否则 Loom 的构建脚本依赖（gson、kotlin-metadata-jvm）会被发往
        // 专用仓库而解析失败。
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

rootProject.name = "LinkLyric"

include("common")
include("fabric")
include("neoforge")
