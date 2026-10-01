package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.EffectiveVars
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.model.inventory.InventoryTestCase

/**
 * [EffectiveVarsServiceImpl] on the synthetic `testData/model-inventory` trees: the full level 3–10 chain, group
 * priority, directory load order, JSON and extension-less files, vault masking, and the `ansible.cfg` settings
 * (`hash_behaviour = merge`, a custom `precedence`, `yaml_valid_extensions`). The winners were checked against
 * `ansible-inventory --list` 2.21 on the same trees.
 */
class EffectiveVarsLayoutTest : InventoryTestCase() {
    override fun setUp() {
        super.setUp()
        copyTree("model-inventory/layout", LAYOUT)
        copyTree("model-inventory/merge", MERGE)
        refreshRoots()
    }

    private fun view(host: String, playbookDir: String? = LAYOUT): EffectiveVars =
        effective.inventoryView(root(LAYOUT), "dev", host, playbookDir?.let(::vf))!!

    private fun VarSourceRef.describe(): String = "L${layer.level} ${layer.name} ${at(this).removePrefix("$LAYOUT/")}"

    fun testTheWholeChainFromInlineAllVarsToPlaybookHostVars() {
        val shared = view("web01.example.com")["shared"]!!
        assertEquals("L10 PLAYBOOK_HOST_VARS host_vars/web01.example.com.yml:2", shared.winner.describe())
        assertEquals("web01.example.com", shared.winner.host)
        assertEquals("playbook-host", shared.winner.preview)
        assertEquals(
            listOf(
                "L9 INVENTORY_HOST_VARS environments/dev/host_vars/web01.example.com:2",
                "L8 INVENTORY_FILE_HOST environments/dev/hosts.yml:9",
                "L7 PLAYBOOK_GROUP_VARS group_vars/web.yml:2",
                "L6 INVENTORY_GROUP_VARS environments/dev/group_vars/web/10-base.yml:2",
                "L5 PLAYBOOK_GROUP_VARS_ALL group_vars/all/vars.yml:2",
                "L4 INVENTORY_GROUP_VARS_ALL environments/dev/group_vars/all.yml:2",
                "L3 INVENTORY_FILE_GROUP environments/dev/hosts.yml:4",
            ),
            shared.shadowed.map { it.describe() },
        )
        assertEquals(
            listOf("host:web01.example.com", "host:web01.example.com", "web", "web", "all", "all", "all"),
            shared.shadowed.map { it.group ?: "host:${it.host}" },
        )
    }

    fun testPlaybookGroupVarsBeatInventoryGroupVarsWhateverTheGroup() {
        val shared = view("web02")["shared"]!!
        assertEquals("L7 PLAYBOOK_GROUP_VARS group_vars/web.yml:2", shared.winner.describe())
        assertEquals("web", shared.winner.group)
    }

    fun testWithoutPlaybookDirOnlyInventorySourcesCount() {
        val shared = view("web01.example.com", playbookDir = null)["shared"]!!
        assertEquals("L9 INVENTORY_HOST_VARS environments/dev/host_vars/web01.example.com:2", shared.winner.describe())
        assertTrue(shared.shadowed.none { it.layer.name.startsWith("PLAYBOOK_") })
    }

    fun testThePlaybookDirIsAParameter() {
        val shared = view("db1", playbookDir = "$LAYOUT/playbooks")["shared"]!!
        assertEquals("L5 PLAYBOOK_GROUP_VARS_ALL playbooks/group_vars/all.yml:2", shared.winner.describe())
        assertEquals("playbooks-dir-all", shared.winner.preview)
    }

    fun testGroupVarsDirectoriesLoadInSortedOrder() {
        val tier = view("web01.example.com")["tier"]!!
        assertEquals("L6 INVENTORY_GROUP_VARS environments/dev/group_vars/web/sub/30-nested.yml:2", tier.winner.describe())
        assertEquals(
            listOf(
                "L6 INVENTORY_GROUP_VARS environments/dev/group_vars/web/20-extra.yaml:2",
                "L6 INVENTORY_GROUP_VARS environments/dev/group_vars/web/10-base.yml:3",
                "L3 INVENTORY_FILE_GROUP environments/dev/hosts.yml:18",
            ),
            tier.shadowed.map { it.describe() },
        )
    }

    fun testGroupPriorityOrdersGroupsOfEqualDepth() {
        val color = view("web01.example.com")["color"]!!
        assertEquals("web (priority 5) applies after zeta (priority 1)", "web", color.winner.group)
        assertEquals(listOf("zeta"), color.shadowed.map { it.group })
        assertNull("ansible_group_priority is no variable", view("web01.example.com")["ansible_group_priority"])
    }

    fun testJsonAndExtensionlessVarsFiles() {
        val db = view("db1")
        val port = db["db_port"]!!.winner
        assertEquals("L6 INVENTORY_GROUP_VARS environments/dev/group_vars/db.json:2", port.describe())
        assertEquals("5432", port.preview)
        assertEquals("L8 INVENTORY_FILE_HOST environments/dev/hosts.yml:32", db["db_role"]!!.winner.describe())
        assertEquals("L6 INVENTORY_GROUP_VARS environments/dev/group_vars/lb:2", view("lb2")["lb_algo"]!!.winner.describe())
        assertNull("orphan group_vars never load", db["orphan_var"])
    }

    fun testVaultValuesAndVaultFilesAreMasked() {
        val web02 = view("web02")
        val token = web02["secret_token"]!!.winner
        assertTrue(token.isVault)
        assertNull(token.preview)
        assertNull("vault_* keys", web02["vault_plain"]!!.winner.preview)
        assertFalse(web02["vault_plain"]!!.winner.isVault)
        assertEquals("{user: admin, vault_password: ***}", web02["nested"]!!.winner.preview)
        val apiKey = web02["api_key"]!!.winner
        assertEquals("environments/dev/host_vars/web02/vault.yml", rel(apiKey.file).removePrefix("$LAYOUT/"))
        assertNull("values in vault files", apiKey.preview)
        assertEquals("8080", web02["web02_port"]!!.winner.preview)
    }

    fun testAnsibleCfgHashBehaviourPrecedenceAndExtensions() {
        val view = effective.inventoryView(root(MERGE), "prod", "h1", vf(MERGE))!!
        val settings = view["settings"]!!
        assertEquals(
            "all_inventory is applied last by the configured precedence",
            VarsLayer.INVENTORY_FILE_GROUP,
            settings.winner.layer,
        )
        assertEquals(
            "hash_behaviour = merge keeps the dictionaries of every definition",
            listOf(VarsLayer.INVENTORY_GROUP_VARS_ALL, VarsLayer.PLAYBOOK_GROUP_VARS_ALL),
            settings.mergedFrom.map { it.layer },
        )
        val order = view["order_var"]!!
        assertEquals("all_plugins_inventory after all_plugins_play", VarsLayer.INVENTORY_GROUP_VARS_ALL, order.winner.layer)
        assertEquals(listOf(VarsLayer.PLAYBOOK_GROUP_VARS_ALL), order.shadowed.map { it.layer })
        assertEquals("replace-only values have no merge sources", emptyList<VarSourceRef>(), order.mergedFrom)
        assertNull("yaml_valid_extensions = .yml skips extra.json", view["json_only"])
    }

    fun testEditsInvalidateTheCachedView() {
        val before = view("web01.example.com")
        assertSame(before, view("web01.example.com"))
        val file = vf("$LAYOUT/environments/dev/group_vars/zeta.yml")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("---\ncolor: yellow\nzeta_only: 1\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = view("web01.example.com")
        assertNull(before["zeta_only"])
        assertEquals("L6 INVENTORY_GROUP_VARS environments/dev/group_vars/zeta.yml:3", after["zeta_only"]!!.winner.describe())
    }

    fun testEveryHostOfTheEnvironment() {
        assertEquals(listOf("web01.example.com", "web02", "lb1", "lb2", "db1"), effective.hostsOf(root(LAYOUT), "dev"))
    }

    private companion object {
        const val LAYOUT = "layout"
        const val MERGE = "merge"
    }
}
