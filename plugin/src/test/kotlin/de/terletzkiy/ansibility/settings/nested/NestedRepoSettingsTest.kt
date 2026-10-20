package de.terletzkiy.ansibility.settings.nested

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.layout.LayoutInventory
import de.terletzkiy.ansibility.settings.layout.LayoutOverride
import de.terletzkiy.ansibility.settings.layout.LayoutSettings
import de.terletzkiy.ansibility.settings.layout.LayoutStorage
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings

/** Settings committed in a sub-repository's own `.idea` (plan amendment R27), seen from the super-repo. */
class NestedRepoSettingsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            runCatching { key() }.getOrNull()?.let { LayoutSettings.getInstance(project).update(it, LayoutOverride(), LayoutStorage.PROJECT, follow = false) }
        } finally {
            super.tearDown()
        }
    }

    fun testSubRepositoryLayoutFileApplies() {
        tree()
        assertEquals("repos/platform/ansible", key())
        val settings = LayoutSettings.getInstance(project).of(key())
        assertEquals(LayoutStorage.REPOSITORY, settings.storage)
        assertEquals("repos/platform/.idea/ansibility-layout.xml", settings.repository)
        assertEquals(listOf("platform build"), ids())
        assertEquals(listOf("group_vars/platform"), layout().inventory("platform build")!!.playbookVars!!.entries)
    }

    fun testProjectSettingsWinOverTheSubRepository() {
        tree()
        LayoutSettings.getInstance(project).update(key(), LayoutOverride(listOf(LayoutInventory("mine", listOf("environments/platform/build/hosts.yml")))), LayoutStorage.PROJECT, follow = false)
        assertEquals(listOf("mine"), ids())
        assertEquals(LayoutStorage.PROJECT, LayoutSettings.getInstance(project).of(key()).storage)
    }

    fun testRepositoryStorageWritesBackKeepingOtherComponentsAndRoots() {
        tree()
        LayoutSettings.getInstance(project).update(
            key(),
            LayoutOverride(listOf(LayoutInventory("platform test", listOf("environments/platform/test/hosts.yml")))),
            LayoutStorage.REPOSITORY,
            follow = false,
        )
        val text = VfsUtilCore.loadText(myFixture.findFileInTempDir("repos/platform/.idea/ansibility-layout.xml")!!)
        assertTrue(text, text.contains("<root custom=\"true\" path=\"ansible\">"))
        assertTrue(text, text.contains("environments/platform/test/hosts.yml"))
        assertFalse(text, text.contains("environments/platform/build/hosts.yml"))
        assertTrue("other roots of the sub-repository stay", text.contains("path=\"other\""))
        assertTrue("other components stay", text.contains("<component name=\"Other\""))
        assertEquals(listOf("platform test"), ids())
    }

    fun testOpenedAloneTheOwnIdeaIsNoSubRepository() {
        myFixture.tempDirFixture.createFile(".idea/ansibility-layout.xml", LAYOUT)
        myFixture.tempDirFixture.createFile("ansible/ansible.cfg", "[defaults]\n")
        assertNull(NestedRepoSettings.getInstance(project).repoOf("ansible"))
    }

    fun testVaultEntriesOfTheSubRepositoryAreReadAndWrittenBack() {
        tree()
        myFixture.tempDirFixture.createFile(
            "repos/platform/.idea/ansibility-vault.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="AnsibilityVault">
                <roots>
                  <root key="ansible">
                    <identities>
                      <identity kind="ENVIRONMENT" label="default" location="PLATFORM_VAULT" />
                    </identities>
                  </root>
                </roots>
              </component>
            </project>
            """.trimIndent(),
        )
        val vault = VaultProjectSettings.getInstance(project)
        assertEquals(listOf(ExplicitIdentity("default", VaultSourceKind.ENVIRONMENT, "PLATFORM_VAULT")), vault.rootSettings(key()).identities)
        assertEquals("repos/platform/.idea/ansibility-vault.xml", vault.repositoryFileOf(key()))
        val before = vault.modificationTracker.modificationCount
        vault.update(key()) { it.copy(environmentIdentities = mapOf("*" to "default")) }
        assertTrue(vault.modificationTracker.modificationCount > before)
        val text = VfsUtilCore.loadText(myFixture.findFileInTempDir("repos/platform/.idea/ansibility-vault.xml")!!)
        assertTrue(text, text.contains("<environment name=\"*\" identity=\"default\""))
        assertEquals(mapOf("*" to "default"), vault.rootSettings(key()).environmentIdentities)
        assertNull("the project's own file gets nothing", vault.state.roots.firstOrNull { it.key == key() })
    }

    private fun tree() {
        myFixture.tempDirFixture.createFile("repos/platform/.idea/ansibility-layout.xml", LAYOUT)
        myFixture.tempDirFixture.createFile("repos/platform/ansible/ansible.cfg", "[defaults]\n")
        myFixture.tempDirFixture.createFile("repos/platform/ansible/environments/platform/build/hosts.yml", "all:\n  children:\n    platform:\n      hosts:\n        b1:\n")
        myFixture.tempDirFixture.createFile("repos/platform/ansible/environments/platform/test/hosts.yml", "all:\n  children:\n    platform:\n      hosts:\n        t1:\n")
        myFixture.tempDirFixture.createFile("repos/platform/ansible/site.yml", "- hosts: platform\n  tasks: []\n")
    }

    private fun root(): AnsibleRoot {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.single { it.dir.name == "ansible" }
    }

    private fun key(): String = RootKeys.keyOf(project, root().dir)

    private fun layout() = root().let { root -> runReadActionBlocking { ProjectLayoutService.getInstance(project).layout(root) } }

    private fun ids() = layout().inventories.map { it.id }

    private companion object {
        val LAYOUT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="AnsibilityLayout">
                <roots>
                  <root custom="true" path="ansible">
                    <inventories>
                      <inventory name="platform build" scopedPlaybookVars="true">
                        <playbookVars>
                          <path value="group_vars/platform" />
                        </playbookVars>
                        <sources>
                          <source value="environments/platform/build/hosts.yml" />
                        </sources>
                      </inventory>
                    </inventories>
                  </root>
                  <root custom="true" path="other">
                    <inventories>
                      <inventory name="x">
                        <sources>
                          <source value="x.yml" />
                        </sources>
                      </inventory>
                    </inventories>
                  </root>
                </roots>
              </component>
              <component name="Other" flag="true" />
            </project>
        """.trimIndent()
    }
}
