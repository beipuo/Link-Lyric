plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

val modVersion = providers.gradleProperty("mod_version").get()
val modId = providers.gradleProperty("mod_id").get()
group = providers.gradleProperty("maven_group").get()
version = modVersion

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
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
