package de.terletzkiy.ansibility.semantics.yaml

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * `YamlText` (SnakeYAML) against the PyYAML ground truth shared with the plugin's `PsiYValueAdapter` tests: every
 * `plugin/src/test/testData/yaml/golden/corpus/<name>.yml` must render to `<name>.yvalue.txt`. The `pyyaml-only`
 * files hold what SnakeYAML's composed nodes cannot express (explicit standard tags, repeated merge keys).
 */
class YamlTextGoldenTest {
    private val corpus = File(System.getProperty("user.dir"), "../plugin/src/test/testData/yaml/golden/corpus")

    @TestFactory
    fun `YamlText matches PyYAML on the shared corpus`(): List<DynamicTest> {
        val files = corpus.listFiles { file -> file.name.endsWith(".yml") }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "golden corpus not found at ${corpus.canonicalPath}")
        return files.map { source ->
            DynamicTest.dynamicTest(source.name) {
                val expected = File(source.path.removeSuffix(".yml") + ".yvalue.txt").readText()
                assertEquals(expected, YValueDump.render(YamlText.parse(source.readText())))
            }
        }
    }
}
