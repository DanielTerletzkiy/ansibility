package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * D33 Follow editor (on: the file scope narrows and, when disjoint, overrides the selection; off: the selection applies
 * as-is and such a file is not loaded) and the two context banners: the override banner and the ex-X49 playbook dir.
 */
@RequiresInfraFixture
class FollowEditorAndBannerTest : ContextSwitchingTestCase() {
    override fun addFixtureFiles() {
        // pelican has no playbook-level group_vars in the fixture; one makes the danger-zone playbook dir visible.
        add("repos/pelican/ansible/group_vars/all.yml", "---\npelican_playbook_level: true")
    }

    private fun selectTest1() = context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("test"), "test-test1"))

    /** The text of [provider]'s banner on [path], or null when it shows none. */
    private fun banner(provider: EditorNotificationProvider, path: String): EditorNotificationPanel? {
        val file = vf(path)
        val data = provider.collectNotificationData(project, file) ?: return null
        val editor = FileEditorManager.getInstance(project).openFile(file, false).first()
        return data.apply(editor) as EditorNotificationPanel
    }

    fun testFollowEditorOnLetsADisjointFileScopeWin() {
        selectTest1()
        val scope = context.hostScope(vf(prod1Vars))
        assertTrue(scope.overriddenSelection)
        assertEquals(listOf("prod/prod-prod1"), hosts(scope))
        assertEquals("the stored selection is never rewritten", RootContext(EnvironmentChoice.Named("test"), "test-test1"), context.selection(root("repos/falcon/ansible")))
    }

    fun testFollowEditorOffAppliesTheSelectionAsIs() {
        selectTest1()
        val tracker = context.selectionTracker.modificationCount
        ContextSwitcher.setFollowEditor(project, false)
        assertTrue("Follow editor is presentation state", context.selectionTracker.modificationCount > tracker)
        val disjoint = context.hostScope(vf(prod1Vars))
        assertFalse(disjoint.overriddenSelection)
        assertEquals(emptyList<Any>(), disjoint.targets)
        assertEquals("This file is not loaded for your Ansible context test › test-test1", disjoint.emptyReason)
        assertEquals("the file's own hosts are still known", listOf("prod/prod-prod1"), disjoint.fileHosts.map(::label))
        assertEquals("an intersecting file narrows as before", listOf("test/test-test1"), hosts(context.hostScope(vf(postfixTemplate))))
        assertEquals("inspections never see the setting", listOf("prod/prod-prod1"), hosts(context.allHostsScope(vf(prod1Vars))))
        restart()
        assertFalse("persisted", ContextSwitcher.followsEditor(project))
        ContextSwitcher.setFollowEditor(project, true)
        assertTrue(context.hostScope(vf(prod1Vars)).overriddenSelection)
    }

    fun testTheOverrideBannerOffersTheFilesHost() {
        assertNull("no banner while the scopes intersect", banner(ContextOverrideBannerProvider(), prod1Vars))
        selectTest1()
        val panel = banner(ContextOverrideBannerProvider(), prod1Vars)!!
        assertEquals("This file applies to prod-prod1 only; your Ansible context is test › test-test1", panel.text)
        assertNotNull(panel.findLabelByName("Make prod › prod-prod1 the Ansible context"))
        assertNotNull(panel.findLabelByName("Switch Context…"))

        ContextSwitcher.setFollowEditor(project, false)
        assertEquals(
            "This file applies to prod-prod1 only, so it is not loaded for your Ansible context test › test-test1 (Follow editor is off)",
            banner(ContextOverrideBannerProvider(), prod1Vars)!!.text,
        )
    }

    fun testMakingTheFilesHostTheContextClearsTheBanner() {
        selectTest1()
        val scope = context.hostScope(vf(prod1Vars))
        val target = FileScopeSegment.narrowestTarget(scope)!!
        assertEquals("prod › prod-prod1", target.label)
        ContextSwitcher.use(project, target)
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), context.selection(root("repos/falcon/ansible")))
        assertNull(banner(ContextOverrideBannerProvider(), prod1Vars))
    }

    fun testAFileOverSeveralEnvironmentsOffersAllEnvironments() {
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("test"), "preview-dev1.bike.example.de"))
        val panel = banner(ContextOverrideBannerProvider(), postfixTemplate)!!
        assertEquals(
            "This file applies to ops › ops-ops1, prod › prod-prod1, prod › prod-prod2 +1 more only; your Ansible context is test › preview-dev1.bike.example.de",
            panel.text,
        )
        assertNotNull(panel.findLabelByName("Make All environments the Ansible context"))
    }

    fun testThePlaybookDirBannerOfADangerZonePlaybook() {
        assertEquals(
            "Plays defined in this file do not load ansible/group_vars/**; the imported ../../playbook-setup-replisync.yml plays do",
            banner(PlaybookDirBannerProvider(), cloneToReplisync)!!.text,
        )
        assertNull("plays of the root's own directory load them", banner(PlaybookDirBannerProvider(), "repos/pelican/ansible/playbook-setup-replisync.yml"))
        assertNull("falcon's playbooks sit next to its group_vars", banner(PlaybookDirBannerProvider(), systemPlaybook))
        assertNull("not for other files", banner(PlaybookDirBannerProvider(), prod1Vars))
        val noImports = ContextBanners.playbookDirText(project, root(DANGER_ZONE), vf("repos/pelican/ansible/danger_zone/database/playbook-stop-replica.yml"))
        assertEquals("Plays defined in this file do not load ansible/group_vars/**", noImports)
    }

    fun testTheFileScopeSegmentOffersTheBannersChoiceInThePopup() {
        val segment = FileScopeSegment().segment(project, vf(prod1Vars))!!
        assertEquals("file: prod-prod1 → 1 host", segment.text)
        assertEquals("Applies to: host_vars of prod-prod1", segment.tooltip)
        assertEquals(listOf("Applies to: host_vars of prod-prod1 (grey)", "Make prod › prod-prod1 the Ansible context"), texts(segment.popupActions))
        perform(segment.popupActions.last())
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), context.selection(root("repos/falcon/ansible")))
        assertEquals("nothing to offer once it is the context", 1, FileScopeSegment().segment(project, vf(prod1Vars))!!.popupActions.size)
        assertNull("no segment for files that follow the selection", FileScopeSegment().segment(project, vf("repos/falcon/ansible/ansible.cfg")))
    }

    fun testTheBannersRefreshWhenTheSelectionFollowEditorOrTheStructureChange() {
        val notifications = CountingNotifications()
        project.replaceService(EditorNotifications::class.java, notifications, testRootDisposable)
        selectTest1()
        assertEquals("a selection change", 1, notifications.updates)
        ContextSwitcher.setFollowEditor(project, false)
        assertEquals("a Follow editor change", 2, notifications.updates)
        AnsibilityWorkspaceState.getInstance(project).update { it.copy(scope = "current-root") }
        assertEquals("R9's workspace scope does not change a banner", 2, notifications.updates)
        project.messageBus.syncPublisher(AnsibleStructureListener.TOPIC).structureChanged()
        assertEquals("a structure change", 3, notifications.updates)
    }

    /** Counts the refresh requests of [ContextBannerRefresher]. */
    private class CountingNotifications : EditorNotifications() {
        var updates = 0

        override fun updateNotifications(file: VirtualFile) = Unit

        @Suppress("OVERRIDE_DEPRECATION") // abstract in 262, so a subclass must implement it
        override fun updateNotifications(provider: EditorNotificationProvider) = Unit

        override fun updateAllNotifications() {
            updates++
        }
    }
}
