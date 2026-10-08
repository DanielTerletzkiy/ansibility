package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.context.MoleculeView
import org.jetbrains.yaml.psi.YAMLPsiElement

/**
 * The role-navigation area's `siteNavigation` (plan F1.8, X50): Ctrl+B / Ctrl+click on the values
 * [RefsSiteClassifier] reports, resolved within the root by [RefResolver]:
 * - task files, `*_from` files, template and copy sources, `vars_files`, imported playbooks and included templates
 *   open the file (every candidate of a dynamic `src`, in search order);
 * - a role opens its `tasks/main.yml` (the directory when it has none);
 * - `notify` goes to the handler (name, `listen` topic or `role : name`), nearest tier first; handlers found only in
 *   other roles are labelled "cross-role (play scope)" in the chooser;
 * - a `listen` topic goes to the tasks that notify it.
 *
 * Other sites (variables, modules, keywords) are left to their areas.
 */
class RefsNavigation : SiteNavigation {
    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> {
        if (!isOurs(site)) return emptyList()
        val project = file.project
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return emptyList()
        val occurrence = RefSites.at(file, site.range.startOffset, context)?.takeIf { it.range == site.range } ?: return emptyList()
        // Outside Molecule, while Molecule is hidden, no Molecule play's handlers or playbook dir (plan amendment R20, D153).
        val resolution = RefResolver(file, context, MoleculeView.of(project, virtualFile)).resolve(occurrence)
        return resolution.targets.mapNotNull { target ->
            ProgressManager.checkCanceled()
            element(project, target, context.root)
        }
    }

    private fun element(project: Project, target: RefTarget, root: AnsibleRoot): PsiElement? {
        val manager = PsiManager.getInstance(project)
        if (!target.file.isValid) return null
        if (target.file.isDirectory) return manager.findDirectory(target.file)
        val psi = manager.findFile(target.file) ?: return null
        val offset = target.offset ?: return psi
        val leaf = psi.findElementAt(offset) ?: return psi
        val anchor = PsiTreeUtil.getParentOfType(leaf, YAMLPsiElement::class.java, false) ?: leaf
        val line = StringUtil.offsetToLineNumber(psi.viewProvider.contents, offset) + 1
        val path = VfsUtilCore.getRelativePath(target.file, root.dir) ?: target.file.name
        val location = listOfNotNull(target.owner, target.label, "$path:$line").joinToString(" · ")
        return RefTargetElement(anchor, target.file, offset, target.name ?: target.file.name, location, target.label)
    }

    companion object {
        /** The site types this area classifies. */
        fun isOurs(site: AnsibleSite?): Boolean =
            site is AnsibleSite.TaskFileRef || site is AnsibleSite.TemplateRef || site is AnsibleSite.HandlerRef ||
                site is AnsibleSite.RoleRef || site is AnsibleSite.PlaybookFileRef
    }
}
