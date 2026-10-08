package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import org.jetbrains.annotations.TestOnly

/**
 * What the vault notifications remember (plan amendment R21, D166), per user at application level in
 * `ansibility-vault-notifications.xml` of the IDE configuration directory (roaming disabled), keyed by the project's
 * location: the findings already announced ([known], with the time they were last seen and whether they were an error)
 * and the ones you silenced with "Don't Show Again for These" ([silenced]), each as `SecretFinding.key` (repository,
 * path below it, category; never content).
 *
 * - A finding is announced once ([isAnnounced]): it stays known while it is present and for [FORGET_AFTER_MS] after it
 *   went away, so switching branches back and forth announces nothing again, while a key that comes back weeks after
 *   its fix is new. A finding known (or silenced) as a warning is announced again when it becomes an error (an
 *   untracked key committed from the terminal).
 * - In the spirit of DEV.md rule 12, nothing here comes from a project file: a cloned repository cannot ship an
 *   acknowledgement that silences its own findings.
 * - Bounded: at most [MAX_KEYS] findings per project and set (never fewer than are present now, so a workspace with more
 *   findings is not announced again on every snapshot), [MAX_PROJECTS] projects, the least recently used dropped first.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilityVaultNotifications", storages = [Storage(value = "ansibility-vault-notifications.xml", roamingType = RoamingType.DISABLED)])
class SecretNotificationMemory : PersistentStateComponent<SecretNotificationMemory.StateBean> {
    /** A remembered finding: when it was last seen, and whether it was ever an error while remembered. */
    private class Entry(val seen: Long, val error: Boolean)

    private class Memory(val known: LinkedHashMap<String, Entry> = LinkedHashMap(), val silenced: LinkedHashMap<String, Boolean> = LinkedHashMap())

    private val lock = Any()

    /** By project location, least recently used first. */
    private val projects = LinkedHashMap<String, Memory>(16, 0.75f, true)

    /** The findings already announced in the project at [location]. */
    fun known(location: String): Set<String> = synchronized(lock) { projects[location]?.known?.keys?.toSet().orEmpty() }

    /** The findings of [location] you silenced. */
    fun silenced(location: String): Set<String> = synchronized(lock) { projects[location]?.silenced?.keys?.toSet().orEmpty() }

    /**
     * True when the finding [key] of [location], an error when [error], needs no announcement: it is known or silenced
     * at its level (an error is not covered by a warning).
     */
    fun isAnnounced(location: String, key: String, error: Boolean): Boolean = synchronized(lock) {
        val memory = projects[location] ?: return false
        fun covers(wasError: Boolean?) = wasError != null && (wasError || !error)
        covers(memory.known[key]?.error) || covers(memory.silenced[key])
    }

    /**
     * Remembers the [current] findings of [location] (key to "is an error") as seen at [now]; forgets the findings
     * absent for longer than [FORGET_AFTER_MS], then the least recently seen beyond the bound.
     */
    fun remember(location: String, current: Map<String, Boolean>, now: Long) = synchronized(lock) {
        val known = memoryOf(location).known
        for ((key, error) in current) {
            val before = known.remove(key)
            known[key] = Entry(now, error || before?.error == true)
        }
        known.entries.removeIf { (key, entry) -> key !in current && now - entry.seen > FORGET_AFTER_MS }
        trim(known, maxOf(MAX_KEYS, current.size))
    }

    /** Silences [findings] (key to "is an error") in [location] for good (the oldest silenced keys go first beyond [MAX_KEYS]). */
    fun silence(location: String, findings: Map<String, Boolean>) = synchronized(lock) {
        val silenced = memoryOf(location).silenced
        for ((key, error) in findings) {
            val before = silenced.remove(key)
            silenced[key] = error || before == true
        }
        trim(silenced, MAX_KEYS)
    }

    private fun trim(map: LinkedHashMap<String, *>, bound: Int) {
        val excess = map.size - bound
        if (excess > 0) map.keys.iterator().let { iterator -> repeat(excess) { iterator.next(); iterator.remove() } }
    }

    private fun memoryOf(location: String): Memory {
        val memory = projects.getOrPut(location, ::Memory)
        while (projects.size > MAX_PROJECTS) projects.remove(projects.keys.first())
        return memory
    }

    override fun getState(): StateBean = synchronized(lock) {
        StateBean().apply {
            projects = this@SecretNotificationMemory.projects.map { (location, memory) ->
                ProjectBean().apply {
                    this.location = location
                    known = memory.known.map { (key, entry) -> FindingBean(key, entry.seen, entry.error) }.toMutableList()
                    silenced = memory.silenced.map { (key, error) -> FindingBean(key, 0, error) }.toMutableList()
                }
            }.toMutableList()
        }
    }

    override fun loadState(state: StateBean) = synchronized(lock) {
        projects.clear()
        for (bean in state.projects) {
            val location = bean.location ?: continue
            val memory = Memory()
            for (finding in bean.known.takeLast(MAX_KEYS)) finding.key?.let { memory.known[it] = Entry(finding.seen, finding.error) }
            for (finding in bean.silenced.takeLast(MAX_KEYS)) finding.key?.let { memory.silenced[it] = finding.error }
            projects[location] = memory
        }
    }

    /** Forgets everything (tests share one application). */
    @TestOnly
    fun resetForTests() = loadState(StateBean())

    /** XML form of the state. */
    class StateBean {
        @get:XCollection(propertyElementName = "projects", elementName = "project")
        var projects: MutableList<ProjectBean> = ArrayList()
    }

    /** XML form of one project's memory. */
    @Tag("project")
    class ProjectBean {
        @get:Attribute("location")
        var location: String? = null

        @get:XCollection(propertyElementName = "known")
        var known: MutableList<FindingBean> = ArrayList()

        @get:XCollection(propertyElementName = "silenced")
        var silenced: MutableList<FindingBean> = ArrayList()
    }

    /** XML form of one remembered finding: its key, when it was last seen (known only) and whether it was an error. */
    @Tag("finding")
    class FindingBean() {
        constructor(key: String, seen: Long, error: Boolean) : this() {
            this.key = key
            this.seen = seen
            this.error = error
        }

        @get:Attribute("key")
        var key: String? = null

        @get:Attribute("seen")
        var seen: Long = 0

        @get:Attribute("error")
        var error: Boolean = false
    }

    companion object {
        /** The most findings remembered per project and set (more while more are present). */
        const val MAX_KEYS: Int = 5000

        /** The most projects remembered. */
        const val MAX_PROJECTS: Int = 100

        /** How long a finding stays known after it went away: a week (branch switches come back sooner). */
        const val FORGET_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000

        fun getInstance(): SecretNotificationMemory = service()
    }
}
