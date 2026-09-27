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
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    implementation("com.electronwill.night-config:yaml:3.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-cio:3.5.2")
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
}
