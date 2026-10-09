package de.terletzkiy.ansibility.golden.align

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.vcs.impl.AlignVcsMergeDialog
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl

/**
 * The Align actions (plan amendment R24, D184): visibility (no golden copy, golden itself, a single copy), texts by
 * place, registration next to Compare, what each action aligns, and the opening step of Align Role… (defaults, ranking,
 * counts, the vault-id warning).
 */
class AlignActionsTest : BasePlatformTestCase() {
    private lateinit var ui: RecordingAlignUi
    private lateinit var dialog: ScriptedMergeDialog

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
        ui = RecordingAlignUi()
        AlignTestSupport.install(ui, testRootDisposable)
        dialog = ScriptedMergeDialog()
        AlignTestSupport.install(listOf(dialog), testRootDisposable)
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

    private fun vf(team: String, relative: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir(team, role)}/$relative")

    private fun copy(team: String, role: String = "web"): RoleCopy = GoldenTestSupport.copy(project, dir(team, role))

    private fun context(file: VirtualFile? = null, roleCopy: VirtualFile? = null): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        file?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        roleCopy?.let { builder.add(GoldenDataKeys.ROLE_COPY, it) }
        return builder.build()
    }

    private fun update(id: String, context: DataContext, place: String = ActionPlaces.UNKNOWN, uiKind: ActionUiKind = ActionUiKind.NONE): AnActionEvent {
        val action = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, uiKind, null)
        runReadActionBlocking { action.update(event) }
        return event
    }

    private fun visible(id: String, context: DataContext): Boolean = update(id, context).presentation.isEnabledAndVisible

    private fun perform(id: String, context: DataContext) {
        val action = ActionManager.getInstance().getAction(id)
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
    }

    // ------------------------------------------------------------------ visibility and texts

    fun testMergeIntoAndAlignWithGoldenAreHiddenWithoutAGoldenCopyAndOnGolden() {
        for (id in listOf(GoldenActionIds.MERGE_INTO_GOLDEN, GoldenActionIds.ALIGN_WITH_GOLDEN)) {
            assertTrue(id, visible(id, context(file = vf("mol", "tasks/main.yml"))))
            assertTrue("$id: the copy row", visible(id, context(roleCopy = dir("mol"))))
            assertFalse("$id: golden itself", visible(id, context(file = vf("golden", "tasks/main.yml"))))
            assertFalse("$id: no golden copy of solo", visible(id, context(file = vf("same", "tasks/main.yml", "solo"))))
            assertFalse("$id: not in a role", visible(id, context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg"))))
        }
        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        for (id in listOf(GoldenActionIds.MERGE_INTO_GOLDEN, GoldenActionIds.ALIGN_WITH_GOLDEN)) {
            assertFalse("$id: no golden root", visible(id, context(file = vf("mol", "tasks/main.yml"))))
        }
        assertTrue("Align Role… needs no golden root", visible("Ansibility.Golden.AlignRole", context(file = vf("mol", "tasks/main.yml"))))
    }

    fun testAlignRoleNeedsAnotherCopy() {
        val id = "Ansibility.Golden.AlignRole"
        assertTrue(visible(id, context(file = vf("golden", "tasks/main.yml"))))
        assertTrue("solo has two copies, none golden", visible(id, context(file = vf("same", "tasks/main.yml", "solo"))))
        assertFalse("app-same has one copy", visible(id, context(file = DriftFixture.file(myFixture, "repos/same/ansible/roles/app-same/tasks/main.yml"))))
        assertFalse("nothing selected", visible(id, context()))
    }

    fun testTextsByPlace() {
        val file = context(file = vf("mol", "tasks/main.yml"))
        val expected = mapOf(
            GoldenActionIds.MERGE_INTO_GOLDEN to "Merge into Golden…",
            GoldenActionIds.ALIGN_WITH_GOLDEN to "Align with Golden…",
            "Ansibility.Golden.AlignRole" to "Align Role…",
        )
        for ((id, text) in expected) {
            assertEquals(text, update(id, file, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP).presentation.text)
            assertEquals(text, update(id, file, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP).presentation.text)
            assertEquals("Ansibility: $text", update(id, file, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.text)
            assertEquals("the details pane button", "Ansibility: $text", ActionManager.getInstance().getAction(id).templateText)
        }
    }

    fun testRegisteredRightAfterTheCompareActions() {
        val actions = ActionManager.getInstance()
        for (group in listOf("Ansibility.ToolWindow.Popup", GoldenTestSupport.SUBMENU)) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            val at = children.indexOf("Ansibility.Golden.CompareWith")
            assertTrue("$group: $children", at >= 0)
            assertEquals(
                "$group: $children",
                listOf(GoldenActionIds.ALIGN_WITH_GOLDEN, GoldenActionIds.MERGE_INTO_GOLDEN, "Ansibility.Golden.AlignRole"),
                children.subList(at + 1, at + 4),
            )
        }
        val vcs = javaClass.getResource("/META-INF/ansibility-vcs.xml")!!.readText()
        assertTrue("the Conflicts dialog is registered by the optional VCS fragment only", "<roleMergeDialog implementation=\"${AlignVcsMergeDialog::class.java.name}\"/>" in vcs)
        val golden = javaClass.getResource("/META-INF/ansibility-golden.xml")!!.readText()
        assertTrue("the extension point lives in the always-loaded fragment", "de.terletzkiy.ansibility.roleMergeDialog" in golden)
    }

    // ------------------------------------------------------------------ what the actions align

    fun testMergeIntoGoldenMakesGoldenTheTarget() {
        perform(GoldenActionIds.MERGE_INTO_GOLDEN, context(roleCopy = dir("mol")))
        GoldenTestSupport.waitFor("the window") { dialog.sessions.isNotEmpty() }
        val session = dialog.sessions.single()
        assertEquals(copy("golden"), session.target)
        assertEquals(copy("mol"), session.source)
        assertEquals(listOf("molecule/default/verify.yml"), session.rows.map { it.relPath })
        assertEquals("Align web: golden ← mol", session.texts.dialogTitle)
        assertNotNull("the label was set before the window", session.labelId)
    }

    fun testAlignWithGoldenMakesTheCopyTheTarget() {
        perform(GoldenActionIds.ALIGN_WITH_GOLDEN, context(file = vf("tasks", "tasks/main.yml")))
        GoldenTestSupport.waitFor("the window") { dialog.sessions.isNotEmpty() }
        val session = dialog.sessions.single()
        assertEquals(copy("tasks"), session.target)
        assertEquals(copy("golden"), session.source)
        assertEquals("golden (source)", session.texts.sourcePanel)
    }

    fun testAlignRoleAsksForTheCopiesFirst() {
        ui.choose = { setup -> AlignRequest(setup.choices.first { it.name == "spec" }.copy, setup.defaultSource.copy) }
        perform("Ansibility.Golden.AlignRole", context(file = vf("mol", "tasks/main.yml")))
        GoldenTestSupport.waitFor("the window") { dialog.sessions.isNotEmpty() }
        val setup = ui.setups.single()
        assertEquals("golden is the default target", "golden", setup.defaultTarget.name)
        assertEquals("the selected copy is the default source", "mol", setup.defaultSource.name)
        val session = dialog.sessions.single()
        assertEquals(copy("spec"), session.target)
        assertEquals(copy("mol"), session.source)

        ui.choose = { null }
        val shown = dialog.sessions.size
        val job = AlignService.getInstance(project).alignRole(GoldenTarget(copy("mol"), null))
        GoldenTestSupport.waitFor("the second opening step") { job.isCompleted }
        assertEquals(2, ui.setups.size)
        assertEquals("cancelled: no window", shown, dialog.sessions.size)
    }

    // ------------------------------------------------------------------ the opening step

    fun testTheOpeningStepRanksEveryCopyInScopeFirstWithDefaults() {
        (WorkspaceScopeService.getInstance(project) as WorkspaceScopeServiceImpl).set(ScopeChoice.Roots(setOf("repos/tasks/ansible", "repos/spec/ansible")))
        val setup = GoldenTestSupport.await { AlignSetup.load(project, copy("mol")) }!!
        assertEquals("every copy, the selected one too; in scope first; never filtered", listOf("spec", "tasks", "golden", "missing", "mol", "mol2", "same", "specmol"), setup.choices.map { it.name })
        assertEquals("golden", setup.defaultTarget.name)
        assertEquals("mol", setup.defaultSource.name)

        val onGolden = GoldenTestSupport.await { AlignSetup.load(project, copy("golden")) }!!
        assertEquals("golden", onGolden.defaultTarget.name)
        assertEquals("the first other copy", "spec", onGolden.defaultSource.name)

        val solo = GoldenTestSupport.await { AlignSetup.load(project, copy("same", "solo")) }!!
        assertEquals("no golden copy: the selected copy is the target", "same", solo.defaultTarget.name)
        assertEquals("tasks", solo.defaultSource.name)

        assertNull("a single copy has nothing to align with", GoldenTestSupport.await { AlignSetup.load(project, GoldenTestSupport.copy(project, DriftFixture.file(myFixture, "repos/same/ansible/roles/app-same"))) })
    }

    fun testTheOpeningStepCountsAndTheVaultIdWarning() {
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/files/ssl/web.key", "synthetic key material of golden\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("missing")}/files/ssl/web.key", "synthetic key material of missing\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("missing")}/files/notes/extra.txt", "only in missing\n")
        val counts = GoldenTestSupport.await { AlignSetup.preview(project, copy("missing"), copy("golden"), includeSensitive = false) }
        assertEquals("3 changed · 9 only in source · 1 only in target · 1 left out", counts.text)
        assertNull(counts.vaultWarning)
        val included = GoldenTestSupport.await { AlignSetup.preview(project, copy("missing"), copy("golden"), includeSensitive = true) }
        assertEquals("4 changed · 9 only in source · 1 only in target · 0 left out", included.text)

        val labelled = "\$ANSIBLE_VAULT;1.2;AES256;prod\n" + "61626364".repeat(10) + "\n"
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/vars/vault.yml", labelled)
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("missing")}/vars/vault.yml", DriftFixture.syntheticVault("missing marker"))
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        val differs = GoldenTestSupport.await { AlignSetup.preview(project, copy("missing"), copy("golden"), includeSensitive = false) }
        assertEquals(
            "Vault ids differ: golden uses prod, missing uses default. missing may not be able to decrypt the vault files of golden.",
            differs.vaultWarning,
        )
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("missing")}/vars/vault.yml", labelled.replace("6162", "6364"))
        val same = GoldenTestSupport.await { AlignSetup.preview(project, copy("missing"), copy("golden"), includeSensitive = false) }
        assertNull("the same vault id", same.vaultWarning)
        assertEquals("headers only, never decrypted", 0, guard.calls.get())
    }
}
