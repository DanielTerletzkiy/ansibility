package de.terletzkiy.ansibility.workspace.search

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.SearchScopeProvider
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle.message

/**
 * Two entries of the Find in Files / Find Usages scope choosers (plan amendment R9, F9.9): "Ansible root: <root>"
 * for the current file's root (its family, nested playbook roots included) and "All Ansible roots" (every
 * non-detached root directory). Never offered in the inspection profile.
 */
class AnsibleSearchScopes : SearchScopeProvider {
    override fun getDisplayName(): String = message("search.scopes.group")

    override fun getGeneralSearchScopes(project: Project, dataContext: DataContext): List<SearchScope> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val roots = workspace.roots().filter { !it.detached }
        if (roots.isEmpty()) return emptyList()
        val scopes = ArrayList<SearchScope>()
        val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
        file?.let(workspace::rootFor)?.takeIf { !it.detached }?.let { root ->
            scopes += named(RootFamily.of(project, root, workspace).scope, message("search.scope.root", root.displayName))
        }
        val all = GlobalSearchScopesCore.directoriesScope(project, true, *roots.map { it.dir }.toTypedArray())
        scopes += named(all, message("search.scope.all"))
        return scopes
    }

    private fun named(scope: GlobalSearchScope, name: String): GlobalSearchScope = object : DelegatingGlobalSearchScope(scope) {
        override fun getDisplayName(): String = name
    }
}
