package de.terletzkiy.ansibility.dispatch

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.registerOrReplaceServiceInstance
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.semantics.schema.ArgumentSpec
import de.terletzkiy.ansibility.semantics.schema.OptionSpec

class ShowAnsibleContextActionTest : DispatchTestCase() {
    private val tasksMain = "$GOLDEN_HAPROXY/tasks/main.yml"
    private val marker = "haproxy_apply_kernel_params | default"

    /** A [VarService] double: [known] names have two definitions and one spec binding, every other name none. */
    private inner class FakeVarService(private val known: Set<String>) : VarService {
        val asked = mutableListOf<Pair<String, String>>()

        override fun symbol(root: AnsibleRoot, name: String): VarSymbol {
            asked += root.displayName to name
            if (name !in known) return VarSymbol(root.dir, name, emptyList(), emptyList())
            val defaults = SourceLocation(vf(GOLDEN_DEFAULTS), 0)
            val role = RoleRef(root.dir, "haproxy", vf(GOLDEN_HAPROXY))
            return VarSymbol(
                root.dir,
                name,
                listOf(
                    VarDefinition(name, VarDefKind.ROLE_DEFAULT, defaults),
                    VarDefinition(name, VarDefKind.GROUP_VARS, defaults),
                    VarDefinition(name, VarDefKind.GROUP_VARS, defaults),
                ),
                listOf(SpecBinding(role, "main", OptionSpec(name), defaults)),
            )
        }

        override fun allNames(root: AnsibleRoot): Collection<String> = known
    }

    /** A [RoleRegistry] double: the haproxy role has an argument_specs entry point `main` only. */
    private inner class FakeRoleRegistry : RoleRegistry {
        private fun info(root: VirtualFile): RoleInfo = RoleInfo(
            ref = RoleRef(root, "haproxy", vf(GOLDEN_HAPROXY)),
            argumentSpecs = mapOf("main" to ArgumentSpec("main")),
            specFile = vf("$GOLDEN_HAPROXY/meta/argument_specs.yml"),
            defaultsFiles = listOf(vf(GOLDEN_DEFAULTS)),
            varsFiles = emptyList(),
            taskFiles = emptyList(),
            handlerFiles = emptyList(),
            templatesDir = null,
            filesDir = null,
            metaDependencies = emptyList(),
        )

        override fun roles(root: AnsibleRoot): List<RoleRef> = listOf(info(root.dir).ref)
        override fun role(root: AnsibleRoot, name: String): RoleInfo? = if (name == "haproxy") info(root.dir) else null
        override fun roleOf(file: VirtualFile): RoleInfo? = info(vf("golden"))
    }

    private lateinit var vars: FakeVarService

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
        vars = FakeVarService(setOf("haproxy_apply_kernel_params"))
        project.registerOrReplaceServiceInstance(VarService::class.java, vars, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleRegistry::class.java, FakeRoleRegistry(), testRootDisposable)
    }

    private fun report(path: String, offset: Int?): AnsibleContextReport? =
        runReadActionBlocking { AnsibleContextReport.build(project, vf(path), offset) }

    private fun varRef(name: String, path: String = tasksMain) =
        AnsibleSite.VarRef(name, emptyList(), JinjaContainer.YAML_EXPRESSION, rangeOf(path, name))

    fun testActionIsRegisteredInToolsMenuAndEditorPopup() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction(ACTION_ID)
        assertInstanceOf(action, ShowAnsibleContextAction::class.java)
        assertEquals("Ansibility: Show Ansible Context", action.templatePresentation.text)
        assertFalse("classifiers need indexes", action is DumbAware)
        for (group in listOf("ToolsMenu", "EditorPopupMenu")) {
            val children = (manager.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().toList()
            assertTrue("missing in $group", children.any { manager.getId(it) == ACTION_ID })
        }
    }

    fun testEnabledOnlyForFilesInsideRoots() {
        val outside = createFile("outside/notes.yml", "a: 1\n")
        assertTrue(update(vf(tasksMain), ActionUiKind.MAIN_MENU).isEnabledAndVisible)
        val inTools = update(outside, ActionUiKind.MAIN_MENU)
        assertFalse(inTools.isEnabled)
        assertTrue("the Tools menu keeps showing it, disabled", inTools.isVisible)
        assertFalse("context menus hide it outside roots", update(outside, ActionUiKind.POPUP).isVisible)
        assertTrue(update(vf(tasksMain), ActionUiKind.POPUP).isEnabledAndVisible)
    }

    private fun update(file: VirtualFile, uiKind: ActionUiKind): Presentation {
        val action = ShowAnsibleContextAction()
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()
        val place = if (uiKind == ActionUiKind.POPUP) ActionPlaces.EDITOR_POPUP else ActionPlaces.MAIN_MENU
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, uiKind, null)
        action.update(event)
        return event.presentation
    }

    fun testReportForAVariableSite() {
        classifiers(RecordingClassifier { _, _ -> null }, RecordingClassifier { _, _ -> varRef("haproxy_apply_kernel_params") })
        val report = report(tasksMain, offsetOf(tasksMain, marker, 3))!!

        assertEquals("golden", report.valueOf("Root"))
        assertEquals("Role library", report.valueOf("Root kind"))
        assertEquals("Role tasks", report.valueOf("File kind"))
        assertEquals("haproxy", report.valueOf("Role"))
        assertEquals("main (argument_specs entry)", report.valueOf("Entry point"))
        assertEquals("unknown", report.valueOf("Target ansible-core"))
        assertEquals("Variable reference haproxy_apply_kernel_params (implicit Jinja expression)", report.valueOf("At caret"))
        assertEquals("haproxy_apply_kernel_params", report.valueOf("Text"))
        assertEquals("RecordingClassifier", report.valueOf("Classified by"))
        assertEquals("haproxy_apply_kernel_params", report.valueOf("Variable"))
        assertEquals("3 in golden (role default, group_vars ×2)", report.valueOf("Definitions"))
        assertEquals("1 (haproxy › main)", report.valueOf("Spec bindings"))
        assertNull(report.valueOf("Resolution"))
        assertEquals(listOf("golden" to "haproxy_apply_kernel_params"), vars.asked)

        val labels = report.lines.map { it.label }
        assertEquals("the entry point follows the role", labels.indexOf("Role") + 1, labels.indexOf("Entry point"))
        assertTrue(report.asText(), report.asText().contains("Entry point: main (argument_specs entry)\n"))
    }

    fun testReportForAnUnknownVariable() {
        classifiers(RecordingClassifier { _, _ -> varRef("haproxy_apply") })
        val report = report(tasksMain, offsetOf(tasksMain, marker))!!

        assertEquals("not found in golden", report.valueOf("Resolution"))
        assertNull(report.valueOf("Definitions"))
    }

    fun testReportForANonVariableSiteAndForNoSite() {
        classifiers(RecordingClassifier { _, _ -> AnsibleSite.ModuleKey(TEMPLATE_FQCN, rangeOf(CONFIGURE, TEMPLATE_FQCN)) })
        val module = report(CONFIGURE, offsetOf(CONFIGURE, TEMPLATE_FQCN))!!
        assertEquals("Module ansible.builtin.template", module.valueOf("At caret"))
        assertEquals("configure (no argument_specs entry)", module.valueOf("Entry point"))
        assertNull(module.valueOf("Variable"))
        assertTrue(vars.asked.isEmpty())

        classifiers(RecordingClassifier { _, _ -> null })
        val none = report(CONFIGURE, 0)!!
        assertEquals("Nothing Ansible-specific", none.valueOf("At caret"))
        assertNull(none.valueOf("Classified by"))

        val withoutCaret = report(CONFIGURE, null)!!
        assertNull(withoutCaret.valueOf("At caret"))
        assertEquals("Role tasks", withoutCaret.valueOf("File kind"))
    }

    fun testReportForADefaultsKeyAndADetachedRoot() {
        myFixture.copyDirectoryToProject("infra/$GOLDEN_HAPROXY", "checkouts/.claude/worktrees/wt-x/$GOLDEN_HAPROXY")
        refreshRoots()
        val detachedDefaults = "checkouts/.claude/worktrees/wt-x/$GOLDEN_DEFAULTS"
        classifiers(RecordingClassifier { _, _ ->
            AnsibleSite.VarKey(listOf("haproxy_log_path"), FileKind.ROLE_DEFAULTS, rangeOf(detachedDefaults, "haproxy_log_path"))
        })

        val report = report(detachedDefaults, offsetOf(detachedDefaults, "haproxy_log_path"))!!

        assertEquals("Role library, detached worktree wt-x", report.valueOf("Root kind"))
        assertEquals("Role defaults", report.valueOf("File kind"))
        assertNull("only task files have entry points", report.valueOf("Entry point"))
        assertEquals("Variable key haproxy_log_path (Role defaults)", report.valueOf("At caret"))
        val root = AnsibleWorkspace.getInstance(project).rootFor(vf(detachedDefaults))!!
        assertTrue(root.detached)
        assertEquals("not found in ${root.displayName}", report.valueOf("Resolution"))
        assertEquals(listOf(root.displayName to "haproxy_log_path"), vars.asked)
    }

    fun testNoReportOutsideRoots() {
        createFile("outside/notes.yml", "a: 1\n")
        assertNull(report("outside/notes.yml", 0))
    }

    companion object {
        const val ACTION_ID = "Ansibility.ShowAnsibleContext"
    }
}
