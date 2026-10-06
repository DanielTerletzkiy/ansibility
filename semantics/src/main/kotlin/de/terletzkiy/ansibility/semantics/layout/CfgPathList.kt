package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.inventory.Py
import de.terletzkiy.ansibility.semantics.vault.PyPaths

/** What makes a cfg path depend on the machine that runs ansible-core (plan amendment R10, D53). */
enum class MachineDependence {
    /** A leading `~` or `~user`: the home directory. */
    HOME,

    /** `$NAME` or `${NAME}`: the environment of the process that reads the cfg. */
    ENVIRONMENT,

    /** The resolved path lies outside the project directory. */
    OUTSIDE_PROJECT,
}

/** Something about a cfg path entry that its author probably did not mean (input of ANS-L002 and ANS-L003). */
enum class EntryHint {
    /**
     * The entry resolves to the cfg's own directory (an empty entry, a trailing comma, `.`, `{{CWD}}`). As an
     * inventory source that makes the whole tree a directory source (ANS-L002).
     */
    CFG_DIR,

    /**
     * Looks like a host name in a comma-separated `inventory` value (`web1,web2`, `localhost,`). Host lists only work
     * with `-i`; in a cfg every entry is a path. A hint only: an existing path of that name is a path.
     */
    HOST_LIST,

    /** A `#` at the start or after whitespace: no comment in an ini value but part of the path ([CfgPath.commentStart]). */
    HASH_COMMENT,

    /** A leading or trailing quote: ansible-core keeps quotes as part of the path. */
    QUOTED,

    /** Leading or trailing whitespace, which a colon-separated pathspec keeps as part of the path. */
    WHITESPACE,

    /** A continuation line without a separator: two lines joined into one path. */
    LINE_BREAK,
}

/**
 * Where cfg paths resolve and whether machine-dependent entries are followed (D53).
 *
 * The rules never read the process environment, the home directory or the file system: `~` and `$VAR` expand only
 * against [follow], and only when the caller passes it (the personal "follow" switch with the environment it chose).
 */
class CfgPathContext(
    /** The cfg file's directory, absolute and `/`-separated. Relative entries resolve against it, and so does `{{CWD}}`. */
    cfgDir: String,
    /** The project directory; when set, an entry that resolves outside it is [MachineDependence.OUTSIDE_PROJECT]. */
    projectDir: String? = null,
    /** The follow switch: null means entries with `~`, `$VAR` or outside [projectDir] are not followed. */
    val follow: PathEnvironment? = null,
) {
    val cfgDir: String = PyPaths.normPath(cfgDir)
    val projectDir: String? = projectDir?.let { PyPaths.normPath(it) }
}

/**
 * One entry of a cfg path setting, resolved the way ansible-core's `resolve_path` resolves it: `{{CWD}}` replaced
 * (by the cfg's directory, where a run that reads this cfg starts), `$VAR` expanded, then `~`, then joined with the
 * cfg directory when relative, then normalised (`os.path.normpath`, symlinks not followed).
 */
data class CfgPath(
    /** The entry as split from the value; `inventory` entries are stripped, pathspec entries are not. */
    val text: String,
    /** The absolute path ansible-core opens; null when it depends on `~` or `$VAR` and is not followed. */
    val path: String?,
    /** Every machine-dependent input needed to resolve or safely read this entry. */
    val dependsOn: Set<MachineDependence>,
    /** True when the plugin reads [path]: it is known, and machine-independent or followed by choice. */
    val isFollowed: Boolean,
    val hints: Set<EntryHint>,
    /** The index in [text] of the `#` behind [EntryHint.HASH_COMMENT], else -1. */
    val commentStart: Int = -1,
)

/** `COLLECTIONS_PATHS` together with the `[defaults]` key it was read from. */
data class CollectionsPath(val key: String, val entries: List<CfgPath>)

/**
 * The path settings of `[defaults]` as ansible-core resolves them (plan amendment R10, F10.3; research
 * `flat-layouts.md` §3 and §8), measured on 2.18.8 and 2.21.4:
 * - `inventory` (`DEFAULT_HOST_LIST`, a pathlist): split at `,` only, each entry stripped; an empty entry, so also an
 *   empty value or a trailing comma, is the cfg directory itself; quotes and `#` are part of the path; a host list is
 *   two paths;
 * - `roles_path`, `collections_path` (pathspecs): split at `:` only and **not** stripped; an empty entry is the cfg
 *   directory; the legacy `collections_paths` counts only below 2.19, and `collections_path` wins when both are set;
 * - `playbook_dir` (a path): one entry; an empty value is the cfg directory.
 *
 * Values are the raw ini values as [CfgSyntax] reads them (inline `;` comments removed, continuation lines joined).
 */
object CfgPathList {
    const val SECTION: String = "defaults"
    const val INVENTORY: String = "inventory"
    const val ROLES_PATH: String = "roles_path"
    const val COLLECTIONS_PATH: String = "collections_path"

    /** The pre-2.10 plural key: read with a deprecation warning by 2.18.8, ignored by 2.21.4 (research §8). */
    const val LEGACY_COLLECTIONS_PATHS: String = "collections_paths"
    const val PLAYBOOK_DIR: String = "playbook_dir"

    /** The deprecation in 2.18 names 2.19 as the version that removes [LEGACY_COLLECTIONS_PATHS]. */
    val LEGACY_COLLECTIONS_PATHS_UNTIL: CoreVersion = CoreVersion(2, 19)

    private const val CWD_MAGIC = "{{CWD}}"
    private val VARIABLE = Regex("""\$([A-Za-z0-9_]+|\{[^}]*\})""")
    private val HOST_LIKE = Regex("""[A-Za-z0-9_\-.\[\]:]+""")

    /** Extensions of inventory files and scripts: an entry with one of them is a missing file, not a host name. */
    private val FILE_EXTENSIONS = setOf(".ini", ".yml", ".yaml", ".json", ".toml", ".cfg", ".py", ".sh", ".txt", ".md")

    /** `[defaults] inventory`: one entry per source, in `-i` order. */
    fun inventory(value: String, context: CfgPathContext): List<CfgPath> {
        val list = value.contains(',')
        return value.split(',').map { entry(PyStrings.strip(it), context, hostListAllowed = list) }
    }

    /** `[defaults] roles_path`: searched after the implicit `<playbook dir>/roles` and before the playbook dir itself. */
    fun rolesPath(value: String, context: CfgPathContext): List<CfgPath> = pathspec(value, context)

    /**
     * `COLLECTIONS_PATHS` from the values of `collections_path` ([singular]) and `collections_paths` ([plural]) for
     * target [core]: the singular key wins; the plural one counts below [LEGACY_COLLECTIONS_PATHS_UNTIL]. Null when
     * neither applies (ansible-core's default, which the rules do not model).
     */
    fun collectionsPath(singular: String?, plural: String?, core: CoreVersion, context: CfgPathContext): CollectionsPath? =
        when {
            singular != null -> CollectionsPath(COLLECTIONS_PATH, pathspec(singular, context))
            plural != null && core < LEGACY_COLLECTIONS_PATHS_UNTIL ->
                CollectionsPath(LEGACY_COLLECTIONS_PATHS, pathspec(plural, context))
            else -> null
        }

    /** `[defaults] playbook_dir`: what `--playbook-dir` is for views without a play. */
    fun playbookDir(value: String, context: CfgPathContext): CfgPath = entry(value, context, hostListAllowed = false)

    /** A colon-separated pathspec: split at `:` only, entries kept as written. */
    fun pathspec(value: String, context: CfgPathContext): List<CfgPath> =
        value.split(':').map { entry(it, context, hostListAllowed = false) }

    private fun entry(text: String, context: CfgPathContext, hostListAllowed: Boolean): CfgPath {
        val substituted = text.replace(CWD_MAGIC, context.cfgDir)
        val dependsOn = LinkedHashSet<MachineDependence>()
        if (VARIABLE.containsMatchIn(substituted)) dependsOn += MachineDependence.ENVIRONMENT
        val follow = context.follow
        val path = if (follow == null) {
            if (substituted.startsWith('~')) dependsOn += MachineDependence.HOME
            if (dependsOn.isEmpty()) resolve(substituted, context.cfgDir) else null
        } else {
            val vars = follow.expandVars(substituted)
            if (vars.startsWith('~')) dependsOn += MachineDependence.HOME
            resolve(follow.expandUser(vars), context.cfgDir)
        }
        val project = context.projectDir
        if (path != null && project != null && !isInside(path, project)) dependsOn += MachineDependence.OUTSIDE_PROJECT
        val commentStart = commentStart(text)
        return CfgPath(
            text = text,
            path = path,
            dependsOn = dependsOn,
            isFollowed = path != null && (dependsOn.isEmpty() || follow != null),
            hints = hints(text, path, context, hostListAllowed, commentStart),
            commentStart = commentStart,
        )
    }

    private fun resolve(expanded: String, cfgDir: String): String =
        PyPaths.normPath(if (expanded.startsWith('/')) expanded else "${cfgDir.trimEnd('/')}/$expanded")

    private fun isInside(path: String, dir: String): Boolean =
        dir == "/" || path == dir || path.startsWith("${dir.trimEnd('/')}/")

    private fun hints(
        text: String,
        path: String?,
        context: CfgPathContext,
        hostListAllowed: Boolean,
        commentStart: Int,
    ): Set<EntryHint> {
        val hints = LinkedHashSet<EntryHint>()
        if (path == context.cfgDir) hints += EntryHint.CFG_DIR
        if (hostListAllowed && looksLikeHost(text)) hints += EntryHint.HOST_LIST
        if (commentStart >= 0) hints += EntryHint.HASH_COMMENT
        if (text.isNotEmpty() && (text.first().isQuote() || text.last().isQuote())) hints += EntryHint.QUOTED
        if (text.isNotEmpty() && (PyStrings.isSpace(text.first()) || PyStrings.isSpace(text.last()))) hints += EntryHint.WHITESPACE
        if ('\n' in text) hints += EntryHint.LINE_BREAK
        return hints
    }

    private fun looksLikeHost(text: String): Boolean =
        HOST_LIKE.matches(text) && (text.first().isLetterOrDigit() || text.first() == '[') &&
            Py.extension(text).lowercase() !in FILE_EXTENSIONS

    private fun commentStart(text: String): Int {
        var i = text.indexOf('#')
        while (i >= 0) {
            if (i == 0 || PyStrings.isSpace(text[i - 1])) return i
            i = text.indexOf('#', i + 1)
        }
        return -1
    }

    private fun Char.isQuote() = this == '"' || this == '\''
}
