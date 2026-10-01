package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.testFramework.ParsingTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData

/**
 * PSI dumps of the parser (plan A.5, WU C2) for the edge cases of the research report (jinja.md): Go templates in
 * `{% raw %}`, `{{ '{{' }}` escapes, whitespace control, a macro with `-%}`, namespace attribute sets, `{% endfor%}`,
 * FQCN filters and tests, `k, v` unpacking, inline `if`, `~`, slices and `.items()`; plus the error-recovery cases,
 * which must keep every error inside the tag that caused it.
 *
 * Expected trees are `testData/jinja/parser/<Name>.txt`.
 */
class AnsibleJinjaParsingTest : ParsingTestCase("jinja/parser", "j2", AnsibleJinjaParserDefinition()) {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    /** Only the Jinja tree: whether a template view provider is in play depends on what else ran in this JVM. */
    override fun checkAllPsiRoots(): Boolean = false

    fun testRawGoTemplates() = valid()
    fun testEscapedDelimiters() = valid()
    fun testWhitespaceControl() = valid()
    fun testMacroWithTrim() = valid()
    fun testNamespaceSet() = valid()
    fun testEndforWithoutSpace() = valid()
    fun testFqcnFilter() = valid()
    fun testKeyValueUnpacking() = valid()
    fun testInlineIf() = valid()
    fun testConcat() = valid()
    fun testSlices() = valid()
    fun testOperators() = valid()
    fun testStatements() = valid()
    fun testLiterals() = valid()

    fun testErrorUnclosedExpression() = withErrors()
    fun testErrorRecoversInsideTag() = withErrors()
    fun testErrorMissingEndTag() = withErrors()
    fun testErrorStrayEndTag() = withErrors()
    fun testErrorBadSet() = withErrors()
    fun testUnterminatedBlockAtEof() = withErrors()

    private fun valid() = doTest(true, true)

    private fun withErrors() = doTest(true, false)
}
