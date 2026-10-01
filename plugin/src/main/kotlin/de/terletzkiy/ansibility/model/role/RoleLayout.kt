package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.RoleDirectories

/**
 * The on-disk anatomy of a role directory (research roles.md §1), read from the VFS only.
 *
 * Listings are sorted: `main` first (the file ansible-core loads by default), then the other files by path.
 * Hidden files and editor backups are skipped, as ansible-core skips them.
 */
object RoleLayout {
    const val TASKS: String = "tasks"
    const val HANDLERS: String = "handlers"
    const val DEFAULTS: String = "defaults"
    const val VARS: String = "vars"
    const val META: String = "meta"
    const val TEMPLATES: String = "templates"
    const val FILES: String = "files"
    const val MAIN: String = "main"

    /**
     * Whether [dir] is a role (plan F1.1): it has `tasks/`, `meta/argument_specs.y(a)ml` or a `defaults/main`
     * file (or `defaults/main/` directory). The `role-state` ghosts (only `callback_plugins/__pycache__`) are not
     * roles. The rule is [RoleDirectories.isRole], shared with root detection and file classification.
     */
    fun isRole(dir: VirtualFile): Boolean = RoleDirectories.isRole(dir)

    /** `meta/argument_specs.yml` (or `.yaml`). */
    fun specFile(roleDir: VirtualFile): VirtualFile? =
        child(roleDir, META)?.children.orEmpty().firstOrNull { !it.isDirectory && AnsibleLayout.isArgumentSpecsName(it.name) }

    /** `meta/main.yml` (or `.yaml`, `.json`, no extension): dependencies and, without a spec file, `argument_specs`. */
    fun metaFile(roleDir: VirtualFile): VirtualFile? =
        child(roleDir, META)?.children.orEmpty().filter { !it.isDirectory && it.isMainVars() }.minByOrNull { mainRank(it.name) }

    /** The files of `defaults/` that `defaults_from` can name: `main` first, a `main/` directory's files next. */
    fun defaultsFiles(roleDir: VirtualFile): List<VirtualFile> = varsFilesIn(child(roleDir, DEFAULTS))

    /** The files of `vars/`, ordered like [defaultsFiles]. */
    fun varsFiles(roleDir: VirtualFile): List<VirtualFile> = varsFilesIn(child(roleDir, VARS))

    /** Every YAML file below `tasks/`, `main` first. */
    fun taskFiles(roleDir: VirtualFile): List<VirtualFile> = yamlFiles(child(roleDir, TASKS))

    /** Every YAML file below `handlers/` (`main.yaml` and `molecule.yml` included), `main` first. */
    fun handlerFiles(roleDir: VirtualFile): List<VirtualFile> = yamlFiles(child(roleDir, HANDLERS))

    fun templatesDir(roleDir: VirtualFile): VirtualFile? = child(roleDir, TEMPLATES)?.takeIf { it.isDirectory }

    fun filesDir(roleDir: VirtualFile): VirtualFile? = child(roleDir, FILES)?.takeIf { it.isDirectory }

    private fun varsFilesIn(dir: VirtualFile?): List<VirtualFile> {
        if (dir == null || !dir.isDirectory) return emptyList()
        val main = ArrayList<VirtualFile>()
        val others = ArrayList<VirtualFile>()
        for (child in dir.children.orEmpty()) {
            ProgressManager.checkCanceled()
            when {
                AnsibleLayout.isIgnoredVarsEntry(child.name) -> Unit
                child.isDirectory && child.name == MAIN -> main += collect(child) { AnsibleLayout.isVarsFileInDirectory(it.name) }
                child.isDirectory -> Unit
                child.isMainVars() -> main += child
                isVarsName(child.name) -> others += child
            }
        }
        val mainFiles = main.filter { it.parent == dir }.sortedBy { mainRank(it.name) } + main.filter { it.parent != dir }.sortedBy { it.path }
        return mainFiles + others.sortedBy { it.name }
    }

    private fun yamlFiles(dir: VirtualFile?): List<VirtualFile> {
        if (dir == null || !dir.isDirectory) return emptyList()
        val files = collect(dir) { AnsibleLayout.isYamlName(it.name) }
        val (main, others) = files.partition { it.parent == dir && AnsibleLayout.stem(it.name) == MAIN }
        return main.sortedBy { mainRank(it.name) } + others.sortedBy { it.path }
    }

    private fun collect(dir: VirtualFile, accept: (VirtualFile) -> Boolean): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()
        fun visit(current: VirtualFile) {
            for (child in current.children.orEmpty()) {
                ProgressManager.checkCanceled()
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) visit(child) else if (accept(child)) result += child
            }
        }
        visit(dir)
        return result
    }

    private fun child(dir: VirtualFile, name: String): VirtualFile? = dir.findChild(name)

    /** `main`, `main.yml`, `main.yaml` or `main.json` (a file). */
    private fun VirtualFile.isMainVars(): Boolean = !isDirectory && (name == MAIN || (AnsibleLayout.stem(name) == MAIN && isVarsName(name)))

    private fun isVarsName(name: String): Boolean =
        !AnsibleLayout.isIgnoredVarsEntry(name) && name.substringAfterLast('.', "").lowercase() in AnsibleLayout.VARS_EXTENSIONS

    /** ansible-core's search order for the default file: `main.yml`, `main.yaml`, `main.json`, `main`. */
    private fun mainRank(name: String): Int = when (name) {
        "main.yml" -> 0
        "main.yaml" -> 1
        "main.json" -> 2
        MAIN -> 3
        else -> 4
    }
}
