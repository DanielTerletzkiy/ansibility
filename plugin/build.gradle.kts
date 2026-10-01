import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // -jvm-default=no-compatibility: interface default methods are plain JVM defaults, and classes implementing a
        // Kotlin interface through a Java one (StatusBarWidget via CustomStatusBarWidget) get no delegating bridges,
        // which the plugin verifier would report as overrides of deprecated API such as getPresentation(PlatformType).
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    implementation(project(":semantics"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        pycharm(providers.gradleProperty("platformVersion"))
        bundledPlugins("org.jetbrains.plugins.yaml", "com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Names the distribution: build/distributions/Ansibility-<version>.zip containing Ansibility/lib/*.jar.
    projectName = providers.gradleProperty("pluginName")

    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "Daniel Terletzkiy"
            url = "https://ansibility.terletzkiy.de"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.PyCharm, "2026.2.3")
            create(IntelliJPlatformType.WebStorm, "2026.2.3")
            create(IntelliJPlatformType.PhpStorm, "2026.2.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

tasks.test {
    // Tests read src/test/testData by path (not from the classpath); declare it so data-only changes re-run them.
    inputs.dir("src/test/testData")
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Opt-in corpus tests (ANSIBLE_INFRA_REPO, ANSIBILITY_PYYAML_HASHES) must re-run when these variables change.
    inputs.property("ansibleInfraRepo", providers.environmentVariable("ANSIBLE_INFRA_REPO").orElse(""))
    inputs.property("ansibilityPyyamlHashes", providers.environmentVariable("ANSIBILITY_PYYAML_HASHES").orElse(""))
}

// Run the plugin in the locally installed (Toolbox) IDEs. Their paths are machine-specific: a Gradle property
// (~/.gradle/gradle.properties) or the git-ignored local.properties in the project root.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}
fun localIdePath(key: String, fallback: String): Provider<String> =
    providers.gradleProperty(key).orElse(localProperties.getProperty(key) ?: fallback)
intellijPlatformTesting.runIde.register("runLocalPyCharm") {
    localPath = file(localIdePath("localPyCharmPath", "/Applications/PyCharm.app"))
}
intellijPlatformTesting.runIde.register("runLocalWebStorm") {
    localPath = file(localIdePath("localWebStormPath", "/Applications/WebStorm.app"))
}
