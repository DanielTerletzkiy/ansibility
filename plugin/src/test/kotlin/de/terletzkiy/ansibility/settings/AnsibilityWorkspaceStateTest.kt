package de.terletzkiy.ansibility.settings

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextTestTree
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.PLATFORM

class AnsibilityWorkspaceStateTest : BasePlatformTestCase() {
    private lateinit var state: AnsibilityWorkspaceState

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        state = AnsibilityWorkspaceState.getInstance(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun root(path: String): AnsibleRoot {
        val dir = myFixture.findFileInTempDir(path)
        return AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == dir }
    }

    fun testDefaultIsAllEnvironmentsWithoutHostOrPlay() {
        val context = state.context(root(FALCON))
        assertEquals(EnvironmentChoice.All, context.environment)
        assertNull(context.host)
        assertNull(context.play)
        assertEquals(WorkspaceState.DEFAULT, state.snapshot)
    }

    fun testStoredInTheWorkspaceFile() {
        val annotation = AnsibilityWorkspaceState::class.java.getAnnotation(State::class.java)
        assertEquals(StoragePathMacros.WORKSPACE_FILE, annotation.storages.single().value)
    }

    fun testContextIsPerRoot() {
        val falcon = root(FALCON)
        state.setEnvironment(falcon, EnvironmentChoice.Named("prod"))
        state.setHost(falcon, "prod-prod1")
        state.setPlay(falcon, "playbook-setup-system.yml#0")
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#0"), state.context(falcon))
        assertEquals(RootContext.DEFAULT, state.context(root(PLATFORM)))
        assertEquals(setOf(FALCON), state.snapshot.roots.keys)
    }

    fun testResettingEverythingDropsTheEntry() {
        val falcon = root(FALCON)
        state.setEnvironment(falcon, EnvironmentChoice.Named("test"))
        state.setHost(falcon, "  ")
        assertNull("blank host is no host", state.context(falcon).host)
        state.setEnvironment(falcon, EnvironmentChoice.All)
        assertTrue(state.snapshot.roots.isEmpty())
    }

    fun testPersistenceRoundTrip() {
        val falcon = root(FALCON)
        state.setEnvironment(falcon, EnvironmentChoice.Named("prod"))
        state.setPlay(falcon, "site.yml#1")
        val (xml, bean) = SettingsTestSupport.xmlRoundTrip(state.state, AnsibilityWorkspaceState.StateBean())
        assertTrue(xml, xml.contains("environment=\"prod\""))
        val copy = AnsibilityWorkspaceState(project)
        copy.loadState(bean)
        assertEquals(state.snapshot, copy.snapshot)
        assertEquals("<component />", SettingsTestSupport.xmlRoundTrip(AnsibilityWorkspaceState(project).state, AnsibilityWorkspaceState.StateBean()).first)
    }

    fun testFollowEditorAndScopeRoundTrip() {
        assertTrue("follows the editor by default", state.snapshot.followEditor)
        assertEquals(WorkspaceState.SCOPE_ALL, state.snapshot.scope)
        state.update { it.copy(followEditor = false, scope = "named:Ansible falcon") }
        val (xml, bean) = SettingsTestSupport.xmlRoundTrip(state.state, AnsibilityWorkspaceState.StateBean())
        assertTrue(xml, xml.contains("followEditor=\"false\"") && xml.contains("scope=\"named:Ansible falcon\""))
        val copy = AnsibilityWorkspaceState(project)
        copy.loadState(bean)
        assertEquals(state.snapshot, copy.snapshot)
    }

    fun testChangesArePublished() {
        val events = mutableListOf<Pair<WorkspaceState, WorkspaceState>>()
        project.messageBus.connect(testRootDisposable).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
                    events += old to new
                }
            },
        )
        val before = state.modificationTracker.modificationCount
        state.setEnvironment(root(FALCON), EnvironmentChoice.Named("prod"))
        assertEquals(1, events.size)
        assertEquals(EnvironmentChoice.Named("prod"), events.single().second.root(FALCON).environment)
        assertTrue(state.modificationTracker.modificationCount > before)
        state.setEnvironment(root(FALCON), EnvironmentChoice.Named("prod"))
        assertEquals("no event without a change", 1, events.size)
    }
}
