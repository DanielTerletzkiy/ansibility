package de.terletzkiy.ansibility.lang.jinja.scopes

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.locator.JinjaReferenceChains
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaImportStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaIncludeStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMemberAccess
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaNamespaceTarget
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.semantics.schema.OptionType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** [JinjaScopes] (plan A.5, WU C5): Jinja's scoping rules on the PSI, and agreement with the text-level analysis. */
class JinjaScopesTest : BasePlatformTestCase() {

    private fun parse(text: String): AnsibleJinjaFile =
        PsiFileFactory.getInstance(project).createFileFromText("scopes.j2", AnsibleJinjaLanguage, text) as AnsibleJinjaFile

    /** The bare names visible at the first occurrence of [marker] in [text] (at its start, plus [delta]). */
    private fun visible(text: String, marker: String, delta: Int = 0): Set<String> = runReadActionBlocking {
        val at = text.indexOf(marker).also { check(it >= 0) { "no '$marker'" } } + delta
        JinjaScopes.of(parse(text)).visibleNamesAt(at)
    }

    /** The binding kind each reference to [name] resolves to, in source order (null: a context variable). */
    private fun resolutions(text: String, name: String): List<JinjaLocalKind?> = runReadActionBlocking {
        val file = parse(text)
        val scopes = JinjaScopes.of(file)
        PsiTreeUtil.findChildrenOfType(file, JinjaVariableReference::class.java).filter { it.name == name }.map { scopes.resolve(it)?.kind }
    }

    fun testForOpensAScopeWithTargetsAndLoop() {
        val text = "{% for x in xs if x.ok %}{{ x }}{{ loop.index }}{% else %}{{ empty }}{% endfor %}{{ after }}"
        assertEquals(setOf("x", "loop"), visible(text, "{{ x }}", 3))
        assertEquals("the loop filter sees the targets, not loop", setOf("x"), visible(text, "x.ok"))
        assertEquals("the iterable is outside the loop", emptySet<String>(), visible(text, "xs"))
        assertEquals("the else body runs without the targets", emptySet<String>(), visible(text, "{{ empty }}", 3))
        assertEquals(emptySet<String>(), visible(text, "{{ after }}", 3))
        assertEquals(listOf(JinjaLocalKind.FOR_TARGET, JinjaLocalKind.FOR_TARGET), resolutions(text, "x"))
        assertEquals(listOf(JinjaLocalKind.LOOP), resolutions(text, "loop"))
        assertEquals(listOf<JinjaLocalKind?>(null), resolutions(text, "xs"))
    }

    fun testSetInsideForDoesNotLeakButInsideIfDoes() {
        val text = "{% for i in r %}{% set inner = 1 %}{{ inner }}{% endfor %}{{ inner }}" +
            "{% if c %}{% set leak = 1 %}{% else %}{% set other = 2 %}{% endif %}{{ leak }}{{ other }}"
        assertEquals(listOf(JinjaLocalKind.SET, null), resolutions(text, "inner"))
        assertEquals("if opens no scope", listOf(JinjaLocalKind.SET), resolutions(text, "leak"))
        assertEquals(listOf(JinjaLocalKind.SET), resolutions(text, "other"))
    }

    fun testSetValueReadsTheOuterBinding() {
        val text = "{% set x = 1 %}{% for i in r %}{% set x = x + 1 %}{% endfor %}"
        val (outer, inner) = runReadActionBlocking {
            val file = parse(text)
            val scopes = JinjaScopes.of(file)
            val reference = PsiTreeUtil.findChildrenOfType(file, JinjaVariableReference::class.java).single { it.name == "x" }
            scopes.resolve(reference) to scopes.bindings.filter { it.name == "x" }
        }
        assertEquals("`x + 1` reads the x bound before the loop", inner.first().definitionRange, outer?.definitionRange)
        assertEquals(2, inner.size)
    }

    fun testMacroScope() {
        val text = "{% macro m(a, b=a) %}{{ a }}{{ caller() }}{{ m }}{% endmacro %}{{ m(1) }}{{ a }}"
        assertEquals(setOf("m", "a", "b", "varargs", "kwargs", "caller"), visible(text, "{{ a }}", 3))
        assertEquals("defaults are read outside the body", listOf(null, JinjaLocalKind.MACRO_PARAMETER, null), resolutions(text, "a"))
        assertEquals(listOf(JinjaLocalKind.MACRO_IMPLICIT), resolutions(text, "caller"))
        assertEquals("the macro name is visible from its tag on", listOf(JinjaLocalKind.MACRO, JinjaLocalKind.MACRO), resolutions(text, "m"))
        assertEquals(setOf("m"), visible(text, "{{ m(1) }}", 3))
    }

    fun testCallFilterWithAndBlockScopes() {
        assertEquals(listOf(JinjaLocalKind.CALL_PARAMETER, null), resolutions("{% call(user) list_users(users) %}{{ user }}{% endcall %}{{ user }}", "user"))
        assertEquals(listOf(JinjaLocalKind.SET, null), resolutions("{% filter upper %}{% set f = 1 %}{{ f }}{% endfilter %}{{ f }}", "f"))
        val with = "{% with a = outer %}{{ a }}{% endwith %}{{ a }}"
        assertEquals(listOf(JinjaLocalKind.WITH, null), resolutions(with, "a"))
        assertEquals("with values are read outside", listOf<JinjaLocalKind?>(null), resolutions(with, "outer"))
        assertEquals(listOf<JinjaLocalKind?>(null), resolutions("{% block b %}{% set s = 1 %}{% endblock %}{{ s }}", "s"))
    }

    fun testBlockSetIsBoundAfterEndset() {
        val text = "{% set content | trim %}{{ content }}{% endset %}{{ content }}"
        assertEquals(listOf(null, JinjaLocalKind.BLOCK_SET), resolutions(text, "content"))
    }

    fun testIncludePassesTheContextImportDoesNot() {
        val text = "{% set shared = 1 %}{% include 'a.j2' %}{% import 'm.j2' as m %}{% import 'n.j2' as n with context %}" +
            "{% from 'f.j2' import g as h, k %}{{ h }}{{ k }}{{ m }}"
        runReadActionBlocking {
            val file = parse(text)
            val scopes = JinjaScopes.of(file)
            val include = PsiTreeUtil.findChildOfType(file, JinjaIncludeStatement::class.java)!!
            assertEquals(listOf("shared"), scopes.contextPassedTo(include).map { it.name })
            val (plain, withContext) = PsiTreeUtil.findChildrenOfType(file, JinjaImportStatement::class.java).toList()
            assertEmpty(scopes.contextPassedTo(plain))
            assertEquals(setOf("shared", "m"), scopes.contextPassedTo(withContext).mapTo(HashSet()) { it.name })
        }
        assertEquals(listOf(JinjaLocalKind.IMPORT), resolutions(text, "h"))
        assertEquals(listOf(JinjaLocalKind.IMPORT), resolutions(text, "k"))
        assertEquals(listOf(JinjaLocalKind.IMPORT), resolutions(text, "m"))
    }

    fun testNamespaceAttributes() {
        val text = "{% set ns = namespace(found=false) %}{% for x in xs %}{% set ns.found = true %}{% set ns.last = x %}{% endfor %}" +
            "{{ ns.found }}{{ ns.last }}"
        runReadActionBlocking {
            val file = parse(text)
            val scopes = JinjaScopes.of(file)
            val attributes = scopes.bindings.filter { it.kind == JinjaLocalKind.NAMESPACE_ATTRIBUTE }
            assertEquals(listOf("found", "found", "last"), attributes.map { it.name })
            assertTrue(attributes.all { it.owner == "ns" })
            val target = PsiTreeUtil.findChildrenOfType(file, JinjaNamespaceTarget::class.java).first()
            assertEquals("set ns.found reads ns", "ns", scopes.resolve(target)?.name)
            val accesses = PsiTreeUtil.findChildrenOfType(file, JinjaMemberAccess::class.java).associateBy { it.text }
            assertEquals(JinjaLocalKind.NAMESPACE_ATTRIBUTE, scopes.resolveMember(accesses.getValue("ns.found"))?.kind)
            assertEquals("namespace writes leave the loop", "last", scopes.resolveMember(accesses.getValue("ns.last"))?.name)
            val after = text.indexOf("{{ ns.found }}")
            assertEquals("attributes are no bare names", setOf("ns"), scopes.visibleNamesAt(after))
            assertEquals(setOf("found", "last"), scopes.attributesOf(scopes.resolve(accesses.getValue("ns.found").qualifier as JinjaVariableReference)!!, after).mapTo(HashSet()) { it.name })
        }
    }

    fun testLoopAttributes() {
        assertContainsElements(JinjaScopes.LOOP_ATTRIBUTES.keys, "index", "index0", "revindex", "first", "last", "length", "previtem", "nextitem", "depth", "cycle", "changed")
        assertEquals(OptionType.Int, JinjaScopes.LOOP_ATTRIBUTES["index"])
        assertEquals(OptionType.Bool, JinjaScopes.LOOP_ATTRIBUTES["last"])
    }

    fun testModelIsCachedUntilTheFileChanges() {
        runReadActionBlocking {
            val file = parse("{% set a = 1 %}{{ a }}")
            assertSame(JinjaScopes.of(file), JinjaScopes.of(file))
        }
    }

    /**
     * The PSI scopes bind the same locals (name, kind, binding range, scope, namespace owner) and resolve every
     * reference the same way as the text-level analysis, on every template of the infra fixture.
     */
    fun testAgreesWithTheTextLevelAnalysisOnTheFixture() {
        val root = InfraTestData.testDataPath.resolve(InfraTestData.INFRA)
        val templates = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && (it.extension == "j2" || isRoleTemplate(it)) }.sorted().toList()
        }
        assertTrue("expected the fixture's templates, found ${templates.size}", templates.size > 100)
        val problems = ArrayList<String>()
        var locals = 0
        var references = 0
        for (path in templates) {
            val text = path.readText()
            val expected = JinjaRefs.analyze(text)
            val expectedLocals = expected.locals.map { "${it.name}/${it.kind}/${it.definitionRange}/${it.scope}/${it.owner}" }.sorted()
            val expectedRefs = (expected.references + expected.localReferences)
                .associate { it.nameRange to (listOf(it.name) + it.attrPath).joinToString(".") + "->" + it.local?.definitionRange }
            val (actualLocals, actualRefs) = runReadActionBlocking {
                val file = parse(text)
                val bindings = JinjaScopes.of(file).bindings.map { "${it.name}/${it.kind}/${it.definitionRange}/${it.scope}/${it.owner}" }.sorted()
                val chains = JinjaReferenceChains.of(file)
                    .associate { it.nameRange to (listOf(it.name) + it.path).joinToString(".") + "->" + it.binding?.definitionRange }
                bindings to chains
            }
            locals += expectedLocals.size
            references += expectedRefs.size
            val relative = root.relativize(path)
            if (expectedLocals != actualLocals) problems += "$relative locals:\n  text ${expectedLocals - actualLocals.toSet()}\n  psi  ${actualLocals - expectedLocals.toSet()}"
            if (expectedRefs != actualRefs) {
                val missing = expectedRefs.entries.filter { actualRefs[it.key] != it.value }.take(5)
                val extra = actualRefs.entries.filter { expectedRefs[it.key] != it.value }.take(5)
                problems += "$relative references:\n  text $missing\n  psi  $extra"
            }
        }
        println("JinjaScopesTest: ${templates.size} templates, $locals locals, $references references compared")
        assertEmpty(problems.take(20).joinToString("\n"), problems)
    }

    private fun isRoleTemplate(path: Path): Boolean {
        val segments = path.map { it.toString() }
        val templates = segments.indexOf("templates")
        if (templates < 2 || segments[templates - 2] != "roles") return false
        val text = runCatching { path.readText() }.getOrNull() ?: return false
        return "{{" in text || "{%" in text
    }
}
