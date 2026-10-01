package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.AnsibleLayout
import org.jetbrains.yaml.YAMLLanguage
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-root facts of the navigation area that several files share (plan A.9 cross-file caches): the template-name
 * slots of [TemplateNames] and whether a root's role search path is closed ([isClosedRoleSearchPath]).
 *
 * One generation is kept until the Ansible structure (which includes `ansible.cfg`) or any YAML file changes; the
 * maps are keyed by root directory, so roots of a detached worktree never share entries with the main checkout.
 */
@Service(Service.Level.PROJECT)
internal class RefCaches(private val project: Project) {
    private class Generation {
        val slots = ConcurrentHashMap<VirtualFile, List<TemplateNameSlot>>()
        val closedSearchPath = ConcurrentHashMap<VirtualFile, Boolean>()
    }

    private val generation: CachedValue<Generation> = CachedValuesManager.getManager(project).createCachedValue(
        {
            CachedValueProvider.Result.create(
                Generation(),
                AnsibleWorkspace.getInstance(project).structureTracker,
                PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
            )
        },
        false,
    )

    /** The template-name slots of [root], computed by [compute] on first use in this generation. */
    fun slots(root: AnsibleRoot, compute: () -> List<TemplateNameSlot>): List<TemplateNameSlot> =
        generation.value.slots.getOrPut(root.dir, compute)

    /**
     * True when [root]'s `ansible.cfg` (the parent's for a NESTED_PLAYBOOK root) sets `roles_path` to directories
     * of the project only. ansible-core then searches exactly `<playbook dir>/roles`, those directories, the
     * dependent role's directory and the playbook dir, so a role name found in none of them is a certain failure.
     * Without `roles_path` (the default includes `~/.ansible/roles`, `/usr/share/ansible/roles` and
     * `/etc/ansible/roles`, which may hold installed roles the project cannot see), or with entries under `~` or
     * `$VAR`, the search path is open.
     */
    fun isClosedRoleSearchPath(root: AnsibleRoot): Boolean =
        generation.value.closedSearchPath.getOrPut(root.dir) { computeClosed(root) }

    private fun computeClosed(root: AnsibleRoot): Boolean {
        val cfgDir = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir else root.dir
        val cfgFile = cfgDir?.findChild(AnsibleLayout.ANSIBLE_CFG)?.takeIf { !it.isDirectory } ?: return false
        val entries = try {
            AnsibleCfg.parse(VfsUtilCore.loadText(cfgFile)).rolesPath
        } catch (_: IOException) {
            emptyList()
        }
        if (entries.isEmpty()) return false
        return entries.all { entry ->
            !entry.startsWith("~") && !entry.contains('$') &&
                (if (entry.startsWith("/")) cfgFile.fileSystem.findFileByPath(entry) else cfgDir.findFileByRelativePath(entry.removePrefix("./")))
                    ?.isDirectory == true
        }
    }

    companion object {
        fun getInstance(project: Project): RefCaches = project.service()
    }
}
