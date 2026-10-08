package de.terletzkiy.ansibility.workspace.search

import com.intellij.icons.AllIcons
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.host.symbols.HostSymbols
import de.terletzkiy.ansibility.dispatch.SitePresentation
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle.message
import javax.swing.Icon

/**
 * Roles, variables, inventory groups and hosts in Search Everywhere › Symbols and Navigate › Symbol (plan amendment
 * R9, F9.9). The tab's own scope chooser filters them through [FindSymbolParameters.getSearchScope]; every item
 * names its root (`postfix_relayhost · falcon · group_vars/all/vars.yml:156`). Runs in smart mode only.
 *
 * Variables follow "Show Molecule in navigation and search" (plan amendment R20, D153): the search starts in no file,
 * so with the setting off neither names nor items come from Molecule scenarios ([MoleculeView.of] with no origin).
 */
class AnsibleSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        if (DumbService.isDumb(project)) return
        val seen = HashSet<String>()
        val view = MoleculeView.of(project, null)
        for (root in roots(project)) {
            ProgressManager.checkCanceled()
            if (!scope.contains(root.dir) && root.dir.children.none { scope.contains(it) }) continue
            for (name in names(project, root, view)) if (seen.add(name) && !processor.process(name)) return
        }
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val project = parameters.project
        if (DumbService.isDumb(project)) return
        val scope = parameters.searchScope
        for (item in items(project, name)) {
            ProgressManager.checkCanceled()
            if (!scope.contains(item.location.file)) continue
            if (!processor.process(item)) return
        }
    }

    private fun roots(project: Project): List<AnsibleRoot> = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }

    private fun names(project: Project, root: AnsibleRoot, view: MoleculeView): Set<String> {
        val names = LinkedHashSet<String>()
        RoleRegistry.getInstance(project).roles(root).mapTo(names) { it.name }
        names += VarViews.allNames(project, root, view)
        if (root.kind != RootKind.ROLE_LIBRARY) {
            for (env in HostSymbols.environments(project, root)) {
                names += env.graph.groups.keys
                names += env.graph.hosts.keys
            }
        }
        return names
    }

    private fun items(project: Project, name: String): List<AnsibleSymbolItem> {
        val items = ArrayList<AnsibleSymbolItem>()
        val view = MoleculeView.of(project, null)
        for (root in roots(project)) {
            RoleRegistry.getInstance(project).roles(root).filter { it.name == name }.forEach { role ->
                val entry = RoleLayout.specFile(role.dir) ?: RoleLayout.taskFiles(role.dir).firstOrNull { it.nameWithoutExtension == "main" }
                items += AnsibleSymbolItem(project, name, SourceLocation(entry ?: role.dir, 0), root, message("symbol.kind.role"), AllIcons.Nodes.Module)
            }
            for (definition in VarViews.symbol(project, root, name, view).definitions) {
                if (definition.kind == VarDefKind.JINJA_LOCAL || definition.kind == VarDefKind.LOOP_VAR || definition.kind == VarDefKind.INDEX_VAR) continue
                items += AnsibleSymbolItem(project, name, definition.location, root, SitePresentation.definitionKindName(definition.kind), AllIcons.Nodes.Variable)
            }
            if (root.kind == RootKind.ROLE_LIBRARY) continue
            for (env in HostSymbols.environments(project, root)) {
                HostSymbols.groupLocations(env, name).firstOrNull()?.let {
                    items += AnsibleSymbolItem(project, name, it, root, message("symbol.kind.group", env.name), AllIcons.Nodes.Folder)
                }
                HostSymbols.hostLocations(env, name).firstOrNull()?.let {
                    items += AnsibleSymbolItem(project, name, it, root, message("symbol.kind.host", env.name), AllIcons.Nodes.Deploy)
                }
            }
        }
        return items.distinctBy { it.location to it.kind }
    }
}

/** One Ansible symbol in Search Everywhere: its [name], where it is written, its root and its kind. */
internal class AnsibleSymbolItem(
    private val project: Project,
    private val symbolName: String,
    val location: SourceLocation,
    private val root: AnsibleRoot,
    val kind: String,
    private val icon: Icon,
) : NavigationItem {
    override fun getName(): String = symbolName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = symbolName

        override fun getLocationString(): String = "$kind · ${root.displayName} · ${relative(location.file)}${line()}"

        override fun getIcon(unused: Boolean): Icon = icon
    }

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(project, location.file, location.offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = location.file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    private fun relative(file: VirtualFile): String = VfsUtilCore.getRelativePath(file, root.dir, '/') ?: file.name

    private fun line(): String {
        if (location.file.isDirectory || location.offset <= 0) return ""
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(location.file) ?: return ""
        return ":" + (document.getLineNumber(location.offset.coerceAtMost(document.textLength)) + 1)
    }

    override fun toString(): String = "AnsibleSymbolItem($symbolName, $kind)"
}
