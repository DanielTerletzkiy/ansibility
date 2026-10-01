package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleReach
import de.terletzkiy.ansibility.model.inventory.ModelCache

/**
 * Role reachability (plan amendment R7/R8, F8.11): every (env, host, play) of a root that applies a role, through
 * [PlayGraph.playsApplying] (`roles:` entries, `meta/main.yml` dependencies, `include_role`/`import_role`) and the
 * plays' `hosts:` patterns per environment ([ContextModel.playHits]). Plays of molecule playbooks run against their
 * scenario and do not count; a templated pattern counts nowhere and makes the reach [RoleReach.approximate].
 *
 * Results are [ModelCache] entries per (root, role) (plan amendment R7/R8, A.9 change 2). An entry depends on the root's
 * play hits (the playbooks' graphs and the environments' `hosts.yml`) and on the plays applying the role (the graphs
 * and the `meta/main.yml` files they expanded), never on vars files, role task files or the selection.
 */
internal class RoleReaches(private val project: Project, private val model: ContextModel) {
    private data class Key(val root: AnsibleRoot, val role: String)

    private val cache = ModelCache<Key, RoleReach>(project, "context.reach", maxSize = MAX_CACHED)

    fun reach(root: AnsibleRoot, role: String): RoleReach = cache.get(Key(root, role)) { compute(root, role) }

    private fun compute(root: AnsibleRoot, role: String): RoleReach {
        val hits = model.playHits(root).associateBy { it.play }
        val applying = PlayGraph.getInstance(project).playsApplying(root, role)
        val plays = applying.filter { it in hits }
        val targets = LinkedHashSet<EvalTarget>()
        var approximate = false
        for (play in plays) {
            ProgressManager.checkCanceled()
            val hit = hits.getValue(play)
            if (hit.templated) approximate = true
            for ((environment, hosts) in hit.hostsByEnvironment) {
                hosts.mapTo(targets) { EvalTarget(model.hostKey(root, environment, it), play, play.playbookDir) }
            }
        }
        val sorted = model.sorted(root, targets, plays)
        val reason = when {
            sorted.isNotEmpty() -> null
            plays.isEmpty() -> AnsibilityHostBundle.message("reach.empty.no.play", role, root.displayName)
            approximate -> AnsibilityHostBundle.message("reach.empty.templated", role, root.displayName)
            else -> AnsibilityHostBundle.message("reach.empty.no.host", role, root.displayName)
        }
        return RoleReach(root, role, sorted, plays, approximate, reason)
    }

    private companion object {
        const val MAX_CACHED = 4096
    }
}
