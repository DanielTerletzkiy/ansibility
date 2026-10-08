package de.terletzkiy.ansibility.settings

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset

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
 * The project's Ansibility settings: per-root [RootSettings] keyed by the root directory relative to the project
 * directory ([RootKeys]), plus the project-wide [PathSettings] and [MoleculeSettings]. Immutable.
 */
data class ProjectSettings(
    /** Only roots whose settings differ from [RootSettings.DEFAULT]. */
    val roots: Map<String, RootSettings> = emptyMap(),
    val paths: PathSettings = PathSettings(),
    val molecule: MoleculeSettings = MoleculeSettings.DEFAULT,
) {
    /** The settings of the root stored under [key], or the defaults. */
    fun root(key: String): RootSettings = roots[key] ?: RootSettings.DEFAULT

    /** A copy with [settings] for the root [key]; default settings remove the entry. */
    fun withRoot(key: String, settings: RootSettings): ProjectSettings =
        copy(roots = if (settings == RootSettings.DEFAULT) roots - key else roots + (key to settings))

    /** Only the root entries that differ from the defaults. */
    fun normalized(): ProjectSettings = copy(roots = roots.filterValues { it != RootSettings.DEFAULT })

    companion object {
        val DEFAULT = ProjectSettings()
    }
}
