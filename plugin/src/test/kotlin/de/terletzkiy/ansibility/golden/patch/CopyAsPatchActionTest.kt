package de.terletzkiy.ansibility.golden.patch

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
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftOptions
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds

/**
 * The action "Copy as Patch for Golden…" (plan amendment R25, X126): where it shows (a copy row, a "Differences from
 * golden" row, the editor's file, a folder in the Project view, a file only golden has) and where it does not (golden
 * itself, no golden copy, no golden root, several files, a path in neither copy, and what the known drift says is the
 * same), its texts by place, its place right after Take This into Golden in both popups, and its details button.
 */
class CopyAsPatchActionTest : BasePlatformTestCase() {
    private lateinit var drift: RoleDriftService

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
        drift = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, drift, testRootDisposable)
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

    private fun dir(team: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team, role))

    private fun vf(team: String, relative: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir(team, role)}/$relative")

    private fun context(file: VirtualFile? = null, roleCopy: VirtualFile? = null, rolePath: String? = null, files: Array<VirtualFile>? = null): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        file?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        files?.let { builder.add(CommonDataKeys.VIRTUAL_FILE_ARRAY, it) }
        roleCopy?.let { builder.add(GoldenDataKeys.ROLE_COPY, it) }
        rolePath?.let { builder.add(GoldenDataKeys.ROLE_PATH, it) }
        return builder.build()
    }

    private fun update(context: DataContext, place: String = ActionPlaces.UNKNOWN, uiKind: ActionUiKind = ActionUiKind.NONE): AnActionEvent {
        val action = ActionManager.getInstance().getAction(ID) ?: error("$ID is not registered")
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, uiKind, null)
        runReadActionBlocking { action.update(event) }
        return event
    }

    private fun visible(context: DataContext): Boolean = update(context).presentation.isEnabledAndVisible

    fun testShownForACopyThatIsNotGolden() {
        assertTrue("a copy row", visible(context(roleCopy = dir("missing"))))
        assertTrue("a Differences row", visible(context(roleCopy = dir("mol"), rolePath = "molecule/default/verify.yml")))
        assertTrue("a file only golden has", visible(context(roleCopy = dir("missing"), rolePath = "meta/argument_specs.yml")))
        assertTrue("the editor's file", visible(context(file = vf("tasks", "tasks/main.yml"))))
        assertTrue("a folder in the Project view", visible(context(file = vf("tasks", "tasks"))))
        assertTrue("one file selected", visible(context(file = vf("tasks", "tasks/main.yml"), files = arrayOf(vf("tasks", "tasks/main.yml")))))
        assertTrue("drift not known yet: an identical copy is offered", visible(context(roleCopy = dir("same"))))
    }

    fun testHiddenOnGoldenWithoutAGoldenCopyAndForOddSelections() {
        assertFalse("golden's copy row", visible(context(roleCopy = dir("golden"))))
        assertFalse("golden's file", visible(context(file = vf("golden", "tasks/main.yml"))))
        assertFalse("no golden copy of solo", visible(context(file = vf("same", "tasks/main.yml", "solo"))))
        assertFalse("not in a role", visible(context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg"))))
        assertFalse("in neither copy", visible(context(roleCopy = dir("mol"), rolePath = "tasks/none.yml")))
        assertFalse("several files", visible(context(file = vf("tasks", "tasks/main.yml"), files = arrayOf(vf("tasks", "tasks/main.yml"), vf("tasks", "tasks/install.yml")))))
        assertFalse("nothing selected", visible(context()))
        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        assertFalse("no golden root", visible(context(roleCopy = dir("missing"))))
    }

    fun testHiddenWhenTheKnownDriftSaysNothingDiffers() {
        DriftFixture.drift(drift, "web")
        assertFalse("an identical copy", visible(context(roleCopy = dir("same"))))
        assertFalse("a file that is the same as golden's", visible(context(file = vf("tasks", "handlers/main.yml"))))
        assertFalse("a folder without differences", visible(context(file = vf("tasks", "handlers"))))
        assertTrue("a differing file", visible(context(file = vf("tasks", "tasks/main.yml"))))
        assertTrue("a folder with one", visible(context(file = vf("tasks", "tasks"))))
        assertTrue("a drifting copy", visible(context(roleCopy = dir("tasks"))))
        assertTrue("a file only golden has", visible(context(roleCopy = dir("missing"), rolePath = "tasks/install.yml")))
    }

    fun testWithMoleculeIgnoredACopyThatDiffersOnlyThereIsHiddenButItsMoleculeFileIsNot() {
        drift.options = DriftOptions(ignoreMolecule = true)
        DriftFixture.drift(drift, "web")
        assertFalse("molecule-only copy, molecule ignored", visible(context(roleCopy = dir("mol"))))
        assertTrue("the molecule file itself: drift does not know", visible(context(roleCopy = dir("mol"), rolePath = "molecule/default/verify.yml")))
        assertTrue("the molecule folder", visible(context(file = vf("mol", "molecule"))))
    }

    fun testADriftComputedWithOtherOptionsDecidesNothing() {
        drift.options = DriftOptions(ignoreMolecule = true)
        DriftFixture.drift(drift, "web")
        drift.options = DriftOptions(ignoreMolecule = false)
        assertTrue("molecule counts again: the cached result no longer says", visible(context(roleCopy = dir("mol"))))
    }

    fun testTextsByPlace() {
        val file = context(file = vf("tasks", "tasks/main.yml"))
        assertEquals(MENU_TEXT, update(file, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals(MENU_TEXT, update(file, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals("Ansibility: $MENU_TEXT", update(file, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.text)
        assertEquals("Ansibility: $MENU_TEXT", ActionManager.getInstance().getAction(ID).templateText)
    }

    fun testItComesRightAfterTakeThisIntoGoldenInBothPopups() {
        val actions = ActionManager.getInstance()
        for (group in GoldenTestSupport.POPUPS) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            val take = children.indexOf("Ansibility.Golden.TakeIntoGolden")
            assertTrue("$group: $children", take >= 0)
            assertEquals("$group: $children", ID, children.getOrNull(take + 1))
        }
        for (group in listOf("EditorPopupMenu", "ProjectViewPopupMenu")) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            assertFalse("$group: only inside the Ansibility Golden submenu, $children", ID in children)
        }
    }

    fun testTheDetailsOfADriftingCopyOfferItAsTheLastButton() {
        assertEquals(ID, GoldenActionIds.COPY_AS_PATCH)
        assertEquals(ID, GoldenActionIds.COPY_ACTIONS.last())
    }

    private companion object {
        const val ID = "Ansibility.Golden.CopyAsPatch"
        const val MENU_TEXT = "Copy as Patch for Golden…"
    }
}
