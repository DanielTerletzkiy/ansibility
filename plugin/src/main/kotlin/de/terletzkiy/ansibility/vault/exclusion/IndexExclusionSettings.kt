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
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Which files the IDE keeps out of its indexes (plan amendment R30, D224, D226, D228; R30 review fixes, decisions 1 and
 * 7): per user and for every project, at application level in `ansibility-vault-index.xml` of the IDE configuration
 * directory (roaming disabled, like the rest of the per-user vault state). Application level because
 * [PasswordFileExcluder], an application-wide workspace file index contributor, gets no project.
 *
 * - Name entries ([patterns]): the built-in names ([PasswordFileNames.BUILT_IN]), the extra names you typed (file names
 *   only, `*` and `?` as wildcards, validated by [PasswordFileNames.problemOf]) and `.env.local` when [excludeEnvLocal]
 *   is on.
 * - Exact-path entries ([paths]): the canonical absolute paths of the password files added from the Vault tab, its
 *   notification or a suggestion of the settings page; only that file is kept out, never another of the same name.
 * - [rules] compiles both; [KeepOutRules.excludes] never takes a directory, a vars or vault file or a script.
 * - A changed list applies when a project opens (D225): the platform has no public API to apply exclusions again.
 * - Per project location, whether the one-time Local History notice was shown (decision 7).
 *
 * File names and paths only, never content. Any thread.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilityVaultIndexExclusions", storages = [Storage(value = "ansibility-vault-index.xml", roamingType = RoamingType.DISABLED)])
class IndexExclusionSettings : PersistentStateComponent<IndexExclusionSettings.StateBean> {
    private val tracker = SimpleModificationTracker()
    private val noticed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var extra: List<String> = emptyList()

    @Volatile
    private var exact: List<String> = emptyList()

    @Volatile
    private var envLocal: Boolean = false

    @Volatile
    private var compiled: KeepOutRules? = null

    /** Bumped whenever [patterns] or [paths] change. */
    val modificationTracker: ModificationTracker get() = tracker

    /** The names you added, in order (valid and distinct). */
    val extraNames: List<String> get() = extra

    /** The exact files kept out, as canonical absolute paths, in the order they were added. */
    val paths: List<String> get() = exact

    /** Whether `.env.local` is kept out too (D228; off by default: the `.env` plugin and Compose read `.env*` files). */
    val excludeEnvLocal: Boolean get() = envLocal

    /** Every name pattern kept out of the indexes: built-in, then the extra names, then `.env.local` when ticked. */
    fun patterns(): List<String> = (PasswordFileNames.BUILT_IN + extra + listOfNotNull(PasswordFileNames.ENV_LOCAL.takeIf { envLocal })).distinct()

    /** The name and path entries, compiled ([KeepOutRules]); the same object until the entries change. */
    fun rules(): KeepOutRules = compiled ?: synchronized(this) { compiled ?: KeepOutRules(patterns(), exact).also { compiled = it } }

    /** True when [file] is kept out by the entries as they are now ([KeepOutRules.excludes]; once a project registers them, D225). */
    fun isKeptOut(file: VirtualFile): Boolean = rules().excludes(file)

    /**
     * True when a file named [fileName] is kept out by a name entry, with the local file system's case rule; exact-path
     * entries and the guard are not asked (a name only).
     */
    fun isKeptOut(fileName: String): Boolean = rules().matchesName(fileName, SystemInfo.isFileSystemCaseSensitive)

    /**
     * Replaces the extra names, the `.env.local` choice and the exact paths; invalid names and paths are dropped,
     * duplicates and built-in names too. Tells [IndexExclusionListener.TOPIC] when something changed. Any thread.
     */
    fun update(extraNames: List<String>, excludeEnvLocal: Boolean, paths: List<String> = this.paths) {
        val names = clean(extraNames)
        val files = cleanPaths(paths)
        val changed = synchronized(this) {
            if (names == extra && excludeEnvLocal == envLocal && files == exact) return
            extra = names
            envLocal = excludeEnvLocal
            exact = files
            compiled = null
            tracker.incModificationCount()
            true
        }
        if (changed) ApplicationManager.getApplication().messageBus.syncPublisher(IndexExclusionListener.TOPIC).exclusionsChanged()
    }

    /** Adds [names] (valid ones not yet kept out) to the extra names; returns the names added. */
    fun addNames(names: Collection<String>): List<String> {
        val (added, envLocalNow, pathsNow) = synchronized(this) { Triple(clean(names).filter { !isKeptOut(it) && it !in extra }, envLocal, exact) }
        if (added.isNotEmpty()) update(extra + added, envLocalNow, pathsNow)
        return added
    }

    /** Adds the exact [paths] not listed yet; returns the paths added. */
    fun addPaths(paths: Collection<String>): List<String> {
        val added = synchronized(this) { cleanPaths(paths).filter { it !in exact } }
        if (added.isNotEmpty()) update(extra, envLocal, exact + added)
        return added
    }

    /** Records that the Local History notice was shown for the project at [projectKey]; false when it was already. */
    fun markLocalHistoryNoticed(projectKey: String): Boolean = noticed.add(projectKey)

    override fun getState(): StateBean = StateBean().also { bean ->
        bean.extraNames = extra.toMutableList()
        bean.excludeEnvLocal = envLocal
        bean.paths = exact.toMutableList()
        bean.localHistoryNoticed = noticed.sorted().toMutableList()
    }

    override fun loadState(state: StateBean) {
        synchronized(this) {
            extra = clean(state.extraNames)
            envLocal = state.excludeEnvLocal
            exact = cleanPaths(state.paths)
            noticed.clear()
            state.localHistoryNoticed.filterTo(noticed) { it.isNotBlank() }
            compiled = null
            tracker.incModificationCount()
        }
    }

    /** Back to the defaults: no extra names or paths, `.env.local` not kept out, no notice shown (tests share one application). */
    @TestOnly
    fun resetForTests() {
        loadState(StateBean())
    }

    private fun clean(names: Collection<String>): List<String> =
        names.map { it.trim() }.filter { PasswordFileNames.isValid(it) && it !in PasswordFileNames.BUILT_IN }.distinct()

    private fun cleanPaths(paths: Collection<String>): List<String> = paths.map { it.trim() }.filter(KeepOutRules::isValidPath).distinct()

    /** XML form of the state. */
    class StateBean {
        @get:XCollection(propertyElementName = "extraNames", elementName = "name", valueAttributeName = "value")
        var extraNames: MutableList<String> = ArrayList()

        @get:Attribute("excludeEnvLocal")
        var excludeEnvLocal: Boolean = false

        @get:XCollection(propertyElementName = "paths", elementName = "file", valueAttributeName = "path")
        var paths: MutableList<String> = ArrayList()

        @get:XCollection(propertyElementName = "localHistoryNoticed", elementName = "project", valueAttributeName = "location")
        var localHistoryNoticed: MutableList<String> = ArrayList()
    }

    companion object {
        fun getInstance(): IndexExclusionSettings = service()
    }
}

/** Published on the application bus when [IndexExclusionSettings.patterns] or its paths changed, on the thread that changed it. */
fun interface IndexExclusionListener {
    fun exclusionsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<IndexExclusionListener> = Topic(IndexExclusionListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
