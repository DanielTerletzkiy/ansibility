package de.terletzkiy.ansibility.completion.jinja

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.util.ui.NamedColorUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import org.jetbrains.yaml.YAMLFileType

/**
 * M3 acceptance 1–4 for Jinja variable completion on the sanitised infra fixture (lines identical to the real repo),
 * plus the X10 fact, group and host keys.
 */
@RequiresInfraFixture
class JinjaVarCompletionAcceptanceTest : JinjaCompletionTestCase() {

    /** Acceptance 1: `"{{ haproxy_| }}"` in a haproxy task lists the spec options with type and tail; Ctrl+Q shows the card. */
    fun testHaproxyOptionsInTaskScalar() {
        copyInfra("golden/roles/haproxy")
        val tasks = "golden/roles/haproxy/tasks/configure.yml"
        val items = completeAfterEdit(tasks, 13, "haproxy_log_path", "haproxy_")

        assertContainsElements(strings(items), "haproxy_servers", "haproxy_balance", "haproxy_bind_ip", "haproxy_check", "haproxy_settings_kernel_somaxconn")
        assertEquals("list[dict] | = [] · haproxy", describe(items, "haproxy_servers"))
        assertEquals("str | = roundrobin · haproxy", describe(items, "haproxy_balance"))
        assertEquals("str | · required · haproxy", describe(items, "haproxy_bind_ip"))
        assertTrue("required without a default is bold", presentation(item(items, "haproxy_bind_ip")).isItemTextBold)
        assertFalse(presentation(item(items, "haproxy_balance")).isItemTextBold)
        assertEquals("dict | · haproxy", describe(items, "haproxy_check"))
        assertTrue("own role options rank in tier T3", priority(item(items, "haproxy_servers")) >= Tier.ROLE.base)

        val card = plain(html(popupDocumentation(item(items, "haproxy_servers"))))
        assertTrue(card, card.startsWith("haproxy_servers : list[dict]"))
        assertTrue(card, "role haproxy" in card)
        assertTrue(card, "List of backend servers for the monoliths backend." in card)
    }

    /** Acceptance 2: `{{ postfix_| }}` in the postfix template, with `*.j2` typed as YAML, as plain text and (M5) as Ansible Jinja. */
    fun testPostfixOptionsInTemplateOfAnyFileType() {
        copyInfra("repos/falcon")
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        for (type in listOf<FileType>(YAMLFileType.YML, PlainTextFileType.INSTANCE, AnsibleJinjaFileType)) {
            withJ2As(type) {
                assertEquals(type, psi(template).fileType)
                val items = completeAfterEdit(template, 9, "postfix_relayhost", "postfix_")
                assertContainsElements(strings(items), "postfix_relayhost", "postfix_header_classification", "postfix_master_entries")
                assertEquals("str | = \"\" · postfix", describe(items, "postfix_relayhost"))
                assertEquals("bool | = false · postfix", describe(items, "postfix_header_classification"))
                val card = plain(html(popupDocumentation(item(items, "postfix_relayhost"))))
                assertTrue(card, card.startsWith("postfix_relayhost : str"))
                reset(template)
            }
        }
    }

    /** Acceptance 3: after line 10 of the chronod nginx tasks `_chronod_` offers the register; at line 9 it does not. */
    fun testRegisterIsOfferedAfterItsTask() {
        copyInfra("golden/roles/chronod")
        val tasks = "golden/roles/chronod/tasks/nginx.yml"

        val after = completeAfterEdit(tasks, 15, "chronod_nginx_ssl_cert_name", "_chronod_")
        assertContainsElements(strings(after), "_chronod_nginx_cert")
        assertEquals("result | · register · chronod", describe(after, "_chronod_nginx_cert"))
        assertTrue(priority(item(after, "_chronod_nginx_cert")) >= Tier.ROLE.base)
        val card = plain(html(popupDocumentation(item(after, "_chronod_nginx_cert"))))
        assertTrue(card, card.startsWith("_chronod_nginx_cert"))
    }

    fun testRegisterIsNotOfferedInItsOwnTaskArguments() {
        copyInfra("golden/roles/chronod")
        val tasks = "golden/roles/chronod/tasks/nginx.yml"
        val before = completeAfterEdit(tasks, 9, "chronod_nginx_ssl_cert_file", "_chronod_")
        assertDoesntContain(strings(before), "_chronod_nginx_cert")
    }

    fun testRegisterInImplicitExpressionAfterItsTask() {
        copyInfra("golden/roles/chronod")
        val tasks = "golden/roles/chronod/tasks/nginx.yml"
        val items = completeAfterEdit(tasks, 18, "_chronod_nginx_cert.stat.exists", "_chronod_")
        assertContainsElements(strings(items), "_chronod_nginx_cert")
    }

    /** Acceptance 3: a `vars/main.yml` name completes with its role tier. */
    fun testRoleVarsNameHasItsTier() {
        copyInfra("golden/roles/grafana")
        val tasks = "golden/roles/grafana/tasks/nginx.yml"
        val items = completeAfterEdit(tasks, 36, "item.config_name", "grafana_app_folders_")
        assertContainsElements(strings(items), "grafana_app_folders_required_sub_folders")
        val description = describe(items, "grafana_app_folders_required_sub_folders")
        assertTrue(description, description.startsWith("list | = [data, logs"))
        assertTrue(description, description.endsWith("· grafana vars"))
        val priority = priority(item(items, "grafana_app_folders_required_sub_folders"))
        assertTrue("T3 (own role): $priority", priority >= Tier.ROLE.base && priority < Tier.TASK.base)
    }

    /** Acceptance 4: `server.` in the haproxy template's for loop offers the element options of haproxy_servers. */
    fun testForLoopLocalMembersInTemplate() {
        copyInfra("golden/roles/haproxy")
        val template = "golden/roles/haproxy/templates/haproxy.cfg.j2"
        val items = completeAt(template, offsetAt(template, 76, "server.name", "server.".length))
        assertContainsElements(strings(items), "name", "port", "ip", "weight")
        assertEquals("str | · required", describe(items, "name"))
        assertEquals("int | · required", describe(items, "port"))
        assertEquals("int |", describe(items, "weight"))
        val card = plain(html(popupDocumentation(item(items, "port"))))
        assertTrue(card, card.startsWith("haproxy_servers.port : int"))
    }

    /** Acceptance 4: `{{ item.floating.ssl.| }}` in the grafana site template, typed through the rendering task's loop. */
    fun testLoopItemMembersInTemplateThroughDynamicSrc() {
        copyInfra("golden/roles/grafana")
        val template = "golden/roles/grafana/templates/nginx/main.site.conf.j2"
        val items = completeAt(template, offsetAt(template, 14, "item.floating.ssl.cert_file", "item.floating.ssl.".length))
        assertContainsElements(strings(items), "cert_file", "key_file", "port", "trusted_intermediate", "client_cert_ca")
        assertEquals("int | · required", describe(items, "port"))
        assertEquals("str | · required", describe(items, "cert_file"))
        assertEquals("str | · required", describe(items, "key_file"))
        assertTrue("required first", strings(items).indexOf("port") < strings(items).indexOf("client_cert_ca"))
        val card = plain(html(popupDocumentation(item(items, "cert_file"))))
        assertTrue(card, card.startsWith("grafana_nginx_sites.floating.ssl.cert_file : str"))

        val item = completeAt(template, offsetAt(template, 14, "item.floating", "it".length))
        assertEquals("dict | · loop over grafana_nginx_sites", describe(item, "item"))
        assertTrue(priority(item(item, "item")) >= Tier.LOOP.base)
    }

    /** F1.7: the loop variable of the `include_tasks` that includes a task file (`loop_control.loop_var: processor`). */
    fun testIncludeLoopVariableReachesTheIncludedFile() {
        copyInfra("golden/roles/postfix")
        val tasks = "golden/roles/postfix/tasks/additional-inbound-email-processing-instance.yml"
        val members = completeAt(tasks, offsetAt(tasks, 5, "processor.system_user", "processor.".length))
        assertContainsElements(strings(members), "name", "listen_port", "storage_dir", "system_user")
        assertEquals("str | · required", describe(members, "name"))
        val names = completeAt(tasks, offsetAt(tasks, 17, "processor.storage_dir", "proc".length))
        assertEquals("dict | · loop over postfix_additional_inbound_processors", describe(names, "processor"))
        assertTier(names, "processor", Tier.LOOP)
        val inLoopValue = strings(completeAt(tasks, offsetAt(tasks, 17, "processor.storage_dir")))
        assertTrue("processor" in inLoopValue)
        assertFalse("the task's own item is not defined in its loop value", "item" in inLoopValue)
    }

    /** X10: fact keys in both quote styles and as attributes, nested keys, and the injected names. */
    fun testFactKeys() {
        copyInfra("golden/roles/chronod")
        val tasks = "golden/roles/chronod/tasks/nginx.yml"
        val single = completeAfterEdit(tasks, 15, "chronod_nginx_ssl_cert_name", "ansible_facts['<caret>']")
        assertContainsElements(strings(single), "distribution", "distribution_release", "os_family", "service_mgr", "default_ipv4", "hostname", "fqdn", "architecture", "python")
        assertEquals("str | · fact", describe(single, "distribution_release"))
        assertEquals("dict | · fact", describe(single, "default_ipv4"))
        val card = plain(html(popupDocumentation(item(single, "os_family"))))
        assertTrue(card, card.startsWith("ansible_facts.os_family : str"))

        myFixture.lookup.currentItem = item(single, "service_mgr")
        myFixture.finishLookup('\n')
        assertTrue(myFixture.editor.document.text.lines()[14], "ansible_facts['service_mgr']" in myFixture.editor.document.text.lines()[14])
    }

    fun testFactKeysDoubleQuotedAndNested() {
        copyInfra("golden/roles/chronod")
        val tasks = "golden/roles/chronod/tasks/nginx.yml"
        val double = completeAfterEdit(tasks, 18, "not _chronod_nginx_cert.stat.exists", "ansible_facts[\"serv")
        assertEquals(listOf("service_mgr", "services"), strings(double).filter { it.startsWith("serv") }.sorted())
        myFixture.lookup.currentItem = item(double, "service_mgr")
        myFixture.finishLookup('\n')
        assertTrue("closes the key", "ansible_facts[\"service_mgr\"]" in myFixture.editor.document.text)
    }

    fun testNestedFactsAndInjectedNames() {
        copyInfra("golden/roles/chronod")
        val tasks = "golden/roles/chronod/tasks/nginx.yml"
        val nested = completeAfterEdit(tasks, 15, "chronod_nginx_ssl_cert_name", "ansible_facts.default_ipv4.")
        assertContainsElements(strings(nested), "address", "gateway", "interface", "netmask")
        reset(tasks)
        val injected = completeAfterEdit(tasks, 15, "chronod_nginx_ssl_cert_name", "ansible_distr")
        assertContainsElements(strings(injected), "ansible_distribution", "ansible_distribution_release")
        assertEquals("str | · fact", describe(injected, "ansible_distribution_release"))
        assertTrue(priority(item(injected, "ansible_distribution_release")) < Tier.INVENTORY.base)
    }

    /** X10: `groups['…']` group names and `hostvars[…]` host names from the root's inventories. */
    fun testGroupAndHostKeys() {
        copyInfra("repos/falcon")
        val tasks = "repos/falcon/ansible/roles/postfix/tasks/main.yml"
        val groups = completeAfterEdit(tasks, 4, "name: postfix", "name: \"{{ groups['<caret>'] }}\"")
        assertContainsElements(strings(groups), "database", "app_mono", "keycloak", "system")
        val database = describe(groups, "database")
        assertTrue(database, database.startsWith("list[str] | · group"))
        val groupCard = plain(html(popupDocumentation(item(groups, "database"))))
        assertTrue(groupCard, "prod-prod1" in groupCard)
        reset(tasks)

        val hosts = completeAfterEdit(tasks, 4, "name: postfix", "name: \"{{ hostvars[<caret>] }}\"")
        assertContainsElements(strings(hosts), "'prod-prod1'", "'prod-prod2'", "inventory_hostname")
        assertTrue("quoted host names first", strings(hosts).indexOf("'prod-prod1'") < strings(hosts).indexOf("inventory_hostname"))
        myFixture.lookup.currentItem = item(hosts, "'prod-prod1'")
        myFixture.finishLookup('\n')
        assertTrue(myFixture.editor.document.text.lines()[3], "hostvars['prod-prod1']" in myFixture.editor.document.text.lines()[3])
    }

    /** T5 inventory-only names are grey; T6 special variables; template-only ones only in templates. */
    fun testInventoryOnlyAndSpecialVariables() {
        copyInfra("repos/falcon")
        val tasks = "repos/falcon/ansible/roles/postfix/tasks/main.yml"
        val items = completeAfterEdit(tasks, 4, "name: postfix", "name: \"{{ <caret> }}\"")
        val names = strings(items)
        assertContainsElements(names, "inventory_docs_client_structure", "inventory_hostname", "group_names", "omit", "hostvars", "ansible_facts")
        assertDoesntContain(names, "ansible_managed", "template_path")
        val inventory = presentation(item(items, "inventory_docs_client_structure"))
        assertEquals(NamedColorUtil.getInactiveTextColor(), inventory.itemTextForeground)
        assertEquals(" · inventory (prod)", inventory.tailText)
        assertEquals("str | · special variable", describe(items, "inventory_hostname"))
        assertTrue(presentation(item(items, "play_hosts")).isStrikeout)
        assertTrue("T5 before T6", priority(item(items, "inventory_docs_client_structure")) > priority(item(items, "inventory_hostname")))
        assertTrue("own role before inventory", priority(item(items, "postfix_relayhost")) > priority(item(items, "inventory_docs_client_structure")))
        val card = plain(html(popupDocumentation(item(items, "omit"))))
        assertTrue(card, card.startsWith("omit : str"))

        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        val inTemplate = completeAfterEdit(template, 1, "ansible_managed", "ansible_ma")
        assertContainsElements(strings(inTemplate), "ansible_managed")
    }

    private fun assertTier(items: List<com.intellij.codeInsight.lookup.LookupElement>, name: String, tier: Tier) {
        val priority = priority(item(items, name))
        val next = Tier.entries.getOrNull(tier.ordinal - 1)?.base ?: Double.MAX_VALUE
        assertTrue("$name: $priority not in $tier", priority >= tier.base && priority < next)
    }
}
