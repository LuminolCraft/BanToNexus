import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.net.URI

plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    kotlin("kapt") version "2.5.0-Beta1"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven {
        url = URI("https://repo.papermc.io/repository/maven-public/")
        name = "papermc"
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    compileOnly("org.libertybans:bans-api:1.2.0-M1")
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    implementation("com.electronwill.night-config:yaml:3.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-websockets:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    // night-config 3.9.0 的格式探测靠 Class.forName("com.electronwill.nightconfig.yaml.YamlFormat")，
    // 而 Velocity 4 的 server.jar 自带 night-config-core，父加载器优先会命中它。
    // 插件自带的 yaml 模块因此对父加载器不可见，config.yml 就抛 NoFormatFoundException。
    // 整包重定位到私有包：插件用自己的 core+yaml，同时避开与 server.jar 的版本混用。
    named<ShadowJar>("shadowJar") {
        relocate("com.electronwill.nightconfig", "org.luminolcraft.bantonexus.lib.nightconfig")
        // yaml 模块依赖 snakeyaml，一并重定位，避免父加载器里的 snakeyaml 版本与插件期望不一致
        relocate("org.yaml.snakeyaml", "org.luminolcraft.bantonexus.lib.snakeyaml")
    }
}
