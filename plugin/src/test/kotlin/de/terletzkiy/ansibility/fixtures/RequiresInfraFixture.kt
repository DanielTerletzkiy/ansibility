package de.terletzkiy.ansibility.fixtures

import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.IdeaTestExecutionPolicy
import java.lang.annotation.Inherited
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Marks a test class (and its subclasses) that reads the git-ignored infra fixture ([InfraTestData.root]). Without the
 * fixture (CI, a fresh clone) the class is skipped: platform tests through [InfraFixturePolicy], plain JUnit 4 tests
 * through [InfraTestData.assumePresent].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
annotation class RequiresInfraFixture

/**
 * Skips [RequiresInfraFixture] platform tests. `plugin/build.gradle.kts` installs it (system property
 * [IdeaTestExecutionPolicy.SYSTEM_PROPERTY_NAME]) only when the fixture is missing.
 */
class InfraFixturePolicy : IdeaTestExecutionPolicy() {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun getName(): String = "ansibility-infra-fixture"

    override fun canRun(testCaseClass: Class<out UsefulTestCase>): Boolean {
        if (!super.canRun(testCaseClass)) return false
        if (!testCaseClass.isAnnotationPresent(RequiresInfraFixture::class.java) || Files.isDirectory(InfraTestData.root)) return true
        if (reported.add(testCaseClass.name)) println("${testCaseClass.name} skipped: the infra fixture is missing (${InfraTestData.root})")
        return false
    }
}
