package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.semantics.inventory.HostPattern

/** `ansible/group_vars/all/vars.yml:156`-style labels of definitions, relative to a root directory. */
object SourceLabels {
    /** [file] relative to [base] (climbing out with `../` when needed), with the 1-based line of [offset]. */
    fun of(base: VirtualFile, file: VirtualFile, offset: Int): String {
        val path = path(base, file)
        val line = line(file, offset) ?: return path
        return "$path:$line"
    }

    /** [file] relative to [base], or its name when they share no ancestor. */
    fun path(base: VirtualFile, file: VirtualFile): String =
        VfsUtilCore.getRelativePath(file, base) ?: VfsUtilCore.findRelativePath(base, file, '/') ?: file.name

    /**
     * A playbook dir as the tree writes it: relative to the directory above the root's inventory root (`ansible`,
     * `ansible/danger_zone/database`), so the root's own directory and a nested root's directory read apart at a glance.
     */
    fun playbookDir(root: RootSnapshot, dir: VirtualFile): String {
        val inventoryRoot = root.parent?.dir ?: root.dir
        return path(inventoryRoot.parent ?: inventoryRoot, dir)
    }

    /** The 1-based line of [offset] in [file]'s document (its committed text), or null when it has none. Needs a read action. */
    fun line(file: VirtualFile, offset: Int): Int? {
        if (!file.isValid || file.isDirectory) return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1
    }
}

/** One play that runs on hosts of a group: how many of them, and whether its `hosts:` pattern names the group itself. */
class PlayOnGroup(val play: PlayRef, val location: SourceLocation?, val hosts: List<String>, val direct: Boolean)

/** One role that runs on hosts of a group, with the plays that apply it there. */
class RoleOnGroup(val name: String, val dir: VirtualFile?, val hosts: List<String>, val plays: List<PlayRef>)

/**
 * The reach of a group (plan amendment R7/R8 F8.7 "Group node", WU HA7b): the plays that run on its hosts (the members
 * of its environment, children included), which of them name the group in their `hosts:` pattern, and the roles they
 * apply there ("consumed by roles …"). Platform prod `contracting`: no play targets contracting or analytics directly;
 * its hosts are reached via `database`, `database_primary`, … .
 */
class GroupReach(
    val group: String,
    val members: List<String>,
    val plays: List<PlayOnGroup>,
    val roles: List<RoleOnGroup>,
) {
    /** The plays whose pattern names the group. */
    val direct: List<PlayOnGroup> get() = plays.filter { it.direct }

    /** The patterns through which the other plays reach the group's hosts, in play order, deduplicated. */
    val reachedVia: List<String> get() = plays.filter { !it.direct }.mapNotNull { it.play.hostsPattern }.distinct()

    companion object {
        /** The reach of [group] in [env], or null without the host context. Needs a read action. */
        fun of(project: Project, env: EnvironmentView, group: InventoryGroup): GroupReach? {
            val reads = ToolWindowModels.getInstance(project).reads ?: return null
            val members = env.membersOf(group).map { it.name }
            val memberSet = members.toSet()
            val graph = PlayGraph.getInstance(project)
            val plays = ArrayList<PlayOnGroup>()
            val roles = LinkedHashMap<String, Triple<VirtualFile?, LinkedHashSet<String>, LinkedHashSet<PlayRef>>>()
            for (match in reads.playMatches(env.root.root)) {
                ProgressManager.checkCanceled()
                val hosts = match.hostsIn(env.name).filter { it in memberSet }
                if (hosts.isEmpty()) continue
                val pattern = match.play.hostsPattern.orEmpty()
                val direct = HostPattern.split(pattern).any { it.trimStart('&') == group.name }
                plays += PlayOnGroup(match.play, graph.play(match.play)?.location, hosts, direct)
                for (entry in graph.rolesOfPlay(match.play)) {
                    val row = roles.getOrPut(entry.name) { Triple(entry.role?.dir, LinkedHashSet(), LinkedHashSet()) }
                    row.second += hosts
                    row.third += match.play
                }
            }
            val roleRows = roles.map { (name, row) ->
                RoleOnGroup(name, row.first, members.filter { it in row.second }, row.third.toList())
            }
            return GroupReach(group.name, members, plays, roleRows)
        }
    }
}

/**
 * A key of a var file that some host loads but no host lets win (the P001 candidates, presentation only), with the
 * distinct definitions that win over it, in host order.
 */
class IneffectiveKey(val name: String, val location: SourceLocation, val shadowedOn: List<HostKey>, val winners: List<SourceLocation>)

/**
 * What a var file achieves (plan amendment R7/R8 F8.7 "Var-file details", WU HA7b): the hosts it applies to, on how
 * many of them at least one of its keys takes effect, and the keys that never do. From the root's background summary
 * (every reachable (env, host, play), D32: never the selection); locations only, never values.
 */
class VarFileEffect(
    /** The hosts the file applies to. */
    val appliesTo: List<HostKey>,
    /** The hosts on which at least one key of the file wins in some context. */
    val effectiveOn: List<HostKey>,
    /** The number of keys some context loads. */
    val keys: Int,
    val ineffective: List<IneffectiveKey>,
) {
    companion object {
        /** The effect of [varFile] of [root] on the hosts it applies to, or null without the host context or while indexing. */
        fun of(project: Project, root: RootSnapshot, varFile: VarFile): VarFileEffect? {
            val models = ToolWindowModels.getInstance(project)
            val reads = models.reads ?: return null
            val summary = models.summary(root.root) ?: return null
            val environments = root.environment(varFile.environment)?.let(::listOf) ?: root.environments
            val applies = environments.flatMap { env -> env.hostsFor(varFile).map { reads.hostKey(root.root, env.name, it.name) } }
            val statuses = summary.statuses().filter { it.definition.location.file == varFile.file }
            val wins = statuses.flatMapTo(HashSet()) { it.winsOn }
            val ineffective = statuses.filter { it.winsOn.isEmpty() && it.shadowedOn.isNotEmpty() }.map { status ->
                val shadowed = status.shadowedOn.keys.toList()
                IneffectiveKey(status.definition.name, status.definition.location, shadowed, status.shadowedOn.values.map { it.location }.distinct())
            }
            return VarFileEffect(applies, applies.filter { it in wins }, statuses.size, ineffective)
        }
    }
}
