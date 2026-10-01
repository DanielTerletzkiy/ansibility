package de.terletzkiy.ansibility.typeflow

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.inspections.templated.AnsibleTemplatedValueTypeInspection
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.JinjaExpr
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTemplate
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTypeEvaluator
import de.terletzkiy.ansibility.semantics.typeflow.TemplateShape
import de.terletzkiy.ansibility.semantics.typeflow.TemplatingRules
import de.terletzkiy.ansibility.semantics.typeflow.VariableDefinition
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq

/**
 * The plugin's Jinja lexer feeds :semantics' template model: the shapes, the bare-name shortcut and the evaluated
 * types match what the evaluator's own unit tests pin with their reference tokenizer. Also: the inspection's
 * registration.
 */
class LexerJinjaTokenizerTest : BasePlatformTestCase() {

    private fun parse(text: String) = JinjaTemplate.parse(text, LexerJinjaTokenizer)

    fun testShapes() {
        val cases = listOf(
            "{{ x }}" to TemplateShape.SINGLE_EXPRESSION,
            "{{ x }}\n" to TemplateShape.SINGLE_EXPRESSION,
            " {{ x }}" to TemplateShape.MULTI_NODE,
            " {{- x }}" to TemplateShape.SINGLE_EXPRESSION,
            "{# c #}{{ x }}" to TemplateShape.SINGLE_EXPRESSION,
            "{{ x }}{{ y }}" to TemplateShape.MULTI_NODE,
            "a-{{ x | int }}" to TemplateShape.MULTI_NODE,
            "{% if b %}1{% endif %}" to TemplateShape.STATEMENTS,
            "{% raw %}{{ x }}{% endraw %}" to TemplateShape.TEXT_ONLY,
            "{{ x" to TemplateShape.MALFORMED,
            "{{ x ? y }}" to TemplateShape.MALFORMED,
            "{{ {'a': {'b': 1}} }}" to TemplateShape.SINGLE_EXPRESSION,
        )
        assertEquals(cases.map { "${it.first} → ${it.second}" }, cases.map { "${it.first} → ${parse(it.first).shape}" })
        assertEquals("x", parse("{{ x }}\n").bareName)
        assertNull(parse(" {{- x }}").bareName)
        assertNull(parse("{{ x | int }}").bareName)
    }

    fun testExpressionsFromLexerTokens() {
        val filter = parse("{{ items | ansible.builtin.join(',') | int }}").singleExpression as JinjaExpr.Filter
        assertEquals("int", filter.name)
        assertEquals("ansible.builtin.join", (filter.target as JinjaExpr.Filter).name)
        val conditional = parse("{{ a if b is defined else c ~ 'x' }}").singleExpression as JinjaExpr.Conditional
        assertTrue(conditional.condition is JinjaExpr.Test)
        assertTrue(conditional.otherwise is JinjaExpr.Concat)
        val access = parse("{{ servers[0].port }}").singleExpression as JinjaExpr.Attribute
        assertEquals("port", access.attribute)
        val keywordNames = parse("{{ item.block ~ raw }}").singleExpression as JinjaExpr.Concat
        assertEquals("statement keywords are names inside expressions", "raw", (keywordNames.parts[1] as JinjaExpr.Name).name)
    }

    fun testEvaluatedTypesMatchTheMeasuredCores() {
        val definitions = mapOf(
            "i" to YScalar("5", ScalarStyle.PLAIN),
            "a1" to YScalar("{{ i }}", ScalarStyle.SINGLE_QUOTED),
            "items" to YSeq(listOf(YScalar("a", ScalarStyle.PLAIN), YScalar("b", ScalarStyle.PLAIN))),
        )
        fun evaluator(core: CoreVersion) = JinjaTypeEvaluator(
            TemplatingRules(CoreSemantics(core)), LexerJinjaTokenizer,
            { name -> definitions[name]?.let { listOf(VariableDefinition("vars.yml:1", it)) } },
        )
        val classic = evaluator(CoreVersion(2, 18, 8))
        val native = evaluator(CoreVersion(2, 21, 4))
        fun types(value: AValue) = if (value.unknown) "unknown" else value.types.joinToString("|") { it.pyName }
        val rows = listOf("{{ i }}", "{{ a1 }}", "{{ i | int }}", "{{ items | join(',') }}", "{{ items | length }}", "x-{{ i }}", "{{ items }}")
        assertEquals(
            listOf(
                "{{ i }}: int / int / int",
                "{{ a1 }}: int / str / int",
                "{{ i | int }}: int / str / int",
                "{{ items | join(',') }}: str / bool|str|list|dict|object / str",
                "{{ items | length }}: int / str / int",
                "x-{{ i }}: str / str / str",
                "{{ items }}: list / list / list",
            ),
            rows.map { "$it: ${types(classic.evaluate(it).logical)} / ${types(classic.evaluate(it).runtime)} / ${types(native.evaluate(it).runtime)}" },
        )
    }

    fun testInspectionIsRegisteredUnderAnsibilityTypes() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == AnsibleTemplatedValueTypeInspection.SHORT_NAME }
        assertEquals(AnsibleTemplatedValueTypeInspection::class.java.name, ep.implementationClass)
        assertEquals("yaml", ep.language)
        assertTrue(ep.enabledByDefault)
        assertEquals("ERROR", ep.level)
        assertEquals("Ansibility", ep.groupPath)
        val tool = InspectionToolRegistrar.getInstance().createTools().single { it.shortName == AnsibleTemplatedValueTypeInspection.SHORT_NAME }
        assertEquals("Templated value type (ANS-T020)", tool.displayName)
        assertEquals("Types", tool.groupDisplayName)
        assertEquals(listOf("Ansibility", "Types"), tool.groupPath.toList())
        assertTrue("the description file exists", !tool.loadDescription().isNullOrBlank())
    }
}
