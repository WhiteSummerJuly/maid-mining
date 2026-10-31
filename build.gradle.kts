val mod_id: String by project
val mod_name: String by project
val mod_version: String by project
val mod_group: String by project
val mod_authors: String by project
val mod_description: String by project
val minecraft_version: String by project
val neoforge_version: String by project

plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.148"
}

version = mod_version
group = mod_group
base.archivesName = mod_id

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

neoForge {
    version = neoforge_version

    runs {
        register("client") {
            client()
            gameDirectory = file("run/")
        }
        register("server") {
            server()
            gameDirectory = file("run-server/")
        }
        register("data") {
            data()
            gameDirectory = file("run-data/")
            programArguments.addAll(
                "--mod", mod_id,
                "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath,
            )
        }
    }

    mods {
        register(mod_id) {
            sourceSet(sourceSets.main.get())
        }
    }
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://api.modrinth.com/maven")   // TML 的 jar 走本地 libs，留作备用
    mavenCentral()
}

dependencies {
    // 车万女仆（TML）：本地 jar，只编译期需要
    compileOnly(files(fileTree("libs") { include("*.jar") }))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<ProcessResources>().configureEach {
    val props = mapOf(
        "mod_id" to mod_id,
        "mod_version" to mod_version,
        "mod_name" to mod_name,
        "mod_authors" to mod_authors,
        "mod_description" to mod_description,
        "neoforge_version" to neoforge_version,
        "minecraft_version" to minecraft_version,
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/neoforge.mods.toml")) {
        expand(props)
    }
}
