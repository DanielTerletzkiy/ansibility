package de.terletzkiy.ansibility.context.layout

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.InventoryDef
import de.terletzkiy.ansibility.api.LayoutOrigin
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

/**
 * The text of Show Detected Layout (plan amendment R10, F10.7): every inventory of a root with its sources, origin,
 * size and vars directories, what `ansible.cfg` names but is not read, and the layout problems. Plain text, so it
 * can be copied into a bug report. Call inside a read action, in smart mode.
 */
object LayoutReport {
    fun text(project: Project, root: AnsibleRoot): String {
        val layout = ProjectLayoutService.getInstance(project).layout(root)
        val models = InventoryModels.getInstance(project)
        val base = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir
        fun rel(file: VirtualFile?): String = file?.let { VfsUtilCore.getRelativePath(it, base) ?: it.presentableUrl } ?: "?"
        val core = TargetVersionDetector.getInstance(project).targetVersion(root).version?.toString() ?: message("report.core.unknown")
        val lines = ArrayList<String>()
        lines += message("report.header", root.displayName, ContextPresentation.rootKindName(root, null), core, layout.cfg?.let(::rel) ?: message("report.cfg.none"))
        if (layout.inventories.isEmpty()) {
            lines += message("report.inventories.none")
        } else {
            lines += message("report.inventories", layout.inventories.size)
            for (def in layout.inventories) {
                ProgressManager.checkCanceled()
                val model = models.environment(root, def.id)
                val sources = def.sources.joinToString(", ") { s ->
                    val shown = s.file?.let(::rel) ?: (s.path + " " + message("report.inventory.missing"))
                    if (s.isDirectory) "$shown/" else shown
                }
                val counts = model?.let { it.inventory.hosts.size to it.inventory.groups.keys.count { g -> g != "all" && g != "ungrouped" } }
                lines += if (counts == null) {
                    "${def.label}  ← $sources    ${message("report.inventory.unparsed")}    ${origin(def)}"
                } else {
                    message("report.inventory", def.label, sources, counts.first, counts.second, origin(def),
                        if (def.isDefault) message("report.inventory.default") else "")
                }
                val vars = def.varsDirs.flatMap { dir ->
                    listOf(AnsibleLayout.GROUP_VARS, AnsibleLayout.HOST_VARS).mapNotNull { name -> dir.findChild(name)?.let { rel(it) + "/" } }
                }
                if (vars.isNotEmpty()) lines += message("report.vars", vars.joinToString(" · "))
                if (def.varsDirs.any { it == root.dir }) lines += message("report.vars.playbook")
            }
        }
        if (layout.notFollowed.isNotEmpty()) lines += message("report.notFollowed", layout.notFollowed.joinToString(", "))
        val problems = problemFiles(project, root).flatMap { file ->
            LayoutDiagnostics.bannerProblems(project, file).map { message("report.problem", rel(file), it.message) }
        }.distinct()
        if (problems.isEmpty()) {
            lines += message("report.problems.none")
        } else {
            lines += message("report.problems")
            lines += problems
        }
        return lines.joinToString("\n")
    }

    /** The files whose banner problems the report lists: the cfg and every inventory source file. */
    private fun problemFiles(project: Project, root: AnsibleRoot): List<VirtualFile> {
        val layout = ProjectLayoutService.getInstance(project).layout(root)
        val sourceFiles = InventoryModels.getInstance(project).environments(root).flatMap { env ->
            env.sourceFiles.filterNotNull() + env.problems.map { it.file }
        }
        return (listOfNotNull(layout.cfg) + sourceFiles).distinct()
    }

    private fun origin(def: InventoryDef): String = when (def.source.origin) {
        LayoutOrigin.ANSIBLE_CFG -> message("report.origin.ANSIBLE_CFG", def.source.text.orEmpty())
        LayoutOrigin.CONVENTION -> message("report.origin.CONVENTION")
        LayoutOrigin.DETECTED -> message("report.origin.DETECTED")
        LayoutOrigin.PROJECT_SETTINGS -> message("report.origin.PROJECT_SETTINGS")
    }

    private fun message(key: String, vararg params: Any): String = AnsibilityLayoutBundle.message(key, *params)
}

/** "Ansibility: Show Detected Layout" for the root of the current file, else the first root (F10.7). */
class ShowLayoutAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !DumbService.isDumb(project) &&
            AnsibleWorkspace.getInstance(project).roots().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workspace = AnsibleWorkspace.getInstance(project)
        val root = e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { workspace.rootFor(it) }
            ?: workspace.roots().firstOrNull { !it.detached } ?: return
        show(project, root)
    }

    companion object {
        fun show(project: Project, root: AnsibleRoot) {
            val text = ReadAction.nonBlocking<String> { LayoutReport.text(project, root) }
                .inSmartMode(project)
                .executeSynchronously()
            LayoutDialog(project, root, text).show()
        }
    }

    private class LayoutDialog(project: Project, root: AnsibleRoot, private val report: String) : DialogWrapper(project, false) {
        init {
            title = AnsibilityLayoutBundle.message("report.title", root.displayName)
            setOKButtonText(com.intellij.CommonBundle.getCloseButtonText())
            init()
        }

        override fun createCenterPanel(): JComponent {
            val area = JBTextArea(report).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                border = JBUI.Borders.empty(8)
            }
            return JBScrollPane(area).apply { preferredSize = JBUI.size(900, 420) }
        }

        override fun createActions(): Array<Action> = arrayOf(
            object : DialogWrapperAction(AnsibilityLayoutBundle.message("report.copy")) {
                override fun doAction(e: ActionEvent?) {
                    CopyPasteManager.getInstance().setContents(StringSelection(report))
                }
            },
            okAction,
        )
    }
}
