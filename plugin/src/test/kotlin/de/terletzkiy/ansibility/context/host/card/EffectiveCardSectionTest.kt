package de.terletzkiy.ansibility.context.host.card

import com.intellij.lang.documentation.DocumentationMarkup
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarSubject

/**
 * F8.2 (ex-X13) on the falcon fixture, All mode: the Effective section at the top of the card for template references,
 * `group_vars`, `host_vars`, role-default and spec cards, with the plan's examples (`postfix_relayhost`,
 * `keepalived_priority`, `environment_group`, `alloy_tenant_api_key`, shared addresses, the runtime-default chain).
 */
class EffectiveCardSectionTest : HostCardTestCase() {
    /** Two plays on prod-prod1 whose roles default `ha4_shared` differently, and a reference to it in prod-prod1's host_vars. */
    override fun addFixtureFiles() {
        super.addFixtureFiles()
        add(
            "$FALCON/playbook-ha4.yml",
            """
            ---
            - name: HA4 A
              hosts: prod-prod1
              roles:
                - ha4_a
            - name: HA4 B
              hosts: prod-prod1
              roles:
                - ha4_b
            """,
        )
        for (role in listOf("a", "b")) {
            add("$FALCON/roles/ha4_$role/defaults/main.yml", "---\nha4_shared: from-$role")
            add("$FALCON/roles/ha4_$role/tasks/main.yml", "---\n- name: Noop\n  ansible.builtin.debug:\n    msg: ha4")
        }
        add("$FALCON/environments/prod/host_vars/prod-prod1/ha4.yml", "---\nha4_ref: \"{{ ha4_shared }}\"")
        add(
            CHAINS,
            """
            ---
            ha4_undefined_ref: "{{ ha4_nowhere }}"
            ha4_template_ref: "{{ ha4_template }}"
            ha4_template: "{{ ha4_base | upper }}"
            ha4_cycle_a: "{{ ha4_cycle_b }}"
            ha4_cycle_b: "{{ ha4_cycle_a }}"
            """,
        )
        add(
            PLAY_VARS,
            """
            ---
            - name: HA4 vars one
              hosts: prod-prod1
              vars:
                ha4_play_var: one
              tasks:
                - name: Show
                  ansible.builtin.debug:
                    msg: "{{ ha4_play_var }}"
            - name: HA4 vars two
              hosts: prod-prod2
              vars:
                ha4_play_var: two
            """,
        )
    }

    /** Acceptance 2: `{{ postfix_relayhost }}` in `main.cf.j2:9` is won by `group_vars/all/vars.yml:156` on every System host. */
    fun testTemplateReferenceGroupsHostsByOutcome() {
        val html = card(POSTFIX_TEMPLATE, 9, "postfix_relayhost")
        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on 4 hosts (play System) — 1 value"))
        assertTrue("environments are never merged", "ops: ops-ops1 · prod: prod-prod1, prod-prod2 · test: test-test1" in section)
        assertTrue(section, "= $relayHost · group_vars/all/vars.yml:156 · L5 playbook group_vars/all · Explain for ops-ops1 ›" in section)
        assertTrue(
            "runner-up first, with the hosts each one loses on",
            "shadowed: environments/prod/group_vars/all/vars.yml:471 (L4, prod ×2) · environments/test/group_vars/all/vars.yml:323 (L4, test ×1) · " +
                "roles/postfix/defaults/main.yml:2 (L2)" in section,
        )
        assertTrue("molecule outcomes on their own line", "molecule default: = $moleculeRelayHost · roles/postfix/molecule/default/molecule.yml:56" in section)

        val definitionEnd = html.indexOf(DocumentationMarkup.DEFINITION_END)
        val effectiveAt = html.indexOf("Effective on")
        val description = html.indexOf("Postfix relayhost (empty string")
        assertTrue("TOP: after the definition line, before the description", definitionEnd in 0 until effectiveAt && effectiveAt < description)
    }

    /** Acceptance 4: `environments/prod/group_vars/all/vars.yml:471` says ✗ ineffective; its Effective section shows the winner. */
    fun testGroupVarsDefinitionCard() {
        val html = card(FALCON_PROD_ALL, 471, "postfix_relayhost")
        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on 2 hosts ("))
        assertTrue(section, "— 1 value prod-prod1, prod-prod2 = $relayHost · group_vars/all/vars.yml:156" in section)
        assertTrue(section, "shadowed: environments/prod/group_vars/all/vars.yml:471 (L4, prod ×2) · roles/postfix/defaults/main.yml:2 (L2)" in section)
        assertEquals("✗ ineffective for prod-prod1, prod-prod2: shadowed by group_vars/all/vars.yml:156 (L5 beats L4)", row(html, "Effect"))
        val effectAt = html.indexOf("<p>Effect</p>")
        assertTrue("right after the built-in This definition row", effectAt > html.indexOf("<p>This definition</p>"))
    }

    /** A `host_vars` file applies to its host only: one line, plus Explain precedence. */
    fun testHostVarsCard() {
        val html = card(FALCON_PROD1_VARS, 16, "keepalived_priority")
        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on prod › prod-prod1 ("))
        assertTrue(section, "= 150 · environments/prod/host_vars/prod-prod1/vars.yml:16 · L9 inventory host_vars/prod-prod1 · Explain precedence ›" in section)
        assertEquals("✓ effective for prod-prod1", row(html, "Effect"))
    }

    /** A role default is ineffective wherever the role runs: inventory hosts by L5, the molecule hosts by their inventory. */
    fun testRoleDefaultsCard() {
        val html = card(POSTFIX_DEFAULTS, 2, "postfix_relayhost")
        assertTrue(effective(html).startsWith("Effective on 4 hosts (play System) — 1 value"))
        val effect = row(html, "Effect")!!
        assertTrue(effect, effect.startsWith("✗ ineffective, shadowed on every host that loads it:"))
        assertTrue(effect, "for ops: ops-ops1 · prod: prod-prod1, prod-prod2 · test: test-test1 by group_vars/all/vars.yml:156 (L5 beats L2)" in effect)
        assertTrue(effect, "by roles/postfix/molecule/default/molecule.yml:56 (L6 beats L2)" in effect)
    }

    /** A spec card shows where the role's variable takes effect, but has no definition status (a declaration). */
    fun testSpecCard() {
        val html = card(POSTFIX_SPEC, 6, "postfix_relayhost")
        assertTrue(effective(html).startsWith("Effective on 4 hosts (play System) — 1 value"))
        assertNull(row(html, "Effect"))
    }

    /** FU's `environment_group` example: a reference in playbook `group_vars/all` gets one value per environment. */
    fun testEnvironmentGroupHasOneValuePerEnvironment() {
        val section = effective(card(FALCON_PLAYBOOK_ALL, 4, "environment_group"))
        assertTrue(section, section.startsWith("Effective on 10 hosts ("))
        assertTrue(section, "— 3 values" in section)
        val test = section.indexOf("= test · environments/test/group_vars/all/vars.yml:292 · L4 inventory group_vars/all")
        val prod = section.indexOf("= prod · environments/prod/group_vars/all/vars.yml:520 · L4 inventory group_vars/all")
        val ops = section.indexOf("= ops · environments/ops/group_vars/all/vars.yml:56 · L4 inventory group_vars/all")
        assertTrue("largest group first: $section", test in 0 until prod && prod < ops)
        assertTrue("test's hosts in inventory order: $section", "test-test1, preview-" in section)

        val definition = card(FALCON_PROD_ALL, 520, "environment_group")
        assertEquals("✓ effective for prod-prod1, prod-prod2", row(definition, "Effect"))
        assertTrue(effective(definition).startsWith("Effective on 2 hosts ("))
    }

    /** V003's `alloy_tenant_api_key`: per environment a reference to a vault value, followed to the masked vault value. */
    fun testAlloyTenantApiKeyFollowsToTheMaskedVaultValue() {
        val html = card(ALLOY_TEMPLATE, 16, "alloy_tenant_api_key")
        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on 4 hosts (2 plays) — 3 values"))
        assertTrue(section, "prod-prod1, prod-prod2 = \"{{ vault_alloy_tenant_api_key_prod }}\" · environments/prod/group_vars/all/vars.yml:4" in section)
        assertTrue(
            section,
            "→ 🔒 vault-encrypted via vault_alloy_tenant_api_key_prod · group_vars/all/vault.yml:28 (prod-prod1, prod-prod2)" in section,
        )
        assertTrue(section, "→ 🔒 vault-encrypted via vault_alloy_tenant_api_key_ops · group_vars/all/vault.yml:12 (ops-ops1)" in section)
        assertFalse("no payload", InfraTestData.containsVaultPayload(html))
    }

    /** Acceptance 3: `{{ keepalived_priority }}` is 150 on prod-prod1 (host_vars) and 100 on prod-prod2 (defaults). */
    fun testPerHostValuesAndEnvironmentsTheRoleDoesNotRunIn() {
        val section = effective(card(KEEPALIVED_TEMPLATE, 2, "keepalived_priority"))
        assertTrue(section, section.startsWith("Effective on 2 hosts (play KeepAliveD) — 2 values"))
        assertTrue(section, "prod-prod1 = 150 · environments/prod/host_vars/prod-prod1/vars.yml:16 · L9 inventory host_vars/prod-prod1" in section)
        assertTrue(section, "prod-prod2 = 100 · roles/keepalived/defaults/main.yml:2 · L2 role defaults" in section)
        assertTrue(section, "not applied in ops, test" in section)
    }

    /** Hosts of the scope where no layer defines the name: `keepalived_priority` read by a postfix template. */
    fun testNotSetFor() {
        assertFalse("every keepalived host has a value", "not set for" in effective(card(KEEPALIVED_TEMPLATE, 2, "keepalived_priority")))
        add("$FALCON/roles/postfix/templates/priority.j2", "priority {{ keepalived_priority }}")
        val section = effective(card("$FALCON/roles/postfix/templates/priority.j2", 1, "keepalived_priority"))
        assertTrue(section, section.startsWith("Effective on 4 hosts (play System) — 1 value"))
        assertTrue(section, "prod-prod1 = 150 · environments/prod/host_vars/prod-prod1/vars.yml:16" in section)
        assertTrue(section, "not set for ops: ops-ops1 · prod: prod-prod2 · test: test-test1" in section)
    }

    /** Host mode with plays that disagree: one line per play instead of one line. */
    fun testHostModeWithPlaysThatDisagree() {
        val section = effective(card("$FALCON/environments/prod/host_vars/prod-prod1/ha4.yml", 2, "ha4_shared", 4))
        assertTrue(section, section.startsWith("Effective on prod › prod-prod1 ("))
        assertTrue(section, "play HA4 A: = from-a · roles/ha4_a/defaults/main.yml:2 · L2 role defaults · Explain precedence ›" in section)
        assertTrue(section, "play HA4 B: = from-b · roles/ha4_b/defaults/main.yml:2 · L2 role defaults · Explain precedence ›" in section)
        assertFalse("some play defines it, so the host is not undefined", "not set for" in section)
    }

    /** Chains end at an undefined name, at a template that is no bare reference, and never loop. */
    fun testChainEnds() {
        val undefined = effective(card(CHAINS, 2, "ha4_undefined_ref"))
        assertTrue(undefined, "→ ha4_nowhere is not set" in undefined)
        val template = effective(card(CHAINS, 3, "ha4_template_ref"))
        assertTrue(template, "→ \"{{ ha4_base | upper }}\" via ha4_template (Jinja, evaluated at runtime) · environments/prod/host_vars/prod-prod1/ha4chain.yml:4" in template)
        val cycle = effective(card(CHAINS, 5, "ha4_cycle_a"))
        assertTrue(cycle, "= \"{{ ha4_cycle_b }}\"" in cycle)
        assertFalse("a cycle shows no chain: $cycle", "→" in cycle)
    }

    /** Seven names share one address in test, and get different ports: the card says why. */
    fun testSharedAddressNote() {
        val section = effective(card(FALCON_TEST_ALL, 88, "app_falcon_mono_http_bind_port"))
        assertTrue(section, "— 7 values" in section)
        assertTrue(section, "test-test1 = 1337 · environments/test/group_vars/all/vars.yml:88" in section)
        // The preview host whose host_vars set port 2337 (its name is the fixture's alias).
        val preview = vf("$FALCON/environments/test/host_vars").children.map { it.name }.single { host ->
            vf("$FALCON/environments/test/host_vars/$host").findChild("vars.yml") != null &&
                keyOn("$FALCON/environments/test/host_vars/$host/vars.yml", 6) == "app_falcon_mono_http_bind_port" &&
                valueOn("$FALCON/environments/test/host_vars/$host/vars.yml", 6) == "2337"
        }
        assertTrue(section, "$preview = 2337 · environments/test/host_vars/$preview/vars.yml:6" in section)
        assertTrue(section, "7 names share 192.0.2.43" in section)
    }

    /** The runtime-default chain goes through the context's precedence, not only the role's defaults. */
    fun testRuntimeDefaultChainThroughTheContexts() {
        val section = effective(card(HAPROXY_TEMPLATE, 37, "haproxy_bind_ip"))
        assertTrue(section, "prod-prod1, prod-prod2 = \"{{ system_ip_floating }}\" · environments/prod/group_vars/all/vars.yml:524" in section)
        assertTrue(section, "→ str 192.0.2.33 via system_ip_floating · environments/prod/group_vars/all/vars.yml:582 (prod-prod1, prod-prod2)" in section)
    }

    /** A role no play applies: the reason instead of values. */
    fun testUnreachedRoleSaysWhy() {
        val section = effective(card("$FALCON/roles/totp-token/meta/argument_specs.yml", 6, "totp_users"))
        assertEquals("Effective No play of falcon applies totp-token", section)
    }

    /** Locals, nested options and names no static layer defines get no section. */
    fun testNoSectionWithoutStaticValues() {
        add("$FALCON/roles/postfix/templates/local.j2", "{% set relay = 'x' %}{{ relay }} {{ inventory_hostname }}\n")
        val local = card("$FALCON/roles/postfix/templates/local.j2", 1, "relay }}", delta = 1)
        assertFalse(local, "Effective on" in local)
        val magic = card("$FALCON/roles/postfix/templates/local.j2", 1, "inventory_hostname")
        assertFalse(magic, "Effective" in text(magic))
        val nested = card(FALCON_PROD_ALL, 540, "port")
        assertTrue(nested, text(nested).startsWith("haproxy_servers.port"))
        assertFalse(nested, "Effective on" in nested)
    }

    /**
     * A definition card reached through a link (offset -1) is seen from its own key: the play of a playbook's `vars:`
     * key, not every play of the file.
     */
    fun testALinkedDefinitionCardIsSeenFromItsKey() {
        val offset = offsetAt(PLAY_VARS, 5, "ha4_play_var")
        val hovered = effective(card(PLAY_VARS, 5, "ha4_play_var", delta = 0))
        assertTrue(hovered, hovered.startsWith("Effective on prod › prod-prod1 (play HA4 vars one) = one"))

        val subject = CardSubject.Variable(root(FALCON), "ha4_play_var", emptyList(), SourceLocation(vf(PLAY_VARS), offset))
        val linked = inBackgroundReadAction { EffectiveCardSection().section(subject, CardContext(project, vf(PLAY_VARS), -1)) }!!.toString()
        assertEquals(hovered, effective(linked))
        val reference = CardSubject.Variable(root(FALCON), "ha4_play_var", emptyList(), definition = null)
        val fileLevel = effective(inBackgroundReadAction { EffectiveCardSection().section(reference, CardContext(project, vf(PLAY_VARS), -1)) }!!.toString())
        assertTrue("a reference card reached through a link covers the whole file: $fileLevel", fileLevel.startsWith("Effective on 2 hosts (2 plays) — 2 values"))
    }

    /**
     * A reference card reached through a link has no position (CardContext offset -1): its Effective section covers the
     * whole file, and its ranked "Set in" rows rank over the same hosts. They used to rank from the origin reference's
     * play (host mode: "✓" and "not for prod-prod1") under an Effective section of both plays.
     */
    fun testALinkedReferenceCardRanksSetInOverTheHostsOfItsEffectiveSection() {
        val hovered = hover(PLAY_VARS, offsetAt(PLAY_VARS, 9, "ha4_play_var", 1)) as VarDocumentationTarget
        val direct = html(hovered)
        assertTrue(effective(direct), effective(direct).startsWith("Effective on prod › prod-prod1 (play HA4 vars one) = one"))
        assertTrue(row(direct, "Set in"), "two · not for prod-prod1" in row(direct, "Set in")!!)

        val linked = html(VarDocumentationTarget(project, hovered.subject, viaLink = true))
        assertTrue(effective(linked), effective(linked).startsWith("Effective on 2 hosts (2 plays) — 2 values"))
        val setIn = row(linked, "Set in")!!
        assertTrue(setIn, "one · wins on 1 of 2 hosts" in setIn && "two · wins on 1 of 2 hosts" in setIn)
        assertFalse("no host-mode marks over a two-host section: $setIn", "not for" in setIn || "✓" in setIn)
    }

    /** The Explain card's other hosts follow the origin card's Effective section: a linked definition card is seen from its key. */
    fun testTheExplainCardOfALinkedDefinitionCardListsTheSameHosts() {
        val location = SourceLocation(vf(PLAY_VARS), offsetAt(PLAY_VARS, 5, "ha4_play_var"))
        val linked = inBackgroundReadAction {
            VarDocumentationTarget(project, VarSubject.definition(project, root(FALCON), "ha4_play_var", emptyList(), location), viaLink = true)
        }
        val html = html(linked)
        assertTrue(effective(html), effective(html).startsWith("Effective on prod › prod-prod1 (play HA4 vars one) = one"))
        val link = Regex("href=\"(psi_element://ansibility-host/explain[^\"]*)\"").findAll(html).single().groupValues[1].replace("&amp;", "&")
        val explained = text(html(inBackgroundReadAction { HostCardLinkHandler().resolveTarget(linked, link) }!!))
        assertTrue(explained, explained.startsWith("ha4_play_var Explain precedence · prod › prod-prod1"))
        assertFalse("the key's play only, not every play of the file: $explained", "Other hosts" in explained)
    }

    private companion object {
        const val PLAY_VARS = "$FALCON/playbook-ha4-vars.yml"
        const val CHAINS = "$FALCON/environments/prod/host_vars/prod-prod1/ha4chain.yml"
    }
}
