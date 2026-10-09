package de.terletzkiy.ansibility.golden.take

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
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds

/**
 * The single-file take actions (plan amendment R24, X121): when they show (a file of a copy with a golden copy, also a
 * file only golden has; never golden itself, a folder, a copy row or several files), their texts by place, and the
 * final order of every golden action in the three popups.
 */
class TakeActionsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
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

    private fun update(id: String, context: DataContext, place: String = ActionPlaces.UNKNOWN, uiKind: ActionUiKind = ActionUiKind.NONE): AnActionEvent {
        val action = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, uiKind, null)
        runReadActionBlocking { action.update(event) }
        return event
    }

    private fun visible(id: String, context: DataContext): Boolean = update(id, context).presentation.isEnabledAndVisible

    fun testShownForOneFileOfACopyWithAGoldenCopy() {
        for (id in IDS) {
            assertTrue("$id: the editor's file", visible(id, context(file = vf("mol", "tasks/main.yml"))))
            assertTrue("$id: a Differences row", visible(id, context(roleCopy = dir("mol"), rolePath = "molecule/default/verify.yml")))
            assertTrue("$id: a file only golden has", visible(id, context(roleCopy = dir("missing"), rolePath = "meta/argument_specs.yml")))
            assertTrue("$id: one file selected", visible(id, context(file = vf("mol", "tasks/main.yml"), files = arrayOf(vf("mol", "tasks/main.yml")))))

            assertFalse("$id: golden itself", visible(id, context(file = vf("golden", "tasks/main.yml"))))
            assertFalse("$id: a copy row", visible(id, context(roleCopy = dir("mol"))))
            assertFalse("$id: a folder", visible(id, context(roleCopy = dir("mol"), rolePath = "tasks")))
            assertFalse("$id: a folder in the Project view", visible(id, context(file = vf("mol", "tasks"))))
            assertFalse("$id: in neither copy", visible(id, context(roleCopy = dir("mol"), rolePath = "tasks/none.yml")))
            assertFalse("$id: no golden copy of solo", visible(id, context(file = vf("same", "tasks/main.yml", "solo"))))
            assertFalse("$id: not in a role", visible(id, context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg"))))
            assertFalse("$id: several files", visible(id, context(file = vf("mol", "tasks/main.yml"), files = arrayOf(vf("mol", "tasks/main.yml"), vf("mol", "tasks/install.yml")))))
            assertFalse("$id: nothing selected", visible(id, context()))
        }
        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        for (id in IDS) assertFalse("$id: no golden root", visible(id, context(file = vf("mol", "tasks/main.yml"))))
    }

    fun testTextsByPlace() {
        val file = context(file = vf("mol", "tasks/main.yml"))
        val expected = mapOf(TAKE_GOLDENS to "Take Golden's Version", TAKE_INTO_GOLDEN to "Take This into Golden")
        for ((id, text) in expected) {
            assertEquals(text, update(id, file, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP).presentation.text)
            assertEquals(text, update(id, file, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP).presentation.text)
            assertEquals("Ansibility: $text", update(id, file, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.text)
            assertEquals("Ansibility: $text", ActionManager.getInstance().getAction(id).templateText)
        }
    }

    fun testTheGoldenActionsComeInTheirFinalOrderInEveryPopup() {
        val expected = listOf(
            GoldenActionIds.COMPARE_WITH_GOLDEN,
            "Ansibility.Golden.CompareWith",
            GoldenActionIds.ALIGN_WITH_GOLDEN,
            GoldenActionIds.MERGE_INTO_GOLDEN,
            "Ansibility.Golden.AlignRole",
            GoldenActionIds.PUSH_TO_REPOS,
            TAKE_GOLDENS,
            TAKE_INTO_GOLDEN,
        )
        val actions = ActionManager.getInstance()
        for (group in listOf("Ansibility.ToolWindow.Popup", GoldenTestSupport.SUBMENU)) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            val at = children.indexOf(expected.first())
            assertTrue("$group: $children", at >= 0 && at + expected.size <= children.size)
            assertEquals("$group: $children", expected, children.subList(at, at + expected.size))
        }
        for (group in listOf("EditorPopupMenu", "ProjectViewPopupMenu")) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            assertTrue("$group: the submenu, $children", GoldenTestSupport.SUBMENU in children)
            assertTrue("$group: never flat, $children", children.none { it in expected })
        }
    }

    private companion object {
        const val TAKE_GOLDENS = "Ansibility.Golden.TakeGoldens"
        const val TAKE_INTO_GOLDEN = "Ansibility.Golden.TakeIntoGolden"
        val IDS = listOf(TAKE_GOLDENS, TAKE_INTO_GOLDEN)
    }
}
