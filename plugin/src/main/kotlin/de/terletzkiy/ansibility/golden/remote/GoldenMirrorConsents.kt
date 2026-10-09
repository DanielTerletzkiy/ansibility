package de.terletzkiy.ansibility.golden.remote

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
 * This machine's answers to "Fetch the golden root from <url>?" (plan amendment R25, D203): per user at application
 * level in `ansibility-golden-mirrors.xml` of the IDE configuration directory, roaming disabled (one answer per
 * machine), keyed by the repository URL ([GoldenGitUrls.effective]). In the spirit of DEV.md rule 12 nothing here comes
 * from a project file: a committed `.idea` file cannot pre-approve its own URL. A URL typed on the settings page of this
 * machine, Fetch Now and the notification's Fetch record "allowed"; "Not on this machine" records "declined".
 * Bounded to [MAX_ENTRIES] URLs, the least recently answered dropped first.
 *
 * Also this machine's opt-in to background refreshes through an external SSH agent ([sshAgentRefresh], R25 step 2):
 * such agents (1Password, Secretive…) may ask for approval on every use, so SSH mirrors refresh only on demand unless
 * the user allows it here.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilityGoldenMirrorConsents", storages = [Storage(value = "ansibility-golden-mirrors.xml", roamingType = RoamingType.DISABLED)])
class GoldenMirrorConsents : PersistentStateComponent<GoldenMirrorConsents.StateBean> {
    private val lock = Any()
    private val answers = LinkedHashMap<String, Boolean>()

    @Volatile
    private var sshAgent = false

    /**
     * "Refresh in the background through my SSH agent (it may ask for approval)": background refreshes of SSH URLs no
     * longer pause for an external SSH agent. Off by default; per user and machine, never in project files.
     */
    var sshAgentRefresh: Boolean
        get() = sshAgent
        set(value) {
            sshAgent = value
        }

    /** True (allowed), false (declined) or null (not asked yet) for [url]. */
    fun answer(url: String): Boolean? = synchronized(lock) { answers[key(url)] }

    /** Records this machine's answer for [url]. */
    fun set(url: String, allowed: Boolean) = synchronized(lock) {
        val key = key(url)
        if (key.isEmpty()) return@synchronized
        answers.remove(key)
        answers[key] = allowed
        while (answers.size > MAX_ENTRIES) answers.remove(answers.keys.first())
    }

    private fun key(url: String): String = GoldenGitUrls.effective(url)

    override fun getState(): StateBean = synchronized(lock) {
        StateBean().apply {
            entries = answers.map { (url, allowed) -> EntryBean(url, allowed) }.toMutableList()
            sshAgentRefresh = sshAgent
        }
    }

    override fun loadState(state: StateBean) = synchronized(lock) {
        sshAgent = state.sshAgentRefresh
        answers.clear()
        for (entry in state.entries.takeLast(MAX_ENTRIES)) {
            val url = entry.url?.takeIf { it.isNotBlank() } ?: continue
            answers[url] = entry.allowed
        }
    }

    @TestOnly
    fun resetForTests() = loadState(StateBean())

    /** XML form. */
    class StateBean {
        @get:XCollection(propertyElementName = "urls", elementName = "url")
        var entries: MutableList<EntryBean> = ArrayList()

        @get:Attribute("sshAgentRefresh")
        var sshAgentRefresh: Boolean = false
    }

    /** XML form of one answer. */
    @Tag("url")
    class EntryBean() {
        constructor(url: String, allowed: Boolean) : this() {
            this.url = url
            this.allowed = allowed
        }

        @get:Attribute("value")
        var url: String? = null

        @get:Attribute("allowed")
        var allowed: Boolean = false
    }

    companion object {
        const val MAX_ENTRIES: Int = 200

        fun getInstance(): GoldenMirrorConsents = service()
    }
}
