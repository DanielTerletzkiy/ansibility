package de.terletzkiy.ansibility.context.layout

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import java.util.function.Function
import javax.swing.JComponent

/**
 * The banner of the layout diagnostics on `ansible.cfg` and inventory sources (ANS-L001–L004, L007, L008): one line
 * per problem, the most severe first, with "Go to line" when a parser said where it stopped and "Show Detected Layout".
 * Not dumb-aware: the inventory models need smart mode.
 */
class LayoutProblemBannerProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (DumbService.isDumb(project) || file.isDirectory) return null
        val problems = LayoutDiagnostics.bannerProblems(project, file).sortedByDescending { it.severity }
        if (problems.isEmpty()) return null
        val worst = problems.first().severity
        return Function { editor ->
            val status = when {
                worst >= HighlightSeverity.ERROR -> EditorNotificationPanel.Status.Error
                worst >= HighlightSeverity.WARNING -> EditorNotificationPanel.Status.Warning
                else -> EditorNotificationPanel.Status.Info
            }
            EditorNotificationPanel(editor, status).apply {
                text = problems.joinToString("  ·  ") { it.message }
                problems.firstNotNullOfOrNull { it.offset }?.let { offset ->
                    createActionLabel(AnsibilityLayoutBundle.message("banner.go.to.problem")) {
                        OpenFileDescriptor(project, file, offset).navigate(true)
                    }
                }
                AnsibleWorkspace.getInstance(project).contextOf(file)?.root?.let { root ->
                    createActionLabel(AnsibilityLayoutBundle.message("banner.show.layout")) { ShowLayoutAction.show(project, root) }
                }
            }
        }
    }
}

/**
 * ANS-L005 (a vars file hidden by first-match) and ANS-L006 (a vars directory nothing loads) on `group_vars` and
 * `host_vars` files, at file level.
 */
class LayoutVarsInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitFile(file: PsiFile) {
            val virtualFile = file.originalFile.viewProvider.virtualFile
            val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return
            for (problem in LayoutDiagnostics.varsProblems(file.project, virtualFile, context)) {
                val type = when (problem.code) {
                    LayoutDiagnostics.Code.L006 -> ProblemHighlightType.WEAK_WARNING
                    else -> ProblemHighlightType.WARNING
                }
                holder.registerProblem(file, problem.message, type)
            }
        }
    }
}
