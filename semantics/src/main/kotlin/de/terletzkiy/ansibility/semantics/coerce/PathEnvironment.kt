package de.terletzkiy.ansibility.semantics.coerce

/**
 * The process environment `check_type_path` expands against (`os.path.expanduser(os.path.expandvars(v))`).
 *
 * Role argument validation runs in the `validate_argument_spec` action on the controller, so ansible-core uses
 * the controller's environment, which the IDE does not know. [EMPTY] therefore leaves `$VAR` and `~` untouched,
 * exactly as Python does for unknown variables and an unknown home directory.
 */
data class PathEnvironment(
    /** `os.environ`. */
    val variables: Map<String, String> = emptyMap(),
    /** The current user's home (`$HOME`, else the password database); null when unknown. */
    val home: String? = variables["HOME"],
    /** Other users' home directories for `~name`; unknown users leave the path unchanged. */
    val userHomes: Map<String, String> = emptyMap(),
) {
    /** `posixpath.expandvars`: `$name` / `${name}` with ASCII word names; unknown variables stay as written. */
    fun expandVars(path: String): String {
        if ('$' !in path) return path
        return VARIABLE.replace(path) { match ->
            var name = match.groupValues[1]
            if (name.startsWith("{")) {
                if (!name.endsWith("}")) return@replace match.value
                name = name.substring(1, name.length - 1)
            }
            variables[name] ?: match.value
        }
    }

    /** `posixpath.expanduser`: a leading `~` or `~name` up to the first `/`. */
    fun expandUser(path: String): String {
        if (!path.startsWith("~")) return path
        val slash = path.indexOf('/', 1).let { if (it < 0) path.length else it }
        val userHome = if (slash == 1) home else userHomes[path.substring(1, slash)]
        if (userHome == null) return path
        return (userHome.trimEnd('/') + path.substring(slash)).ifEmpty { "/" }
    }

    companion object {
        private val VARIABLE = Regex("\\$([A-Za-z0-9_]+|\\{[^}]*\\}?)")

        /** No variables and no home directory: paths are returned as written. */
        val EMPTY: PathEnvironment = PathEnvironment()
    }
}
