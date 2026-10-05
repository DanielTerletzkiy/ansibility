package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.HostFacts
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.context.host.HostContextReads
import de.terletzkiy.ansibility.context.host.RootEffectiveSummaries
import de.terletzkiy.ansibility.context.host.RootEffectiveSummary
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheKind
import de.terletzkiy.ansibility.model.inventory.ModelCacheStats
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The tool window's caches over the host context (WU HA7, plan amendment R7/R8 F8.7). They are [ModelCache] entries of
 * kind [ModelCacheKind.PRESENTATION], so they are validated by the model inputs they read (inventory views, play hits:
 * HA2's content stamps), are computed only when a node asks for them, and never count as model recomputations.
 *
 * Every method needs a read action and may be slow on a cold model: callers are the tree's background invoker and the
 * details pane's background read action, never the EDT.
 */
@Service(Service.Level.PROJECT)
class ToolWindowModels(private val project: Project) {
    private data class FactsKey(val rootDir: VirtualFile, val environment: String)

    private val facts = ModelCache<FactsKey, Map<String, HostRowFacts>>(project, FACTS_CACHE, ModelCacheKind.PRESENTATION)

    /** The counters of the host facts cache (one computation per (root, environment)). */
    val factsStats: ModelCacheStats get() = facts.stats

    /** The reads of the host context service, or null when the service is not the plugin's own. */
    val reads: HostContextReads? get() = HostContextReads.getInstance(project)

    /**
     * Address, `group_names` and shared-address facts of every host of [environment] in [root] (an evaluated templated
     * `ansible_host` uses the root's playbook dir), by host name, under the one vault-safe preview rule: an address
     * that must stay hidden ([HostContextReads.addressHidden]) is dropped and flagged, and hosts with a hidden address
     * are left out of every other host's shared-address names (they would tell the hidden address).
     */
    fun hostFacts(root: AnsibleRoot, environment: String): Map<String, HostRowFacts> =
        facts.get(FactsKey(root.dir, environment)) {
            val service = AnsibleContextService.getInstance(project)
            val scope = service.selectionScope(root, RootContext(EnvironmentChoice.Named(environment)))
            val hosts = service.inventoryFacts(scope).environment(environment)?.hosts.orEmpty()
            val contextReads = reads
            val playbookDir = contextReads?.defaultPlaybookDir(root)
            val hidden = HashMap<HostKey, Boolean>()
            fun isHidden(key: HostKey): Boolean = contextReads != null && hidden.getOrPut(key) { contextReads.addressHidden(key, playbookDir, root) }
            hosts.associate { facts ->
                ProgressManager.checkCanceled()
                val row = if (isHidden(facts.key)) {
                    HostRowFacts(facts.copy(address = null, addressTemplate = null, sharesAddressWith = emptyList()), addressHidden = true)
                } else {
                    HostRowFacts(facts.copy(sharesAddressWith = facts.sharesAddressWith.filterNot(::isHidden)), addressHidden = false)
                }
                facts.key.host to row
            }
        }

    /**
     * The current background summary of [root]'s effective values, building it in the caller's read action when it is
     * missing or stale (it is cached until a model input changes); null while indexing, which the summary's execution
     * inputs need.
     */
    fun summary(root: AnsibleRoot): RootEffectiveSummary? {
        if (DumbService.isDumb(project)) return null
        return try {
            RootEffectiveSummaries.getInstance(project).compute(root)
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    /** One host's facts as its row shows them: [facts] carry no address when [addressHidden] (the vault-safe rule). */
    class HostRowFacts(val facts: HostFacts, val addressHidden: Boolean)

    companion object {
        /** The [ModelCache] name of the host facts entries. */
        const val FACTS_CACHE: String = "toolwindow.hostFacts"

        fun getInstance(project: Project): ToolWindowModels = project.service()
    }
}
