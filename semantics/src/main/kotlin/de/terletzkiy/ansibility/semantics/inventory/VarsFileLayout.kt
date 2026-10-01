package de.terletzkiy.ansibility.semantics.inventory

/** One directory entry as the vars plugin sees it. */
data class DirEntry(val name: String, val isDirectory: Boolean)

/**
 * Read access to a directory tree, rooted at an inventory directory or a playbook directory.
 * Paths are relative with `/` separators; `""` is the root itself.
 */
fun interface DirectoryLister {
    /** The entries of the directory at [relativePath], or null when it does not exist or is not a directory. */
    fun list(relativePath: String): List<DirEntry>?
}

/**
 * Port of the file discovery of ansible-core's `host_group_vars` vars plugin (`DataLoader.find_vars_files` and
 * `_get_dir_vars_files`): which `group_vars`/`host_vars` files load for one group or host, in load order.
 *
 * - candidates are `<name>` (file or directory), `<name>.yml`, `<name>.yaml`, `<name>.json`; the **first** that
 *   exists wins, so a `web/` directory hides `web.yml`;
 * - a directory is read recursively in sorted name order, skipping hidden entries (leading `.`) and backups
 *   (trailing `~`), taking files with no extension or a known one, and descending only into extension-less
 *   subdirectories;
 * - names starting with `/` (chroot-style host names) are skipped.
 */
object VarsFileLayout {
    /** `YAML_FILENAME_EXTENSIONS`. */
    val DEFAULT_EXTENSIONS: List<String> = listOf(".yml", ".yaml", ".json")

    const val GROUP_VARS = "group_vars"
    const val HOST_VARS = "host_vars"

    /**
     * The files to load for [entityName] from `<base>/<subdir>` ([subdir] is [GROUP_VARS] or [HOST_VARS]),
     * as paths relative to the base directory of [base], in load order.
     */
    fun findVarsFiles(
        base: DirectoryLister,
        subdir: String,
        entityName: String,
        extensions: List<String> = DEFAULT_EXTENSIONS,
    ): List<String> {
        if (entityName.isEmpty() || entityName.startsWith("/")) return emptyList()
        base.list(subdir) ?: return emptyList()
        val found = ArrayList<String>()
        for (ext in listOf("") + extensions) {
            val candidate = "$subdir/" + when {
                '.' in ext -> entityName + ext
                ext.isNotEmpty() -> "$entityName.$ext"
                else -> entityName
            }
            val entry = lookup(base, candidate) ?: continue
            if (entry.isDirectory) found += dirVarsFiles(base, candidate, extensions) else found += candidate
            break
        }
        return found
    }

    private fun lookup(base: DirectoryLister, path: String): DirEntry? {
        val slash = path.lastIndexOf('/')
        val parent = if (slash < 0) "" else path.substring(0, slash)
        val name = path.substring(slash + 1)
        return base.list(parent)?.firstOrNull { it.name == name }
    }

    private fun dirVarsFiles(base: DirectoryLister, path: String, extensions: List<String>): List<String> {
        val found = ArrayList<String>()
        val entries = base.list(path).orEmpty().sortedWith(compareBy(Py.STRING_ORDER) { it.name })
        for (entry in entries) {
            if (entry.name.startsWith(".") || entry.name.endsWith("~")) continue
            val ext = Py.extension(entry.name)
            val full = "$path/${entry.name}"
            if (entry.isDirectory) {
                if (ext.isEmpty()) found += dirVarsFiles(base, full, extensions)
            } else if (ext.isEmpty() || ext in extensions) {
                found += full
            }
        }
        return found
    }
}
