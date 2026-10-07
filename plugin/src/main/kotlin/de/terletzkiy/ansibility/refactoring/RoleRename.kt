package de.terletzkiy.ansibility.refactoring

import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.navigation.RefKind
import de.terletzkiy.ansibility.navigation.RefOccurrence
import de.terletzkiy.ansibility.navigation.RefResolver
import de.terletzkiy.ansibility.navigation.RefSites
import de.terletzkiy.ansibility.navigation.RoleLocator
import de.terletzkiy.ansibility.navigation.RoleSite
import de.terletzkiy.ansibility.vars.usages.VarScope
import de.terletzkiy.ansibility.vars.usages.VarSymbolElement
import java.util.concurrent.Callable
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Rename of a role: its directory, every reference that resolves to it the way ansible-core looks roles up (a play's
 * `roles:` entry, `include_role`/`import_role` `name`, a `meta/main.yml` dependency, molecule playbooks; a path keeps
 * its directories), `galaxy_info.role_name` when it repeats the name, and on request the variables the role defines
 * with its prefix (`haproxy_port` → `lb_port`), each with all its uses ([VarRenamer]).
 */
internal object RoleRenamer {
    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")

    /** The error for [name] as the new name of [roleDir], or null. */
    fun invalidName(roleDir: VirtualFile, name: String): String? = when {
        name.isEmpty() -> AnsibilityRefactoringBundle.message("rename.role.error.empty")
        !NAME.matches(name) -> AnsibilityRefactoringBundle.message("rename.role.error.chars", name)
        name != roleDir.name && roleDir.parent?.findChild(name) != null -> AnsibilityRefactoringBundle.message("rename.role.error.exists", name)
        else -> null
    }

    /** The role directory the caret's role reference resolves to, or null. Read action. */
    fun roleAt(file: PsiFile, offset: Int): VirtualFile? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
        val occurrence = (RefSites.at(file, offset, context) ?: offset.takeIf { it > 0 }?.let { RefSites.at(file, it - 1, context) })
            ?.takeIf { it.kind == RefKind.ROLE && !it.isTemplated } ?: return null
        return (RefResolver(file, context).locateRole(occurrence.text, occurrence.roleSite ?: RoleSite.PLAY_ROLE) as? RoleLocator.Result.Found)?.dir
    }

    /** True for a role directory of the project's own sources (no installed collection or galaxy role). */
    fun isOwnRole(project: Project, dir: VirtualFile): Boolean =
        dir.isDirectory && RoleLayout.isRole(dir) && ProjectFileIndex.getInstance(project).isInContent(dir) &&
            AnsibleWorkspace.getInstance(project).rootFor(dir) != null

    /** The variables [roleDir]'s role defines with its prefix (`<role>_…`), sorted. Read action, smart mode. */
    fun prefixedVariables(project: Project, roleDir: VirtualFile): List<String> {
        val root = AnsibleWorkspace.getInstance(project).rootFor(roleDir) ?: return emptyList()
        val prefix = roleDir.name + "_"
        val vars = VarService.getInstance(project)
        return vars.allNames(root).filter { name ->
            name.startsWith(prefix) && name.length > prefix.length &&
                vars.symbol(root, name).definitions.any { it.kind != VarDefKind.JINJA_LOCAL && it.roleName == roleDir.name }
        }.sorted()
    }

    /** The edits renaming the role at [roleDir] to [newName]. Read action, smart mode. */
    fun plan(project: Project, roleDir: VirtualFile, newName: String, withVariables: Boolean): RenamePlan {
        val old = roleDir.name
        val documents = FileDocumentManager.getInstance()
        val workspace = AnsibleWorkspace.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val edits = ArrayList<RenameEdit>()
        var skipped = 0
        for (file in candidateFiles(project, old)) {
            ProgressManager.checkCanceled()
            val context = workspace.contextOf(file) ?: continue
            val psi = psiManager.findFile(file) ?: continue
            val text = documents.getDocument(file)?.immutableCharSequence ?: continue
            val resolver = RefResolver(psi, context)
            for (occurrence in RefSites.occurrences(psi)) {
                if (occurrence.kind != RefKind.ROLE || occurrence.isTemplated) continue
                if (RefOccurrence.roleNameOf(occurrence.text) != old) continue
                val found = resolver.locateRole(occurrence.text, occurrence.roleSite ?: RoleSite.PLAY_ROLE) as? RoleLocator.Result.Found
                if (found?.dir != roleDir) continue
                val written = text.subSequence(occurrence.range.startOffset, occurrence.range.endOffset).toString()
                val at = written.trimEnd('/').lastIndexOf(old)
                if (at < 0) {
                    skipped++
                    continue
                }
                edits += RenameEdit(file, com.intellij.openapi.util.TextRange.from(occurrence.range.startOffset + at, old.length), newName)
            }
        }
        galaxyRoleName(project, roleDir)?.let { scalar ->
            val range = scalar.textRange
            val file = scalar.containingFile.viewProvider.virtualFile
            documents.getDocument(file)?.immutableCharSequence?.let { text -> RenamePlan.edit(file, text, range, old, newName)?.let(edits::add) }
        }
        if (withVariables) {
            val root = workspace.rootFor(roleDir)
            if (root != null) {
                for (name in prefixedVariables(project, roleDir)) {
                    val symbol = VarSymbolElement(psiManager.findDirectory(roleDir) ?: continue, root, name, VarScope.Root(null))
                    val renamed = newName + name.removePrefix(old)
                    val variable = VarRenamer.plan(project, symbol, renamed)
                    edits += variable.edits
                    skipped += variable.skipped
                }
            }
        }
        return RenamePlan(RenamePlan.distinct(edits), directory = roleDir to newName, skipped = skipped)
    }

    /** `galaxy_info.role_name` of the role's `meta/main.yml` when it is the directory's name. */
    private fun galaxyRoleName(project: Project, roleDir: VirtualFile): YAMLScalar? {
        val meta = roleDir.findFileByRelativePath("meta/main.yml") ?: roleDir.findFileByRelativePath("meta/main.yaml") ?: return null
        val yaml = PsiManager.getInstance(project).findFile(meta) as? YAMLFile ?: return null
        val scalar = YAMLUtil.getQualifiedKeyInFile(yaml, "galaxy_info", "role_name")?.value as? YAMLScalar ?: return null
        return scalar.takeIf { it.textValue == roleDir.name }
    }

    /** The project's files that hold the role's name as a word (role names may hold `-` or `.`: their longest word). */
    private fun candidateFiles(project: Project, name: String): Collection<VirtualFile> {
        val word = name.split(Regex("[^A-Za-z0-9_]+")).maxByOrNull { it.length }?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return CacheManager.getInstance(project).getVirtualFilesWithWord(word, UsageSearchContext.ANY, GlobalSearchScope.projectScope(project), true).toList()
    }
}

/** Shift+F6 on a role reference, or on a role's directory in the project view. */
class RoleRenameHandler : RenameHandler, TitledHandler {
    /** The entry in the platform's chooser when "Rename directory" is offered too. */
    override fun getActionTitle(): String = AnsibilityRefactoringBundle.message("rename.role.action")

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean = roleDir(dataContext) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        val dir = roleDir(dataContext) ?: return
        rename(project, dir, dataContext, editor)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        val dir = (elements.singleOrNull() as? PsiDirectory)?.virtualFile ?: roleDir(dataContext) ?: return
        rename(project, dir, dataContext, null)
    }

    private fun roleDir(dataContext: DataContext): VirtualFile? {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        if (DumbService.isDumb(project)) return null
        return runReadActionBlocking {
            val editor = CommonDataKeys.EDITOR.getData(dataContext)
            val file = CommonDataKeys.PSI_FILE.getData(dataContext)
            val dir = if (editor != null && file != null) {
                if (file.isValid) RoleRenamer.roleAt(file, editor.caretModel.offset) else null
            } else {
                (CommonDataKeys.PSI_ELEMENT.getData(dataContext) as? PsiDirectory)?.virtualFile
            }
            dir?.takeIf { RoleRenamer.isOwnRole(project, it) }
        }
    }

    private fun rename(project: Project, dir: VirtualFile, dataContext: DataContext, editor: Editor?) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val title = AnsibilityRefactoringBundle.message("rename.role.title")
        val old = dir.name
        val (newName, withVariables) = if (ApplicationManager.getApplication().isUnitTestMode) {
            (PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext) ?: return) to (WITH_VARIABLES.getData(dataContext) ?: true)
        } else {
            val prefixed = runReadActionBlocking { RoleRenamer.prefixedVariables(project, dir).size }
            val option = if (prefixed > 0) AnsibilityRefactoringBundle.message("rename.role.variables", old, prefixed) else null
            val dialog = RenameDialog(project, title, AnsibilityRefactoringBundle.message("rename.role.label", old), old, option) {
                RoleRenamer.invalidName(dir, it)
            }
            if (!dialog.showAndGet()) return
            dialog.newName to dialog.optionSelected
        }
        if (newName == old) return
        RoleRenamer.invalidName(dir, newName)?.let {
            CommonRefactoringUtil.showErrorHint(project, editor, it, title, null)
            return
        }
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<RenamePlan, RuntimeException> {
                ReadAction.nonBlocking(Callable { RoleRenamer.plan(project, dir, newName, withVariables) }).inSmartMode(project).executeSynchronously()
            },
            AnsibilityRefactoringBundle.message("rename.role.searching", old), true, project,
        )
        plan.apply(project, AnsibilityRefactoringBundle.message("rename.role.command", old, newName))
        report(project, AnsibilityRefactoringBundle.message("rename.done", old, newName, plan.edits.size, plan.files, plan.skipped))
    }

    companion object {
        /** Test seam: whether the role-prefixed variables are renamed too (the dialog's check box). */
        val WITH_VARIABLES: com.intellij.openapi.actionSystem.DataKey<Boolean> = com.intellij.openapi.actionSystem.DataKey.create("ansibility.rename.role.withVariables")
    }
}
