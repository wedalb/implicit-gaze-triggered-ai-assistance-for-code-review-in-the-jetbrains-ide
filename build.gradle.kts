plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.github.gazehighlighter"
version = "0.3.0"

repositories {
    mavenCentral()
}

intellij {
    // Base IDE for compilation — works across all JetBrains IDEs at runtime
    version.set("2023.1")
    type.set("IC")          // IntelliJ IDEA Community
    plugins.set(emptyList())
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("")
    }
    // Disabled: requires a headless IDE launch which conflicts with a running PyCharm
    buildSearchableOptions {
        enabled = false
    }
}
