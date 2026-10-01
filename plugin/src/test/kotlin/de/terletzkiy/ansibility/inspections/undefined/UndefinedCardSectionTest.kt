package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject

/**
 * The card rows of ANS-V003 (F8.2, F8.12 (f)): "Not set for" and "No runtime default", called directly and through
 * [CardSection.collect] (the card owner's seam).
 */
class UndefinedCardSectionTest : UndefinedTestCase() {
    private val prodLine = "alloy_tenant_api_key: \"{{ vault_alloy_tenant_api_key_prod }}\""

    private fun falcon(): AnsibleRoot = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }

    private fun card(name: String, file: String = CONFIG_BASE, local: Boolean = false): String? = runReadActionBlocking {
        UndefinedCardSection().section(CardSubject.Variable(falcon(), name, emptyList(), null, local), CardContext(project, vf(file), 0))?.toString()
    }

    fun testEveryEnvironmentSetsTheKey() {
        val html = card("alloy_tenant_api_key")!!
        assertFalse(html, "Not set for" in html)
        assertTrue(html, "No runtime default" in html && "unguarded uses fail where it is not set" in html)
    }

    fun testNotSetForTheProdHosts() {
        replace(PROD_ALL, prodLine, "")
        val html = card("alloy_tenant_api_key")!!
        assertTrue(html, "Not set for" in html)
        assertTrue(html, "env prod (hosts prod-prod1, prod-prod2)" in html)
        assertTrue(html, "No runtime default" in html)
    }

    fun testFromAnInventoryFileTheDeclaringRolesAreUsed() {
        replace(PROD_ALL, prodLine, "")
        val html = card("alloy_tenant_api_key", file = "$FALCON/environments/test/group_vars/all/vars.yml")!!
        assertTrue(html, "env prod (hosts prod-prod1, prod-prod2)" in html)
        assertTrue(html, "alloy: unguarded uses fail where it is not set" in html)
    }

    fun testNoRowsForDefaultsRequiredLocalsAndSpecialVariables() {
        assertNull("role default", card("alloy_config_templates"))
        assertNull("required: ANS-P003", card("alloy_tenant_id"))
        assertNull("a Jinja local", card("alloy_tenant_api_key", local = true))
        assertNull("a special variable", card("inventory_hostname"))
    }

    fun testRegisteredForTheSectionPlacement() {
        replace(PROD_ALL, prodLine, "")
        val chunks = runReadActionBlocking {
            CardSection.collect(CardSubject.Variable(falcon(), "alloy_tenant_api_key", emptyList(), null), CardContext(project, vf(CONFIG_BASE), 0), CardPlacement.SECTION)
        }
        assertTrue(chunks.map(HtmlChunk::toString).toString(), chunks.any { "env prod (hosts prod-prod1, prod-prod2)" in it.toString() })
    }
}
