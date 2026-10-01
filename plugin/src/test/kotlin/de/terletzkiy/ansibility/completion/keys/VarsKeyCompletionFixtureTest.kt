package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.util.ui.NamedColorUtil
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import java.util.concurrent.TimeUnit

/**
 * Plan M3 acceptance 5 and F4.2 on the sanitised falcon fixture: top-level keys in
 * `repos/falcon/ansible/environments/prod/group_vars/all/vars.yml`, nested keys inside `haproxy_servers` items and inside
 * `loki_nginx_sites[0].floating` (`environments/ops/group_vars/all/vars.yml:526`), `hosts.yml` group vars, and
 * inventory-only names.
 */
class VarsKeyCompletionFixtureTest : KeyCompletionTestCase() {
    override fun setUp() {
        super.setUp()
        copyInfra(FALCON)
    }

    fun testFixtureLinesAreTheOnesThePlanCites() {
        assertLine(PROD_VARS, 471, "postfix_relayhost: relay.mx.example.de")
        assertLine(PROD_VARS, 537, "haproxy_servers:")
        assertLine(PROD_VARS, 538, "  - name: prod-prod1")
        assertLine(OPS_VARS, 526, "loki_nginx_sites:")
        assertLine(OPS_VARS, 528, "    floating:")
    }

    fun testPostfixPrefixOffersThePostfixOptions() {
        val items = completeInsertingLine(PROD_VARS, 472, "postfix_$CARET")

        assertContainsElements(
            names(items),
            "postfix_header_classification", "postfix_header_classification_template",
            "postfix_header_classification_product", "postfix_master_entries",
        )
        assertDoesntContain("already set in the file", names(items), "postfix_relayhost")
        val flag = presentation(items, "postfix_header_classification")
        assertEquals(" postfix", flag.tailText)
        assertEquals("bool", flag.typeText)
        assertEquals("str", presentation(items, "postfix_master_entries").typeText)
        assertFalse("optional options are not bold", flag.isItemTextBold)
    }

    fun testATopLevelKeyAfterANestedList() {
        // After the haproxy_servers items, at column 0 (line 546 is `haproxy_route_specifics:`).
        val items = completeInsertingLine(PROD_VARS, 546, "postfix_m$CARET")
        assertEquals(listOf("postfix_master_entries"), names(items))
    }

    fun testRequiredOptionsWithoutDefaultAreBoldAndNameEveryDeclaringRole() {
        val items = completeInsertingLine(PROD_VARS, 472, "system_host$CARET")

        val hostname = presentation(items, "system_hostname")
        assertTrue("required, no default: inventory must set it", hostname.isItemTextBold)
        assertEquals(" postfix, system", hostname.tailText)
    }

    fun testKeysAlreadyInTheFileAreLeftOut() {
        val items = completeInsertingLine(PROD_VARS, 472, "system_$CARET")

        assertDoesntContain("set at lines 581-584", names(items), "system_ip_fixed_public", "system_ip_floating", "system_ip_floating_db")
        assertContainsElements("host_vars keys are not in this file", names(items), "system_hostname")
    }

    fun testAcceptingAKeyWritesTheColon() {
        val items = completeInsertingLine(PROD_VARS, 472, "postfix_master$CARET")

        accept(items, "postfix_master_entries")

        assertEquals("postfix_master_entries: ", caretLine())
        assertEquals(caretLine().length, myFixture.editor.caretModel.logicalPosition.column)
    }

    fun testCompletingInsideAnExistingKeyKeepsItsColon() {
        val items = completeReplacingLine(PROD_VARS, 471, "postfix_relay${CARET}host: relay.mx.example.de")

        assertContainsElements("the key being edited is not an existing sibling", names(items), "postfix_relayhost")
        myFixture.lookup.currentItem = item(items, "postfix_relayhost")
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.REPLACE_SELECT_CHAR)
        assertEquals("postfix_relayhost: relay.mx.example.de", caretLine())
    }

    fun testInsideANewHaproxyServersItemTheRequiredKeysComeFirst() {
        val items = completeInsertingLine(PROD_VARS, 546, "  - $CARET")

        assertEquals(listOf("name", "ip", "port", "weight"), names(items))
        assertTrue(presentation(items, "name").isItemTextBold)
        assertTrue(presentation(items, "port").isItemTextBold)
        assertFalse(presentation(items, "weight").isItemTextBold)
        assertEquals("int", presentation(items, "port").typeText)
        assertEquals(" haproxy", presentation(items, "name").tailText)
    }

    fun testInsideAHaproxyServersItemExistingKeysAreLeftOut() {
        completeInsertingLine(PROD_VARS, 546, "  - name: prod-prod3\n    $CARET")
        val items = ours()

        assertEquals(listOf("ip", "port", "weight"), names(items))
        accept(items, "port")
        assertEquals("    port: ", caretLine())
    }

    fun testInAHaproxyServersItemWithAllKeysNothingIsLeft() {
        val items = completeInsertingLine(PROD_VARS, 542, "    $CARET")
        assertEquals("prod-prod1's item has name, ip, port and weight", emptyList<String>(), names(items))
    }

    fun testNestedFloatingSubOptionsOfLokiNginxSites() {
        // Inside loki_nginx_sites[0].floating, after `template:` (line 542).
        val items = completeInsertingLine(OPS_VARS, 543, "      $CARET")

        assertEquals("ip, ip_whitelist, port, ssl and template are written already", listOf("proxy_protocol"), names(items))
        assertEquals("dict", presentation(items, "proxy_protocol").typeText)
    }

    fun testNestedKeysBelowANewDictValue() {
        val items = completeInsertingLine(OPS_VARS, 543, "      proxy_protocol:\n        $CARET")

        assertEquals(listOf("port"), names(items))
        assertTrue(presentation(items, "port").isItemTextBold)
    }

    fun testANewLokiSiteItemOffersTheSiteKeys() {
        assertLine(OPS_VARS, 545, "loki_tenants: \"{{ alloy_tenants }}\"")
        val items = completeInsertingLine(OPS_VARS, 545, "  - $CARET")

        assertEquals(listOf("config_name", "hostnames", "floating"), names(items))
    }

    fun testHostsYmlGroupVarsOfferTopLevelVariables() {
        // environments/ops/hosts.yml: `all.vars` has ansible_user and ansible_port.
        assertLine(OPS_HOSTS, 6, "  hosts:")
        val items = completeInsertingLine(OPS_HOSTS, 6, "    postfix_$CARET")

        assertContainsElements(names(items), "postfix_relayhost", "postfix_master_entries")
    }

    fun testInventoryOnlyNamesComeLastAndGrey() {
        // The fixture has no iptables role: its variables are set only by the inventory.
        val items = completeInsertingLine(PROD1_VARS, 2, "iptables_docker_$CARET")

        val names = names(items)
        assertContainsElements(names, "iptables_docker_network", "iptables_docker_compose_keycloak_network")
        val network = presentation(items, "iptables_docker_network")
        assertEquals(NamedColorUtil.getInactiveTextColor(), network.itemTextForeground)
        assertEquals(" inventory", network.tailText)
    }

    fun testRoleOptionsRankBeforeInventoryOnlyNames() {
        val items = completeInsertingLine(PROD1_VARS, 2, "system_$CARET")

        // Middle matches (`grafana_oauth2_proxy_systemd_name`) come after start matches whatever their rank.
        val starting = items.filter { it.lookupString.startsWith("system_") }
        val names = names(starting)
        val firstInventoryOnly = starting.indexOfFirst { LookupElementPresentationTail.of(it) == " inventory" }
        val lastRoleOption = starting.indexOfLast { LookupElementPresentationTail.of(it) != " inventory" }
        assertTrue("some role options: $names", lastRoleOption >= 0)
        if (firstInventoryOnly >= 0) assertTrue("inventory-only names last: $names", firstInventoryOnly > lastRoleOption)
    }

    fun testCtrlQInThePopupShowsTheVariableCard() {
        val items = completeInsertingLine(PROD_VARS, 472, "postfix_header_classification_te$CARET")
        val element = item(items, "postfix_header_classification_template")

        val target = ApplicationManager.getApplication().executeOnPooledThread<VarDocumentationTarget?> {
            runReadActionBlocking {
                IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, myFixture.file, element)
                    .filterIsInstance<VarDocumentationTarget>().firstOrNull()
            }
        }.get(60, TimeUnit.SECONDS)
        assertNotNull("the variable card documents the item", target)
        val html = ApplicationManager.getApplication().executeOnPooledThread<String> {
            runReadActionBlocking { (target!!.computeDocumentation() as DocumentationData).html }
        }.get(60, TimeUnit.SECONDS)
        assertTrue(html, html.contains("Header classification template name"))
    }

    fun testNestedItemsAreDocumentedByTheirOption() {
        val items = completeInsertingLine(PROD_VARS, 546, "  - $CARET")
        val doc = item(items, "port").`object` as VarLookupDoc
        assertEquals("haproxy_servers", doc.name)
        assertEquals(listOf("2", "port"), doc.path)
    }

    /** The tail text of an item as the popup renders it. */
    private object LookupElementPresentationTail {
        fun of(element: LookupElement): String? = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(element).tailText
    }
}
