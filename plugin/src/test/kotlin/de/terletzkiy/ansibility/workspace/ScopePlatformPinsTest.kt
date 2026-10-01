package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.ide.scopeView.ScopeViewPane
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import de.terletzkiy.ansibility.api.AnsibleWorkspace

/**
 * Platform behaviour the workspace scope relies on that `javap` cannot show (plan amendment R9, testing item 1):
 * holder contents, package-set evaluation with the right holder, file patterns relative to the content root, and
 * the holders' scope listeners.
 */
class ScopePlatformPinsTest : WorkspaceScopeTestCase() {

    fun testEditableScopesAreTheUserScopesAndGetScopesAddsThePredefinedOnes() {
        addScope("falcon", "file:repos/falcon//*")
        addScope("mine", "file:golden//*", local)

        assertEquals(listOf("falcon"), shared.editableScopes.map { it.scopeId })
        assertEquals(listOf("mine"), local.editableScopes.map { it.scopeId })
        val predefined = shared.predefinedScopes.map { it.scopeId }
        assertTrue("the shared holder offers predefined scopes: $predefined", predefined.isNotEmpty())
        assertEquals(predefined + "falcon", shared.scopes.map { it.scopeId })
        assertNull("predefined scopes are not editable", shared.editableScopes.firstOrNull { it.scopeId in predefined })
        assertEquals("both holders, local first", listOf(local, shared), NamedScopesHolder.getAllNamedScopeHolders(project).toList())
    }

    fun testFilePatternsAreRelativeToTheContentRootAndSkipTheWorktreeCopy() {
        copyInfra()
        val falcon = GlobalSearchScopesCore.filterScope(project, addScope("falcon", "file:repos/falcon//*"))
        val golden = GlobalSearchScopesCore.filterScope(project, addScope("golden", "file:golden//*"))
        runReadActionBlocking {
            assertTrue(falcon.contains(vf("repos/falcon/ansible/ansible.cfg")))
            assertTrue(falcon.contains(vf("repos/falcon/ansible/roles/haproxy/tasks/main.yml")))
            assertTrue("directories match too", falcon.contains(vf("repos/falcon/ansible/roles/haproxy")))
            assertFalse(falcon.contains(vf("repos/heron/ansible/ansible.cfg")))
            assertTrue(golden.contains(vf("golden/roles/haproxy/tasks/main.yml")))
            assertFalse(
                "the worktree's golden copy is not golden/ of the content root",
                golden.contains(vf("$WORKTREE/golden/roles/haproxy/tasks/main.yml")),
            )
        }
    }

    fun testFilterScopeEvaluatesReferencesWithTheScopesOwnHolder() {
        copyInfra()
        addScope("lfvk", "file:repos/falcon//*", local)
        val reference = addScope("lref", "\$lfvk", local)
        val sharedReference = addScope("sref", "\$lfvk")
        runReadActionBlocking {
            assertTrue(GlobalSearchScopesCore.filterScope(project, reference).contains(vf("repos/falcon/ansible/ansible.cfg")))
            assertFalse(GlobalSearchScopesCore.filterScope(project, reference).contains(vf("repos/heron/ansible/ansible.cfg")))
            assertTrue(
                "a reference resolves through every holder, so a shared scope sees a local one",
                GlobalSearchScopesCore.filterScope(project, sharedReference).contains(vf("repos/falcon/ansible/ansible.cfg")),
            )
        }
    }

    fun testScopeListenersFireOnAddScopeAndRemoveAllSets() {
        val disposable = Disposer.newDisposable(testRootDisposable)
        var sharedEvents = 0
        var localEvents = 0
        shared.addScopeListener({ sharedEvents++ }, disposable)
        local.addScopeListener({ localEvents++ }, disposable)
        addScope("falcon", "file:repos/falcon//*")
        assertEquals(1, sharedEvents)
        shared.removeAllSets()
        assertEquals(2, sharedEvents)
        addScope("mine", "file:golden//*", local)
        assertEquals(1, localEvents)
        assertEquals("holders notify their own listeners only", 2, sharedEvents)
        Disposer.dispose(disposable)
        addScope("heron", "file:repos/heron//*")
        assertEquals("disposed listeners stay quiet", 2, sharedEvents)
    }

    fun testTheScopePaneNamesItsSubIdsByTheScopesPresentableName() {
        addScope("falcon", "file:repos/falcon//*")
        addScope("mine", "file:golden//*", local)
        val pane = ScopeViewPane(project)
        try {
            val falcon = DefaultScopeUi.subIdOf(pane, "falcon")
            assertNotNull("sub-ids: ${pane.subIds.toList()}", falcon)
            assertNotNull("local scopes are listed too", DefaultScopeUi.subIdOf(pane, "mine"))
            assertNull(DefaultScopeUi.subIdOf(pane, "hawk"))
            assertEquals("Scope", ScopeViewPane.ID)
        } finally {
            Disposer.dispose(pane)
        }
    }

    fun testFixtureRoots() {
        copyInfra()
        val roots = AnsibleWorkspace.getInstance(project).roots()
        assertEquals(
            listOf(
                "golden" to false, "falcon" to false, "heron" to false, "pelican" to false, "pelican › danger_zone/database" to false,
                "platform" to false, "wren" to false,
            ).sortedBy { it.first },
            roots.filter { !it.detached }.map { it.displayName to it.detached }.sortedBy { it.first },
        )
        assertTrue("the worktree copy is detached", roots.any { it.detached })
    }
}
