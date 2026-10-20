package de.terletzkiy.ansibility.layout

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EffectiveVarsService
import de.terletzkiy.ansibility.api.PlaybookVarsScope
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.layout.LayoutConfigurable
import de.terletzkiy.ansibility.settings.layout.LayoutInventory
import de.terletzkiy.ansibility.settings.layout.LayoutOverride
import de.terletzkiy.ansibility.settings.layout.LayoutSettings
import de.terletzkiy.ansibility.settings.layout.LayoutSettingsBean
import de.terletzkiy.ansibility.settings.layout.LayoutStorage

/** Playbook-level vars scoped per environment in the Layout settings (plan amendment R26). */
class PlaybookVarsScopeTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            runCatching { key() }.getOrNull()?.let { LayoutSettings.getInstance(project).update(it, LayoutOverride(), LayoutStorage.PROJECT, follow = false) }
        } finally {
            super.tearDown()
        }
    }

    fun testScopeMatchesDirectoriesFilesAndExtensionlessNames() {
        val scope = PlaybookVarsScope(listOf("group_vars/platform", " group_vars/all ", "host_vars/web1.yml"))
        assertTrue(scope.includes("group_vars/platform/system.yml"))
        assertTrue(scope.includes("group_vars/platform.yml"))
        assertTrue(scope.includes("group_vars/all.yml"))
        assertTrue(scope.includes("group_vars/all/vars.yml"))
        assertTrue(scope.includes("host_vars/web1.yml"))
        assertFalse(scope.includes("group_vars/alias/system.yml"))
        assertFalse(scope.includes("group_vars/platformx/a.yml"))
        assertFalse(PlaybookVarsScope(emptyList()).includes("group_vars/all.yml"))
    }

    fun testWithoutScopeEveryEnvironmentLoadsEverything() {
        tree()
        custom(null, null)
        assertEquals("shared", value("a", "a1", "shared_var"))
        assertEquals("all", value("a", "a1", "all_var"))
        assertEquals("shared", value("b", "b1", "shared_var"))
    }

    fun testScopedEnvironmentLoadsOnlyItsPlaybookVars() {
        tree()
        custom(listOf("group_vars/all"), null)
        assertNull(value("a", "a1", "shared_var"))
        assertEquals("all", value("a", "a1", "all_var"))
        assertEquals("shared", value("b", "b1", "shared_var"))
        custom(emptyList(), null)
        assertNull(value("a", "a1", "all_var"))
    }

    fun testScopeRoundTripsAndChangesTheFingerprint() {
        tree()
        custom(null, null)
        val before = layout().fingerprint
        custom(listOf("group_vars/all"), null)
        assertFalse(before == layout().fingerprint)
        assertEquals(listOf("group_vars/all"), layout().inventory("a")!!.playbookVars!!.entries)
        assertNull(layout().inventory("b")!!.playbookVars)

        val bean = LayoutSettingsBean.of(
            mapOf("." to LayoutOverride(listOf(LayoutInventory("a", listOf("a.yml"), playbookVars = emptyList()), LayoutInventory("b", listOf("b.yml"))))),
            emptySet(),
        )
        val back = XmlSerializer.deserialize(XmlSerializer.serialize(bean), LayoutSettingsBean::class.java)
        assertEquals(bean.overrides(), back.overrides())
        assertEquals(emptyList<String>(), back.overrides()["."]!!.inventories!![0].playbookVars)
        assertNull(back.overrides()["."]!!.inventories!![1].playbookVars)
    }

    fun testSettingsCellParsing() {
        assertNull(LayoutConfigurable.parsePlaybookVars("  "))
        assertEquals(emptyList<String>(), LayoutConfigurable.parsePlaybookVars("None"))
        assertEquals(listOf("group_vars/a", "group_vars/all.yml"), LayoutConfigurable.parsePlaybookVars("group_vars/a, group_vars/all.yml"))
        assertEquals("none", LayoutConfigurable.playbookVarsText(emptyList()))
        assertEquals("", LayoutConfigurable.playbookVarsText(null))
    }

    /** Two environments sharing group `web`; `group_vars/web` and `group_vars/all.yml` next to the playbook. */
    private fun tree() {
        add("envs/a.yml", "all:\n  children:\n    web:\n      hosts:\n        a1:\n")
        add("envs/b.yml", "all:\n  children:\n    web:\n      hosts:\n        b1:\n")
        add("group_vars/web/shared.yml", "shared_var: shared\n")
        add("group_vars/all.yml", "all_var: all\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
    }

    private fun custom(scopeA: List<String>?, scopeB: List<String>?) {
        LayoutSettings.getInstance(project).update(
            key(),
            LayoutOverride(listOf(LayoutInventory("a", listOf("envs/a.yml"), playbookVars = scopeA), LayoutInventory("b", listOf("envs/b.yml"), playbookVars = scopeB))),
            LayoutStorage.PROJECT,
            follow = false,
        )
    }

    private fun value(env: String, host: String, name: String): String? = root().let { root ->
        runReadActionBlocking { EffectiveVarsService.getInstance(project).inventoryView(root, env, host, root.dir) }
            ?.get(name)?.winner?.preview?.trim('"', '\'')
    }

    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun root(): AnsibleRoot {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.single()
    }

    private fun key(): String = RootKeys.keyOf(project, root().dir)

    private fun layout() = root().let { root -> runReadActionBlocking { ProjectLayoutService.getInstance(project).layout(root) } }
}
