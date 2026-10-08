package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.RoleDirectories

/**
 * Where a file sits relative to Ansible content, decided from the VFS alone (plan A.9: the file type overrider has
 * no project and may use neither PSI nor indexes). Every answer is a pure function of the file's ancestors and their
 * children, so it is safe on any thread and never touches the file's own content.
 *
 * A file is inside Ansible content when one of these holds:
 * - an ancestor directory contains an `ansible.cfg` file (a PROJECT root, `repos/<team>/ansible`);
 * - it lies inside a role, `roles/<role>/<dir>/…`, and `<role>` is a role by [RoleDirectories.isRole] (this covers
 *   the `golden/` role library, which has no `ansible.cfg`, and molecule scenarios inside roles);
 * - an ancestor directory has a `roles/` directory holding at least one role (a role library root such as `golden/`,
 *   for files outside its roles).
 */
object AnsibleTemplatePaths {
    private const val ROLES = AnsibleLayout.ROLES
    private const val TEMPLATES = "templates"

    /** How many ancestors are inspected; deeper trees are never Ansible content in practice. */
    private const val MAX_DEPTH = 40

    /** Whether [file] lies inside Ansible content (see the class description). */
    fun isInsideAnsibleContent(file: VirtualFile): Boolean = ansibleBase(file) != null

    /**
     * The directory Ansible paths of [file] are relative to: the nearest ancestor with `ansible.cfg`, else the
     * directory that holds the `roles/` directory of the file's role (or of a role library), else null when the file is
     * not inside Ansible content.
     */
    fun ansibleBase(file: VirtualFile): VirtualFile? {
        var roleBase: VirtualFile? = null
        var libraryBase: VirtualFile? = null
        var child: VirtualFile = file
        var childOfChild: VirtualFile? = null
        var dir = file.parent
        var depth = 0
        while (dir != null && depth < MAX_DEPTH) {
            if (hasAnsibleCfg(dir)) return dir
            // dir/roles/<role>/<x>/…/file: `child` is the role directory, `childOfChild` the directory inside it
            if (roleBase == null && dir.name == ROLES && childOfChild != null && child.isDirectory && RoleDirectories.isRole(child)) {
                roleBase = dir.parent
            }
            if (roleBase == null && libraryBase == null) {
                val roles = dir.findChild(ROLES)
                if (roles != null && roles.isDirectory && roles != child && RoleDirectories.hasRoles(roles)) libraryBase = dir
            }
            childOfChild = child.takeIf { it != file }
            child = dir
            dir = dir.parent
            depth++
        }
        return roleBase ?: libraryBase
    }

    /**
     * Whether [file] is below the `templates/` directory of a role, `roles/<role>/templates/…`, the only place where
     * files without `.j2` are Ansible templates (plan A.5 `ROLE_TEMPLATE`). Path only; the role itself is not checked.
     */
    fun isUnderRoleTemplates(file: VirtualFile): Boolean {
        var previous: String? = null
        var beforePrevious: String? = null
        var dir = file.parent
        var depth = 0
        while (dir != null && depth < MAX_DEPTH) {
            val name = dir.name
            // walking up: …/roles/<role>/templates/… gives "templates", then <role>, then "roles"
            if (name == ROLES && beforePrevious == TEMPLATES && previous != null) return true
            beforePrevious = previous
            previous = name
            dir = dir.parent
            depth++
        }
        return false
    }

    /**
     * Whether [dir] is the `templates/` directory of a role, `roles/<role>/templates`: the files below it are
     * [isUnderRoleTemplates]. Path only, like that.
     */
    fun isRoleTemplatesDir(dir: VirtualFile): Boolean = dir.name == TEMPLATES && dir.parent?.parent?.name == ROLES

    /**
     * The path of [file] relative to its [ansibleBase] (`/`-separated, no leading slash), for the path patterns of the
     * outer-language rules; the absolute path without the leading `/` when it is outside Ansible content.
     */
    fun relativePath(file: VirtualFile): String {
        val base = ansibleBase(file)
        val relative = base?.let { VfsUtilCore.getRelativePath(file, it, '/') }
        return relative ?: file.path.trimStart('/')
    }

    private fun hasAnsibleCfg(dir: VirtualFile): Boolean {
        val cfg = dir.findChild(AnsibleLayout.ANSIBLE_CFG) ?: return false
        return !cfg.isDirectory
    }
}
