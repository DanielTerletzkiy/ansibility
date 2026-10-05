package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.HostFacts
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import org.jetbrains.annotations.Nls

/**
 * The badges of a host row (plan amendment R7/R8 F8.7, WU HA7a): the address, the `ansible_host` template it was
 * evaluated from (grey, after it) and how many inventory names share the address.
 *
 * - [address]: the evaluated `ansible_host` (a template of the forms `{{ name }}`, `{{ name['key'] }}`, `{{ name.key }}`
 *   is evaluated against the host's inventory view with the root's playbook dir), else the value as written; null when
 *   the host sets none (ansible-core connects to the inventory name).
 * - [template]: the template when [address] was evaluated from one, or when it could not be evaluated (then [address]
 *   is null and the template is the only thing shown).
 * - [shared]: `1 of 7 names on 192.0.2.43` when other hosts of the root have the same address.
 * - [hidden]: the address must stay hidden by the one vault-safe preview rule (it is written in a vault file, or is
 *   templated through a `vault_*` name); [address] is then the masked text and nothing else is shown.
 */
class HostBadge(
    @Nls val address: String?,
    @Nls val template: String?,
    @Nls val shared: String?,
    /** The other hosts of the root on the same address, `env › host`, for the details pane. */
    val sharedWith: List<String>,
    val hidden: Boolean = false,
) {
    companion object {
        private val LOG = logger<HostBadge>()

        /** The badge of [host] in [env]: from the host context's facts when [project] is known, else from `hosts.yml` alone. */
        fun of(project: Project?, env: EnvironmentView, host: InventoryHost): HostBadge {
            val row = project?.let { facts(it, env, host) } ?: return written(host)
            return if (row.addressHidden) hidden() else of(row.facts, host)
        }

        /** The badge of a host whose address must stay hidden: a masked text, no template, no shared names. */
        fun hidden(): HostBadge = HostBadge(message("host.badge.hidden"), null, null, emptyList(), hidden = true)

        /** The badge from [facts]; [host] supplies the written value when the facts have no address. */
        fun of(facts: HostFacts, host: InventoryHost): HostBadge {
            val template = facts.addressTemplate
            val address = facts.address ?: host.ansibleHost?.takeIf { !JinjaBearing.hasTemplateMarkers(it) }
            val sharing = facts.sharesAddressWith
            val shared = if (address != null && sharing.isNotEmpty()) message("host.badge.shared", sharing.size + 1, address) else null
            return HostBadge(
                address = address,
                template = template ?: host.ansibleHost?.takeIf { address == null && JinjaBearing.hasTemplateMarkers(it) },
                shared = shared,
                sharedWith = sharing.map { "${it.environment} › ${it.host}" },
            )
        }

        /** The badge from `hosts.yml` only: the address as written, a template unevaluated. */
        fun written(host: InventoryHost): HostBadge {
            val written = host.ansibleHost ?: return HostBadge(null, null, null, emptyList())
            return if (JinjaBearing.hasTemplateMarkers(written)) HostBadge(null, written, null, emptyList()) else HostBadge(written, null, null, emptyList())
        }

        private fun facts(project: Project, env: EnvironmentView, host: InventoryHost): ToolWindowModels.HostRowFacts? = try {
            ToolWindowModels.getInstance(project).hostFacts(env.root.root, env.name)[host.name]
        } catch (e: IndexNotReadyException) {
            // The facts need no index today; should a model input start to, the row falls back to hosts.yml as written.
            LOG.debug("Ansibility: no host facts for ${host.name} of ${env.name} while indexing", e)
            null
        }
    }
}
