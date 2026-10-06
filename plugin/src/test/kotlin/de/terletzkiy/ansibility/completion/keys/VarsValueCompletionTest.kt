package de.terletzkiy.ansibility.completion.keys

import com.intellij.icons.AllIcons
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * Value completion (plan X19, value part): choices of spec'd options, top-level and nested, booleans, list elements,
 * and the quoting of choices whose plain spelling YAML would load as another type. On the falcon fixture
 * (`system_apt_debian_version` choices bookworm/trixie at `repos/falcon/ansible/roles/haproxy/meta/argument_specs.yml:11`,
 * loki's `floating.template` choices, `haproxy_apply_kernel_params: bool`) and on the small `mini` root.
 */
@RequiresInfraFixture
class VarsValueCompletionTest : KeyCompletionTestCase() {

    fun testChoicesOfATopLevelOption() {
        copyInfra(FALCON)
        assertLine(PROD_VARS, 682, "system_apt_debian_version: trixie")

        val items = completeReplacingLine(PROD_VARS, 682, "system_apt_debian_version: $CARET")

        assertEquals(listOf("bookworm", "trixie"), names(items))
        assertEquals("str", presentation(items, "bookworm").typeText)
    }

    fun testChoicesMatchTheTypedPrefix() {
        copyInfra(FALCON)
        val items = completeReplacingLine(PROD_VARS, 682, "system_apt_debian_version: bo$CARET")

        assertEquals(listOf("bookworm"), names(items))
        accept(items, "bookworm")
        assertEquals("system_apt_debian_version: bookworm", caretLine())
    }

    fun testChoicesOfAnOptionWithADefaultMarkTheDefault() {
        copyInfra(FALCON)
        val items = completeInsertingLine(PROD_VARS, 546, "haproxy_balance: $CARET")

        // The spec's order: repos/falcon/ansible/roles/haproxy/meta/argument_specs.yml:24-33.
        assertEquals(listOf("roundrobin", "first", "leastconn", "random", "rdp-cookie", "source", "static-rr", "sticky", "uri"), names(items))
        assertEquals(" (default)", presentation(items, "roundrobin").tailText)
        assertNull(presentation(items, "first").tailText)
    }

    fun testChoicesWithADashMatchTheWholeTypedValue() {
        copyInfra(FALCON)
        val items = completeInsertingLine(PROD_VARS, 546, "haproxy_balance: rdp-c$CARET")
        assertEquals(listOf("rdp-cookie"), names(items))
    }

    fun testBooleanOption() {
        copyInfra(FALCON)
        val items = completeInsertingLine(PROD_VARS, 546, "haproxy_apply_kernel_params: $CARET")

        assertEquals(listOf("true", "false"), names(items))
        assertEquals(" (default)", presentation(items, "true").tailText)
        assertEquals("bool", presentation(items, "false").typeText)
    }

    fun testChoicesOfANestedOption() {
        copyInfra(FALCON)
        assertLine(OPS_VARS, 542, "      template: main.site.proxy_protocol.conf.j2")

        val items = completeReplacingLine(OPS_VARS, 542, "      template: $CARET")

        assertEquals(listOf("main.site.combined.conf.j2", "main.site.proxy_protocol.conf.j2"), names(items))
    }

    /**
     * Template-name values whose spec declares choices (`loki_nginx_sites[].floating.template`,
     * golden/roles/loki/meta/argument_specs.yml:102): the choices carry the template's file icon, also with a typed
     * prefix (where the classifier reports a template reference), and appear once.
     */
    fun testTemplateNameChoicesShowTheTemplatesFileIcon() {
        copyInfra(FALCON)
        val combined = "$FALCON/ansible/roles/loki/templates/nginx/main.site.combined.conf.j2"
        for (typed in listOf("", "main.site.")) {
            val items = completeReplacingLine(OPS_VARS, 542, "      template: $typed$CARET")
            assertEquals(typed, listOf("main.site.combined.conf.j2", "main.site.proxy_protocol.conf.j2"), names(items))
            val presentation = presentation(items, "main.site.combined.conf.j2")
            assertEquals("template", presentation.typeText)
            assertEquals(myFixture.findFileInTempDir(combined)!!.fileType.icon, presentation.icon)
            assertEquals("no second item from the template-name source", 1, myFixture.lookupElementStrings.orEmpty().count { it == "main.site.combined.conf.j2" })
            myFixture.lookup?.hideLookup(true)
        }
    }

    fun testChoicesThatNameNoTemplateKeepTheChoiceIcon() {
        copyInfra(FALCON)
        val items = completeReplacingLine(PROD_VARS, 682, "system_apt_debian_version: $CARET")
        assertEquals(AllIcons.Nodes.Enum, presentation(items, "bookworm").icon)
        assertEquals("str", presentation(items, "bookworm").typeText)
    }

    fun testNoValuesForOptionsWithoutChoices() {
        copyInfra(FALCON)
        assertEquals(emptyList<String>(), names(completeReplacingLine(PROD_VARS, 471, "postfix_relayhost: $CARET")))
    }

    fun testStringChoicesThatYamlWouldRetypeAreQuoted() {
        copyKeysData(MINI)
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"

        val items = completeInsertingLine(path, 3, "web_mode: $CARET")

        assertEquals(listOf("yes", "no", "auto", "on: demand"), names(items))
        assertEquals("\"yes\"", presentation(items, "yes").itemText)
        assertEquals("auto", presentation(items, "auto").itemText)
        assertEquals("\"on: demand\"", presentation(items, "on: demand").itemText)
        accept(items, "yes")
        assertEquals("web_mode: \"yes\"", caretLine())
    }

    fun testInsideQuotesChoicesAreInsertedAsWritten() {
        copyKeysData(MINI)
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"

        val items = completeInsertingLine(path, 3, "web_mode: \"y$CARET\"")

        assertEquals(listOf("yes"), names(items))
        assertEquals("yes", presentation(items, "yes").itemText)
        accept(items, "yes")
        assertEquals("web_mode: \"yes\"", caretLine())
    }

    fun testListElementChoicesAndBooleans() {
        copyKeysData(MINI)
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"

        assertEquals(listOf("http", "https"), names(completeInsertingLine(path, 3, "web_protocols:\n  - $CARET")))
    }

    fun testListElementBooleans() {
        copyKeysData(MINI)
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"

        val items = completeInsertingLine(path, 3, "web_flags:\n  - $CARET")
        assertEquals(listOf("true", "false"), names(items))
        assertNull("elements have no default", presentation(items, "true").tailText)
    }

    fun testAListValueItselfGetsNoChoices() {
        copyKeysData(MINI)
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 3, "web_protocols: $CARET")))
    }

    fun testNoValuesInsideJinja() {
        copyKeysData(MINI)
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 3, "web_mode: \"{{ a$CARET }}\"")))
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 3, "web_enabled: {{ t$CARET }}")))
    }

    fun testValuesInPlayVars() {
        copyKeysData(MINI)
        val items = completeIn(
            "$MINI/playbook-values.yml",
            "- name: Web\n  hosts: webservers\n  vars:\n    web_enabled: $CARET\n  roles:\n    - role: web\n",
        )
        assertEquals(listOf("true", "false"), names(items))
    }

    fun testNoValuesForTaskKeywords() {
        copyKeysData(MINI)
        val items = completeIn("$MINI/roles/web/tasks/values.yml", "- name: Run\n  become: $CARET\n  ansible.builtin.debug:\n    msg: hi\n")
        assertEquals(emptyList<String>(), names(items))
    }
}
