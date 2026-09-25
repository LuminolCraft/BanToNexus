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
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }
}
