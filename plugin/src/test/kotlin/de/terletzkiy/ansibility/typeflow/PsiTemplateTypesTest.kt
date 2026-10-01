package de.terletzkiy.ansibility.typeflow

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaInjectionMode
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaInjectionTestCase
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaOutputTag
import de.terletzkiy.ansibility.semantics.typeflow.BaseType
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTypeEvaluator
import de.terletzkiy.ansibility.semantics.typeflow.TemplateTypes
import de.terletzkiy.ansibility.semantics.typeflow.TemplatingRules
import de.terletzkiy.ansibility.semantics.typeflow.VariableDefinition
import de.terletzkiy.ansibility.semantics.typeflow.VariableResolver
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** [PsiTemplateTypes]: the T020 evaluation fed from the Jinja PSI agrees with the text path, and types PSI expressions. */
class PsiTemplateTypesTest : JinjaInjectionTestCase() {
    private val resolver = VariableResolver { name ->
        when (name) {
            "td_port" -> listOf(VariableDefinition("defaults/main.yml:1", YScalar("8080", ScalarStyle.PLAIN)))
            "td_name" -> listOf(VariableDefinition("defaults/main.yml:2", YScalar("web", ScalarStyle.PLAIN)))
            else -> null
        }
    }

    private fun evaluator() = JinjaTypeEvaluator(TemplatingRules(), LexerJinjaTokenizer, resolver)

    private fun describe(types: TemplateTypes): String =
        "${types.logical} / ${types.runtime} / ${types.origin} / ${types.template.shape} / ${types.template.bareName}"

    fun testPsiAndTextPathsAgreeOnTemplatedValues() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        val defaults = "site/roles/web/defaults/main.yml"
        createFile(
            defaults,
            """
            td_bare: "{{ td_port }}"
            td_plain: port-{{ td_port | int }}
            td_single: '{{ td_name ~ "x" }}'
            td_multi: "{{ td_port }}-{{ td_name }}"
            td_escaped: "a\tb {{ td_port | string }}\n"
            td_statement: "{% if td_port %}{{ td_port }}{% endif %}"
            td_raw: "{% raw %}{{ go }}{% endraw %}{{ td_port }}"
            td_comment: "{# note #}{{ td_port }}"
            td_trim: "  {{- td_port -}}  "
            td_literal: |
              {{ td_port }}
            td_folded: >-
              {{ td_name }}
              and more
            td_unknown: "{{ lookup('env', 'HOME') }}"
            """,
        )
        // the PSI entry reads fragments the platform has built (highlighting does this for an open file)
        assertTrue(fragments(defaults).size >= 10)
        val (psiPath, textPath, compared) = inBackgroundReadAction {
            val file = PsiManager.getInstance(project).findFile(vf(defaults))!!
            var psi = 0
            var fallback = 0
            val differences = ArrayList<String>()
            for (keyValue in PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)) {
                val value = PsiYValueAdapter.valueOf(keyValue) as? YScalar ?: continue
                val host = keyValue.value as? YAMLScalar ?: continue
                val template = PsiTemplateTypes.templateOf(host, value.text)
                if (template == null) {
                    fallback++
                    continue
                }
                psi++
                val fromPsi = describe(evaluator().evaluate(template))
                val fromText = describe(evaluator().evaluate(value.text))
                if (fromPsi != fromText) differences += "${keyValue.keyText}: psi=$fromPsi text=$fromText"
            }
            Triple(psi, fallback, differences)
        }
        assertEmpty(compared.joinToString("\n"), compared)
        assertTrue("most values are evaluated from the PSI: $psiPath of ${psiPath + textPath}", psiPath >= 10)
    }

    /** A fragment the platform has not built is never computed for the type check: the text path answers. */
    fun testFragmentsAreNotInjectedForTheTypeCheck() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        val defaults = "site/roles/web/defaults/main.yml"
        createFile(defaults, "td_x: \"{{ td_port }}\"\n")
        fun template() = inBackgroundReadAction {
            val file = PsiManager.getInstance(project).findFile(vf(defaults))!!
            PsiTemplateTypes.templateOf(PsiTreeUtil.findChildOfType(file, YAMLScalar::class.java)!!, "{{ td_port }}")
        }
        assertNull("not injected yet", template())
        assertEquals(1, fragments(defaults).size)
        val built = template()
        assertNotNull("read from the built fragment", built)
        assertEquals(describe(evaluator().evaluate("{{ td_port }}")), describe(evaluator().evaluate(built!!)))
    }

    fun testExpressionModeValuesHaveNoTemplate() {
        createFile("site/ansible.cfg", "[defaults]")
        val tasks = "site/roles/web/tasks/main.yml"
        createFile(tasks, "- ansible.builtin.debug:\n    msg: x\n  when: td_port > 0\n")
        assertEquals(JinjaInjectionMode.EXPRESSION, fragments(tasks).single().mode)
        val template = inBackgroundReadAction {
            val file = PsiManager.getInstance(project).findFile(vf(tasks))!!
            val host = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).single { it.keyText == "when" }.value as YAMLScalar
            PsiTemplateTypes.templateOf(host, "td_port > 0")
        }
        assertNull(template)
    }

    fun testStaleOrDifferentTextFallsBack() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        val defaults = "site/roles/web/defaults/main.yml"
        createFile(defaults, "td_x: \"{{ td_port }}\"\n")
        assertEquals(1, fragments(defaults).size)
        val template = inBackgroundReadAction {
            val file = PsiManager.getInstance(project).findFile(vf(defaults))!!
            val host = PsiTreeUtil.findChildOfType(file, YAMLScalar::class.java)!!
            PsiTemplateTypes.templateOf(host, "{{ td_name }}")
        }
        assertNull("a fragment reading other text is never used", template)
    }

    fun testLogicalTypeOfPsiExpressions() {
        val types = runReadActionBlocking {
            val file = PsiFileFactory.getInstance(project).createFileFromText(
                "types.j2", AnsibleJinjaLanguage,
                "{{ td_port | int }}{{ 'a' ~ td_name }}{{ td_port is defined }}{{ [1, 2] }}{{ td_port }}{{ other }}{{ td_name | length }}",
            ) as AnsibleJinjaFile
            PsiTreeUtil.findChildrenOfType(file, JinjaOutputTag::class.java).map { PsiTemplateTypes.logicalType(it.expression!!, evaluator()) }
        }
        assertEquals(setOf(BaseType.INT), types[0].types)
        assertEquals(setOf(BaseType.STR), types[1].types)
        assertEquals(setOf(BaseType.BOOL), types[2].types)
        assertEquals(setOf(BaseType.LIST), types[3].types)
        assertEquals("a bare name follows its definition", setOf(BaseType.INT), types[4].types)
        assertTrue("an unknown name is unknown", types[5].unknown)
        assertEquals(setOf(BaseType.INT), types[6].types)
    }
}
