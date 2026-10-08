import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.changelog.Changelog
import java.util.Locale
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.changelog")
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

changelog {
    version = providers.gradleProperty("pluginVersion")
    path = rootProject.file("CHANGELOG.md").canonicalPath
}

intellijPlatform {
    // Names the distribution: build/distributions/Ansibility-<version>.zip containing Ansibility/lib/*.jar.
    projectName = providers.gradleProperty("pluginName")

    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "Daniel Terletzkiy"
            url = "https://ansibility.terletzkiy.de"
        }
    }

    // Marketplace credentials come from the environment (GitHub Actions secrets in .github/workflows/release.yml).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginChannel").map { listOf(it) }.orElse(listOf("default"))
    }

    pluginVerification {
        // Experimental API stays allowed: the completion-popup documentation and ModCommand file creation have no stable alternative yet.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.MISSING_DEPENDENCIES,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
        ides {
            create(IntelliJPlatformType.PyCharm, "2026.2.3")
            create(IntelliJPlatformType.WebStorm, "2026.2.3")
            create(IntelliJPlatformType.PhpStorm, "2026.2.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// Production code compiles without warnings, so a newly deprecated platform API fails the build instead of piling up.
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions.allWarningsAsErrors.set(true)
}

tasks.test {
    // Tests read src/test/testData by path (not from the classpath); declare it so data-only changes re-run them.
    inputs.dir("src/test/testData")
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Opt-in corpus tests (ANSIBLE_INFRA_REPO, ANSIBILITY_PYYAML_HASHES) must re-run when these variables change.
    inputs.property("ansibleInfraRepo", providers.environmentVariable("ANSIBLE_INFRA_REPO").orElse(""))
    inputs.property("ansibilityPyyamlHashes", providers.environmentVariable("ANSIBILITY_PYYAML_HASHES").orElse(""))
    // Without the local infra fixture, skip the @RequiresInfraFixture tests.
    if (!file("src/test/testData/infra").isDirectory) {
        systemProperty("idea.test.execution.policy", "de.terletzkiy.ansibility.fixtures.InfraFixturePolicy")
    }
}

// Coverage (plan amendment R13): Kover measures the run area (`run.*`) during the plugin's tests, and
// `checkRunCoverage` (part of `check`) holds the run events and run view packages to their minimum line coverage.
// Only the run area is instrumented: instrumenting the whole plugin slows the timing-budget tests past their budgets.
kover {
    currentProject {
        instrumentation {
            includedClasses.add("de.terletzkiy.ansibility.run.*")
        }
    }
    reports {
        filters {
            includes { packages("de.terletzkiy.ansibility.run") }
        }
    }
}

val runCoverageMinimums = mapOf(
    "de/terletzkiy/ansibility/run/events" to 85.0,
    "de/terletzkiy/ansibility/run/view" to 60.0,
    "de/terletzkiy/ansibility/run/molecule" to 60.0,
    // Run-end notifications (plan amendment R19): pure outcome texts and the notifier.
    "de/terletzkiy/ansibility/run/notify" to 85.0,
)

val checkRunCoverage = tasks.register("checkRunCoverage") {
    group = "verification"
    description = "Fails when the run events and run view packages fall below their minimum line coverage."
    dependsOn("koverXmlReport")
    val report = layout.buildDirectory.file("reports/kover/report.xml")
    inputs.file(report)
    doLast {
        val xml = report.get().asFile.readText()
        val failures = runCoverageMinimums.mapNotNull { (pkg, minimum) ->
            val block = Regex("""<package name="$pkg">(.*?)</package>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?: return@mapNotNull "$pkg: not in the coverage report"
            val line = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""").findAll(block).lastOrNull()
                ?: return@mapNotNull "$pkg: no line counter"
            val missed = line.groupValues[1].toDouble()
            val covered = line.groupValues[2].toDouble()
            val percent = 100.0 * covered / (covered + missed)
            logger.lifecycle("line coverage of ${pkg.replace('/', '.')}: %.1f%% (minimum %.0f%%)".format(Locale.ROOT, percent, minimum))
            if (percent < minimum) "${pkg.replace('/', '.')}: %.1f%% < %.0f%%".format(Locale.ROOT, percent, minimum) else null
        }
        if (failures.isNotEmpty()) throw GradleException("Coverage below the minimum: " + failures.joinToString("; "))
    }
}

tasks.named("check") { dependsOn(checkRunCoverage) }

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
