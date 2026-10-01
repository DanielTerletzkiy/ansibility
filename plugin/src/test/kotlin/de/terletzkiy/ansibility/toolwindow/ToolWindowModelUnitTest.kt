package de.terletzkiy.ansibility.toolwindow

import com.intellij.testFramework.LightVirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.Inventory
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.TargetVersionSource
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEntry
import de.terletzkiy.ansibility.toolwindow.model.ConnectionSettings
import de.terletzkiy.ansibility.toolwindow.model.EnvironmentNode
import de.terletzkiy.ansibility.toolwindow.model.EnvironmentView
import de.terletzkiy.ansibility.toolwindow.model.GroupNode
import de.terletzkiy.ansibility.toolwindow.model.GroupsNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.LayerSourceNode
import de.terletzkiy.ansibility.toolwindow.model.LayerTexts
import de.terletzkiy.ansibility.toolwindow.model.RootSnapshot
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowTexts
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot

/** Wording, parsing and ordering rules of the tool window model, on synthetic data. */
class ToolWindowModelUnitTest : ToolWindowTestCase() {

    // ------------------------------------------------------------------ level wording (plan F6.2)

    fun testEveryLayerHasALabelAndAPlainWordsTooltip() {
        for (layer in VarsLayer.entries) {
            val label = LayerTexts.label(layer, "web")
            val tooltip = LayerTexts.tooltip(layer, "web")
            assertFalse(layer.name, label.isBlank() || label.startsWith("!"))
            assertTrue("$layer: $tooltip", tooltip.length > 40 && tooltip.endsWith("."))
            if (layer != VarsLayer.MOLECULE_INVENTORY) {
                assertTrue("$layer: $label", label.startsWith("L${layer.level} "))
                assertTrue("$layer: $tooltip", tooltip.startsWith("Level ${layer.level}: "))
            }
            assertFalse("no unformatted placeholders: $label / $tooltip", label.contains("{") || tooltip.contains("{0}"))
        }
        assertEquals("L5 playbook group_vars/all — beats env group_vars/all (L4)", LayerTexts.label(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, "all"))
        assertEquals("L6 env group_vars/web — beats group_vars/all (L4, L5)", LayerTexts.label(VarsLayer.INVENTORY_GROUP_VARS, "web"))
        assertEquals("L7 playbook group_vars/web — beats every env group_vars file (L6)", LayerTexts.label(VarsLayer.PLAYBOOK_GROUP_VARS, "web"))
        assertTrue(LayerTexts.tooltip(VarsLayer.INVENTORY_GROUP_VARS, "web").contains("sort_groups order (depth, then ansible_group_priority, then name)"))
    }

    fun testCompactPathsAndCappedLists() {
        assertEquals("group_vars/all/{vars,vault}.yml", ToolWindowTexts.compactPaths(listOf("group_vars/all/vars.yml", "group_vars/all/vault.yml")))
        assertEquals("group_vars/all.yml", ToolWindowTexts.compactPaths(listOf("group_vars/all.yml")))
        assertEquals(
            "group_vars/web/{a,b}.yml, group_vars/web/c.json, group_vars/web",
            ToolWindowTexts.compactPaths(listOf("group_vars/web/a.yml", "group_vars/web/b.yml", "group_vars/web/c.json", "group_vars/web")),
        )
        assertEquals("a, b", ToolWindowTexts.joinCapped(listOf("a", "b"), 3))
        assertEquals("a, b, c +2", ToolWindowTexts.joinCapped(listOf("a", "b", "c", "d", "e"), 3))
    }

    fun testCoreText() {
        assertEquals("core 2.18.8 (docker pin)", ToolWindowTexts.coreText(TargetVersion(CoreVersion.PINNED, TargetVersionSource.DOCKERFILE, "docker/x/Dockerfile")))
        assertEquals("core 2.21.4 (local install, guessed)", ToolWindowTexts.coreText(TargetVersion(CoreVersion(2, 21, 4), TargetVersionSource.LOCAL_GUESSED, "/opt/ansible")))
        assertEquals("core 2.19.0 (settings override)", ToolWindowTexts.coreText(TargetVersion(CoreVersion(2, 19), TargetVersionSource.SETTINGS, "")))
        assertEquals("core unknown", ToolWindowTexts.coreText(TargetVersion.UNKNOWN))
    }

    // ------------------------------------------------------------------ ansible.cfg level 1

    fun testConnectionSettingsFromTheDefaultsSection() {
        val file = LightVirtualFile("ansible.cfg")
        val text = "[ssh_connection]\nremote_user = wrong\n[defaults]\n; comment\nremote_port: 2222 ; inline comment\nremote_user=provisioner\n"
        val settings = ConnectionSettings.parse(file, text)!!
        assertEquals("provisioner", settings.remoteUser)
        assertEquals("2222", settings.remotePort)
        assertEquals("remote_user=provisioner, remote_port=2222", settings.text)
        assertEquals("the first key inside [defaults]", text.indexOf("remote_port"), settings.offset)
        assertNull(ConnectionSettings.parse(file, "[defaults]\nhost_key_checking = False\n"))
        assertEquals("remote_port=22", ConnectionSettings.parse(file, "[defaults]\nremote_port = 22")!!.text)
    }

    // ------------------------------------------------------------------ refresh filter

    fun testRefreshFilterLooksBelowTheShownRootsOnly() {
        val filter = RefreshFilter(listOf("/work/build/repo/ansible", "/work/build/repo/ansible/danger_zone/database"))
        val relevant = listOf(
            "/work/build/repo/ansible/environments/prod/hosts.yml",
            "/work/build/repo/ansible/environments/prod/group_vars/web/vars.yml",
            "/work/build/repo/ansible/group_vars/all.yml",
            "/work/build/repo/ansible/host_vars",
            "/work/build/repo/ansible/ansible.cfg",
            "/work/build/repo/ansible/playbook-setup.yml",
            "/work/build/repo/ansible/danger_zone/database/playbook-clone.yml",
            "/work/build/repo/ansible/playbooks/site.yml",
            "/work/build/repo/ansible",
        )
        val irrelevant = listOf(
            "/work/build/repo/ansible/roles/web/tasks/main.yml",
            "/work/build/repo/ansible/README.md",
            "/work/build/repo/ansible/node_modules/x/environments/hosts.yml",
            "/elsewhere/environments/prod/hosts.yml",
        )
        for (path in relevant) assertTrue(path, filter.isRelevantPath(path))
        for (path in irrelevant) assertFalse(path, filter.isRelevantPath(path))
        assertFalse("nothing is relevant before the first snapshot", RefreshFilter(emptyList()).isRelevantPath(relevant.first()))
    }

    // ------------------------------------------------------------------ precedence from ansible.cfg

    fun testAnsibleCfgPrecedenceReordersTheGroupLevels() {
        val cfg = "[defaults]\nprecedence = groups_plugins_play, all_plugins_play, groups_plugins_inventory, all_plugins_inventory, groups_inventory, all_inventory\n"
        val tree = myFixture.tempDirFixture
        tree.createFile("site/ansible.cfg", cfg)
        tree.createFile("site/environments/prod/hosts.yml", "all:\n  vars:\n    a: 1\n  hosts:\n    h1:\nweb:\n  hosts:\n    h1:\n  vars:\n    b: 2\n")
        tree.createFile("site/environments/prod/group_vars/all.yml", "a: 1\n")
        tree.createFile("site/environments/prod/group_vars/web.yml", "b: 2\n")
        tree.createFile("site/environments/prod/host_vars/h1.yml", "c: 3\n")
        tree.createFile("site/group_vars/all.yml", "a: 1\n")
        tree.createFile("site/group_vars/web.yml", "b: 2\n")
        tree.createFile("site/host_vars/h1.yml", "c: 3\n")
        refreshRoots()
        val root = snapshot().root(vf("site"))!!
        assertTrue(root.customPrecedence)
        val host = path("site", "Environments", "prod", "Hosts", "h1")
        assertEquals(
            listOf(
                "site/group_vars/web.yml",
                "site/group_vars/all.yml",
                "group_vars/web.yml",
                "group_vars/all.yml",
                "hosts.yml: b",
                "hosts.yml: a",
                "host_vars/h1.yml",
                "site/host_vars/h1.yml",
            ),
            names(host),
        )
        val note = (host as HostNode).details().section("Precedence")!!.items.single().text
        assertTrue(note, note.startsWith("ansible.cfg sets precedence = groups_plugins_play, all_plugins_play"))
    }

    // ------------------------------------------------------------------ inline host vars (level 8)

    fun testInlineHostVarsAreLevel8BetweenTheGroupLevelsAndHostVars() {
        val tree = myFixture.tempDirFixture
        tree.createFile("site/ansible.cfg", "[defaults]\n")
        tree.createFile(
            "site/environments/prod/hosts.yml",
            "all:\n  hosts:\n    h1:\n      ansible_host: 192.0.2.10\n      ansible_user: deploy\n    h2:\n      ansible_host: 192.0.2.11\n    h3:\n" +
                "web:\n  hosts:\n    h1:\n      web_port: 8080\n    h2:\n    h3:\n",
        )
        tree.createFile("site/environments/prod/group_vars/web.yml", "b: 2\n")
        tree.createFile("site/environments/prod/host_vars/h1.yml", "c: 3\n")
        refreshRoots()
        val env = snapshot().root(vf("site"))!!.environments.single()
        assertEquals("keys of every entry of the host, in file order", listOf("ansible_host", "ansible_user", "web_port"), env.host("h1")!!.inlineVarKeys)
        assertEquals(emptyList<String>(), env.host("h3")!!.inlineVarKeys)

        val h1 = path("site", "Environments", "prod", "Hosts", "h1")
        assertEquals("h1  192.0.2.10 · inline (3): ansible_host, ansible_user, web_port · groups: all, web", h1.presentation().text)
        assertEquals(
            listOf(
                "group_vars/web.yml  L6 env group_vars/web — beats group_vars/all (L4, L5)",
                "hosts.yml: ansible_host, ansible_user, web_port  L8 inventory file vars of host h1 — beats every group level",
                "host_vars/h1.yml  L9 env host_vars/h1 — beats every group level (L3–L7)",
            ),
            texts(h1),
        )
        val inline = children(h1)[1] as LayerSourceNode
        assertEquals(8, inline.source.level)
        assertTrue(inline.presentation().tooltip.first().startsWith("Level 8: variables written inline for host h1"))
        assertEquals("site/environments/prod/hosts.yml:3", describe(inline.target))
        val details = inline.details()
        assertEquals(listOf("ansible_host", "ansible_user", "web_port"), details.section("Inline vars in hosts.yml")!!.items.map { it.text })
        assertEquals(listOf("h1"), details.section("Hosts it applies to")!!.items.map { it.text })
        assertEquals(
            listOf("ansible_host", "ansible_user", "web_port"),
            (h1 as HostNode).details().section("Inline vars in hosts.yml")!!.items.map { it.text },
        )

        val h2 = path("site", "Environments", "prod", "Hosts", "h2")
        assertEquals("only ansible_host: the address already shows it", "h2  192.0.2.11 · groups: all, web", h2.presentation().text)
        assertEquals("hosts.yml: ansible_host  L8 inventory file vars of host h2 — beats every group level", texts(h2)[1])
        assertEquals("no inline vars, no level 8", listOf("group_vars/web.yml"), names(path("site", "Environments", "prod", "Hosts", "h3")))
    }

    // ------------------------------------------------------------------ group tree on synthetic inventories

    fun testGroupCyclesAreCutAndMultiParentGroupsAppearTwice() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("lab")
        val hostsFile = myFixture.tempDirFixture.createFile("lab/environments/dev/hosts.yml", "# synthetic\n")
        fun group(name: String, parents: List<String>, children: List<String>, hosts: List<String> = emptyList(), depth: Int = 1) =
            InventoryGroup(name, parents, children, hosts, emptyList(), SourceLocation(hostsFile, 0), depth, 1)
        val groups = listOf(
            group("all", emptyList(), listOf("a", "c"), depth = 0),
            group("a", listOf("all", "b"), listOf("b")),
            group("b", listOf("a"), listOf("a"), listOf("h1"), depth = 2),
            group("c", listOf("all"), listOf("b"), depth = 1),
        ).associateBy { it.name }
        val inventory = Inventory(
            dir, "dev", hostsFile, groups,
            mapOf("h1" to InventoryHost("h1", null, listOf("all", "a", "c", "b"), null)),
            emptyList(),
        )
        val root = RootSnapshot(
            root = AnsibleRoot(dir, RootKind.PROJECT, false, null, emptyList(), null, "lab"),
            target = TargetVersion.UNKNOWN,
            roleCount = 0,
            inventories = listOf(inventory),
            playbookVarFiles = emptyList(),
            playbooks = emptyList(),
            cfgFile = null,
            connection = null,
            precedence = PrecedenceEntry.DEFAULT,
            parent = null,
        )
        val env = EnvironmentView(root, inventory)
        assertEquals(listOf("all", "a", "c", "b"), env.applyOrder.map { it.name })
        assertEquals(setOf("b", "a"), env.descendantsOf(groups.getValue("c")))
        assertEquals(listOf("b"), env.viaGroups(groups.getValue("c"), inventory.hosts.getValue("h1")))

        val workspace = WorkspaceNode(WorkspaceSnapshot(listOf(root), emptyList()))
        val envNode = EnvironmentNode(workspace, env)
        val groupsNode = GroupsNode(envNode, env)
        val top = groupsNode.children(TreeContext.NONE)
        assertEquals(listOf("all", "a", "c"), top.map { it.presentation().name })
        fun names(node: GroupNode) = node.children(TreeContext.NONE).map { it.presentation().name }
        val a = top[1] as GroupNode
        val b = a.children(TreeContext.NONE).single() as GroupNode
        assertEquals("a › b stops at the cycle back to a", listOf("h1"), names(b))
        val c = top[2] as GroupNode
        assertEquals("b appears under each parent", listOf("b"), names(c))
        assertEquals("the host has no sources here", emptyList<String>(), (b.children(TreeContext.NONE).single() as HostNode).children(TreeContext.NONE).map { it.key })
        assertTrue("the details list every host of a group", a.details().section("Hosts it applies to")!!.items.map { it.text } == listOf("h1"))
        assertTrue(top.none { it is LayerSourceNode })
    }
}
