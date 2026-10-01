package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.PlayKeys
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import org.jetbrains.annotations.Nls

/** One environment the context popup offers: `prod` with its host count. */
data class EnvironmentOption(val name: String, val hostCount: Int)

/**
 * One host the context popup offers: its `ansible_host` [address] and how many names of the root share that address
 * ([sharedBy] counts this host too, so `1 of 7 names on 192.0.2.43` reads `sharedBy = 7`; 1 when nobody shares it).
 */
data class HostOption(val environment: String, val host: String, val address: String?, val sharedBy: Int)

/** One play the context popup offers: the play, its stored key (`playbook-setup-system.yml#4`) and its label. */
data class PlayOption(val play: PlayRef, val key: String, val label: String)

/**
 * What a root's context can be switched to (plan amendment R7/R8, F8.1, "Selection rules"), and the rules for keeping
 * the play when env or host change. Reads the host-context model (environments, play hits, addresses), never the
 * selection-dependent caches; every method takes a read lock when the caller holds none.
 */
class ContextChoices(private val project: Project) {
    private val context: AnsibleContextService get() = AnsibleContextService.getInstance(project)

    private val impl: AnsibleContextServiceImpl? get() = AnsibleContextServiceImpl.getInstance(project)

    /** The environments of [root] (a nested root lists its parent's), sorted by name. */
    fun environments(root: AnsibleRoot): List<EnvironmentOption> = readLocked {
        val model = impl?.model ?: return@readLocked emptyList()
        model.environments(root).map { EnvironmentOption(it.name, it.graph.hosts.size) }
    }

    /** The hosts of [environment] in inventory order, or of every environment for null, with address badges. */
    fun hosts(root: AnsibleRoot, environment: String?): List<HostOption> = readLocked {
        val model = impl?.model ?: return@readLocked emptyList()
        val environments = model.environments(root).filter { environment == null || it.name == environment }
        if (environments.isEmpty()) return@readLocked emptyList()
        val selection = RootContext(environment?.let { EnvironmentChoice.Named(it) } ?: EnvironmentChoice.All)
        val facts = context.inventoryFacts(context.selectionScope(root, selection))
        environments.flatMap { env ->
            val known = facts.environment(env.name)
            env.graph.hosts.keys.map { host ->
                ProgressManager.checkCanceled()
                val hostFacts = known?.hosts?.firstOrNull { it.key.host == host }
                HostOption(env.name, host, hostFacts?.address, (hostFacts?.sharesAddressWith?.size ?: 0) + 1)
            }
        }
    }

    /**
     * The plays that hit [host] of [environment] (every host of the environment for a null host, every environment
     * for a null environment), in play-graph order with [root]'s own playbooks first. Plays with a templated `hosts:`
     * pattern hit nothing statically and are left out.
     */
    fun plays(root: AnsibleRoot, environment: String?, host: String?): List<PlayOption> = readLocked {
        val model = impl?.model ?: return@readLocked emptyList()
        val hits = model.playHits(root).filter { hits ->
            ProgressManager.checkCanceled()
            when {
                environment == null -> hits.hostsByEnvironment.isNotEmpty()
                host == null -> hits.hostsByEnvironment[environment].orEmpty().isNotEmpty()
                else -> host in hits.hostsByEnvironment[environment].orEmpty()
            }
        }
        val labelBase = model.inventoryRoot(root)
        hits.map { PlayOption(it.play, PlayKeys.of(root, it.play), ContextTexts.playLabel(labelBase, it.play)) }
            .sortedBy { if (VfsUtilCore.isAncestor(root.dir, it.play.file, true)) 0 else 1 }
    }

    /** The label of the stored play [key] of [root] (`playbook-setup-system.yml › KeepAliveD`), or null when it is gone. */
    fun playLabel(root: AnsibleRoot, key: String): String? = readLocked {
        val play = PlayKeys.resolve(project, root, key) ?: return@readLocked null
        val base = impl?.model?.inventoryRoot(root) ?: root
        ContextTexts.playLabel(base, play)
    }

    /** [current] with [environment] (null: All) chosen: the host is cleared, the play kept while it hits the environment. */
    fun withEnvironment(root: AnsibleRoot, current: RootContext, environment: String?): RootContext {
        val choice = environment?.let { EnvironmentChoice.Named(it) } ?: EnvironmentChoice.All
        return RootContext(choice, null, keptPlay(root, current.play, environment, null))
    }

    /** [current] with [host] of [environment] chosen: the play is kept while it still hits the host. */
    fun withHost(root: AnsibleRoot, current: RootContext, environment: String, host: String): RootContext =
        RootContext(EnvironmentChoice.Named(environment), host, keptPlay(root, current.play, environment, host))

    /** [current] with the play [key] chosen (null: Auto); env and host stay. */
    fun withPlay(current: RootContext, key: String?): RootContext = current.copy(play = key)

    private fun keptPlay(root: AnsibleRoot, key: String?, environment: String?, host: String?): String? {
        key ?: return null
        return key.takeIf { plays(root, environment, host).any { it.key == key } }
    }

    /**
     * The roots whose context can be switched (they have environments), for Switch Context: [preferred] first, then
     * the roots of R9's workspace scope, then the others, each group in workspace order. Detached worktrees are left
     * out.
     */
    fun switchableRoots(preferred: AnsibleRoot?): List<AnsibleRoot> = readLocked {
        val roots = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached && it.kind != RootKind.ROLE_LIBRARY }
            .filter { environments(it).isNotEmpty() }
        val inScope = project.serviceOrNull<WorkspaceScopeService>()?.current()?.roots?.toSet()
        roots.sortedBy { root ->
            when {
                root == preferred -> 0
                inScope == null || root in inScope -> 1
                else -> 2
            }
        }
    }

    /** What is wrong with [root]'s stored selection ("prod-db9 is no longer in environments/prod/hosts.yml"), or null. */
    @Nls
    fun problem(root: AnsibleRoot): String? = impl?.selectionProblem(root)
}
