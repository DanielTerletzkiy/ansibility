package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.search.FilenameIndex
import com.intellij.testFramework.PsiTestUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * [WorkspaceScopeServiceImpl] on the infra fixture (plan amendment R9, F9.1): coverage of every root under each
 * choice (full, partial, none; nested roots; the detached worktree), the golden reference, problems, the deleted
 * scope fallback, chosen roots, the tracker and the topic, caching, persistence and the search scope.
 */
class WorkspaceScopeServiceTest : WorkspaceScopeTestCase() {
    private val allRoots = listOf("falcon", "heron", "pelican", "pelican › danger_zone/database", "platform", "wren", "golden")

    private companion object {
        const val HERON_SECOND_FILE = "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml"
    }

    // ------------------------------------------------------------------------------------------------ All roots

    fun testTheDefaultIsAllRootsInDisplayOrderWithGoldenAsReference() {
        copyInfra()
        val scope = service.current()
        assertEquals(ScopeChoice.AllRoots, scope.choice)
        assertSame("the registered implementation", service, WorkspaceScopeService.getInstance(project))
        assertEquals(allRoots, names(scope.roots))
        assertTrue(scope.partial.isEmpty())
        assertEquals(listOf("golden"), names(scope.references))
        assertNull(scope.problem)
        assertEquals("All roots", service.currentScope().label)
        assertTrue(scope.contains(vf("repos/falcon/ansible/roles/haproxy/tasks/main.yml")))
        assertTrue(scope.contains(vf("repos/pelican/ansible/danger_zone/database/playbook-clone-to-primary.yml")))
        assertFalse("detached worktrees never join a scope", scope.contains(vf("$WORKTREE/golden/roles/haproxy/tasks/main.yml")))
        val outside = myFixture.tempDirFixture.createFile("README.md", "# infra\n")
        assertFalse("files outside every root are not in a workspace scope", scope.contains(outside))
    }

    // ------------------------------------------------------------------------------------------------ named scopes

    fun testNamedScopesCoverTheirRepoAndNestedRoots() {
        copyInfra()
        addScope("falcon", "file:repos/falcon//*")
        addScope("pelican", "file:repos/pelican//*")

        service.set(ScopeChoice.Named("falcon"))
        val falcon = service.currentScope()
        assertEquals(ScopeChoice.Named("falcon"), falcon.choice)
        assertEquals("falcon", falcon.label)
        assertEquals(listOf("falcon"), names(falcon.roots))
        assertTrue(falcon.partial.isEmpty())
        assertNull(falcon.problem)
        assertEquals("golden stays the reference outside the scope", listOf("golden"), names(falcon.references))
        assertTrue(falcon.contains(vf("repos/falcon/ansible/playbook-setup-system.yml")))
        assertFalse(falcon.contains(vf("repos/heron/ansible/ansible.cfg")))
        assertFalse(falcon.contains(vf("golden/roles/haproxy/tasks/main.yml")))

        service.set(ScopeChoice.Named("pelican"))
        val pelican = service.currentScope()
        assertEquals(listOf("pelican", "pelican › danger_zone/database"), names(pelican.roots))
        assertEquals(RootCoverage.FULL, pelican.coverage().of(root("pelican › danger_zone/database")))
    }

    fun testAStaleScopeMatchesNoAnsibleRoot() {
        copyInfra()
        addScope("hawk", "file:repos/hawk//*")
        service.set(ScopeChoice.Named("hawk"))
        val scope = service.currentScope()
        assertTrue(scope.roots.isEmpty())
        assertEquals("'hawk' matches no Ansible root", scope.problem)
        assertEquals(scope.problem, scope.problemIfComputed())
        assertFalse(scope.contains(vf("repos/falcon/ansible/ansible.cfg")))
    }

    fun testAScopeNarrowerThanItsRootsIsPartial() {
        copyInfra()
        addScope("prod-only", "file:repos/*/ansible/environments/prod//*")
        service.set(ScopeChoice.Named("prod-only"))
        val scope = service.currentScope()
        val repos = listOf("falcon", "heron", "pelican", "platform", "wren")
        assertEquals(repos, names(scope.roots))
        assertEquals(repos.toSet(), names(scope.partial).toSet())
        val coverage = scope.coverage()
        assertEquals("the nested root has no prod inventory of its own", RootCoverage.NONE, coverage.of(root("pelican › danger_zone/database")))
        assertEquals(RootCoverage.NONE, coverage.of(root("golden")))
        assertTrue(scope.contains(vf("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml")))
        assertFalse("the playbook-level group_vars are outside the pattern", scope.contains(vf("repos/falcon/ansible/group_vars/all/vars.yml")))
    }

    fun testIgnoredPathsAreNotJudgedAndASettingsChangeRecomputesTheCoverage() {
        copyInfra()
        addScope("falcon-without-roles", "file:repos/falcon//*&&!file:repos/falcon/ansible/roles//*")
        service.set(ScopeChoice.Named("falcon-without-roles"))
        assertEquals(listOf("falcon"), names(service.current().roots))
        assertEquals(setOf("falcon"), names(service.current().partial).toSet())

        var events = 0
        project.messageBus.connect(testRootDisposable).subscribe(WorkspaceScopeListener.TOPIC, WorkspaceScopeListener { events++ })
        val stamp = service.modificationTracker.modificationCount
        AnsibilityProjectSettings.getInstance(project).update {
            it.copy(paths = it.paths.copy(extraIgnoredPaths = it.paths.extraIgnoredPaths + "repos/falcon/ansible/roles/**"))
        }
        assertTrue("a project settings change moves the tracker", service.modificationTracker.modificationCount > stamp)
        assertTrue("and publishes", events > 0)
        val scope = service.currentScope()
        assertEquals(listOf("falcon"), names(scope.roots))
        assertTrue("ignored files are not judged: ${scope.coverage()}", scope.partial.isEmpty())
        assertEquals(RootCoverage.FULL, scope.coverage().of(root("falcon")))
    }

    fun testADetachedWorktreeNeverJoinsAScopeEvenWhenThePatternMatchesIt() {
        copyInfra()
        addScope("checkouts", "file:checkouts//*")
        service.set(ScopeChoice.Named("checkouts"))
        val scope = service.currentScope()
        assertTrue(names(scope.roots).toString(), scope.roots.isEmpty())
        assertEquals("'checkouts' matches no Ansible root", scope.problem)
        assertFalse(scope.contains(vf("$WORKTREE/golden/roles/haproxy/tasks/main.yml")))
        addScope("everything", "file:repos//*||file:golden//*||file:checkouts//*")
        service.set(ScopeChoice.Named("everything"))
        assertEquals("a pattern over everything covers every non-detached root", allRoots, names(service.current().roots))
    }

    fun testLocalScopesWork() {
        copyInfra()
        addScope("mine", "file:golden//*", local)
        service.set(ScopeChoice.Named("mine"))
        val scope = service.currentScope()
        assertSame(local, scope.holder)
        assertEquals(listOf("golden"), names(scope.roots))
    }

    fun testADeletedScopeFallsBackToAllUntilItReturns() {
        copyInfra()
        service.set(ScopeChoice.Named("falcon"))
        val deleted = service.currentScope()
        assertEquals("the stored choice is kept", ScopeChoice.Named("falcon"), deleted.choice)
        assertNull(deleted.namedScope)
        assertEquals("falcon (deleted)", deleted.label)
        assertEquals(allRoots, names(deleted.roots))
        assertEquals("Scope 'falcon' was deleted; all roots are shown until you choose again", deleted.problem)
        assertTrue(deleted.contains(vf("repos/heron/ansible/ansible.cfg")))

        val stamp = service.modificationTracker.modificationCount
        addScope("falcon", "file:repos/falcon//*")
        assertTrue(service.modificationTracker.modificationCount > stamp)
        assertEquals(listOf("falcon"), names(service.current().roots))
        shared.removeAllSets()
        assertEquals("falcon (deleted)", service.currentScope().label)
    }

    // ------------------------------------------------------------------------------------------------ chosen roots

    fun testChosenRootsIncludeTheirNestedRoots() {
        copyInfra()
        service.set(ScopeChoice.Roots(setOf("repos/pelican/ansible", "repos/falcon/ansible")))
        val scope = service.currentScope()
        assertEquals(listOf("falcon", "pelican", "pelican › danger_zone/database"), names(scope.roots))
        assertEquals(listOf("falcon", "pelican"), names(scope.chosenRoots))
        assertEquals("falcon + pelican", scope.label)
        assertNull(scope.problem)
        assertTrue(scope.contains(vf("repos/pelican/ansible/danger_zone/database/playbook-clone-to-primary.yml")))
        assertFalse(scope.contains(vf("repos/heron/ansible/ansible.cfg")))

        service.set(ScopeChoice.Roots(setOf("repos/pelican/ansible/danger_zone/database")))
        assertEquals("a nested root alone does not pull in its parent", listOf("pelican › danger_zone/database"), names(service.current().roots))
        assertFalse(service.current().contains(vf("repos/pelican/ansible/ansible.cfg")))
    }

    fun testChosenRootsThatNoLongerExistAreReported() {
        copyInfra()
        service.set(ScopeChoice.Roots(setOf("repos/falcon/ansible", "repos/gone/ansible")))
        assertEquals(listOf("falcon"), names(service.current().roots))
        assertEquals("Chosen roots not found: repos/gone/ansible", service.current().problem)

        service.set(ScopeChoice.Roots(emptySet()))
        val none = service.currentScope()
        assertTrue(none.roots.isEmpty())
        assertEquals("No roots chosen", none.problem)
        assertEquals("No roots chosen", none.label)
        assertFalse(none.contains(vf("repos/falcon/ansible/ansible.cfg")))

        service.set(ScopeChoice.Roots(setOf("repos/falcon/ansible", "repos/heron/ansible", "golden", "repos/wren/ansible")))
        assertEquals("falcon + heron + 2 more", service.currentScope().label)
    }

    fun testRootOptionsNameEachRootWithItsKey() {
        copyInfra()
        val options = service.rootOptions()
        assertEquals(allRoots, options.map { it.label })
        assertEquals("repos/pelican/ansible/danger_zone/database", options[3].key)
        assertEquals("pelican", options[3].nestedIn)
        assertNull(options[0].nestedIn)
        assertEquals("golden", options.last().key)
    }

    // ------------------------------------------------------------------------------------------------ current root

    fun testCurrentFileRootFollowsTheEditorAndKeepsTheLastRoot() {
        copyInfra()
        service.set(ScopeChoice.CurrentFileRoot)
        val empty = service.currentScope()
        assertTrue(empty.roots.isEmpty())
        assertEquals("No Ansible file selected", empty.problem)
        assertEquals("Current file's root", empty.label)

        myFixture.openFileInEditor(vf("repos/falcon/ansible/playbook-setup-system.yml"))
        waitFor("falcon becomes the current root") { names(service.current().roots) == listOf("falcon") }
        assertNull(service.current().problem)
        assertEquals("falcon (current file)", service.currentScope().label)
        assertEquals(listOf("falcon"), names(service.currentScope().chosenRoots))

        val outside = myFixture.tempDirFixture.createFile("notes.txt", "notes\n")
        myFixture.openFileInEditor(outside)
        waitFor("the problem shows") { service.current().problem != null }
        assertEquals("No Ansible file selected", service.current().problem)
        assertEquals("the last root is kept", listOf("falcon"), names(service.current().roots))

        myFixture.openFileInEditor(vf("repos/pelican/ansible/playbook-setup-replisync.yml"))
        waitFor("pelican becomes the current root") { names(service.current().roots).firstOrNull() == "pelican" }
        assertEquals("the parent's nested root follows", listOf("pelican", "pelican › danger_zone/database"), names(service.current().roots))
    }

    fun testTheEditorMovesTheTrackerOnlyWhenItsRootChanges() {
        copyInfra()
        val falcon = root("falcon")
        service.editorSelectedForTests(vf("repos/falcon/ansible/ansible.cfg"), falcon)
        val stampUnderAll = service.modificationTracker.modificationCount
        service.editorSelectedForTests(vf("repos/heron/ansible/ansible.cfg"), root("heron"))
        assertEquals("other choices ignore the editor", stampUnderAll, service.modificationTracker.modificationCount)

        service.set(ScopeChoice.CurrentFileRoot)
        assertEquals(listOf("heron"), names(service.current().roots))
        val stamp = service.modificationTracker.modificationCount
        service.editorSelectedForTests(vf(HERON_SECOND_FILE), root("heron"))
        assertEquals("same root, no move", stamp, service.modificationTracker.modificationCount)
        service.editorSelectedForTests(vf("repos/falcon/ansible/ansible.cfg"), falcon)
        assertTrue(service.modificationTracker.modificationCount > stamp)
        assertEquals(listOf("falcon"), names(service.current().roots))

        val worktree = vf("$WORKTREE/golden/roles/haproxy/tasks/main.yml")
        val detached = AnsibleWorkspace.getInstance(project).rootFor(worktree)!!
        assertTrue(detached.detached)
        service.editorSelectedForTests(worktree, detached)
        assertEquals("The selected file is in a detached worktree, which never joins a scope", service.current().problem)
        assertEquals(listOf("falcon"), names(service.current().roots))
    }

    // ------------------------------------------------------------------------------------------------ tracker

    fun testTheTrackerAndTopicFollowChoicesHoldersStructureAndRefresh() {
        copyInfra()
        var events = 0
        project.messageBus.connect(testRootDisposable).subscribe(WorkspaceScopeListener.TOPIC, WorkspaceScopeListener { events++ })
        val tracker = service.modificationTracker
        fun moves(what: String, action: () -> Unit) {
            val stamp = tracker.modificationCount
            val before = events
            action()
            assertTrue("$what moves the tracker", tracker.modificationCount > stamp)
            assertTrue("$what publishes", events > before)
        }
        moves("a choice") { service.set(ScopeChoice.Named("falcon")) }
        val stamp = tracker.modificationCount
        service.set(ScopeChoice.Named("falcon"))
        assertEquals("the same choice again changes nothing", stamp, tracker.modificationCount)
        moves("a shared scope") { addScope("falcon", "file:repos/falcon//*") }
        moves("a local scope") { addScope("mine", "file:golden//*", local) }
        moves("removing scopes") { shared.removeAllSets() }
        moves("the structure") { rescan() }
        val excluded = vf("repos/heron/ansible/roles")
        try {
            moves("an excluded directory (project roots)") { PsiTestUtil.addExcludedRoot(myFixture.module, excluded) }
        } finally {
            PsiTestUtil.removeExcludedRoot(myFixture.module, excluded)
        }
        moves("a refresh") { service.refresh() }
        moves("a reload of the workspace state") {
            AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean().apply { scope = "current-root" })
        }
        assertEquals(ScopeChoice.CurrentFileRoot, service.current().choice)
    }

    fun testAChoiceChangeNeverRescansTheWorkspace() {
        copyInfra()
        addScope("falcon", "file:repos/falcon//*")
        val structure = AnsibleWorkspace.getInstance(project).structureTracker
        val stamp = structure.modificationCount
        var rescans = 0
        project.messageBus.connect(testRootDisposable).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { rescans++ })
        val choices = listOf(ScopeChoice.Named("falcon"), ScopeChoice.CurrentFileRoot, ScopeChoice.Roots(setOf("golden")), ScopeChoice.AllRoots)
        for (choice in choices) {
            service.set(choice)
            assertEquals(choice, service.current().choice)
            service.current().roots
        }
        assertEquals("the scope is a view filter, not a structure input", stamp, structure.modificationCount)
        assertEquals(0, rescans)
    }

    fun testCurrentIsCachedUntilTheTrackerMoves() {
        copyInfra()
        addScope("falcon", "file:repos/falcon//*")
        service.set(ScopeChoice.Named("falcon"))
        val first = service.current()
        assertSame(first, service.current())
        val coverage = service.currentScope().coverage()
        assertSame("coverage is cached per scope", coverage, service.cachedCoverage(service.currentScope().namedScope!!, shared))
        replaceScope("falcon", "file:repos/heron//*")
        assertNotSame(first, service.current())
        assertEquals("an edited scope is walked again", listOf("heron"), names(service.current().roots))
    }

    // ------------------------------------------------------------------------------------------------ persistence

    fun testTheChoiceRoundTripsThroughTheWorkspaceFile() {
        copyInfra()
        val choices = listOf(
            ScopeChoice.AllRoots,
            ScopeChoice.CurrentFileRoot,
            ScopeChoice.Named("falcon"),
            ScopeChoice.Roots(setOf("repos/falcon/ansible", "odd,key%2C")),
        )
        for (choice in choices) {
            service.set(choice)
            val state = AnsibilityWorkspaceState.getInstance(project)
            val (xml, bean) = SettingsTestSupport.xmlRoundTrip(state.state, AnsibilityWorkspaceState.StateBean())
            val copy = AnsibilityWorkspaceState(project)
            copy.loadState(bean)
            assertEquals(xml, choice, ScopeChoices.decode(copy.snapshot.scope))
            if (choice == ScopeChoice.AllRoots) assertFalse("the default is not written: $xml", xml.contains("scope="))
        }
        assertEquals("roots:odd%2Ckey%252C,repos/falcon/ansible", AnsibilityWorkspaceState.getInstance(project).snapshot.scope)
    }

    // ------------------------------------------------------------------------------------------------ search scope

    fun testTheSearchScopeEnumeratesOnlyTheScopesFiles() {
        copyInfra()
        addScope("falcon", "file:repos/falcon//*")
        service.set(ScopeChoice.Named("falcon"))
        val scope = service.current().searchScope
        assertFalse(scope.isSearchInLibraries)
        assertEquals("Ansibility workspace scope: falcon", scope.displayName)
        val found = runReadActionBlocking { FilenameIndex.getVirtualFilesByName("ansible.cfg", scope) }
        assertEquals(listOf(vf("repos/falcon/ansible/ansible.cfg")), found.toList())

        service.set(ScopeChoice.AllRoots)
        val all = runReadActionBlocking { FilenameIndex.getVirtualFilesByName("main.yml", service.current().searchScope) }
        assertTrue(all.any { it.path.contains("/golden/roles/") })
        assertTrue("never the worktree copy", all.none { it.path.contains("/checkouts/") })
    }

    // ------------------------------------------------------------------------------------------------ selector

    fun testTheCatalogSortsUserScopesByTheirRootsAndNamesTheUncoveredRoots() {
        copyInfra()
        addScope("hawk", "file:repos/hawk//*")
        addScope("pelican", "file:repos/pelican//*")
        addScope("falcon", "file:repos/falcon//*")
        addScope("mine", "file:repos/wren//*", local)

        val cold = service.catalog()
        assertNull("not known before the coverage is computed", cold.uncovered)
        waitFor("every coverage is computed") { service.catalog().let { c -> (c.userScopes + c.predefinedScopes).all { it.coverage != null } } }
        val catalog = service.catalog()
        assertEquals(listOf("falcon", "pelican", "mine", "hawk"), catalog.userScopes.map { it.scopeId })
        assertEquals(ScopeOrigin.LOCAL, catalog.userScopes[2].origin)
        assertEquals(ScopeOrigin.SHARED, catalog.userScopes[0].origin)
        assertEquals(listOf("heron", "platform", "golden"), names(catalog.uncovered!!))
        assertTrue(catalog.predefinedScopes.isNotEmpty())
        assertTrue(catalog.predefinedScopes.all { it.origin == ScopeOrigin.PREDEFINED })
        assertEquals(allRoots, names(catalog.roots))
    }
}
