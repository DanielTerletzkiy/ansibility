package de.terletzkiy.ansibility.toolwindow

import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Plan amendment R20, D153: the Roles node's "applied by N plays" counts converge and verify plays only with "Show
 * Molecule in navigation and search" on (the tree starts in no file, so the setting alone decides).
 */
class MoleculePlayCountTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        MoleculeNavigationFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The text after the role's name: `applied by 1 play`. */
    private fun applied(role: String): String {
        val root = children(workspaceNode()).single()
        val roles = children(root).single { it.presentation().name.startsWith("Roles (") }
        return children(roles).single { it.presentation().name == role }.presentation().text.substringAfter(role).trim()
    }

    fun testConvergePlaysCountOnlyWhenMoleculeIsShown() {
        assertEquals("off: site.yml only", "applied by 1 play", applied("web"))
        assertEquals("off: only converge applies db", "no play applies this copy", applied("db"))
        MoleculeNavigationFixture.showInNavigation(project, true)
        assertEquals("on: site.yml and converge.yml", "applied by 2 plays", applied("web"))
        assertEquals("on", "applied by 1 play", applied("db"))
    }
}
