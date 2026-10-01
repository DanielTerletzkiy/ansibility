package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys
import org.jetbrains.annotations.Nls

/**
 * A stored [RootContext] checked against the current model (plan amendment R7/R8, "Selection rules"). A part that no
 * longer exists is dropped here and reported in [problem], while the stored selection itself is kept: a half-edited
 * `hosts.yml` must not lose the user's choice. A stale host falls back to its environment, a stale environment to
 * All, a stale play to Auto.
 */
internal class ResolvedSelection(
    val stored: RootContext,
    /** The selected environment (an inventory or `molecule:<role>/<scenario>`), or null for All. */
    val environment: String?,
    /** The selected host of [environment], or null for All. */
    val host: String?,
    /** The selected play, or null for Auto. */
    val play: PlayRef?,
    @Nls val problem: String?,
)

/**
 * The file scope of one file before the selection is applied ("applies to"). [followsSelection] marks scopes whose
 * targets are the selection's own ([HostScopeOrigin.RootWide]); [runningRole] is the role whose tasks run in the
 * scope's contexts (role files, templates, molecule scenarios).
 */
internal class FileScope(
    val origin: HostScopeOrigin,
    val targets: List<EvalTarget>,
    @Nls val emptyReason: String?,
    val runningRole: String?,
    val followsSelection: Boolean = false,
)

/** Selection resolution, file-free scopes and the intersection rule (plan amendment R7/R8, "Per-file inference"). */
internal class Selections(private val project: Project, private val model: ContextModel) {
    /** [context] of [root] checked against the model. */
    fun resolve(root: AnsibleRoot, context: RootContext): ResolvedSelection {
        val problems = ArrayList<String>()
        val named = (context.environment as? EnvironmentChoice.Named)?.name
        val environment = named?.takeIf { model.graphOf(root, it) != null }
        if (named != null && environment == null) {
            problems += AnsibilityHostBundle.message("selection.stale.environment", named, root.displayName)
        }
        val host = context.host?.takeIf { host -> environment != null && model.graphOf(root, environment)?.host(host) != null }
        if (context.host != null && host == null && environment != null) {
            problems += AnsibilityHostBundle.message("selection.stale.host", context.host, inventoryLabel(root, environment))
        }
        val play = context.play?.let { PlayKeys.resolve(project, root, it) }
        if (context.play != null && play == null) {
            problems += AnsibilityHostBundle.message("selection.stale.play", context.play, root.displayName)
        }
        return ResolvedSelection(context, environment, host, play, problems.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /** The scope of [selection] without any file: every host it selects, with the selected play or inventory-only. */
    fun selectionScope(root: AnsibleRoot, selection: ResolvedSelection, origin: HostScopeOrigin): HostScope {
        val (targets, reason) = selectionTargets(root, selection)
        return HostScope(root, selection.stored, origin, targets, targets.map { it.host }.distinct(), false, reason)
    }

    /** File scope ∩ selection; the file scope wins when they are disjoint, a [FileScope.followsSelection] scope is the selection's. */
    fun intersect(root: AnsibleRoot, file: FileScope, selection: ResolvedSelection): HostScope {
        if (file.followsSelection) return selectionScope(root, selection, file.origin).let { scope ->
            scope.copy(emptyReason = scope.emptyReason ?: file.emptyReason.takeIf { scope.targets.isEmpty() })
        }
        val fileHosts = file.targets.map { it.host }.distinct()
        if (file.targets.isEmpty()) return HostScope(root, selection.stored, file.origin, emptyList(), fileHosts, false, file.emptyReason)
        val filtered = file.targets.filter { matches(it, selection) }
        return if (filtered.isEmpty()) {
            HostScope(root, selection.stored, file.origin, file.targets, fileHosts, overriddenSelection = true, emptyReason = null)
        } else {
            HostScope(root, selection.stored, file.origin, filtered, fileHosts, overriddenSelection = false, emptyReason = null)
        }
    }

    private fun matches(target: EvalTarget, selection: ResolvedSelection): Boolean =
        (selection.environment == null || target.host.environment == selection.environment) &&
            (selection.host == null || target.host.host == selection.host) &&
            (selection.play == null || target.play == null || target.play == selection.play)

    /** The targets of [selection] in [root], or none with the reason. */
    private fun selectionTargets(root: AnsibleRoot, selection: ResolvedSelection): Pair<List<EvalTarget>, String?> {
        val environment = selection.environment
        if (environment != null && environment.startsWith(HostKey.MOLECULE_PREFIX)) {
            val molecule = model.molecule(model.inventoryRoot(root), environment)
                ?: return emptyList<EvalTarget>() to AnsibilityHostBundle.message("scope.empty.no.inventory", root.displayName)
            val targets = model.moleculeTargets(root, molecule).filter { selection.host == null || it.host.host == selection.host }
            return targets to AnsibilityHostBundle.message("scope.empty.molecule.no.hosts", environment).takeIf { targets.isEmpty() }
        }
        val environments = model.environments(root).filter { environment == null || it.name == environment }
        if (environments.isEmpty()) return emptyList<EvalTarget>() to AnsibilityHostBundle.message("scope.empty.no.inventory", root.displayName)
        val play = selection.play
        val hits = play?.let { model.hitsOf(root, it) }
        val targets = ArrayList<EvalTarget>()
        for (env in environments) {
            val hosts = env.graph.hosts.keys.filter { selection.host == null || it == selection.host }
            for (host in hosts) {
                val key = model.hostKey(root, env.name, host)
                when {
                    hits == null -> targets += EvalTarget(key, null, model.defaultPlaybookDir(root))
                    host in hits.hostsByEnvironment[env.name].orEmpty() -> targets += EvalTarget(key, play, play.playbookDir)
                }
            }
        }
        val reason = when {
            targets.isNotEmpty() -> null
            play != null && selection.host != null -> AnsibilityHostBundle.message("scope.empty.play.not.on.host", playLabel(play), selection.host)
            play != null -> AnsibilityHostBundle.message("scope.empty.play.no.hosts", playLabel(play), root.displayName)
            else -> AnsibilityHostBundle.message("scope.empty.no.inventory", root.displayName)
        }
        return targets to reason
    }

    private fun inventoryLabel(root: AnsibleRoot, environment: String): String =
        model.environment(root, environment)?.hostsFile?.let { file ->
            RootKeys.relativePath(model.inventoryRoot(root).dir, file)
        } ?: environment

    companion object {
        /** A play as users read it: its name, or `<file>#<index>` for an unnamed play. */
        fun playLabel(play: PlayRef): String = play.name ?: "${play.file.name}#${play.playIndex}"
    }
}
