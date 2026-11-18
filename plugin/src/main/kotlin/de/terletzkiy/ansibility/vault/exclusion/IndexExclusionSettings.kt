package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import org.jetbrains.annotations.TestOnly
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Which files the IDE keeps out of its indexes (plan amendment R30, D224, D226, D228): per user and for every project,
 * at application level in `ansibility-vault-index.xml` of the IDE configuration directory (roaming disabled, like the
 * rest of the per-user vault state). Application level because [PasswordFileExcluder], an application-wide workspace
 * file index contributor, gets no project.
 *
 * - [patterns]: the built-in names ([PasswordFileNames.BUILT_IN]), the extra names you added (file names only, `*` and
 *   `?` as wildcards, validated by [PasswordFileNames.problemOf]) and `.env.local` when [excludeEnvLocal] is on.
 * - A changed list applies when a project opens (D225): the platform has no public API to apply exclusions again.
 * - Per project location, the [fingerprint] of the list its excluded password files were last re-indexed for (D226),
 *   so `PasswordFileReindex` asks the platform once per project and list version.
 *
 * File names only, never a path or content. Any thread.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilityVaultIndexExclusions", storages = [Storage(value = "ansibility-vault-index.xml", roamingType = RoamingType.DISABLED)])
class IndexExclusionSettings : PersistentStateComponent<IndexExclusionSettings.StateBean> {
    private val tracker = SimpleModificationTracker()
    private val reindexed = ConcurrentHashMap<String, String>()

    @Volatile
    private var extra: List<String> = emptyList()

    @Volatile
    private var envLocal: Boolean = false

    /** Bumped whenever [patterns] changes. */
    val modificationTracker: ModificationTracker get() = tracker

    /** The names you added, in order (valid and distinct). */
    val extraNames: List<String> get() = extra

    /** Whether `.env.local` is kept out too (D228; off by default: the `.env` plugin and Compose read `.env*` files). */
    val excludeEnvLocal: Boolean get() = envLocal

    /** Every name pattern kept out of the indexes: built-in, then the extra names, then `.env.local` when ticked. */
    fun patterns(): List<String> = (PasswordFileNames.BUILT_IN + extra + listOfNotNull(PasswordFileNames.ENV_LOCAL.takeIf { envLocal })).distinct()

    /** True when a file named [fileName] is kept out by [patterns] (once a project registers them, D225). */
    fun isKeptOut(fileName: String): Boolean = PasswordFileNames.matchesAny(patterns(), fileName)

    /**
     * Replaces the extra names and the `.env.local` choice; invalid names are dropped, duplicates and built-in names
     * too. Tells [IndexExclusionListener.TOPIC] when something changed. Any thread.
     */
    fun update(extraNames: List<String>, excludeEnvLocal: Boolean) {
        val names = clean(extraNames)
        val changed = synchronized(this) {
            if (names == extra && excludeEnvLocal == envLocal) return
            extra = names
            envLocal = excludeEnvLocal
            tracker.incModificationCount()
            true
        }
        if (changed) ApplicationManager.getApplication().messageBus.syncPublisher(IndexExclusionListener.TOPIC).exclusionsChanged()
    }

    /** Adds [names] (valid ones not yet kept out) to the extra names; returns the names added. */
    fun addNames(names: Collection<String>): List<String> {
        val added = synchronized(this) { clean(names).filter { !isKeptOut(it) && it !in extra } }
        if (added.isNotEmpty()) update(extra + added, envLocal)
        return added
    }

    /**
     * A short digest of [patterns] (sorted) and of how this build re-indexes ([REINDEX_VERSION]): what a project's last
     * re-index is compared with (D226).
     */
    fun fingerprint(): String {
        val text = "$REINDEX_VERSION\n" + patterns().sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** The fingerprint the excluded password files of the project at [projectKey] were last re-indexed for, or null. */
    fun reindexedFingerprint(projectKey: String): String? = reindexed[projectKey]

    /** Records that the excluded password files of the project at [projectKey] were re-indexed for [fingerprint]. */
    fun markReindexed(projectKey: String, fingerprint: String) {
        reindexed[projectKey] = fingerprint
    }

    override fun getState(): StateBean = StateBean().also { bean ->
        bean.extraNames = extra.toMutableList()
        bean.excludeEnvLocal = envLocal
        bean.reindexed = reindexed.entries.sortedBy { it.key }.mapTo(ArrayList()) { (project, fingerprint) ->
            ReindexedBean().also {
                it.project = project
                it.fingerprint = fingerprint
            }
        }
    }

    override fun loadState(state: StateBean) {
        synchronized(this) {
            extra = clean(state.extraNames)
            envLocal = state.excludeEnvLocal
            reindexed.clear()
            for (bean in state.reindexed) {
                val project = bean.project ?: continue
                val fingerprint = bean.fingerprint ?: continue
                reindexed[project] = fingerprint
            }
            tracker.incModificationCount()
        }
    }

    /** Back to the defaults: no extra names, `.env.local` not kept out, nothing re-indexed (tests share one application). */
    @TestOnly
    fun resetForTests() {
        loadState(StateBean())
    }

    private fun clean(names: Collection<String>): List<String> =
        names.map { it.trim() }.filter { PasswordFileNames.isValid(it) && it !in PasswordFileNames.BUILT_IN }.distinct()

    /** XML form of the state. */
    class StateBean {
        @get:XCollection(propertyElementName = "extraNames", elementName = "name", valueAttributeName = "value")
        var extraNames: MutableList<String> = ArrayList()

        @get:Attribute("excludeEnvLocal")
        var excludeEnvLocal: Boolean = false

        @get:XCollection(propertyElementName = "reindexed", elementName = "project")
        var reindexed: MutableList<ReindexedBean> = ArrayList()
    }

    /** XML form of one project's last re-index: its location and the list's fingerprint. */
    @Tag("project")
    class ReindexedBean {
        @get:Attribute("location")
        var project: String? = null

        @get:Attribute("fingerprint")
        var fingerprint: String? = null
    }

    companion object {
        /** Bumped when the re-index of [PasswordFileReindex] changes, so every project re-indexes once more. */
        private const val REINDEX_VERSION = 1

        private const val FINGERPRINT_BYTES = 8

        fun getInstance(): IndexExclusionSettings = service()
    }
}

/** Published on the application bus when [IndexExclusionSettings.patterns] changed, on the thread that changed it. */
fun interface IndexExclusionListener {
    fun exclusionsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<IndexExclusionListener> = Topic(IndexExclusionListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
