package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * The definedness facts of conditions ([ConditionFacts], F8.12 (c)), from the PSI ([JinjaConditions]) and from tokens
 * ([JinjaTokenExpressions]): both front ends must give the expected facts under the 2.18 and the 2.19+ rules.
 */
class ConditionFactsTest : BasePlatformTestCase() {
    private val v218 = GuardRules.of(CoreVersion(2, 18, 8))
    private val v221 = GuardRules.of(CoreVersion(2, 21, 4))

    /** (condition, names proved when true, names proved when false), the same under both rule sets. */
    private val table: List<Triple<String, Set<String>, Set<String>>> = listOf(
        Triple("x is defined", setOf("x"), emptySet()),
        Triple("x is not defined", emptySet(), setOf("x")),
        Triple("x is undefined", emptySet(), setOf("x")),
        Triple("x.y['z'] is defined", setOf("x"), emptySet()),
        Triple("x is defined and y is defined", setOf("x", "y"), emptySet()),
        Triple("x is defined or y is defined", emptySet(), emptySet()),
        Triple("x is not defined or y is not defined", emptySet(), setOf("x", "y")),
        Triple("not (x is not defined)", setOf("x"), emptySet()),
        Triple("x is defined and x", setOf("x"), emptySet()),
        Triple("x", emptySet(), emptySet()),
        Triple("x is not none", emptySet(), emptySet()),
        Triple("x | length > 0", emptySet(), emptySet()),
        Triple("x | default(false)", setOf("x"), emptySet()),
        Triple("x | d(false)", setOf("x"), emptySet()),
        Triple("x | default(false) | bool", setOf("x"), emptySet()),
        Triple("x | default('no') | bool", setOf("x"), emptySet()),
        Triple("x | default(true) | bool", emptySet(), setOf("x")),
        Triple("x | default('yes') | bool", emptySet(), setOf("x")),
        Triple("x | default('') | trim | length > 0", setOf("x"), emptySet()),
        Triple("x | default([]) | length > 0", setOf("x"), emptySet()),
        Triple("x | default({}) | length", setOf("x"), emptySet()),
        Triple("x | default('', true) | string | length > 0", setOf("x"), emptySet()),
        Triple("x | default(false) | string", emptySet(), setOf("x")),
        Triple("x | default(0) | int > 0", setOf("x"), emptySet()),
        Triple("x | default('1') | int == 1", emptySet(), setOf("x")),
        Triple("x | default('A') | lower == 'a'", emptySet(), setOf("x")),
        Triple("x | default(none) is none", emptySet(), setOf("x")),
        Triple("x | default(none) is not none", setOf("x"), emptySet()),
        Triple("x | default([]) | length > 0 and x[0] is defined", setOf("x"), emptySet()),
        Triple("x | default(false) or x is defined", setOf("x"), emptySet()),
        Triple("not x | default(false)", emptySet(), setOf("x")),
        Triple("x | default(y)", emptySet(), emptySet()),
        Triple("x | default(omit)", emptySet(), emptySet()),
        Triple("'a' in x | default([])", setOf("x"), emptySet()),
        Triple("x | default(false) if y else z", emptySet(), emptySet()),
        Triple("x | ansible.builtin.default(false)", emptySet(), emptySet()),
        Triple("x | default(false) | ansible.builtin.bool", emptySet(), emptySet()),
        Triple("x | default(1) > 0 > -1", emptySet(), emptySet()),
        Triple("x | default(2) > 1 > 0", emptySet(), setOf("x")),
    )

    /** Conditions whose facts depend on the target version: (condition, 2.18 when true, 2.19+ when true). */
    private val versioned: List<Triple<String, Set<String>, Set<String>>> = listOf(
        Triple("x | int | default(0) > 0", emptySet(), setOf("x")),
        Triple("x | lower | default('') | length", emptySet(), setOf("x")),
        Triple("x | bool | default(false)", emptySet(), setOf("x")),
    )

    private fun psiFacts(condition: String, rules: GuardRules): DefinednessFacts = runReadActionBlocking {
        val file = PsiFileFactory.getInstance(project).createFileFromText("c.j2", AnsibleJinjaLanguage, "{{ $condition }}")
        val jinja = file.viewProvider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
        JinjaConditions.facts(jinja.outputTags.single().expression, rules)
    }

    private fun tokenFacts(condition: String, rules: GuardRules): DefinednessFacts {
        val tokens = JinjaTokenExpressions.tokens(condition, JinjaLexMode.EXPRESSION)
        val node = JinjaTokenExpressions.expression(condition, tokens, 0, tokens.size) ?: error("no expression: $condition")
        return ConditionFacts.of(JinjaTokenExpressions.cond(node), rules)
    }

    private fun check(condition: String, rules: GuardRules, whenTrue: Set<String>, whenFalse: Set<String>) {
        for ((side, facts) in listOf("PSI" to psiFacts(condition, rules), "tokens" to tokenFacts(condition, rules))) {
            assertEquals("$side, true: $condition ($rules)", whenTrue, facts.whenTrue)
            assertEquals("$side, false: $condition ($rules)", whenFalse, facts.whenFalse)
        }
    }

    fun testFactsTable() {
        for ((condition, whenTrue, whenFalse) in table) {
            check(condition, v218, whenTrue, whenFalse)
            check(condition, v221, whenTrue, whenFalse)
        }
    }

    fun testVersionDependentFacts() {
        for ((condition, old, new) in versioned) {
            check(condition, v218, old, emptySet())
            check(condition, v221, new, emptySet())
        }
    }

    fun testInlineFactsFromTokens() {
        val text = "{{ x | default(false) and x.y }} {{ x.z if x | default('') else 'n' }} {{ x is not defined or x.w }}"
        val tokens = JinjaTokenExpressions.tokens(text, JinjaLexMode.TEMPLATE)
        val tags = tokens.indices.filter { tokens[it].type == AnsibleJinjaTokenTypes.VAR_START }
        val references = listOf("x.y" to 0, "x.z" to 1, "x.w" to 2).map { (access, tag) ->
            val offset = text.indexOf(access)
            Triple(access, tag, tokens.indexOfFirst { it.start == offset })
        }
        for ((access, tag, index) in references) {
            val start = tags[tag] + 1
            val end = (start until tokens.size).first { tokens[it].type == AnsibleJinjaTokenTypes.VAR_END }
            val node = JinjaTokenExpressions.expressionsOf(text, tokens, start, end).single()
            assertEquals(access, setOf("x"), JinjaTokenExpressions.inlineFacts(node, index, v218))
        }
    }

    fun testUnparsableTokensGiveNoExpression() {
        val text = "x is defined and"
        val tokens = JinjaTokenExpressions.tokens(text, JinjaLexMode.EXPRESSION)
        assertNull(JinjaTokenExpressions.expression(text, tokens, 0, tokens.size))
    }
}
