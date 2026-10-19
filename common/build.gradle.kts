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
