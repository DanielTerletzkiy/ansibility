package de.terletzkiy.ansibility.toolwindow.host

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.HostKey
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * What a host's Effective vars are evaluated in (plan amendment R7/R8 F8.7, the play selector of WU HA7a).
 */
sealed interface PlayChoice {
    /**
     * Follow the Ansible context (D33, presentation only): the context's play when it selects this host and a play that
     * runs on it, else every play that runs on the host (F8.7 "Auto = all plays that hit the host"), each with its own
     * playbook dir, grouped by the definition that wins; the inventory view for a host no play runs on.
     */
    data object Auto : PlayChoice

    /** The inventory view: levels 3–10 with the root's playbook dir, as `ansible-inventory --host` reports them. */
    data object Inventory : PlayChoice

    /**
     * One play that runs on the host, by its stored key (`context.host.PlayKeys`: `<playbook path>#<index>`). It adds
     * role defaults (L2), play vars and `vars_files` (L12, L13), role vars (L14) and the runtime markers, and takes
     * L5/L7/L10 from the play's own playbook dir.
     */
    data class Play(val key: String) : PlayChoice
}

/**
 * The play choice of every host's Effective vars in this IDE session (in memory: the persisted Ansible context is
 * HA3's, and this is only a view setting). Hosts without a choice are [PlayChoice.Auto]. The tool window re-renders
 * after a change ([set] says whether there was one); nothing here is a model input.
 */
@Service(Service.Level.PROJECT)
class EffectivePlayChoices {
    private val choices = ConcurrentHashMap<HostKey, PlayChoice>()

    /** The choice for [host]; [PlayChoice.Auto] when none was made. */
    fun get(host: HostKey): PlayChoice = choices[host] ?: PlayChoice.Auto

    /** Forgets every choice (tests share one light project). */
    @TestOnly
    fun clear() = choices.clear()

    /** Stores [choice] for [host]; true when it changed. */
    fun set(host: HostKey, choice: PlayChoice): Boolean {
        val previous = if (choice == PlayChoice.Auto) choices.remove(host) else choices.put(host, choice)
        return (previous ?: PlayChoice.Auto) != choice
    }

    companion object {
        fun getInstance(project: Project): EffectivePlayChoices = project.service()
    }
}
