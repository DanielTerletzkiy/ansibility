package de.terletzkiy.ansibility.settings

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import java.nio.file.Path

/** Where a root's collection versions come from (plan A.11). */
enum class CollectionsSource {
    /** `requirements.yml` pins (the versions the Docker images install). */
    PINS,

    /** The collections installed locally (`MANIFEST.json` versions). */
    LOCAL,
}

/** Severity of ANS-M001 when the module docs come from another ansible-core than the target (plan A.6). */
enum class DocsMismatchSeverity { WARNING, OFF }

/** `ansible.cfg` `hash_behaviour`. */
enum class HashBehaviour(val cfgValue: String) { REPLACE("replace"), MERGE("merge") }

/** `ansible.cfg` `playbook_vars_root`. */
enum class PlaybookVarsRoot(val cfgValue: String) { TOP("top"), BOTTOM("bottom"), ALL("all") }

/**
 * Overrides for `ansible.cfg` values that the repos only ever set through environment variables (plan
 * "Coexistence & settings"). Null means "as `ansible.cfg` or ansible-core's default says".
 */
data class AnsibleCfgOverrides(
    val hashBehaviour: HashBehaviour? = null,
    /** `precedence`: the order of the inventory/play var sources, e.g. `all_inventory, groups_inventory, …`. */
    val precedence: List<String>? = null,
    val playbookVarsRoot: PlaybookVarsRoot? = null,
    val jinja2Native: Boolean? = null,
    val privateRoleVars: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = this == NONE

    companion object {
        val NONE = AnsibleCfgOverrides()

        /** The valid entries of `precedence` (ansible-core `VARIABLE_PRECEDENCE`). */
        val PRECEDENCE_ENTRIES: List<String> = listOf(
            "all_inventory", "groups_inventory", "all_plugins_inventory", "all_plugins_play",
            "groups_plugins_inventory", "groups_plugins_play",
        )

        /** Parses a comma- or whitespace-separated `precedence` list; null for a blank text. */
        fun parsePrecedence(text: String?): List<String>? =
            text?.split(',', ' ', '\n', '\t')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Strictness and runtime settings of one Ansible root (plan A.6, D4–D7). Stored only when they differ from the
 * defaults.
 */
data class RootSettings(
    /**
     * The target ansible-core as typed (`2.19`, `2.18.8`), or null for Auto: detected from the root's Dockerfile pins,
     * then the majority pin (D10). A NESTED_PLAYBOOK root on Auto follows its parent root's explicit target.
     */
    val targetCore: String? = null,
    val collectionsSource: CollectionsSource = CollectionsSource.PINS,
    val cfgOverrides: AnsibleCfgOverrides = AnsibleCfgOverrides.NONE,
    /** The strictness preset; "Documented types" is the default (D4). */
    val preset: Preset = Preset.DOCUMENTED_TYPES,
    /** D5: highlight scalar coercions on module options (`owner: 1000` for a `str` option). Off by default. */
    val moduleOptionCoercions: Boolean = false,
    /** D6: demote red findings to yellow when no play applies the declaring role. Off by default. */
    val requireReachablePlayForRed: Boolean = false,
    /** D7: 🟣 CLAUDE's certain-failure checks (ANS-R001, ANS-S001, ANS-T012b) are red. On by default. */
    val redForClaudeCertainFailures: Boolean = true,
    /** F8.12: unguarded optional variables without a runtime default are ERROR even when every host sets them. */
    val unguardedOptionalAlwaysError: Boolean = false,
    /** ANS-M001 when the docs version differs from the target: a warning, or nothing. */
    val unknownModuleOptionWhenDocsDiffer: DocsMismatchSeverity = DocsMismatchSeverity.WARNING,
    /** How many `{{ name }}` definitions a type chain may follow (plan A.5 `JinjaTypeEvaluator`). */
    val chainDepth: Int = DEFAULT_CHAIN_DEPTH,
) {
    /** [targetCore] parsed; null for Auto or an unparsable text. */
    val targetCoreVersion: CoreVersion?
        get() = targetCore?.let(CoreVersion::parse)

    /** [chainDepth] clamped to the supported range. */
    val effectiveChainDepth: Int
        get() = chainDepth.coerceIn(CHAIN_DEPTH_RANGE)

    companion object {
        const val DEFAULT_CHAIN_DEPTH: Int = 8
        val CHAIN_DEPTH_RANGE: IntRange = 1..32
        val DEFAULT = RootSettings()
    }
}

/** Project-wide path rules (plan "Coexistence & settings", Paths). */
data class PathSettings(
    /** 🟣 CLAUDE X01: git worktrees and `.claude/worktrees` copies are detached roots. */
    val detachedRule: Boolean = true,
    /** Globs relative to the project directory that Ansibility ignores at query time ([PathGlob] syntax). */
    val extraIgnoredPaths: List<String> = DEFAULT_IGNORED_PATHS,
    /** Exclude the task, handler and playbook files Ansibility classifies from SchemaStore validation. */
    val schemaStoreExclusion: Boolean = true,
) {
    private val globs: List<PathGlob> by lazy(LazyThreadSafetyMode.PUBLICATION) { extraIgnoredPaths.mapNotNull(PathGlob::compile) }

    /** Whether [relativePath] (relative to the project directory, `/`-separated) matches an ignored-path glob. */
    fun isIgnored(relativePath: String): Boolean = globs.any { it.matches(relativePath) }

    companion object {
        val DEFAULT_IGNORED_PATHS: List<String> = listOf("**/.ansible/**", "patches/**")
    }
}

/**
 * Molecule in the IDE (plan amendment R20, D150–D152). They replace the old "Molecule support" switch: code insight
 * inside Molecule files is always on; an ignored-path glob for `molecule` folders makes Ansibility skip them altogether.
 */
data class MoleculeSettings(
    /**
     * Offer Molecule runs: the ▶ of scenario files, Run Molecule Test(s) in the Project view and the tool window, the
     * Roles tab's test markers, bulk runs. Saved Molecule run configurations run either way (D152).
     */
    val runTests: Boolean = true,
    /**
     * Requests that start outside a `molecule` folder (Go to Declaration, Find Usages, Search Everywhere, completion,
     * variable cards) also list Molecule variables, plays and hosts. Requests from a Molecule file always do (D153, D154).
     */
    val showInNavigation: Boolean = false,
) {
    companion object {
        val DEFAULT = MoleculeSettings()
    }
}

/**
 * Which root holds the golden copies that role drift compares every other copy with (plan amendment R24, D177; R25,
 * D193 and X125). Detached worktrees are never golden.
 */
sealed interface GoldenRoot {
    /** No golden root: drift is not computed and the drift UI is hidden (the default, D178). */
    data object None : GoldenRoot

    /** Per role name, the copy in the first role library (by path) that has one: R9's implicit reference. */
    data object FirstRoleLibrary : GoldenRoot

    /** The copies owned by the root stored under [key] (`settings.RootKeys`), whatever its kind. */
    data class Root(val key: String) : GoldenRoot

    /**
     * A git repository outside the project (plan amendment R25, D193): [DriftSettings.remote] says which; a shallow,
     * sparse mirror of it in the IDE's cache (`golden.remote.GoldenMirrors`) holds the golden copies.
     */
    data object Git : GoldenRoot

    /** A folder outside the project (X125): [DriftSettings.folder], read-only like the mirror, never fetched. */
    data object Folder : GoldenRoot

    companion object {
        private const val FIRST_ROLE_LIBRARY = "first-role-library"
        private const val ROOT_PREFIX = "root:"
        private const val GIT = "git"
        private const val FOLDER = "folder"

        /** The stored form: null for [None], `first-role-library`, `root:<key>`, `git` or `folder`. */
        fun encode(golden: GoldenRoot): String? = when (golden) {
            None -> null
            FirstRoleLibrary -> FIRST_ROLE_LIBRARY
            is Root -> ROOT_PREFIX + golden.key
            Git -> GIT
            Folder -> FOLDER
        }

        /** Reads [encode]'s form; anything else (blank, unknown, an empty key) is [None]. */
        fun decode(text: String?): GoldenRoot {
            val value = text?.trim().orEmpty()
            return when {
                value == FIRST_ROLE_LIBRARY -> FirstRoleLibrary
                value == GIT -> Git
                value == FOLDER -> Folder
                value.startsWith(ROOT_PREFIX) && value.length > ROOT_PREFIX.length -> Root(value.removePrefix(ROOT_PREFIX))
                else -> None
            }
        }
    }
}

/**
 * The git repository of [GoldenRoot.Git] (plan amendment R25, D193–D195, X127). Stored with the other project
 * settings (team-shareable), so [url] never carries credentials (`golden.remote.GoldenGitUrls.problem` refuses them).
 */
data class RemoteGolden(
    /** ssh (`git@host:path`, `ssh://`), https, git, `file://` or a local path; blank until set. */
    val url: String = "",
    /** A branch or a tag; blank: the remote's default branch. */
    val ref: String = "",
    /**
     * The directory inside the repository whose child directories are the roles; blank: automatic (`roles/`, else the
     * first `roles_path` of the repository's `ansible.cfg`, else the top level).
     */
    val rolesPath: String = "",
    /** Minutes between background refreshes: one of [REFRESH_CHOICES]; 0 = on demand only. */
    val refreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
    /** X127: how many commits the mirror holds (`--depth`); 1 = only the newest commit, e.g. 50 gives per-file history. */
    val historyDepth: Int = DEFAULT_HISTORY_DEPTH,
) {
    /** [refreshMinutes] clamped to 0 (on demand) .. one day. */
    val effectiveRefreshMinutes: Int
        get() = refreshMinutes.coerceIn(0, MAX_REFRESH_MINUTES)

    /** [historyDepth] clamped to [HISTORY_DEPTH_RANGE]. */
    val effectiveHistoryDepth: Int
        get() = historyDepth.coerceIn(HISTORY_DEPTH_RANGE)

    /** Trimmed text fields; the roles path without a leading `./` or trailing slashes. */
    fun normalized(): RemoteGolden = copy(
        url = url.trim(),
        ref = ref.trim(),
        rolesPath = rolesPath.trim().removePrefix("./").trimEnd('/'),
    )

    companion object {
        const val DEFAULT_REFRESH_MINUTES: Int = 30
        const val DEFAULT_HISTORY_DEPTH: Int = 1
        const val MAX_REFRESH_MINUTES: Int = 24 * 60

        /** The refresh choices of the settings page (D195); 0 is "on demand only". */
        val REFRESH_CHOICES: List<Int> = listOf(5, 10, 30, 60, 0)
        val HISTORY_DEPTH_RANGE: IntRange = 1..10_000
        val DEFAULT = RemoteGolden()
    }
}

/**
 * Role drift (plan amendment R24, D177): the [golden] root and "Ignore `molecule/` in drift" (R9's D41), with the data
 * of the external golden roots (plan amendment R25): the git repository [remote] (D193) and the [folder] (X125). They
 * are kept when another golden root is chosen. Changing them re-tiers the drift without a rescan and without
 * restarting highlighting.
 */
data class DriftSettings(
    val golden: GoldenRoot = GoldenRoot.None,
    /** Molecule files take no part in tiers or variants. Off by default. */
    val ignoreMolecule: Boolean = false,
    /** The repository of [GoldenRoot.Git]. */
    val remote: RemoteGolden = RemoteGolden.DEFAULT,
    /**
     * The folder of [GoldenRoot.Folder]: an absolute path (the settings page stores it with `~` expanded; [folderPath]
     * also expands a leading `~` of a hand-edited file); blank until set.
     */
    val folder: String = "",
) {
    /** [folder] with a leading `~` expanded to the user's home; null when blank, relative or not a path. */
    fun folderPath(): Path? = expandFolder(folder)

    companion object {
        val DEFAULT = DriftSettings()

        /** [text] with a leading `~` expanded to the user's home, normalized; null when blank, relative or not a path. */
        fun expandFolder(text: String): Path? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val expanded = when {
                trimmed == "~" -> System.getProperty("user.home")
                trimmed.startsWith("~/") || trimmed.startsWith("~\\") -> System.getProperty("user.home") + trimmed.substring(1)
                else -> trimmed
            }
            val path = runCatching { Path.of(expanded) }.getOrNull() ?: return null
            return path.takeIf { it.isAbsolute }?.normalize()
        }
    }
}

/**
 * The project's Ansibility settings: per-root [RootSettings] keyed by the root directory relative to the project
 * directory ([RootKeys]), plus the project-wide [PathSettings], [MoleculeSettings] and [DriftSettings]. Immutable.
 */
data class ProjectSettings(
    /** Only roots whose settings differ from [RootSettings.DEFAULT]. */
    val roots: Map<String, RootSettings> = emptyMap(),
    val paths: PathSettings = PathSettings(),
    val molecule: MoleculeSettings = MoleculeSettings.DEFAULT,
    val drift: DriftSettings = DriftSettings.DEFAULT,
) {
    /** Whether [other] differs from these settings in [drift] only. */
    fun differsOnlyInDrift(other: ProjectSettings): Boolean = drift != other.drift && copy(drift = other.drift) == other

    /** The settings of the root stored under [key], or the defaults. */
    fun root(key: String): RootSettings = roots[key] ?: RootSettings.DEFAULT

    /** A copy with [settings] for the root [key]; default settings remove the entry. */
    fun withRoot(key: String, settings: RootSettings): ProjectSettings =
        copy(roots = if (settings == RootSettings.DEFAULT) roots - key else roots + (key to settings))

    /** Only the root entries that differ from the defaults. */
    fun normalized(): ProjectSettings = copy(
        roots = roots.filterValues { it != RootSettings.DEFAULT },
        drift = drift.copy(remote = drift.remote.normalized(), folder = drift.folder.trim()),
    )

    companion object {
        val DEFAULT = ProjectSettings()
    }
}
