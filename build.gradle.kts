import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.denglei1024"
version = "0.1.1"

val localRiderPath = providers.gradleProperty("localRiderPath")
    .orElse(providers.environmentVariable("RIDER_HOME"))

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (localRiderPath.isPresent) {
            local(localRiderPath.get())
        } else {
            rider("2026.1.1") {
                useInstaller = false
            }
            jetbrainsRuntime()
        }
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }
}

tasks.wrapper {
    gradleVersion = "9.5.0"
}

