package de.terletzkiy.ansibility.navigation

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.navigation.AnsibilityNavigationBundle.message

/**
 * Go to Related (Ctrl+Alt+Home, plan X53): from any file of a role to the role's spec, meta, defaults, vars, tasks,
 * handlers and molecule scenarios, grouped by kind; from a template also to the tasks that render it.
 */
class RoleRelatedItems : GotoRelatedProvider() {
    override fun getItems(context: PsiElement): List<GotoRelatedItem> {
        val psiFile = context.containingFile ?: return emptyList()
        val file = psiFile.originalFile.virtualFile ?: return emptyList()
        val project = psiFile.project
        val fileContext = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return emptyList()
        val roleDir = fileContext.roleDir ?: return emptyList()
        val psi = PsiManager.getInstance(project)
        val items = ArrayList<GotoRelatedItem>()
        fun add(target: VirtualFile?, group: String) {
            if (target == null || target == file || !target.isValid || target.isDirectory) return
            psi.findFile(target)?.let { items += GotoRelatedItem(it, group) }
        }
        add(RoleLayout.specFile(roleDir), message("related.spec"))
        add(RoleLayout.metaFile(roleDir), message("related.meta"))
        RoleLayout.defaultsFiles(roleDir).forEach { add(it, message("related.defaults")) }
        RoleLayout.varsFiles(roleDir).forEach { add(it, message("related.vars")) }
        RoleLayout.taskFiles(roleDir).forEach { add(it, message("related.tasks")) }
        RoleLayout.handlerFiles(roleDir).forEach { add(it, message("related.handlers")) }
        roleDir.findChild(MOLECULE)?.children.orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { scenario ->
            add(scenario.findChild("molecule.yml") ?: scenario.findChild("molecule.yaml"), message("related.molecule", scenario.name))
        }
        if (fileContext.kind == FileKind.ROLE_TEMPLATE && !DumbService.isDumb(project)) {
            for (render in TemplateContextService.getInstance(project).renderContexts(file)) {
                val element = psi.findFile(render.taskSite.file)?.findElementAt(render.taskSite.offset) ?: continue
                items += GotoRelatedItem(element, message("related.rendered.by"))
            }
        }
        if (fileContext.kind == FileKind.ROLE_TASKS) {
            RoleLayout.templatesDir(roleDir)?.let { templates ->
                VfsUtilCore.iterateChildrenRecursively(templates, null) { child ->
                    if (!child.isDirectory) add(child, message("related.templates"))
                    items.size < MAX_ITEMS
                }
            }
        }
        return items
    }

    private companion object {
        const val MOLECULE = "molecule"
        const val MAX_ITEMS = 200
    }
}
