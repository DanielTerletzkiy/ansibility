package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles

/** A name a handler answers to: its `name`, or one of its `listen` topics. */
internal data class HandlerDef(
    val name: String,
    val listen: Boolean,
    val file: VirtualFile,
    /** The start of the name or topic scalar. */
    val offset: Int,
    /** The role whose `handlers/` defines it; null for a play's `handlers:` section. */
    val role: String?,
    /** The start of the handler's mapping: a handler with a name and a topic appears twice with the same value. */
    val taskOffset: Int,
) {
    val isTemplated: Boolean get() = RefOccurrence.isTemplated(name)
}

/** Reads handler definitions from the task model (cached per file). Call inside a read action. */
internal object HandlerDefs {
    /** Every handler of the role at [roleDir] (all files under `handlers/`, `main` first). */
    fun ofRole(project: Project, roleDir: VirtualFile): List<HandlerDef> {
        val files = RoleRegistry.getInstance(project).roleOf(roleDir)?.handlerFiles ?: RoleLayout.handlerFiles(roleDir)
        return files.flatMap { file ->
            val yaml = YamlFiles.yamlFile(project, file) ?: return@flatMap emptyList()
            defs(TaskFileModels.of(yaml, TaskFileKind.HANDLERS).items, file, roleDir.name)
        }
    }

    /** The `handlers:` section of play [playIndex] of [playbook]. */
    fun ofPlay(project: Project, playbook: VirtualFile, playIndex: Int): List<HandlerDef> {
        val yaml = YamlFiles.yamlFile(project, playbook) ?: return emptyList()
        val play = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK).plays.getOrNull(playIndex) ?: return emptyList()
        return defs(play.handlers, playbook, null)
    }

    private fun defs(items: List<TaskItem>, file: VirtualFile, role: String?): List<HandlerDef> {
        val result = ArrayList<HandlerDef>()
        fun visit(items: List<TaskItem>) {
            for (item in items) {
                ProgressManager.checkCanceled()
                when (item) {
                    is BlockNode -> {
                        visit(item.block)
                        visit(item.rescue)
                        visit(item.always)
                    }
                    is TaskNode -> {
                        val task = item.range.startOffset
                        item.name?.let { name -> name.text.trim().takeIf { it.isNotEmpty() }?.let { result += HandlerDef(it, false, file, name.range.startOffset, role, task) } }
                        item.listen.forEach { topic -> topic.text.trim().takeIf { it.isNotEmpty() }?.let { result += HandlerDef(it, true, file, topic.range.startOffset, role, task) } }
                    }
                }
            }
        }
        visit(items)
        return result
    }
}

/**
 * The handlers a `notify` in one file can reach, nearest first (plan F1.8): ansible-core looks a notification up
 * among all handlers of the play, by name, by `role : name` and by `listen` topic.
 *
 * Tiers, each only when the previous one found nothing:
 * 1. [Tier.QUALIFIED]: `role : name` → that role's handlers named (or listening to) `name`;
 * 2. [Tier.OWN]: the file's own role; for a task of a play, that play's `handlers:` and the handlers of the roles it
 *    applies ([Tier.PLAY]);
 * 3. [Tier.PLAY_SCOPE]: for a role, the other roles (and `handlers:` sections) of every play that applies it;
 * 4. [Tier.ROOT]: any other handler of the root with that name (indexed), for roles the root's plays never combine.
 *
 * [isOpen] tells whether an unresolved name may still exist at runtime: a templated handler name in scope, or a play in
 * scope with a role the project cannot find. Everything stays inside [root].
 *
 * With [MoleculeView.EXCLUDE] (Ctrl+B and completion started outside Molecule while Molecule is hidden, plan amendment
 * R20, D153) the plays of Molecule playbooks are not in the play scope and the root tier skips Molecule files; the
 * unresolved-reference inspection keeps [MoleculeView.INCLUDE] (D155).
 */
internal class HandlerScope(
    private val project: Project,
    private val root: AnsibleRoot,
    private val file: VirtualFile,
    private val context: FileContext,
    private val playIndex: Int?,
    private val view: MoleculeView = MoleculeView.INCLUDE,
) {
    enum class Tier { QUALIFIED, OWN, PLAY, PLAY_SCOPE, ROOT }

    data class Match(val def: HandlerDef, val tier: Tier)

    private val graph = PlayGraph.getInstance(project)
    private var open = false

    /** The role this file belongs to, when it is a role's task or handler file. */
    val ownRoleDir: VirtualFile? = context.roleDir?.takeIf { context.kind == FileKind.ROLE_TASKS || context.kind == FileKind.ROLE_HANDLERS }

    private val ownPlay: PlayRef? by lazy {
        if (ownRoleDir != null || playIndex == null) null else graph.playsOf(file).getOrNull(playIndex)?.ref
    }

    /** The own role's handlers, or for a play's task that play's own `handlers:` section. */
    val own: List<Match> by lazy {
        val dir = ownRoleDir
        when {
            dir != null -> HandlerDefs.ofRole(project, dir).map { Match(it, Tier.OWN) }
            ownPlay != null -> HandlerDefs.ofPlay(project, file, playIndex!!).map { Match(it, Tier.OWN) } + playRoles(listOf(ownPlay!!), exclude = null)
            else -> {
                // A task file outside roles and plays (molecule `*_tasks.yml`): the plays that include it are unknown.
                open = true
                emptyList()
            }
        }.also { matches -> if (matches.any { it.def.isTemplated }) open = true }
    }

    /** For a role: the handlers of the other roles and the `handlers:` sections of the plays that apply it. */
    val playScope: List<Match> by lazy {
        val dir = ownRoleDir ?: return@lazy emptyList()
        val plays = MoleculeVisibility.playsInView(project, view, graph.playsApplying(root, dir.name))
        val sections = plays.flatMap { play -> HandlerDefs.ofPlay(project, play.file, play.playIndex).map { Match(it, Tier.PLAY_SCOPE) } }
        (sections + playRoles(plays, exclude = dir).map { it.copy(tier = Tier.PLAY_SCOPE) })
            .also { matches -> if (matches.any { it.def.isTemplated }) open = true }
    }

    /** True when a name this scope cannot find might still exist at runtime (call after [resolve]). */
    val isOpen: Boolean get() = open

    /** The handlers [notification] reaches, from the nearest non-empty tier; one match per handler (its name before a topic). */
    fun resolve(notification: String): List<Match> = find(notification).distinctBy { it.def.file to it.def.taskOffset }

    private fun find(notification: String): List<Match> {
        val name = notification.trim()
        qualified(name)?.let { (role, handler) ->
            val dir = roleDir(role)
            if (dir != null) {
                val found = HandlerDefs.ofRole(project, dir).filter { it.name == handler }.map { Match(it, Tier.QUALIFIED) }
                if (found.isNotEmpty()) return found
            } else if (!RefCaches.getInstance(project).isClosedRoleSearchPath(root) && '/' !in role) {
                open = true
            }
        }
        own.filter { it.def.name == name }.takeIf { it.isNotEmpty() }?.let { return it }
        playScope.filter { it.def.name == name }.takeIf { it.isNotEmpty() }?.let { return it }
        return rootHandlers(name)
    }

    /** Every non-templated handler name and topic of [own] and [playScope], nearest first. */
    fun completionCandidates(): List<Match> = (own + playScope).filter { !it.def.isTemplated }

    private fun playRoles(plays: List<PlayRef>, exclude: VirtualFile?): List<Match> {
        val result = ArrayList<Match>()
        val seen = HashSet<VirtualFile>()
        for (play in plays) {
            for (entry in graph.rolesOfPlay(play)) {
                ProgressManager.checkCanceled()
                val dir = entry.role?.dir
                if (dir == null) {
                    open = true
                    continue
                }
                if (dir == exclude || !seen.add(dir)) continue
                HandlerDefs.ofRole(project, dir).forEach { result += Match(it, Tier.PLAY) }
            }
        }
        return result
    }

    private fun rootHandlers(name: String): List<Match> {
        val known = (own + playScope).mapTo(HashSet()) { it.def.file to it.def.taskOffset }
        return try {
            AnsibleIndexQueries.handlers(project, root, name)
                .filter { (it.file to it.value.taskOffset) !in known }
                .filter { view.includesMolecule || !MoleculeVisibility.isMoleculeFile(project, it.file) }
                .map { Match(HandlerDef(name, it.value.listen, it.file, it.value.offset, it.context.roleName, it.value.taskOffset), Tier.ROOT) }
        } catch (_: IndexNotReadyException) {
            open = true
            emptyList()
        }
    }

    private fun roleDir(role: String): VirtualFile? =
        RoleRegistry.getInstance(project).role(root, role)?.ref?.dir ?: ownRoleDir?.takeIf { it.name == role }

    companion object {
        private const val SEPARATOR = " : "

        /** `role : name` → (role, name); null for a plain name. */
        fun qualified(notification: String): Pair<String, String>? {
            val at = notification.indexOf(SEPARATOR)
            if (at <= 0) return null
            val role = notification.substring(0, at).trim()
            val name = notification.substring(at + SEPARATOR.length).trim()
            return if (role.isEmpty() || name.isEmpty()) null else role to name
        }

        fun qualify(role: String, name: String): String = "$role$SEPARATOR$name"
    }
}
