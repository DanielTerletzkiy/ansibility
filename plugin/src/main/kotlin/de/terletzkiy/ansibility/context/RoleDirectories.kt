package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.VirtualFile

/**
 * The one rule that decides whether a directory under a roles dir is a role (plan F1.1). Root detection
 * ([RootDetector]), file classification ([AnsibleFileClassifier]) and the role registry
 * (`model.role.RoleRegistryImpl`) all use it, so `contextOf` never reports a role directory that the registry does
 * not list.
 *
 * A role has at least one of:
 * - a `tasks/` directory;
 * - `meta/argument_specs.yml` (or `.yaml`);
 * - a `defaults/main` file (`main.yml`, `main.yaml`, `main.json` or plain `main`) or a `defaults/main/` directory.
 *
 * Everything else is a ghost: the `role-state` copies of the target repo hold only a `.pyc` file in
 * `callback_plugins/__pycache__` and are not roles. Reads the VFS only.
 */
object RoleDirectories {
    private const val TASKS = "tasks"
    private const val META = "meta"
    private const val DEFAULTS = "defaults"
    private const val MAIN = "main"

    /** Whether [dir] is a role directory. */
    fun isRole(dir: VirtualFile): Boolean {
        if (!dir.isValid || !dir.isDirectory) return false
        if (dir.childDirectory(TASKS) != null) return true
        if (dir.childDirectory(META)?.children.orEmpty().any { !it.isDirectory && AnsibleLayout.isArgumentSpecsName(it.name) }) return true
        val defaults = dir.childDirectory(DEFAULTS) ?: return false
        return defaults.children.orEmpty().any(::isDefaultsMain)
    }

    /** Whether [rolesDir] holds at least one role. */
    fun hasRoles(rolesDir: VirtualFile): Boolean = rolesDir.isValid && rolesDir.children.orEmpty().any(::isRole)

    /** `defaults/main/` (a directory), or a `main` file with no extension or a vars extension. */
    private fun isDefaultsMain(child: VirtualFile): Boolean {
        if (child.isDirectory) return child.name == MAIN
        val name = child.name
        if (name == MAIN) return true
        return AnsibleLayout.stem(name) == MAIN && name.substringAfterLast('.', "").lowercase() in AnsibleLayout.VARS_EXTENSIONS
    }
}
