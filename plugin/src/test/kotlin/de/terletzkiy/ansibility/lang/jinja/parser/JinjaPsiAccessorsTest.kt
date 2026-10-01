package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBlockStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallBlock
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCompareExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaConditionalExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaElementVisitor
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaFilterBlock
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaFilterCall
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaForStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaFromImportStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaIfStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaImportStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaIncludeStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaLiteral
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMacro
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMemberAccess
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaOutputTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaRawStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSetBlockStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSetStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSlice
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaStrings
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSubscription
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTestExpr
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaWithStatement

/** The accessors that the scopes, locator and type-evaluation work units build on. */
class JinjaPsiAccessorsTest : ParsingTestCase("jinja/parser", "j2", AnsibleJinjaParserDefinition()) {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun checkAllPsiRoots(): Boolean = false

    fun testForStatement() {
        val file = parse("{% for key, value in settings.items() if value is defined recursive %}{{ key }}{% else %}none{% endfor %}")
        val loop = single<JinjaForStatement>(file)
        assertEquals(listOf("key", "value"), loop.targetNames.map { it.name })
        assertEquals("settings.items()", loop.iterable?.text)
        assertEquals("items", (loop.iterable as JinjaCallExpression).calleeName)
        assertEquals("value is defined", loop.filterCondition?.text)
        assertTrue(loop.isRecursive)
        assertEquals("{{ key }}", loop.body?.text)
        assertEquals("none", loop.elseBody?.text)
        assertEquals("endfor", loop.endTag?.tagName)
        assertEquals(2, loop.bodies.size)
        assertSame(loop.forTag, loop.openingTag)
        assertSame(loop.forTag, loop.body?.ownerTag)
    }

    fun testIfStatementBranches() {
        val file = parse("{% if a %}1{% elif b %}2{% elif c %}3{% else %}4{% endif %}")
        val statement = single<JinjaIfStatement>(file)
        assertEquals("a", statement.condition?.text)
        assertEquals(listOf("a", "b", "c", null), statement.branches.map { it.condition?.text })
        assertEquals(listOf("1", "2", "3", "4"), statement.branches.map { it.body?.text })
        assertEquals(2, statement.elifTags.size)
        assertEquals("4", statement.elseBody?.text)
    }

    fun testSetStatements() {
        val file = parse("{% set ns = namespace(found=false) %}{% set ns.found = true %}{% set a, b = 1, 2 %}{% set body | trim | upper %}x{% endset %}")
        val sets = PsiTreeUtil.findChildrenOfType(file, JinjaSetStatement::class.java).toList()
        assertEquals(listOf("ns"), sets[0].targetNames.map { it.name })
        assertEquals("namespace(found=false)", sets[0].value?.text)
        assertEquals("found", (sets[0].value as JinjaCallExpression).argumentList?.keywordArguments?.single()?.name)
        assertEquals("ns", sets[1].namespaceTarget?.namespaceName)
        assertEquals("found", sets[1].namespaceTarget?.attributeName)
        assertEmpty(sets[1].targetNames)
        assertEquals(listOf("a", "b"), sets[2].targetNames.map { it.name })
        assertSame(sets[2], sets[2].openingTag)
        val block = single<JinjaSetBlockStatement>(file)
        assertEquals(listOf("body"), block.targetNames.map { it.name })
        assertEquals("upper", block.filter?.filterName)
        assertEquals("trim", (block.filter?.operand as JinjaFilterCall).filterName)
        assertNull((block.filter?.operand as JinjaFilterCall).operand)
        assertEquals("x", block.body?.text)
    }

    fun testMacroCallFilterWithBlocks() {
        val file = parse(
            "{% macro env(key, value='x') -%}{{ key }}{%- endmacro %}" +
                "{% call(item) env('A', 1) %}{{ item }}{% endcall %}" +
                "{% filter indent(2) | upper %}t{% endfilter %}" +
                "{% with a = 1, b = c %}{% endwith %}{% block main scoped required %}{% endblock main %}",
        )
        val macro = single<JinjaMacro>(file)
        assertEquals("env", macro.name)
        assertEquals(listOf("key", "value"), macro.parameters.map { it.name })
        assertEquals(listOf(null, "'x'"), macro.parameters.map { it.defaultValue?.text })
        assertTrue(macro.macroTag!!.trimsAfter)
        assertTrue(macro.endTag!!.trimsBefore)
        val call = single<JinjaCallBlock>(file)
        assertEquals(listOf("item"), call.parameterList?.parameters?.map { it.name })
        assertEquals("env", call.call?.calleeName)
        val filter = single<JinjaFilterBlock>(file)
        assertEquals(listOf("indent", "upper"), filter.filters.map { it.filterName })
        assertEquals(listOf("2"), filter.filters.first().arguments.map { it.text })
        val with = single<JinjaWithStatement>(file)
        assertEquals(listOf("a", "b"), with.assignments.flatMap { it.target!!.targetNames }.map { it.name })
        assertEquals(listOf("1", "c"), with.assignments.map { it.value?.text })
        val block = single<JinjaBlockStatement>(file)
        assertEquals("main", block.blockName)
        assertTrue(block.blockTag!!.isScoped)
        assertTrue(block.blockTag!!.isRequired)
        assertEquals("main", block.endTag?.blockName)
    }

    fun testImportsAndIncludes() {
        val file = parse(
            "{% include 'part.j2' ignore missing without context %}{% import 'm.j2' as m %}" +
                "{% from 'f.j2' import input as field, label with context %}",
        )
        val include = single<JinjaIncludeStatement>(file)
        assertEquals("part.j2", include.templateName)
        assertTrue(include.ignoreMissing)
        assertFalse(include.withContext)
        val import = single<JinjaImportStatement>(file)
        assertEquals("m", import.alias?.name)
        assertFalse(import.withContext)
        val from = single<JinjaFromImportStatement>(file)
        assertEquals(listOf("input", "label"), from.importedNames.map { it.importedName })
        assertEquals(listOf("field", "label"), from.importedNames.map { it.boundName })
        assertTrue(from.withContext)
    }

    fun testExpressions() {
        val file = parse(
            "{{ x | ansible.builtin.splitext | first }}{{ v is not ansible.builtin.version('2', '>=') }}" +
                "{{ n is divisibleby 3 }}{{ a if b else c }}{{ 0 < x <= 9 and y not in z }}{{ items[1:-1:2] }}" +
                "{{ item.0.name }}{{ 'a' \"b\\n\" }}",
        )
        val fqcn = PsiTreeUtil.findChildrenOfType(file, JinjaFilterCall::class.java).first { it.filterName != "first" }
        assertEquals("ansible.builtin.splitext", fqcn.filterName)
        assertEquals("splitext", fqcn.filterNameElement?.shortName)
        assertTrue(fqcn.filterNameElement!!.isQualified)
        assertEquals("x", fqcn.operand?.text)
        val tests = PsiTreeUtil.findChildrenOfType(file, JinjaTestExpr::class.java).toList()
        assertEquals("ansible.builtin.version", tests[0].testName)
        assertTrue(tests[0].isNegated)
        assertEquals(listOf("'2'", "'>='"), tests[0].arguments.map { it.text })
        assertEquals(listOf("3"), tests[1].arguments.map { it.text })
        assertFalse(tests[1].isNegated)
        val conditional = single<JinjaConditionalExpression>(file)
        assertEquals(listOf("a", "b", "c"), listOf(conditional.thenExpression, conditional.condition, conditional.elseExpression).map { it?.text })
        val compares = PsiTreeUtil.findChildrenOfType(file, JinjaCompareExpression::class.java).toList()
        assertEquals(listOf("<", "<="), compares[0].operators)
        assertEquals(3, compares[0].operands.size)
        assertEquals(listOf("not in"), compares[1].operators)
        val slice = single<JinjaSlice>(file)
        assertEquals(listOf("1", "-1", "2"), listOf(slice.start, slice.stop, slice.step).map { it?.text })
        assertTrue(single<JinjaSubscription>(file).isSlice)
        val member = PsiTreeUtil.findChildrenOfType(file, JinjaMemberAccess::class.java).first { it.isIndex }
        assertEquals("0", member.memberName)
        val literal = PsiTreeUtil.findChildrenOfType(file, JinjaLiteral::class.java).last()
        assertEquals(JinjaLiteral.Kind.STRING, literal.kind)
        assertEquals("ab\n", literal.stringValue)
    }

    fun testRawStatementIsOpaque() {
        val file = parse("{% raw %}{{ if .x }}{{ end }}{% endraw %}{{ y }}")
        assertEquals("{{ if .x }}{{ end }}", single<JinjaRawStatement>(file).content)
        val names = PsiTreeUtil.findChildrenOfType(file, JinjaVariableReference::class.java).map { it.name }
        assertEquals(listOf("y"), names)
    }

    fun testVisitorDispatch() {
        val file = parse("{% for x in xs %}{{ x.y | f }}{% endfor %}{% set z = 1 %}")
        val visited = ArrayList<String>()
        file.accept(object : JinjaElementVisitor() {
            override fun visitElement(element: PsiElement) {
                element.acceptChildren(this)
            }

            override fun visitForStatement(statement: JinjaForStatement) {
                visited += "for"
                super.visitForStatement(statement)
            }

            override fun visitStatement(statement: JinjaStatement) {
                if (statement is JinjaSetStatement) visited += "statement:set"
                super.visitStatement(statement)
            }

            override fun visitVariableReference(reference: JinjaVariableReference) {
                visited += "ref:${reference.name}"
                super.visitVariableReference(reference)
            }

            override fun visitFilterCall(filter: JinjaFilterCall) {
                visited += "filter:${filter.filterName}"
                super.visitFilterCall(filter)
            }

            override fun visitOutputTag(tag: JinjaOutputTag) {
                visited += "output"
                super.visitOutputTag(tag)
            }
        })
        assertEquals(listOf("for", "ref:xs", "output", "filter:f", "ref:x", "statement:set"), visited)
    }

    fun testStringDecoding() {
        assertEquals("a'b", JinjaStrings.unquote("'a\\'b'"))
        assertEquals("tab\there", JinjaStrings.unquote("\"tab\\there\""))
        assertEquals("\\d+", JinjaStrings.unquote("'\\d+'"))
        assertEquals("é", JinjaStrings.unquote("'\\u00e9'"))
        assertEquals("A", JinjaStrings.unquote("'\\x41'"))
        assertEquals("open", JinjaStrings.unquote("'open"))
        assertEquals("line\nnext", JinjaStrings.unquote("'line\r\nnext'"))
    }

    fun testKeywordsUsedAsNames() {
        val file = parse("{% set recursive = 1 %}{{ combine(a, recursive=True) }}{% for with in as %}{{ import }}{% endfor %}")
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        val names = PsiTreeUtil.findChildrenOfType(file, JinjaVariableReference::class.java).map { it.name }
        assertEquals(listOf("combine", "a", "as", "import"), names)
        assertTrue(PsiTreeUtil.findChildrenOfType(file, JinjaVariableReference::class.java).all { it.nameIdentifier.node.elementType == AnsibleJinjaTokenTypes.IDENTIFIER })
    }

    private fun parse(text: String): AnsibleJinjaFile = createPsiFile("a", text) as AnsibleJinjaFile

    private inline fun <reified T : PsiElement> single(file: AnsibleJinjaFile): T =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).single()
}
