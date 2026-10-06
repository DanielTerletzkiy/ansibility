package de.terletzkiy.ansibility.toolwindow.host

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.context.host.ValueKind
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.toolwindow.EffectiveVarsView
import de.terletzkiy.ansibility.toolwindow.ToolWindowTestCase
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodeStyle
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowModels
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * HA7a (plan amendment R7/R8 F8.7, ex-X39 and part 1 of ex-X43) on the sanitised falcon and pelican roots: a host's Effective
 * vars (winning layer and file, shadowed definitions struck through, the play selector with Auto over every play on the
 * host), Targeted by, vault masking without decryption, navigation to the winner, and laziness.
 */
@RequiresInfraFixture
class HostEffectiveVarsTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        for (repo in listOf(FALCON, PELICAN)) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$repo", repo)
        // Synthetic additions: playbook-level group_vars for pelican and its danger zone (the fixture has none), and secrets
        // that must stay hidden.
        myFixture.addFileToProject("$PELICAN/group_vars/all.yml", "zone_probe: root-level\nzone_split: root-level\n")
        myFixture.addFileToProject("$DANGER_ZONE/group_vars/all.yml", "zone_split: danger-zone\n")
        myFixture.addFileToProject("$FALCON/environments/prod/host_vars/prod-prod1/extra.yml", "vault_plain_secret: $SENTINEL_NAME\n")
        myFixture.addFileToProject("$FALCON/environments/prod/host_vars/prod-prod1/vault_more.yml", "plain_in_vault_file: $SENTINEL_FILE\n")
        refreshRoots()
    }

    private val prod1: AnsibleTreeNode get() = path("falcon", "Environments", "prod", "Hosts", "prod-prod1")

    private fun effective(host: AnsibleTreeNode = prod1): EffectiveVarsNode = path(host, "Effective vars") as EffectiveVarsNode

    private fun variable(name: String, host: AnsibleTreeNode = prod1): EffectiveVarNode =
        children(effective(host)).single { (it as EffectiveVarNode).row.name == name } as EffectiveVarNode

    private fun presentation(node: AnsibleTreeNode) = runReadActionBlocking { node.presentation() }

    private fun choose(env: String, host: String, choice: PlayChoice, repo: String = FALCON) {
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(repo) }
        val key = runReadActionBlocking { EffectiveTables.getInstance(project).context(root, env, host)!!.host }
        EffectivePlayChoices.getInstance(project).set(key, choice)
    }

    // ------------------------------------------------------------------ Effective vars (tree texts)

    fun testHostGetsEffectiveVarsAndTargetedByAfterItsSources() {
        assertEquals(listOf("Effective vars", "Targeted by"), names(prod1).takeLast(2))
        assertEquals("sources first (two synthetic host_vars files included)", 13, sources(prod1).size)
        assertEquals(sources(prod1), children(prod1).take(13))
        assertEquals("Auto: every play on the host", "Effective vars  394 · all 27 plays", presentation(effective()).text)
    }

    fun testEffectiveVarsSubset() {
        val rows = children(effective()).associate { (it as EffectiveVarNode).row.name to presentation(it).text }
        assertEquals(
            "the System play's postfix defaults count",
            "postfix_relayhost = relayinternal.mx.example.de  L5 playbook group_vars/all · group_vars/all/vars.yml:156 · 2 shadowed",
            rows["postfix_relayhost"],
        )
        assertEquals(
            "a role var only one of the plays loads",
            "__color_prompt_department = \"{{ color_prompt_department }}\"  L14 role vars of system · roles/system/vars/main.yml:2 · set in 1 of 27 plays",
            rows["__color_prompt_department"],
        )
        assertEquals(
            "keepalived_priority = 150  L9 env host_vars/prod-prod1 · environments/prod/host_vars/prod-prod1/vars.yml:16",
            rows["keepalived_priority"],
        )
        assertEquals("ansible_host = 192.0.2.29  L8 hosts.yml vars of host prod-prod1 · environments/prod/hosts.yml:8", rows["ansible_host"])
        assertEquals("ansible_user = provisioner  L3 hosts.yml vars of all · environments/prod/hosts.yml:4", rows["ansible_user"])
        assertEquals(
            "inventory_docs_client_structure = {ha: {floating_ip: \"{{ system_ip_floating }}\"}}  L3 hosts.yml vars of app_mono · environments/prod/hosts.yml:23",
            rows["inventory_docs_client_structure"],
        )
        assertEquals("sorted by name", rows.keys.sorted(), rows.keys.toList())

        val relayhost = variable("postfix_relayhost")
        assertEquals(
            "runner-up first",
            listOf(
                "relay.mx.example.de  L4 env group_vars/all · environments/prod/group_vars/all/vars.yml:471",
                "\"\"  L2 role defaults of postfix · roles/postfix/defaults/main.yml:2",
            ),
            children(relayhost).map { presentation(it).text },
        )
        val shadowed = children(relayhost).first() as ShadowedDefinitionNode
        assertEquals("shadowed definitions are struck through", NodeStyle.STRUCK, presentation(shadowed).style)
        assertEquals(NodeIcon.VARIABLE, presentation(relayhost).icon)
        assertTrue(presentation(relayhost).tooltip.first(), presentation(relayhost).tooltip.first() == "Type: str")
        assertTrue("a variable without shadowed definitions is a leaf", variable("keepalived_priority").isLeaf)

        choose("prod", "prod-prod1", PlayChoice.Inventory)
        val inventory = children(effective()).associate { (it as EffectiveVarNode).row.name to presentation(it).text }
        assertEquals("Effective vars  363 · inventory view", presentation(effective()).text)
        assertEquals(
            "postfix_relayhost = relayinternal.mx.example.de  L5 playbook group_vars/all · group_vars/all/vars.yml:156 · 1 shadowed",
            inventory["postfix_relayhost"],
        )
        assertNull("no play, no role vars", inventory["__color_prompt_department"])
        assertEquals("inventory variables read the same", rows["keepalived_priority"], inventory["keepalived_priority"])
    }

    fun testEffectiveVarDetailsShowTheWinnerAndWhatItShadows() {
        val details = runReadActionBlocking { variable("postfix_relayhost").details() }
        assertEquals("postfix_relayhost", details.title)
        assertEquals("falcon › prod · all 27 plays", details.subtitle)
        assertEquals(
            "it wins in every play, so no play is named",
            listOf("relayinternal.mx.example.de — str · L5 playbook group_vars/all · group_vars/all/vars.yml:156"),
            details.section("Effective value")!!.items.map { it.toString() },
        )
        assertEquals(
            listOf(
                "relay.mx.example.de — str · L4 env group_vars/all · environments/prod/group_vars/all/vars.yml:471",
                "\"\" — str · L2 role defaults of postfix · roles/postfix/defaults/main.yml:2",
            ),
            details.section("Shadowed definitions, runner-up first")!!.items.map { it.toString() },
        )
        assertNull(details.section("Other values per play"))
        val role = runReadActionBlocking { variable("__color_prompt_department").details() }
        assertEquals(
            listOf("\"{{ color_prompt_department }}\" — template · L14 role vars of system · roles/system/vars/main.yml:2 · in 1 play: System"),
            role.section("Effective value")!!.items.map { it.toString() },
        )
    }

    // ------------------------------------------------------------------ navigation

    fun testNavigationFromAnEffectiveVarToItsWinner() {
        val relayhost = variable("postfix_relayhost")
        assertTrue(relayhost.navigatesOnDoubleClick)
        assertEquals("$FALCON/group_vars/all/vars.yml:156", describe(relayhost.target))
        assertEquals("$FALCON/environments/prod/group_vars/all/vars.yml:471", describe(children(relayhost).first().target))
        assertEquals("$FALCON/environments/prod/host_vars/prod-prod1/vars.yml:16", describe(variable("keepalived_priority").target))
        assertNull("the container itself has no target", effective().target)
    }

    // ------------------------------------------------------------------ play selector

    fun testChoosingAPlayAddsRoleDefaultsAndRuntimeMarkers() {
        choose("prod", "prod-prod1", PlayChoice.Play("playbook-setup-system.yml#2"))
        val node = effective()
        val text = presentation(node).text
        assertTrue(text, text.startsWith("Effective vars  ") && text.endsWith(" · play System"))
        val relayhost = variable("postfix_relayhost")
        assertEquals(
            "the System play applies postfix: its defaults are shadowed too",
            listOf(
                "relay.mx.example.de  L4 env group_vars/all · environments/prod/group_vars/all/vars.yml:471",
                "\"\"  L2 role defaults of postfix · roles/postfix/defaults/main.yml:2",
            ),
            children(relayhost).map { presentation(it).text },
        )
        assertTrue("2 shadowed", presentation(relayhost).text.endsWith(" · 2 shadowed"))
        val table = runReadActionBlocking { node.table()!! }
        assertTrue("runtime markers are known in smart mode", table.markersKnown)
        assertTrue("role defaults join the table", table.rows.any { it.winner.layerText == "L2 role defaults of postfix" })
        assertTrue("more variables than the inventory view", table.rows.size > 363)
        assertTrue("fewer than all plays together", table.rows.size < 394)

        choose("prod", "prod-prod1", PlayChoice.Inventory)
        assertEquals("Effective vars  363 · inventory view", presentation(effective()).text)
        assertEquals(1, children(variable("postfix_relayhost")).size)
    }

    fun testAPlayAddsTheRuntimeMarkersOfItsRoleTasksOnly() {
        myFixture.addFileToProject("$FALCON/roles/postfix/tasks/relay.yml", "- name: Pick a relay\n  ansible.builtin.set_fact:\n    postfix_relayhost: relay.example.test\n")
        // The playbooks of a role's molecule scenario never run with the role.
        myFixture.addFileToProject(
            "$FALCON/roles/postfix/molecule/default/check.yml",
            "- hosts: all\n  tasks:\n    - name: Read\n      ansible.builtin.command: postconf\n      register: keepalived_priority\n",
        )
        choose("prod", "prod-prod1", PlayChoice.Play("playbook-setup-system.yml#2"))
        val relayhost = variable("postfix_relayhost")
        assertEquals(
            "postfix_relayhost = relayinternal.mx.example.de  L5 playbook group_vars/all · group_vars/all/vars.yml:156 · 2 shadowed · may change at runtime",
            presentation(relayhost).text,
        )
        val marker = children(relayhost).last() as RuntimeMarkerNode
        assertEquals("may be replaced at runtime by set_fact  roles/postfix/tasks/relay.yml:3", presentation(marker).text)
        assertEquals("$FALCON/roles/postfix/tasks/relay.yml:3", describe(marker.target))
        assertEquals(
            listOf("may be replaced at runtime by set_fact — roles/postfix/tasks/relay.yml:3"),
            runReadActionBlocking { relayhost.details() }.section("May be replaced at runtime")!!.items.map { it.toString() },
        )
        assertTrue("a molecule playbook of the role is no runtime source", variable("keepalived_priority").row.markers.isEmpty())

        val content = runReadActionBlocking { effective().details()!!.content as EffectiveVarsContent }
        val view = EffectiveVarsView(content, { _, _ -> }, { })
        val index = view.variableRows.indexOfFirst { it.row.name == "postfix_relayhost" }
        view.table.tree.expandRow(index)
        val runtime = view.rowAt(index + 3) as EffectiveVarsView.Row.Runtime
        val model = view.table.tableModel
        assertEquals(
            listOf("may be replaced at runtime by set_fact", null, "L17 set_fact and register", "roles/postfix/tasks/relay.yml:3", null),
            (1 until model.columnCount).map { model.getValueAt(runtime, it) },
        )

        choose("prod", "prod-prod1", PlayChoice.Inventory)
        assertTrue("the inventory view has no runtime markers", variable("postfix_relayhost").row.markers.isEmpty())
        choose("prod", "prod-prod1", PlayChoice.Auto)
        assertEquals("Auto unites the markers of every play", 1, variable("postfix_relayhost").row.markers.size)
    }

    fun testAutoFollowsTheAnsibleContext() {
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#2"))
        val text = presentation(effective()).text
        assertTrue(text, text.endsWith(" · play System (Ansible context)"))
        assertEquals(2, children(variable("postfix_relayhost")).size)
        assertEquals("another host evaluates all its plays", "all 25 plays", presentation(effective(path("falcon", "Environments", "prod", "Hosts", "prod-prod2"))).text.substringAfterLast(" · "))

        val details = runReadActionBlocking { effective().details()!! }
        val content = details.content as EffectiveVarsContent
        assertEquals("Auto: play System (Ansible context)", content.choices.first().text)
        assertEquals(PlayChoice.Auto, content.selected.choice)
        assertEquals("Inventory view (levels 3–10)", content.choices[1].text)
        assertEquals("Auto, the inventory view, then the 27 plays on the host", 29, content.choices.size)
        assertEquals("playbook-setup-system.yml › System", content.choices.single { it.choice == PlayChoice.Play("playbook-setup-system.yml#2") }.text)

        choose("prod", "prod-prod1", PlayChoice.Inventory)
        val inventory = runReadActionBlocking { effective().details()!!.content as EffectiveVarsContent }
        assertEquals("Auto still says what it would evaluate", "Auto: play System (Ansible context)", inventory.choices.first().text)
        assertEquals(PlayChoice.Inventory, inventory.selected.choice)
    }

    fun testAStaleChoiceFallsBackToAuto() {
        choose("prod", "prod-prod1", PlayChoice.Play("playbook-setup-system.yml#99"))
        val text = presentation(effective()).text
        assertEquals("Effective vars  394 · all 27 plays · The chosen play playbook-setup-system.yml#99 no longer runs on prod-prod1; Auto is shown.", text)
        val details = runReadActionBlocking { effective().details()!! }
        val content = details.content as EffectiveVarsContent
        assertEquals("the selector shows Auto for a stale play", PlayChoice.Auto, content.selected.choice)
        assertEquals(
            "the details pane says why",
            listOf("The chosen play playbook-setup-system.yml#99 no longer runs on prod-prod1; Auto is shown."),
            details.section("Play selector")!!.items.map { it.text },
        )
    }

    fun testADangerZonePlayDropsThePlaybookLevelGroupVars() {
        val replisync = path("pelican", "Environments", "prod", "Hosts", "prod-replisync1")
        assertEquals(
            "Auto: the danger-zone plays do not load ansible/group_vars",
            "zone_probe = root-level  L5 playbook group_vars/all · group_vars/all.yml:1 · set in 3 of 7 plays",
            presentation(variable("zone_probe", replisync)).text,
        )
        choose("prod", "prod-replisync1", PlayChoice.Inventory, PELICAN)
        assertEquals(
            "the inventory view uses the root's playbook dir",
            "zone_probe = root-level  L5 playbook group_vars/all · group_vars/all.yml:1",
            presentation(variable("zone_probe", replisync)).text,
        )
        choose("prod", "prod-replisync1", PlayChoice.Play("danger_zone/database/playbook-clone-to-replisync.yml#1"), PELICAN)
        val rows = runReadActionBlocking { effective(replisync).table()!! }.rows
        assertNull("danger-zone plays do not load ansible/group_vars", rows.firstOrNull { it.name == "zone_probe" })
        choose("prod", "prod-replisync1", PlayChoice.Play("playbook-setup-replisync.yml#2"), PELICAN)
        assertEquals("L5 playbook group_vars/all", runReadActionBlocking { effective(replisync).table()!! }.row("zone_probe")!!.winner.layerText)
    }

    fun testAutoGroupsThePlaysByTheDefinitionThatWins() {
        val replisync = path("pelican", "Environments", "prod", "Hosts", "prod-replisync1")
        assertEquals("Effective vars  7 · all 7 plays", presentation(effective(replisync)).text)
        val split = variable("zone_split", replisync)
        assertEquals(
            "the definition that wins in most plays first",
            "zone_split = danger-zone  L5 playbook group_vars/all · danger_zone/database/group_vars/all.yml:1 · another value in 3 plays",
            presentation(split).text,
        )
        assertEquals(
            listOf("root-level  L5 playbook group_vars/all · group_vars/all.yml:2 · in 3 plays: Ping replisync hosts serially, Debug, Install Percona on the replisync"),
            children(split).map { presentation(it).text },
        )
        assertTrue(children(split).single() is PlayOutcomeNode)
        assertEquals(
            "the tooltip names each play with its playbook",
            "Wins in playbook-setup-replisync.yml › Ping replisync hosts serially, playbook-setup-replisync.yml › Debug, " +
                "playbook-setup-replisync.yml › Install Percona on the replisync",
            presentation(children(split).single()).tooltip[1],
        )
        assertEquals("$PELICAN/group_vars/all.yml:2", describe(children(split).single().target))
        val details = runReadActionBlocking { split.details() }
        assertEquals(
            listOf("danger-zone — str · L5 playbook group_vars/all · danger_zone/database/group_vars/all.yml:1 · in 4 plays: Ping database hosts serially, Check replica state, Ping replisync hosts serially +1"),
            details.section("Effective value")!!.items.map { it.toString() },
        )
        assertEquals(1, details.section("Other values per play")!!.items.size)

        // The tree table shows the other play's winner as a sub-row.
        val content = runReadActionBlocking { effective(replisync).details()!!.content as EffectiveVarsContent }
        val view = EffectiveVarsView(content, { _, _ -> }, { })
        val index = view.variableRows.indexOfFirst { it.row.name == "zone_split" }
        view.table.tree.expandRow(index)
        val outcome = view.rowAt(index + 1) as EffectiveVarsView.Row.Outcome
        val model = view.table.tableModel
        assertEquals(listOf("root-level", "str", "L5 playbook group_vars/all", "group_vars/all.yml:2", null), (1 until model.columnCount).map { model.getValueAt(outcome, it) })
        assertEquals("$PELICAN/group_vars/all.yml:2", describe(outcome.target))
    }

    // ------------------------------------------------------------------ Targeted by

    fun testTargetedByListsThePlaysInPlaybookOrder() {
        val targeted = path(prod1, "Targeted by")
        assertEquals("Targeted by  27 plays · 6 playbooks", presentation(targeted).text)
        assertEquals(
            listOf(
                "playbook-initial-setup.yml  3 plays · playbook dir ansible",
                "playbook-setup-apps.yml  7 plays · playbook dir ansible",
                "playbook-setup-jenkins.yml  3 plays · playbook dir ansible",
                "playbook-setup-keycloak.yml  6 plays · playbook dir ansible",
                "playbook-setup-ops.yml  2 plays · playbook dir ansible",
                "playbook-setup-system.yml  6 plays · playbook dir ansible",
            ),
            texts(targeted),
        )
        val system = path(targeted, "playbook-setup-system.yml")
        assertEquals(
            listOf(
                "Ping all hosts serially  hosts: all",
                "Debug  hosts: all · roles: debug",
                "System  hosts: system · roles: system, system-access, system-apt +4",
                "Monitoring Client  hosts: monitoring_client · roles: alloy",
                "KeepAliveD  hosts: keepalived · roles: keepalived",
                "Proxy  hosts: haproxy · roles: haproxy",
            ),
            texts(system),
        )
        val play = path(system, "System")
        assertEquals(listOf("system", "system-access", "system-apt", "system-heartbeat", "postfix", "inventory-docs-client", "iptables"), names(play))
        assertEquals("postfix  in roles:", texts(play)[4])
        assertEquals("$FALCON/playbook-setup-system.yml:20", describe(play.target))
        assertEquals("the role reference in the playbook", "$FALCON/playbook-setup-system.yml:29", describe(path(play, "postfix").target))

        val details = runReadActionBlocking { targeted.details() }!!
        assertEquals("Plays on prod-prod1", details.title)
        assertEquals("falcon › prod", details.subtitle)
        assertEquals("one section per playbook, in the tree's order", texts(targeted).map { it.substringBefore("  ") }, details.sections.map { it.title })
        val systemPlays = details.section("playbook-setup-system.yml")!!.items
        assertEquals("System — hosts: system · roles: system, system-access, system-apt +4", systemPlays[2].toString())
        assertEquals("$FALCON/playbook-setup-system.yml:20", describe(systemPlays[2].target))
    }

    fun testTargetedByCountsNestedRootPlaysWithTheirPlaybookDir() {
        val replisync = path("pelican", "Environments", "prod", "Hosts", "prod-replisync1")
        val playbooks = texts(path(replisync, "Targeted by"))
        assertTrue(playbooks.toString(), "playbook-setup-replisync.yml  3 plays · playbook dir ansible" in playbooks)
        assertTrue(playbooks.toString(), "danger_zone/database/playbook-clone-to-replisync.yml  2 plays · playbook dir ansible/danger_zone/database" in playbooks)
        assertEquals("Targeted by  no play", presentation(path("pelican", "Environments", "prod", "Hosts", "prod-prod1", "Targeted by")).text)
    }

    fun testANestedRootPlayNamesItsPlaybookDir() {
        val play = path("pelican › danger_zone/database", "Playbooks", "playbook-clone-to-replisync.yml", "Clone to replisync")
        assertEquals("Clone to replisync  hosts: replisync · 1 host", presentation(play).text)
        assertEquals(listOf("prod  prod-replisync1"), texts(play).take(1))
        val details = runReadActionBlocking { play.details() }!!
        assertTrue(details.section("Play")!!.items.map { it.text }.toString(), details.section("Play")!!.items.any { it.text == "Playbook dir: ansible/danger_zone/database" })
    }

    // ------------------------------------------------------------------ vault

    fun testVaultValuesAreMaskedAndNeverDecrypted() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        val texts = ArrayList<String>()
        for (env in listOf("ops", "prod", "test")) {
            for (host in children(path("falcon", "Environments", env, "Hosts"))) {
                host as HostNode
                for (play in listOf(PlayChoice.Auto, PlayChoice.Inventory, PlayChoice.Play("playbook-setup-system.yml#2"))) {
                    choose(env, host.host.name, play)
                    for (node in walk(effective(host))) {
                        val presentation = presentation(node)
                        texts += presentation.text
                        texts += presentation.tooltip
                        runReadActionBlocking { node.details() }?.let { details ->
                            details.sections.forEach { section -> section.items.forEach { texts += it.toString() } }
                            (details.content as? EffectiveVarsContent)?.table?.rows?.forEach { row ->
                                texts += row.winner.value
                                row.shadowed.forEach { texts += it.value }
                                row.others.forEach { texts += it.winner.value }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("walked ${texts.size} texts", texts.size > 3000)
        val leaks = texts.filter { InfraTestData.containsVaultPayload(it) || "\$ANSIBLE_VAULT" in it || InfraTestData.FIXTURE_VAULT_PLAINTEXT.containsMatchIn(it) || SENTINEL_NAME in it || SENTINEL_FILE in it }
        assertTrue(leaks.take(5).toString(), leaks.isEmpty())
        assertEquals("nothing was decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)

        choose("prod", "prod-prod1", PlayChoice.Inventory)
        val rows = runReadActionBlocking { effective().table()!! }
        assertEquals("🔒 vault-encrypted", rows.row("vault_alloy_tenant_api_key_prod")!!.winner.value)
        assertTrue(rows.row("vault_alloy_tenant_api_key_prod")!!.winner.masked)
        assertEquals(ValueKind.VAULT, rows.row("vault_alloy_tenant_api_key_prod")!!.winner.kind)
        assertEquals("🔒 value hidden (vault_ name)", rows.row("vault_plain_secret")!!.winner.value)
        assertEquals("🔒 value hidden (vault file)", rows.row("plain_in_vault_file")!!.winner.value)
        assertEquals(
            "vault_alloy_tenant_api_key_prod = 🔒 vault-encrypted  L5 playbook group_vars/all · group_vars/all/vault.yml:28",
            presentation(variable("vault_alloy_tenant_api_key_prod")).text,
        )
    }

    // ------------------------------------------------------------------ laziness

    fun testExpandingAHostComputesOnlyThatHost() {
        val tables = EffectiveTables.getInstance(project)
        val facts = ToolWindowModels.getInstance(project).factsStats
        val before = tables.stats.computations
        val factsBefore = facts.computations
        val hosts = children(path("falcon", "Environments", "prod", "Hosts"))
        hosts.forEach { presentation(it) }
        assertEquals("listing hosts computes no table", before, tables.stats.computations)
        assertTrue("the badges read the host facts of prod at most once: ${facts.computations - factsBefore}", facts.computations - factsBefore <= 1)
        val factsListed = facts.computations
        hosts.forEach { presentation(it) }
        assertEquals("rendering the hosts again reads them from the cache", factsListed, facts.computations)
        val contributed = children(prod1).takeLast(2)
        assertEquals("creating the nodes computes nothing", before, tables.stats.computations)
        val started = System.nanoTime()
        contributed.forEach { presentation(it) }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("TOOLWINDOW: prod-prod1 Effective vars (all 27 plays) and Targeted by in $millis ms")
        assertEquals("expanding prod-prod1 computes one table", before + 1, tables.stats.computations)
        assertEquals(listOf("prod-prod1"), tables.computedHosts.drop(tables.computedHosts.size - 1).map(HostKey::host))
        children(effective()).forEach { presentation(it) }
        assertEquals("its rows come from the cached table", before + 1, tables.stats.computations)
        assertTrue("well within an interactive budget: $millis ms", millis < 2_000)
    }

    private companion object {
        const val SENTINEL_NAME = "sentinel-plain-vault-name-7f3a"
        const val SENTINEL_FILE = "sentinel-plain-in-vault-file-91c2"
    }
}
