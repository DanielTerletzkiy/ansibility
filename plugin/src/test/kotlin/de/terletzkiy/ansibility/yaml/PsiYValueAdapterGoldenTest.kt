package de.terletzkiy.ansibility.yaml

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import java.io.File

/**
 * The adapter against PyYAML: each `golden/{corpus,pyyaml-only}/<name>.yml` must convert to exactly what
 * `<name>.yvalue.txt` records, which `generate.py` wrote with Ansible's PyYAML. The `corpus/` files are also checked
 * against the SnakeYAML-based `YamlText` in the :semantics tests, so all three agree on them.
 */
class PsiYValueAdapterGoldenTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/yaml/golden"

    fun testScalarsPlain() = check("corpus/scalars-plain")

    fun testScalarsQuoted() = check("corpus/scalars-quoted")

    fun testScalarsBlock() = check("corpus/scalars-block")

    fun testBlockScalarAtEndOfFile() = check("corpus/block-at-eof")

    fun testTopLevelBlockScalar() = check("corpus/top-level-block")

    fun testTagsAndVault() = check("corpus/tags-vault")

    fun testEmptyValues() = check("corpus/empty")

    fun testAnchorsAndMergeKeys() = check("corpus/anchors-merge")

    fun testCollections() = check("corpus/collections")

    fun testPlaybook() = check("corpus/playbook")

    fun testStandardTags() = check("pyyaml-only/standard-tags")

    fun testAnchorsWithTags() = check("pyyaml-only/anchors-with-tags")

    fun testMergeRules() = check("pyyaml-only/merge-rules")

    fun testFirstDocumentOnly() = check("pyyaml-only/documents")

    fun testJsonEscapes() = check("pyyaml-only/json-escapes")

    fun testEveryGoldenFileIsChecked() {
        val names = listOf("corpus", "pyyaml-only").flatMap { dir ->
            val sources = File(testDataPath, dir).listFiles { file -> file.name.endsWith(".yml") }.orEmpty()
            sources.map { "$dir/${it.name.removeSuffix(".yml")}" }
        }
        // Every test method checks one file, except this one.
        val checked = javaClass.methods.count { it.name.startsWith("test") } - 1
        assertEquals("add a test method for each golden file: $names", names.size, checked)
    }

    private fun check(name: String) {
        val source = File(testDataPath, "$name.yml")
        val expected = File(testDataPath, "$name.yvalue.txt").readText()
        val file = myFixture.configureByText(source.name, source.readText()) as YAMLFile
        assertEquals(name, expected, YValueDump.render(PsiYValueAdapter.documentValue(file)))
    }
}
