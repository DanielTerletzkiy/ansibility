package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.util.ui.StatusText
import de.terletzkiy.ansibility.AnsibilityBundle
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.TimeUnit

/** Registration of the "Ansibility" tool window, when it is offered, and the content it creates. */
@RequiresInfraFixture
class AnsibleToolWindowFactoryTest : ToolWindowTestCase() {
    private fun applicable(): Boolean =
        ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
            runBlocking { AnsibleToolWindowFactory().isApplicableAsync(project) }
        }.get(30, TimeUnit.SECONDS)

    fun testToolWindowIsRegistered() {
        val ep = ToolWindowEP.EP_NAME.extensionList.single { it.id == AnsibleToolWindowFactory.ID }
        assertEquals("left", ep.anchor)
        assertEquals(AnsibleToolWindowFactory::class.java.name, ep.factoryClass)
        assertEquals("/icons/ansibilityToolWindow.svg", ep.icon)
        assertNotNull(AnsibilityToolWindowIcons.ToolWindow)
        assertEquals(13, AnsibilityToolWindowIcons.ToolWindow.iconWidth)
    }

    /** The stripe title comes from `toolwindow.stripe.<id>` of the plugin's resource bundle, so it can be localized. */
    fun testStripeTitleIsInThePluginBundle() {
        val ep = ToolWindowEP.EP_NAME.extensionList.single { it.id == AnsibleToolWindowFactory.ID }
        val bundleName = ep.pluginDescriptor.resourceBundleBaseName
        assertEquals("messages.AnsibilityBundle", bundleName)
        val bundle = ResourceBundle.getBundle(bundleName!!, Locale.ROOT, ep.pluginDescriptor.classLoader)
        assertEquals("Ansibility", bundle.getString("toolwindow.stripe.${AnsibleToolWindowFactory.ID}"))
        assertEquals("Ansibility", AnsibilityBundle.message("toolwindow.stripe.Ansibility"))
    }

    fun testNotApplicableWithoutRoots() {
        myFixture.tempDirFixture.createFile("docs/README.md", "no ansible here\n")
        refreshRoots()
        assertFalse(applicable())
    }

    fun testNotApplicableWithOnlyADetachedWorktree() {
        // The platform excludes <project>/.claude/worktrees itself; a copy elsewhere is detected, but detached.
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", "$WORKTREE_PARENT/${InfraTestData.WORKTREE_DIR}")
        refreshRoots()
        assertTrue(snapshot().roots.isEmpty())
        assertEquals(1, snapshot().worktrees.size)
        assertFalse("a detached worktree alone does not offer the tool window", applicable())
    }

    fun testApplicableWithAProjectRoot() {
        copyInfraFile("$FALCON/ansible.cfg")
        refreshRoots()
        assertTrue(applicable())
    }

    fun testCreatesThePanelAsContent() {
        copyInfraFile("$FALCON/ansible.cfg")
        refreshRoots()
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        AnsibleToolWindowFactory().createToolWindowContent(project, toolWindow)
        val contents = toolWindow.contentManager.contents
        assertEquals(listOf("Repos", "Roles", "Environments"), contents.map { it.displayName })
        assertTrue("the fixed tabs cannot be closed", contents.none { it.isCloseable })
        val content = contents.first()
        val panel = content.component as AnsibleToolWindowPanel
        assertSame(panel.tree, content.preferredFocusableComponent)
        PlatformTestUtil.waitWithEventsDispatching("no refresh", { panel.refreshCount > 0 }, 20)
        assertEquals(listOf("falcon"), panel.snapshot.roots.map { it.root.displayName })
        toolWindow.contentManager.removeContent(content, true)
        assertTrue("removing the content disposes the panel", panel.isDisposed)
    }

    fun testEmptyProjectShowsTheEmptyText() {
        val panel = AnsibleToolWindowPanel.create(project, testRootDisposable)
        PlatformTestUtil.waitWithEventsDispatching("no refresh", { panel.refreshCount > 0 }, 20)
        val empty: StatusText = panel.tree.emptyText
        assertEquals("No Ansible roots in this project", empty.text)
        assertTrue(panel.snapshot.isEmpty)
    }
}
