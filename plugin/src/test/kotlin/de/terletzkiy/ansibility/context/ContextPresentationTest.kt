package de.terletzkiy.ansibility.context

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.ContextTestTree.DANGER_ZONE
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.context.ContextTestTree.PLATFORM
import de.terletzkiy.ansibility.context.ContextTestTree.WT_FALCON
import de.terletzkiy.ansibility.semantics.CoreVersion

/** X02 texts, the widget state and the X01 summary/balloon on the synthetic tree. */
class ContextPresentationTest : BasePlatformTestCase() {
    private lateinit var workspace: AnsibleWorkspaceImpl

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        workspace = AnsibleWorkspaceImpl.getInstance(project)!!
        workspace.structureChanged()
        PropertiesComponent.getInstance(project).unsetValue(AnsibleOnboarding.SHOWN_KEY)
    }

    override fun tearDown() {
        try {
            PropertiesComponent.getInstance(project).unsetValue(AnsibleOnboarding.SHOWN_KEY)
        } finally {
            super.tearDown()
        }
    }

    private fun root(path: String): AnsibleRoot = workspace.roots().single { it.dir == myFixture.findFileInTempDir(path) }

    private fun pinned(version: String) = TargetVersion(CoreVersion.parse(version), TargetVersionSource.DOCKERFILE, "docker/ansible-playbook/Dockerfile")

    // ------------------------------------------------------------------ X02 status text

    fun testStatusTextOfAProjectRoot() {
        assertEquals("Ansibility: falcon · All envs · core 2.18.8", ContextPresentation.statusText(root(FALCON), pinned("2.18.8")))
    }

    fun testStatusTextOfTheRoleLibrary() {
        assertEquals("Ansibility: golden (role library) · core 2.18.8", ContextPresentation.statusText(root(GOLDEN), pinned("2.18.8")))
    }

    fun testStatusTextOfANestedRootAndGuessedOrUnknownVersions() {
        val guessed = TargetVersion(CoreVersion(2, 18, 8), TargetVersionSource.MAJORITY, "majority of 3 roots")
        assertEquals("Ansibility: pelican › danger_zone/database · All envs · core 2.18.8?", ContextPresentation.statusText(root(DANGER_ZONE), guessed))
        assertEquals("Ansibility: platform · All envs", ContextPresentation.statusText(root(PLATFORM), TargetVersion.UNKNOWN))
        assertEquals("Ansibility: [wt-2] falcon", ContextPresentation.statusText(root(WT_FALCON), TargetVersion.UNKNOWN))
    }

    fun testWidgetStateFollowsTheSelectedFile() {
        val template = myFixture.findFileInTempDir("$FALCON/roles/postfix/templates/main.cf.j2")!!
        val state = AnsibleContextWidget.computeState(project, template)!!
        assertEquals("Ansibility: falcon · All envs · core 2.18.8", state.text)
        assertEquals("postfix", state.context?.roleName)
        assertNull(state.worktree)

        val haproxy = myFixture.findFileInTempDir("$GOLDEN/roles/haproxy/tasks/apt.yml")!!
        assertEquals("Ansibility: golden (role library) · core 2.18.8", AnsibleContextWidget.computeState(project, haproxy)!!.text)

        val clone = myFixture.findFileInTempDir("$DANGER_ZONE/playbook-clone-to-replisync.yml")!!
        assertTrue(AnsibleContextWidget.computeState(project, clone)!!.text.startsWith("Ansibility: pelican › danger_zone/database"))

        assertEquals("[wt-2] falcon", AnsibleContextWidget.computeState(project, myFixture.findFileInTempDir(WT_FALCON))?.root?.displayName)
        assertNull("hidden outside roots", AnsibleContextWidget.computeState(project, myFixture.findFileInTempDir("docs/README.md")))
        assertNull(AnsibleContextWidget.computeState(project, null))
    }

    fun testDetailsPopupLines() {
        val file = myFixture.findFileInTempDir("$FALCON/environments/test/host_vars/preview-dev1.bike.example.de/vars.yml")!!
        val state = AnsibleContextWidget.computeState(project, file)!!
        val details = ContextPresentation.details(state.root, state.context, state.target, state.worktree).associate { it.label to it.value }
        assertEquals("falcon", details["Root"])
        assertEquals("Project (ansible.cfg)", details["Root kind"])
        assertEquals("host_vars", details["File kind"])
        assertEquals("inventory host_vars (precedence 9)", details["Variable layer"])
        assertEquals("test", details["Environment"])
        assertEquals("preview-dev1.bike.example.de", details["Host"])
        assertEquals("2.18.8 (pinned in docker/ansible-playbook/Dockerfile)", details["Target ansible-core"])
        assertFalse("no role for inventory files", "Role" in details)
    }

    fun testDetailsOfADetachedRoot() {
        val file = myFixture.findFileInTempDir("$WT_FALCON/roles/postfix/tasks/main.yml")!!
        val state = AnsibleContextWidget.computeState(project, file)!!
        val details = ContextPresentation.details(state.root, state.context, state.target, state.worktree).associate { it.label to it.value }
        assertEquals("Project (ansible.cfg), detached worktree wt-2", details["Root kind"])
        assertEquals("Role tasks", details["File kind"])
        assertEquals("postfix", details["Role"])
        assertTrue(details.getValue("Target ansible-core").contains("guessed from the majority"))
    }

    fun testEveryFileKindHasAName() {
        for (kind in FileKind.entries) {
            assertFalse(kind.name, ContextPresentation.fileKindName(kind).isBlank())
        }
    }

    // ------------------------------------------------------------------ X01 onboarding

    fun testRootsSummary() {
        val summary = RootsSummary.of(workspace.roots(), workspace.detachedWorktrees())
        assertEquals(3, summary.projectRoots)
        assertEquals(listOf("golden"), summary.roleLibraries)
        assertEquals(mapOf("danger-zone" to 1), summary.nestedByCategory)
        assertEquals("3 project roots · golden · 1 danger-zone · 2 detached worktrees", summary.text())
    }

    fun testRootsSummarySingulars() {
        val summary = RootsSummary(1, emptyList(), emptyMap(), workspace.detachedWorktrees().take(1))
        assertEquals("1 project root · 1 detached worktree", summary.text())
        assertTrue(RootsSummary(0, emptyList(), emptyMap(), emptyList()).isEmpty)
    }

    fun testOnboardingBalloonIsShownOnce() {
        val summary = RootsSummary.of(workspace.roots(), workspace.detachedWorktrees())
        val notification = AnsibleOnboarding.notifyIfNeeded(project, summary)
        assertNotNull(notification)
        assertEquals("Ansibility", notification!!.groupId)
        assertTrue(notification.content.contains("3 project roots · golden · 1 danger-zone · 2 detached worktrees"))
        assertTrue(notification.content.contains("Find in Files"))
        assertEquals(listOf("Exclude worktrees from project"), notification.actions.map { it.templateText })
        assertTrue(PropertiesComponent.getInstance(project).isTrueValue(AnsibleOnboarding.SHOWN_KEY))
        assertNull("once per project", AnsibleOnboarding.notifyIfNeeded(project, summary))
        notification.expire()
    }

    fun testNoBalloonWithoutRootsAndNoExcludeActionWithoutWorktrees() {
        assertNull(AnsibleOnboarding.notifyIfNeeded(project, RootsSummary(0, emptyList(), emptyMap(), emptyList())))
        assertFalse("an empty project does not use up the one balloon", PropertiesComponent.getInstance(project).isTrueValue(AnsibleOnboarding.SHOWN_KEY))
        val plain = AnsibleOnboarding.createNotification(project, RootsSummary(8, listOf("golden"), mapOf("danger-zone" to 2), emptyList()))
        assertTrue(plain.content.contains("8 project roots · golden · 2 danger-zone"))
        assertTrue(plain.actions.isEmpty())
    }
}
