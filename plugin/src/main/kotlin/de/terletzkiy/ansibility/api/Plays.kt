package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.semantics.yaml.YMap

/**
 * One play, identified by the file that defines it and its position among that file's plays.
 *
 * A play imported through `import_playbook` keeps the identity of its defining file: the play at
 * `repos/pelican/ansible/playbook-setup-replisync.yml` is the same [PlayRef] whether it runs on its own or through
 * `danger_zone/database/playbook-clone-to-replisync.yml`.
 */
data class PlayRef(
    val file: VirtualFile,
    /** 0-based index among the plays of [file]; `import_playbook` entries do not count. */
    val playIndex: Int,
    val name: String?,
    /** The `hosts:` pattern as written; a YAML list is joined with `,` (as ansible-core does). Null when missing. */
    val hostsPattern: String?,
    /**
     * The playbook dir of this play: the directory of [file] (plan A.5). ansible-core sets the loader basedir per
     * play, so it decides the playbook-level `group_vars`/`host_vars` and the `<playbook_dir>/roles` lookup.
     */
    val playbookDir: VirtualFile,
)

/** The part of a play a role entry comes from, in execution order. */
enum class PlaySection { PRE_TASKS, ROLES, TASKS, POST_TASKS, HANDLERS }

/** How a role takes part in a play. */
enum class RoleEntryKind {
    /** An entry of the play's `roles:` list (always entry point `main`). */
    PLAY_ROLE,

    /** A task `include_role:` (dynamic). */
    INCLUDE_ROLE,

    /** A task `import_role:` (static). */
    IMPORT_ROLE,

    /** A `meta/main.yml` dependency of another entry, expanded before it (entry point `main`). */
    DEPENDENCY,
}

/** One role applied by a play, in execution order (see [PlayGraph.rolesOfPlay]). */
data class PlayRoleEntry(
    /** The role name: the text as written, or the last path segment when the role is given by path. */
    val name: String,
    /** The reference as written, e.g. `haproxy` or `/ansible/roles/haproxy`. */
    val written: String,
    /** The role directory it resolves to, or null when no directory matches. */
    val role: RoleRef?,
    /** The argument-spec entry point: the literal `tasks_from` value, or `main`. */
    val entryPoint: String,
    /** The literal `handlers_from` value of an include/import, if any. */
    val handlersFrom: String?,
    /** The entry's own `tags` (a dependency lists the tags written in `meta/main.yml`). */
    val tags: List<String>,
    val kind: RoleEntryKind,
    val section: PlaySection,
    /** Where the reference is written (the role name in the playbook, or the dependency in `meta/main.yml`). */
    val location: SourceLocation?,
    /** For [RoleEntryKind.DEPENDENCY]: the name of the role that declares the dependency. */
    val requiredBy: String? = null,
)

/** One `vars_files` entry of a play. */
data class VarsFileRef(
    /** The path as written (may be templated). */
    val path: String,
    /** The file it resolves to relative to the playbook dir, or null (missing or templated). */
    val file: VirtualFile?,
    val location: SourceLocation?,
)

/** Everything the play graph knows about one play. */
data class PlayInfo(
    val ref: PlayRef,
    /** The start of the play's mapping. */
    val location: SourceLocation,
    /** The keys of the play's `vars:` mapping, in source order. */
    val varsKeys: List<String>,
    val varsFiles: List<VarsFileRef>,
    /** The roles the play applies, in execution order, with `meta/main.yml` dependencies expanded. */
    val roles: List<PlayRoleEntry>,
    /**
     * The play's `vars:` mapping as loaded, with the source ranges of keys and values in [PlayRef.file] (plan amendment
     * R7/R8, A.14: the L12 source of the ExecutionView, `VarSource.fromDocument(PLAY_VARS, …, vars)`). `!vault` values
     * are value-free [de.terletzkiy.ansibility.semantics.yaml.YVault] markers. Null when the play has no `vars:`
     * mapping; [varsKeys] lists the same keys.
     */
    val vars: YMap? = null,
)

/** One `import_playbook` edge. */
data class PlaybookImport(
    /** The importing playbook. */
    val file: VirtualFile,
    /** The path as written. */
    val path: String,
    /** The imported playbook, resolved relative to the importing file's directory; null when missing or templated. */
    val target: VirtualFile?,
    val location: SourceLocation?,
)

/**
 * Plays → host patterns → roles (with entry points), following `import_playbook` edges (plan A.5 `PlayGraph`).
 *
 * Every lookup is scoped to one [AnsibleRoot]; plays of a detached worktree never show up for a normal root.
 * All methods run in a read action (they take one themselves when the caller holds none). Results are cached per
 * playbook on the content of the files read, and stay the same objects while cached.
 */
interface PlayGraph {
    /** The playbooks of [root] (`FileKind.PLAYBOOK` and `FileKind.MOLECULE_PLAYBOOK`), sorted by path. */
    fun playbooks(root: AnsibleRoot): List<VirtualFile>

    /** The plays defined in [playbook] itself, in file order (imports are not followed). */
    fun playsOf(playbook: VirtualFile): List<PlayInfo>

    /** The `import_playbook` edges written in [playbook], in file order. */
    fun imports(playbook: VirtualFile): List<PlaybookImport>

    /** The plays that run when [playbook] runs, in order, following `import_playbook` edges (cycles are cut). */
    fun executionOrder(playbook: VirtualFile): List<PlayRef>

    /**
     * The plays that apply role [roleName] of [root] (directly, by include/import, or as a dependency). With
     * [entryPoint], only entries with that literal entry point (`main` for `roles:` entries) count.
     */
    fun playsApplying(root: AnsibleRoot, roleName: String, entryPoint: String? = null): List<PlayRef>

    /** The role entries of [play] in execution order; empty for an unknown play. */
    fun rolesOfPlay(play: PlayRef): List<PlayRoleEntry>

    /** Details of [play], or null when the file no longer defines it. */
    fun play(play: PlayRef): PlayInfo?

    companion object {
        fun getInstance(project: Project): PlayGraph = project.service()
    }
}
