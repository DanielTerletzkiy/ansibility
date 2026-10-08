plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.intellij.platform") version "2.19.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("org.jetbrains.changelog") version "2.2.1" apply false
}

allprojects {
    group = providers.gradleProperty("pluginGroup").get()
    version = providers.gradleProperty("pluginVersion").get()
}
