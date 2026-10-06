package de.terletzkiy.ansibility.layout

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.LayoutOrigin
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.layout.LayoutInventory
import de.terletzkiy.ansibility.settings.layout.LayoutOverride
import de.terletzkiy.ansibility.settings.layout.LayoutSettings
import de.terletzkiy.ansibility.settings.layout.LayoutSettingsBean
import de.terletzkiy.ansibility.settings.layout.LayoutStorage
import de.terletzkiy.ansibility.settings.layout.PersonalLayoutSettings
import de.terletzkiy.ansibility.settings.layout.SharedLayoutSettings
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializer

/** The Layout settings (plan amendment R10, F10.6): custom inventories, one env per file, storage and precedence. */
class LayoutSettingsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            val key = runCatching { key() }.getOrNull()
            if (key != null) LayoutSettings.getInstance(project).update(key, LayoutOverride(), LayoutStorage.PROJECT, follow = false)
        } finally {
            super.tearDown()
        }
    }

    fun testCustomInventoryReplacesDetection() {
        inventoryDirLayout()
        assertEquals(listOf("prod", "staging"), ids())
        LayoutSettings.getInstance(project).update(
            key(),
            LayoutOverride(inventories = listOf(LayoutInventory("all", listOf("inventory/"), isDefault = true))),
            LayoutStorage.PROJECT,
            follow = false,
        )
        val layout = layout()
        assertEquals(listOf("all"), layout.inventories.map { it.id })
        assertEquals(LayoutOrigin.PROJECT_SETTINGS, layout.inventories.single().source.origin)
        assertTrue(layout.isSingleInventory)
        val inventory = runReadActionBlocking { InventoryService.getInstance(project).inventories(root()) }.single()
        assertEquals(setOf("prod-web1", "stage-web1"), inventory.hosts.keys)
    }

    fun testMissingCustomSourceIsKept() {
        inventoryDirLayout()
        LayoutSettings.getInstance(project).update(
            key(),
            LayoutOverride(inventories = listOf(LayoutInventory("prod", listOf("inventory/prod.yml")), LayoutInventory("gone", listOf("nope/hosts")))),
            LayoutStorage.ONLY_ME,
            follow = false,
        )
        val layout = layout()
        assertEquals(listOf("prod", "gone"), layout.inventories.map { it.id })
        assertNull(layout.inventories[1].sources.single().file)
    }

    fun testOnePerFileSplitsCfgDirectory() {
        add("ansible.cfg", "[defaults]\ninventory = ./inventory\n")
        add("inventory/prod.yml", "all:\n  hosts:\n    p1:\n")
        add("inventory/staging.yml", "all:\n  hosts:\n    s1:\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        assertEquals(listOf("inventory"), ids())
        LayoutSettings.getInstance(project).update(key(), LayoutOverride(onePerFile = true), LayoutStorage.PROJECT, follow = false)
        assertEquals(listOf("prod", "staging"), ids())
        val hosts = runReadActionBlocking { InventoryService.getInstance(project).inventories(root()) }.map { it.environment to it.hosts.keys }
        assertEquals(listOf("prod" to setOf("p1"), "staging" to setOf("s1")), hosts)
    }

    fun testPersonalOverrideWinsPerFieldAndStorageMoves() {
        inventoryDirLayout()
        val key = key()
        val settings = LayoutSettings.getInstance(project)
        settings.update(key, LayoutOverride(inventories = listOf(LayoutInventory("team", listOf("inventory/")))), LayoutStorage.PROJECT, false)
        assertEquals(LayoutStorage.PROJECT, settings.of(key).storage)
        assertNotNull(project.service<SharedLayoutSettings>().overrides[key])

        settings.update(key, LayoutOverride(inventories = listOf(LayoutInventory("mine", listOf("inventory/prod.yml")))), LayoutStorage.ONLY_ME, true)
        assertEquals(LayoutStorage.ONLY_ME, settings.of(key).storage)
        assertTrue(settings.of(key).follow)
        assertNull("moving to Only me removes the shared copy", project.service<SharedLayoutSettings>().overrides[key])
        assertEquals(listOf("mine"), ids())
    }

    fun testStateRoundTrips() {
        val bean = LayoutSettingsBean.of(
            mapOf("." to LayoutOverride(listOf(LayoutInventory("prod", listOf("a.ini", "b/"), isDefault = true)), onePerFile = true)),
            setOf("."),
        )
        val element = XmlSerializer.serialize(bean)
        val back = XmlSerializer.deserialize(element, LayoutSettingsBean::class.java)
        assertEquals(bean.overrides(), back.overrides())
        val personal = PersonalLayoutSettings().apply { loadState(back) }
        assertEquals(setOf("."), personal.follow)
    }

    private fun inventoryDirLayout() {
        add("inventory/prod.yml", "all:\n  children:\n    web:\n      hosts:\n        prod-web1:\n")
        add("inventory/staging.ini", "[web]\nstage-web1\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
    }

    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun root(): AnsibleRoot {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.single()
    }

    private fun key(): String = RootKeys.keyOf(project, root().dir)

    private fun layout() = root().let { root -> runReadActionBlocking { ProjectLayoutService.getInstance(project).layout(root) } }

    private fun ids(): List<String> = layout().inventories.map { it.id }
}
