package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.vfs.VfsUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * ANS-V003 and the FU2 additions to `ansible.var.use` (documented in [TextJinjaUses]): reads by name through `hostvars`
 * or `vars` and the outside names of braced implicit expressions are indexed for Find Usages but not judged, so the
 * findings of the fixture stay what they were. Each case has a control: the same name read directly is reported.
 */
@RequiresInfraFixture
class UndefinedIndirectReadsTest : UndefinedTestCase() {
    private val prodLine = "alloy_tenant_api_key: \"{{ vault_alloy_tenant_api_key_prod }}\""

    private fun keyFindings(path: String) = findings(path).filter { it.contains(" alloy_tenant_api_key") }

    fun testReadsThroughHostvarsAndVarsAreNotJudged() {
        replace(PROD_ALL, prodLine, "")
        val lines = VfsUtil.loadText(vf(CONFIG_BASE)).lines().toMutableList()
        assertEquals(listOf(GUARD, "    bearer_token = \"{{ alloy_tenant_api_key }}\"", "{% endif %}"), lines.subList(31, 34))
        // the guards of lines 15/17 and 32/34 go, so line numbers stay
        for (index in listOf(14, 16, 31, 33)) lines[index] = ""
        lines[15] = "    bearer_token = \"{{ hostvars[groups['monitoring_client'][0]].alloy_tenant_api_key }}\""
        lines[32] = "    bearer_token = \"{{ vars['alloy_tenant_api_key'] }}{{ lookup('vars', 'alloy_tenant_api_key') }}\""
        write(CONFIG_BASE, lines.joinToString("\n"))
        assertEmpty("a hostvars member is another host's variable; vars reads are not judged", keyFindings(CONFIG_BASE))

        replace(CONFIG_BASE, "{{ vars['alloy_tenant_api_key'] }}", "{{ alloy_tenant_api_key }}")
        assertEquals(listOf("33 MISSING alloy_tenant_api_key prod/prod-prod1,prod/prod-prod2"), keyFindings(CONFIG_BASE))
    }

    fun testOutsideNamesOfBracedExpressionsAreNotJudged() {
        replace(PROD_ALL, prodLine, "")
        val tasks = "$ALLOY/tasks/configure.yml"
        val text = VfsUtil.loadText(vf(tasks))
        val braced = """

            - name: Braced condition
              ansible.builtin.debug:
                msg: braced
              when: "'{{ 'x' }}' in alloy_tenant_api_key"
        """.trimIndent()
        write(tasks, text + braced + "\n")
        assertEmpty("the braced condition is analysed as the template it is rendered as first", keyFindings(tasks))

        replace(tasks, "\"'{{ 'x' }}' in alloy_tenant_api_key\"", "\"'x' in alloy_tenant_api_key\"")
        assertEquals("the same condition without braces is judged", 1, keyFindings(tasks).size)
    }
}
