pluginManagement {
    repositories {
        maven("https://maven.neoforged.net/releases")
        maven("https://plugins.gradle.org/m2")
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "maidmining"
