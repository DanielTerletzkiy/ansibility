package de.terletzkiy.ansibility.golden.push

import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.JBUI
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.RecordingDiffUi
import de.terletzkiy.ansibility.golden.history.LocalChangesLookup
import de.terletzkiy.ansibility.golden.vcs.impl.PushLocalChangesLookup
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl
import kotlinx.coroutines.CompletableDeferred

/**
 * The Push dialog and its rows (plan amendment R24, D188) on the synthetic drift tree: sections and order, detached
 * worktrees and the source never offered, nothing ticked, counts per row and option, Select differing, drift badges,
 * the uncommitted-changes warning, the different-vault-id warning (never decrypted), Compare, the loading state, and
 * the action's visibility, texts, registration and bare data context.
 */
class PushDialogTest : BasePlatformTestCase() {
    private lateinit var ui: RecordingPushUi

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
        ui = RecordingPushUi()
        PushTestSupport.install(ui, testRootDisposable)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
            (WorkspaceScopeService.getInstance(project) as WorkspaceScopeServiceImpl).resetForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dir(team: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team, role))

    private fun copy(team: String, role: String = "web"): RoleCopy = GoldenTestSupport.copy(project, dir(team, role))

    private fun rows(team: String = "golden", role: String = "web"): List<PushRow> = PushTestSupport.rows(project, copy(team, role))

    private fun write(path: String, text: String) {
        DriftFixture.write(myFixture, path, text)
        ModelFixture.rescan(project)
    }

    /** A project root without `web` (wren), one without any roles directory (bare) and a detached worktree root with roles. */
    private fun addRoots() {
        write("repos/wren/ansible/ansible.cfg", "[defaults]\n")
        write("repos/wren/ansible/roles/other/tasks/main.yml", "---\n")
        write("repos/bare/ansible/ansible.cfg", "[defaults]\n")
        write("checkouts/.claude/worktrees/wt-2/repos/wren/ansible/ansible.cfg", "[defaults]\n")
        write("checkouts/.claude/worktrees/wt-2/repos/wren/ansible/roles/other/tasks/main.yml", "---\n")
    }

    private fun dialog(rows: List<PushRow>, source: RoleCopy = copy("golden")): PushDialog = PushDialog(project, PushTestSupport.model(project, source, rows))

    private inline fun <T> withDialog(dialog: PushDialog, block: (PushDialog) -> T): T = try {
        block(dialog)
    } finally {
        dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    // ------------------------------------------------------------------ rows

    fun testRowsComeInScopeFirstThenOutsideThenRootsWithoutTheRole() {
        addRoots()
        val detached = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.filter { it.detached }
        assertTrue("the fixture has a detached worktree root with a roles directory: $detached", detached.any { it.rolesDirs.isNotEmpty() })
        (WorkspaceScopeService.getInstance(project) as WorkspaceScopeServiceImpl).set(ScopeChoice.Roots(setOf("repos/tasks/ansible", "repos/spec/ansible")))

        val rows = rows()
        assertEquals(listOf("spec", "tasks", "missing", "mol", "mol2", "same", "specmol", "wren"), rows.map { it.name })
        assertEquals(
            listOf(PushSection.IN_SCOPE, PushSection.IN_SCOPE) + List(5) { PushSection.OUTSIDE_SCOPE } + PushSection.WITHOUT_ROLE,
            rows.map { it.section },
        )
        assertFalse("the source is never offered", rows.any { it.copy?.dir == dir("golden") })
        assertFalse("detached worktrees never", rows.any { it.root.detached })
        assertFalse("a root without a roles directory cannot take a role", rows.any { it.name == "bare" })
        val wren = rows.last()
        assertTrue(wren.createsRole)
        assertEquals("roles/web", wren.createdPath)
        assertEquals("creates roles/web, 16 files", wren.countsText(PushOptions()))
    }

    fun testAnIgnoredRolesDirectoryTakesNoRole() {
        addRoots()
        AnsibilityProjectSettings.getInstance(project).update { it.copy(paths = it.paths.copy(extraIgnoredPaths = listOf("repos/wren/ansible/roles"))) }
        ModelFixture.rescan(project)
        val wren = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.single { it.displayName == "wren" }
        assertEquals("the root still reaches its roles directory", listOf("roles"), wren.rolesDirs.map { it.name })
        assertFalse(rows().any { it.name == "wren" })
    }

    fun testEveryCopyIsInScopeWithAllRootsAndRolesFromARepoCopyListGoldenFirst() {
        val rows = rows("mol")
        assertEquals(listOf("golden", "missing", "mol2", "same", "spec", "specmol", "tasks"), rows.map { it.name })
        assertTrue(rows.all { it.section == PushSection.IN_SCOPE })
        assertEquals("1 changed", rows.first().countsText(PushOptions()))
    }

    fun testRootsWithoutTheRoleForARoleFewRootsHave() {
        val rows = rows(role = "base")
        assertEquals(listOf("same", "mol", "missing", "mol2", "spec", "specmol", "tasks").sorted(), rows.map { it.name }.sorted())
        assertEquals(listOf("mol", "same"), rows.filter { !it.createsRole }.map { it.name })
        assertEquals(listOf("missing", "mol2", "spec", "specmol", "tasks"), rows.filter { it.createsRole }.map { it.name })
        assertEquals("creates roles/base, 2 files", rows.last().countsText(PushOptions()))
    }

    fun testCountsPerRowFollowTheOptions() {
        write("${DriftFixture.roleDir("missing")}/files/notes/extra.txt", "only in missing\n")
        write("${DriftFixture.roleDir("same")}/files/ssl/web.key", "synthetic key material, not secret\n")
        val rows = rows()
        val (missing, same, mol) = PushTestSupport.byName(rows, "missing", "same", "mol")
        assertEquals("3 changed · 9 added · 1 deleted", missing.countsText(PushOptions()))
        assertEquals("3 changed · 9 added", missing.countsText(PushOptions(deleteExtra = false)))
        assertEquals(PushCounts(3, 9, 1, 0), missing.counts(PushOptions()))
        assertEquals("1 changed", mol.countsText(PushOptions()))
        assertEquals("a sensitive difference only", "nothing to do · 1 key or vault file left as it is", same.countsText(PushOptions()))
        assertEquals("1 deleted", same.countsText(PushOptions(includeSensitive = true)))
        assertEquals("nothing to do", same.countsText(PushOptions(deleteExtra = false, includeSensitive = true)))
        assertFalse(same.differs(PushOptions()))
        assertTrue(same.differs(PushOptions(includeSensitive = true)))
    }

    fun testBadgesAreAsCurrentAsTheCounts() {
        val service = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, service, testRootDisposable)
        assertEquals(
            "computed when the dialog opens, nothing cached before",
            listOf("Δ tasks/templates", "≈ molecule only", "≈ molecule only", "= golden", "Δ spec/defaults", "Δ spec/defaults", "Δ tasks/templates"),
            rows().map { it.badge },
        )

        // A cached result older than an edit (no worker follows edits here) is not what the dialog shows.
        val golden = String(DriftFixture.file(myFixture, "${DriftFixture.roleDir("golden")}/molecule/default/verify.yml").contentsToByteArray())
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("mol")}/molecule/default/verify.yml", golden)
        val mol = rows().single { it.name == "mol" }
        assertEquals("nothing to do", mol.countsText(PushOptions()))
        assertEquals("= golden", mol.badge)
    }

    fun testNoBadgesWithoutAGoldenRoot() {
        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        assertTrue("no golden root: nothing to be relative to (D178)", rows("mol").all { it.badge == null })
    }

    fun testUncommittedChangesComeFromTheLookup() {
        ExtensionTestUtil.maskExtensions(LocalChangesLookup.EP_NAME, listOf(FakeLocalChanges(setOf(dir("mol")))), testRootDisposable)
        val rows = rows()
        assertEquals(listOf("mol"), rows.filter { it.uncommitted }.map { it.name })
        withDialog(dialog(rows)) { dialog ->
            assertEquals(listOf("mol"), dialog.uncommittedLabels.keys.map { it.name })
            assertEquals("uncommitted changes in this role", dialog.uncommittedLabels.values.single().text)
        }
    }

    fun testNoUncommittedWarningWithoutVcs() {
        assertNull("no VCS manages the light project", PushLocalChangesLookup().hasLocalChanges(project, dir("mol")))
        ExtensionTestUtil.maskExtensions(LocalChangesLookup.EP_NAME, emptyList(), testRootDisposable)
        assertNull("without the VCS module there is no lookup", LocalChangesLookup.hasLocalChanges(project, dir("mol")))
        val rows = rows()
        assertTrue(rows.none { it.uncommitted })
        withDialog(dialog(rows)) { dialog -> assertTrue(dialog.uncommittedLabels.isEmpty()) }
    }

    // ------------------------------------------------------------------ the dialog

    fun testNothingIsTickedAndSelectDifferingTicksTheCopiesThatDiffer() {
        addRoots()
        withDialog(dialog(rows())) { dialog ->
            assertEquals(8, dialog.checkBoxes.size)
            assertTrue("nothing ticked at first", dialog.checkBoxes.values.none { it.isSelected } && dialog.tickedRows().isEmpty())
            assertEquals("Compare needs a copy", listOf("wren"), dialog.compareLinks.filterValues { !it.isEnabled }.keys.map { it.name })
            assertFalse(dialog.isOKActionEnabled)
            assertEquals("Push", dialog.okText)

            dialog.selectDiffering()
            assertEquals("every differing copy; never an identical one, never a root without the role", listOf("missing", "mol", "mol2", "spec", "specmol", "tasks"), dialog.tickedRows().map { it.name })
            assertTrue(dialog.isOKActionEnabled)
            assertEquals("Push to 6 Repos", dialog.okText)
            assertEquals(dialog.tickedRows(), dialog.choice().rows)
            assertEquals(PushOptions(deleteExtra = true, includeSensitive = false), dialog.choice().options)

            dialog.selectNone()
            assertTrue(dialog.tickedRows().isEmpty())
            assertFalse(dialog.isOKActionEnabled)
            dialog.checkBoxes.entries.single { it.key.name == "wren" }.value.doClick()
            assertEquals("Push to 1 Repo", dialog.okText)
            assertEquals(listOf("wren"), dialog.choice().rows.map { it.name })
        }
    }

    fun testTheCountsFollowTheDeleteOption() {
        write("${DriftFixture.roleDir("missing")}/files/notes/extra.txt", "only in missing\n")
        withDialog(dialog(rows())) { dialog ->
            val label = dialog.countLabels.entries.single { it.key.name == "missing" }.value
            assertEquals("3 changed · 9 added · 1 deleted", label.text)
            dialog.deleteBox.doClick()
            assertFalse(dialog.choice().options.deleteExtra)
            assertEquals("3 changed · 9 added", label.text)
        }
    }

    fun testTheRowsLoadInTheBackground() {
        val pending = CompletableDeferred<List<PushRow>>()
        val dialog = PushDialog(project, PushModel(project, copy("golden"), pending))
        withDialog(dialog) {
            assertTrue("loading: no rows yet", dialog.checkBoxes.isEmpty())
            // The choices are visible from the start: the rows area is never collapsed while it loads (2026-10-09).
            assertTrue("loading keeps room for the rows", dialog.rowsArea.preferredSize.height >= JBUI.scale(200))
            assertTrue(dialog.rowsArea.preferredSize.width >= JBUI.scale(720))
            pending.complete(rows())
            PlatformTestUtil.waitWithEventsDispatching("the rows are shown", { dialog.checkBoxes.isNotEmpty() }, 20)
            assertEquals(7, dialog.checkBoxes.size)
            assertTrue(dialog.tickedRows().isEmpty())
            val height = dialog.rowsArea.preferredSize.height
            assertTrue("the rows get their room, up to the cap: $height", height in JBUI.scale(200)..JBUI.scale(480))
        }
    }

    fun testDifferentVaultIdsAreAWarningOnlyWithKeyAndVaultFilesIncluded() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        write("${DriftFixture.roleDir("golden")}/vars/secrets.yml", vault("1.2;AES256;dev", "golden"))
        write("${DriftFixture.roleDir("mol")}/vars/secrets.yml", vault("1.2;AES256;prod", "mol"))
        write("${DriftFixture.roleDir("same")}/vars/secrets.yml", vault("1.1;AES256", "same"))
        write("${DriftFixture.roleDir("tasks")}/vars/secrets.yml", vault("1.2;AES256;dev", "tasks"))
        val rows = rows()
        val (mol, same, tasks) = PushTestSupport.byName(rows, "mol", "same", "tasks")
        assertEquals(listOf(VaultIdMismatch("vars/secrets.yml", "dev", "prod")), mol.vaultIdMismatches)
        assertEquals("a header without an id is the default id", listOf(VaultIdMismatch("vars/secrets.yml", "dev", "default")), same.vaultIdMismatches)
        assertEquals("the same id, another body", emptyList<VaultIdMismatch>(), tasks.vaultIdMismatches)

        withDialog(dialog(rows)) { dialog ->
            dialog.checkBoxes.getValue(mol).doClick()
            dialog.checkBoxes.getValue(tasks).doClick()
            assertFalse("key and vault files are left out: no warning", dialog.vaultWarning.isVisible)
            dialog.sensitiveBox.doClick()
            assertTrue(dialog.vaultWarning.isVisible)
            assertTrue(dialog.vaultWarning.text, dialog.vaultWarning.text.contains("mol: vars/secrets.yml (dev in the source, prod there)"))
            assertFalse(dialog.vaultWarning.text, dialog.vaultWarning.text.contains("tasks"))
        }
        assertEquals("nothing is decrypted", 0, guard.calls.get())
    }

    fun testTheHeaderLineGivesTheVaultId() {
        assertEquals("dev", PushVaultIds.parse("\$ANSIBLE_VAULT;1.2;AES256;dev"))
        assertEquals("default", PushVaultIds.parse("\$ANSIBLE_VAULT;1.1;AES256"))
        assertNull(PushVaultIds.parse("---"))
        assertNull(PushVaultIds.parse(null))
    }

    fun testCompareShowsTheSourceLeftAndTheCopyRight() {
        val diffUi = RecordingDiffUi()
        GoldenTestSupport.install(diffUi, testRootDisposable)
        val mol = rows().single { it.name == "mol" }
        PushService.getInstance(project).compare(copy("golden"), mol, ModalityState.nonModal())
        GoldenTestSupport.waitFor("the chain is shown") { diffUi.chains.isNotEmpty() }
        val chain = diffUi.chains.single()
        assertEquals(listOf("molecule/default/verify.yml"), chain.requests.map { it.name })
        val request = GoldenTestSupport.process(chain.requests.single()) as SimpleDiffRequest
        assertEquals(listOf("golden", "mol"), request.contentTitles)
    }

    // ------------------------------------------------------------------ the action

    private fun context(file: VirtualFile? = null, roleCopy: VirtualFile? = null): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        file?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        roleCopy?.let { builder.add(GoldenDataKeys.ROLE_COPY, it) }
        return builder.build()
    }

    private fun update(context: DataContext, place: String = ActionPlaces.UNKNOWN, uiKind: ActionUiKind = ActionUiKind.NONE): AnActionEvent {
        val action = ActionManager.getInstance().getAction(ID) ?: error("$ID is not registered")
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, uiKind, null)
        runReadActionBlocking { action.update(event) }
        return event
    }

    fun testTheActionShowsForCopiesWithSomewhereToGo() {
        assertTrue("golden has other copies", update(context(file = DriftFixture.file(myFixture, "${DriftFixture.roleDir("golden")}/tasks/main.yml"))).presentation.isEnabledAndVisible)
        assertTrue("the copy row", update(context(roleCopy = dir("mol"))).presentation.isEnabledAndVisible)
        assertTrue("one copy, but roots without it", update(context(roleCopy = dir("same", "app-same"))).presentation.isEnabledAndVisible)
        assertFalse("not in a role", update(context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg"))).presentation.isEnabledAndVisible)
        assertFalse("nothing selected", update(context()).presentation.isEnabledAndVisible)
    }

    fun testTextsByPlaceAndRegistration() {
        val file = context(file = DriftFixture.file(myFixture, "${DriftFixture.roleDir("mol")}/tasks/main.yml"))
        assertEquals("Push Role to Repos…", update(file, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals("Push Role to Repos…", update(file, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals("Ansibility: Push Role to Repos…", update(file, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.text)
        val actions = ActionManager.getInstance()
        assertEquals("Ansibility: Push Role to Repos…", actions.getAction(ID).templateText)
        for (group in listOf("Ansibility.ToolWindow.Popup", GoldenTestSupport.SUBMENU)) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            val at = children.indexOf(ID)
            assertTrue("$group: $children", at > 0 && children.subList(0, at).contains("Ansibility.Golden.CompareWith"))
            // The full order of the golden actions is asserted by TakeActionsTest; Push comes right after Align Role….
            assertEquals("$group: $children", "Ansibility.Golden.AlignRole", children[at - 1])
        }
    }

    fun testTheActionWorksFromABareRoleCopyContext() {
        val action = ActionManager.getInstance().getAction(ID)
        action.actionPerformed(TestActionEvent.createTestEvent(action, context(roleCopy = dir("mol"))))
        val model = ui.models.single()
        assertEquals(dir("mol"), model.source.dir)
        assertEquals("web", model.roleName)
        assertEquals("mol", model.sourceName)
    }

    private fun vault(header: String, marker: String): String =
        "\$ANSIBLE_VAULT;$header\n" + marker.toByteArray().joinToString("") { "%02x".format(it) } + "\n"

    private companion object {
        const val ID = "Ansibility.Golden.PushToRepos"
    }
}
