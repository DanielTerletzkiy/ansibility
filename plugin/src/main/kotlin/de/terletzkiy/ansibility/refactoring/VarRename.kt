package de.terletzkiy.ansibility.refactoring

import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.vars.usages.VarOccurrences
import de.terletzkiy.ansibility.vars.usages.VarScope
import de.terletzkiy.ansibility.vars.usages.VarSymbolElement
import de.terletzkiy.ansibility.vars.usages.VarUsageSearch
import java.util.concurrent.Callable

/**
 * Rename of one variable: exactly the occurrences Find Usages lists for it ([VarOccurrences]). For a root variable
 * that is every definition (argument spec option, `defaults/`, `vars/`, inventory, `set_fact`, `register`, task and
 * play `vars:`) and every Jinja read (YAML values, `when:`, templates, `hostvars[h].x`, `vars['x']`) in the root's
 * family; a loop variable renames its `loop_var`/`index_var` and the reads in the loop; a Jinja local stays in its file.
 */
internal object VarRenamer {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Python and Jinja words a variable cannot be called. */
    private val RESERVED = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield", "True", "False", "None", "true", "false", "none", "loop", "self",
    )

    /** Names a loop binds without a key to rename (`loop:` itself); `loop_control.loop_var` gives them one. */
    private val LOOP_IMPLICIT = setOf("item", "ansible_loop")

    /** The error for [name] as a new variable name, or null. */
    fun invalidName(name: String): String? = when {
        name.isEmpty() -> AnsibilityRefactoringBundle.message("rename.var.error.empty")
        !NAME.matches(name) -> AnsibilityRefactoringBundle.message("rename.var.error.identifier", name)
        name in RESERVED -> AnsibilityRefactoringBundle.message("rename.var.error.reserved", name)
        else -> null
    }

    private val MEMBER = Regex("""[A-Za-z0-9_.:@-]+""")

    /** The error for [name] as a new member key (`ops-pxe1`), or null. */
    fun invalidMemberName(name: String): String? = when {
        name.isEmpty() -> AnsibilityRefactoringBundle.message("rename.var.error.empty")
        !MEMBER.matches(name) -> AnsibilityRefactoringBundle.message("rename.member.error", name)
        else -> null
    }

    /** The text [symbol] has at each occurrence: the variable name, or a member's last key. */
    fun oldName(symbol: VarSymbolElement): String = (symbol.scope as? VarScope.Member)?.path?.last() ?: symbol.name

    /** Why [symbol] cannot be renamed, or null. Read action, smart mode. */
    fun refusal(project: Project, symbol: VarSymbolElement): String? = when (symbol.scope) {
        is VarScope.Loop -> if (symbol.name in LOOP_IMPLICIT) AnsibilityRefactoringBundle.message("rename.var.refuse.loop", symbol.name) else null
        is VarScope.Local -> null
        is VarScope.Member -> null
        is VarScope.Root -> {
            val defined = VarService.getInstance(project).symbol(symbol.root, symbol.name).definitions.any { it.kind != VarDefKind.JINJA_LOCAL }
            if (defined) null else AnsibilityRefactoringBundle.message("rename.var.refuse.undefined", symbol.name)
        }
    }

    /** How many definitions [newName] already has where [symbol] lives (a rename onto them merges the variables). */
    fun clashes(project: Project, symbol: VarSymbolElement, newName: String): Int = when (symbol.scope) {
        is VarScope.Root -> VarService.getInstance(project).symbol(symbol.root, newName).definitions.count { it.kind != VarDefKind.JINJA_LOCAL }
        else -> 0
    }

    /** The edits renaming [symbol] to [newName]. Read action, smart mode. */
    fun plan(project: Project, symbol: VarSymbolElement, newName: String): RenamePlan {
        val documents = FileDocumentManager.getInstance()
        val edits = ArrayList<RenameEdit>()
        var skipped = 0
        for (occurrence in VarOccurrences.of(project, symbol, null)) {
            ProgressManager.checkCanceled()
            val text = documents.getDocument(occurrence.file)?.immutableCharSequence
            val edit = text?.let { RenamePlan.edit(occurrence.file, it, occurrence.range, oldName(symbol), newName) }
            if (edit != null) edits += edit else skipped++
        }
        return RenamePlan(RenamePlan.distinct(edits), skipped = skipped)
    }

    /** The variable at the caret (or right before it, at the end of a name). Read action. */
    fun symbolAt(file: PsiFile, offset: Int): VarSymbolElement? =
        VarUsageSearch.symbolAt(file, offset) ?: offset.takeIf { it > 0 }?.let { VarUsageSearch.symbolAt(file, it - 1) }
}

/** Shift+F6 on a variable's definition or use: [VarRenamer] behind a name dialog. */
class VarRenameHandler : RenameHandler, TitledHandler {
    override fun getActionTitle(): String = AnsibilityRefactoringBundle.message("rename.var.action")

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        if (DumbService.isDumb(file.project)) return false
        return ReadAction.compute<Boolean, RuntimeException> { file.isValid && VarRenamer.symbolAt(file, editor.caretModel.offset) != null }
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val symbol = VarRenamer.symbolAt(file, editor.caretModel.offset) ?: return
        val title = AnsibilityRefactoringBundle.message("rename.var.title")
        VarRenamer.refusal(project, symbol)?.let {
            CommonRefactoringUtil.showErrorHint(project, editor, it, title, null)
            return
        }
        val newName = askName(project, dataContext, symbol) ?: return
        val oldName = VarRenamer.oldName(symbol)
        if (newName == oldName) return
        val clashes = VarRenamer.clashes(project, symbol, newName)
        if (clashes > 0 && !ApplicationManager.getApplication().isUnitTestMode) {
            val answer = Messages.showYesNoDialog(
                project, AnsibilityRefactoringBundle.message("rename.var.clash", newName, clashes), title,
                AnsibilityRefactoringBundle.message("rename.var.clash.continue"), Messages.getCancelButton(), Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<RenamePlan, RuntimeException> {
                ReadAction.nonBlocking(Callable { VarRenamer.plan(project, symbol, newName) }).inSmartMode(project).executeSynchronously()
            },
            AnsibilityRefactoringBundle.message("rename.var.searching", oldName), true, project,
        )
        plan.apply(project, AnsibilityRefactoringBundle.message("rename.var.command", oldName, newName))
        report(project, AnsibilityRefactoringBundle.message("rename.done", oldName, newName, plan.edits.size, plan.files, plan.skipped))
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {}

    private fun askName(project: Project, dataContext: DataContext, symbol: VarSymbolElement): String? {
        if (ApplicationManager.getApplication().isUnitTestMode) return PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext)
        val dialog = RenameDialog(
            project, AnsibilityRefactoringBundle.message("rename.var.title"),
            AnsibilityRefactoringBundle.message("rename.var.label", symbol.presentableText), VarRenamer.oldName(symbol), null,
            if (symbol.scope is VarScope.Member) VarRenamer::invalidMemberName else VarRenamer::invalidName,
        )
        return if (dialog.showAndGet()) dialog.newName else null
    }
}

internal fun report(project: Project, text: String) {
    WindowManager.getInstance().getStatusBar(project)?.info = text
}
