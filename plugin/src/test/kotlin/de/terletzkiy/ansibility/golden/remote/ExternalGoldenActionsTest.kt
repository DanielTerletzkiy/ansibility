package de.terletzkiy.ansibility.golden.remote

import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.align.AlignRequest
import de.terletzkiy.ansibility.golden.align.AlignService
import de.terletzkiy.ansibility.golden.align.AlignSetup
import de.terletzkiy.ansibility.golden.align.AlignSide
import de.terletzkiy.ansibility.golden.align.AlignTestSupport
import de.terletzkiy.ansibility.golden.align.RecordingAlignUi
import de.terletzkiy.ansibility.golden.align.ScriptedMergeDialog
import de.terletzkiy.ansibility.golden.compare.RoleCompare
import de.terletzkiy.ansibility.golden.push.PushOutcome
import de.terletzkiy.ansibility.golden.push.PushTestSupport
import de.terletzkiy.ansibility.golden.push.RecordingPushUi
import de.terletzkiy.ansibility.golden.take.TakeDirection
import de.terletzkiy.ansibility.golden.take.TakeOutcome
import de.terletzkiy.ansibility.golden.take.TakeService
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.run.molecule.MoleculeTargets
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds

/**
 * What the golden actions do with an external golden root (plan amendment R25, D197/D198): the write actions into it
 * are hidden (Merge into Golden…, Take This into Golden, Align Role…'s targets), Push never targets it but pushes from
 * it, Align with Golden and Take Golden's Version write the project's copy, Compare shows its side read-only, and no
 * Molecule run starts there (D199).
 */
class ExternalGoldenActionsTest : ExternalGoldenTestCase() {
    private lateinit var alignUi: RecordingAlignUi
    private lateinit var pushUi: RecordingPushUi

    override fun setUp() {
        super.setUp()
        alignUi = RecordingAlignUi()
        AlignTestSupport.install(alignUi, testRootDisposable)
        AlignTestSupport.install(listOf(ScriptedMergeDialog()), testRootDisposable)
        pushUi = RecordingPushUi()
        PushTestSupport.install(pushUi, testRootDisposable)
        useFolderGolden()
    }

    private fun context(file: VirtualFile? = null, roleCopy: VirtualFile? = null, path: String? = null): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        file?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        roleCopy?.let { builder.add(GoldenDataKeys.ROLE_COPY, it) }
        path?.let { builder.add(GoldenDataKeys.ROLE_PATH, it) }
        return builder.build()
    }

    private fun visible(id: String, context: DataContext): Boolean {
        val action = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        runReadActionBlocking { action.update(event) }
        return event.presentation.isEnabledAndVisible
    }

    private fun localFile(team: String, relative: String): VirtualFile = vf("${DriftFixture.roleDir(team)}/$relative")

    fun testTheWriteActionsIntoTheExternalGoldenAreHidden() {
        val local = context(file = localFile("tasks", "tasks/main.yml"))
        assertFalse("Merge into Golden…", visible(GoldenActionIds.MERGE_INTO_GOLDEN, local))
        assertFalse("Merge into Golden… from the copy row", visible(GoldenActionIds.MERGE_INTO_GOLDEN, context(roleCopy = localCopy("tasks").dir)))
        assertFalse("Take This into Golden", visible("Ansibility.Golden.TakeIntoGolden", local))
        assertTrue("Align with Golden… (golden into the copy)", visible(GoldenActionIds.ALIGN_WITH_GOLDEN, local))
        assertTrue("Take Golden's Version", visible("Ansibility.Golden.TakeGoldens", local))
        assertTrue("Compare with Golden", visible(GoldenActionIds.COMPARE_WITH_GOLDEN, local))
        assertTrue("Compare with Other Copy…", visible("Ansibility.Golden.CompareWith", local))
        assertTrue("Push Role to Repos…", visible(GoldenActionIds.PUSH_TO_REPOS, local))

        val golden = context(file = goldenVf("roles/web/tasks/main.yml"))
        assertFalse("nothing aligns golden with itself", visible(GoldenActionIds.ALIGN_WITH_GOLDEN, golden))
        assertFalse(visible(GoldenActionIds.MERGE_INTO_GOLDEN, golden))
        assertFalse(visible("Ansibility.Golden.TakeGoldens", golden))
        assertFalse(visible("Ansibility.Golden.TakeIntoGolden", golden))
        assertTrue("Compare with Other Copy… from the mirror's file", visible("Ansibility.Golden.CompareWith", golden))
        assertTrue("Push from the golden copy", visible(GoldenActionIds.PUSH_TO_REPOS, golden))
        assertTrue("Align Role… (golden as the source)", visible("Ansibility.Golden.AlignRole", golden))
    }

    fun testMergeAndTakeIntoTheExternalGoldenWriteNothing() {
        val before = text(folder.resolve("roles/web/tasks/main.yml"))
        GoldenTestSupport.await { AlignService.getInstance(project).mergeIntoGolden(GoldenTarget(localCopy("tasks"), null)).join() }
        val take = GoldenTestSupport.await { TakeService.getInstance(project).take(GoldenTarget(localCopy("tasks"), "tasks/main.yml"), TakeDirection.INTO_GOLDEN) }
        assertEquals(TakeOutcome.Status.NOT_APPLICABLE, take.status)
        GoldenTestSupport.await { AlignService.getInstance(project).align(AlignRequest(target = goldenCopy(), source = localCopy("tasks"))).join() }
        assertEquals("Align says why", 1, alignUi.notices.size)
        assertTrue(alignUi.notices.single(), alignUi.notices.single().contains("read-only"))
        assertEquals(before, text(folder.resolve("roles/web/tasks/main.yml")))
    }

    fun testAlignRoleNeverOffersTheExternalGoldenAsTheTarget() {
        val setup = GoldenTestSupport.await { AlignSetup.load(project, goldenCopy()) } ?: error("no setup")
        assertTrue("golden is a source choice", setup.choices.any { it.copy.isExternal })
        assertTrue("never a target choice", setup.targets.none { it.copy.isExternal })
        assertFalse(setup.defaultTarget.copy.isExternal)
        assertTrue("the default source is the selected golden copy", setup.defaultSource.copy.isExternal)
    }

    fun testAlignWithGoldenWritesTheProjectsCopy() {
        val target = localCopy("tasks")
        val session = GoldenTestSupport.await { AlignService.getInstance(project).session(AlignRequest(target = target, source = goldenCopy())) }
        assertEquals(listOf("tasks/main.yml"), session.rows.map { it.relPath })
        GoldenTestSupport.pooled { session.accept(session.rows, AlignSide.SOURCE) }
        assertEquals(text(folder.resolve("roles/web/tasks/main.yml")), text(projectDir.resolve("${DriftFixture.roleDir("tasks")}/tasks/main.yml")))
    }

    fun testTakeGoldensVersionWritesTheProjectsCopy() {
        val take = GoldenTestSupport.await { TakeService.getInstance(project).take(GoldenTarget(localCopy("tasks"), "tasks/main.yml"), TakeDirection.FROM_GOLDEN) }
        assertEquals(take.toString(), TakeOutcome.Status.TAKEN, take.status)
        assertEquals(text(folder.resolve("roles/web/tasks/main.yml")), text(projectDir.resolve("${DriftFixture.roleDir("tasks")}/tasks/main.yml")))
    }

    fun testPushNeverTargetsTheExternalGoldenButPushesFromIt() {
        val fromLocal = PushTestSupport.rows(project, localCopy("tasks"))
        assertTrue("rows: ${fromLocal.map { it.name }}", fromLocal.none { it.copy?.isExternal == true || it.name == "golden" })
        assertTrue(fromLocal.any { it.name == "same" })

        val fromGolden = PushTestSupport.rows(project, goldenCopy())
        assertTrue(fromGolden.none { it.copy?.isExternal == true })
        assertEquals("the badges are against golden", "Δ tasks/templates", fromGolden.single { it.name == "tasks" }.badge)
        val outcome = PushTestSupport.push(project, goldenCopy(), listOf("tasks"))
        assertEquals(outcome.toString(), PushOutcome.Status.PUSHED, outcome.status)
        assertEquals(text(folder.resolve("roles/web/tasks/main.yml")), text(projectDir.resolve("${DriftFixture.roleDir("tasks")}/tasks/main.yml")))
    }

    fun testTheGoldenSideOfCompareIsReadOnly() {
        val compare = RoleCompare.getInstance(project)
        val request = GoldenTestSupport.pooled { compare.request(goldenCopy(), localCopy("tasks"), "tasks/main.yml") } as SimpleDiffRequest
        val (left, right) = request.contents
        assertEquals("the golden side", true, left.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        assertNull("the project's side stays editable", right.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        val reversed = GoldenTestSupport.pooled { compare.request(localCopy("same"), goldenCopy(), "tasks/main.yml") } as SimpleDiffRequest
        assertEquals(true, reversed.contents[1].getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
    }

    fun testNoMoleculeRunOnTheExternalGolden() {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = it.molecule.copy(runTests = true)) }
        val local = runReadActionBlocking { MoleculeTargets.ofFiles(project, listOf(localFile("mol", "molecule"))) }
        assertEquals("the project's copy runs", 1, local.size)
        val golden = runReadActionBlocking { MoleculeTargets.ofFiles(project, listOf(goldenVf("roles/web/molecule"), goldenVf("roles/web"))) }
        assertTrue("never the golden root outside the project: $golden", golden.isEmpty())
    }
}
