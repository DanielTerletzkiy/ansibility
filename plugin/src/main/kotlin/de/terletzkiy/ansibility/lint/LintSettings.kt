package de.terletzkiy.ansibility.lint

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/** Where ansible-lint runs (plan amendment R28, D-L1). */
enum class LintExecutor {
    /** The root's Compose service with "lint" in its name when one mounts the root, else a local ansible-lint. */
    AUTO,
    LOCAL,
    COMPOSE,
}

/** How ansible-lint's levels show in the editor (D-L2). */
enum class LintSeverity {
    /** error → warning, warning/note → weak warning: red stays for Ansibility's certain-failure checks. */
    YELLOW,

    /** error → error, warning → warning, note → weak warning (like ansible-lint and the other plugin). */
    MIRROR,

    /** Everything a weak warning. */
    WEAK,
}

/**
 * The ansible-lint settings of one root (plan amendment R28). Shared (committed): [enabled], [config], [extraArgs],
 * [offline], [severity]. Personal (machine-dependent): [executor], [executable], [service].
 */
data class LintRootSettings(
    val enabled: Boolean = true,
    /** The config file, relative to the root (Auto: `ansible-lint.yml`, `.ansible-lint`, … next to the root or above). */
    val config: String? = null,
    val extraArgs: String = "",
    val offline: Boolean = true,
    val severity: LintSeverity = LintSeverity.YELLOW,
    val executor: LintExecutor = LintExecutor.AUTO,
    /** A local ansible-lint (Auto: next to ansible-playbook, then PATH). */
    val executable: String? = null,
    /** The Compose service (Auto: the first with "lint" in its name that mounts the root). */
    val service: String? = null,
) {
    companion object {
        val DEFAULT = LintRootSettings()
    }
}

/** The lint settings of the project's roots: [SharedLintSettings] plus [PersonalLintSettings], by `RootKeys` key. */
@Service(Service.Level.PROJECT)
class LintSettings(private val project: Project) {
    private val shared: SharedLintSettings get() = project.service()
    private val personal: PersonalLintSettings get() = project.service()

    val modificationTracker: ModificationTracker
        get() = ModificationTracker { shared.tracker.modificationCount + personal.tracker.modificationCount }

    fun rootSettings(rootKey: String): LintRootSettings {
        val s = shared.roots[rootKey] ?: SharedLintSettings.Entry()
        val p = personal.roots[rootKey] ?: PersonalLintSettings.Entry()
        return LintRootSettings(s.enabled, s.config, s.extraArgs, s.offline, s.severity, p.executor, p.executable, p.service)
    }

    fun update(rootKey: String, settings: LintRootSettings) {
        shared.put(rootKey, SharedLintSettings.Entry(settings.enabled, settings.config, settings.extraArgs, settings.offline, settings.severity))
        personal.put(rootKey, PersonalLintSettings.Entry(settings.executor, settings.executable, settings.service))
    }

    /** Lint changed files before commit (personal, off by default, D-L4). */
    var checkCommits: Boolean
        get() = personal.checkCommits
        set(value) = personal.setCheckCommits(value)

    companion object {
        fun getInstance(project: Project): LintSettings = project.service()
    }
}

/** The committed part, in `.idea/ansibility.xml`. Use [LintSettings]. */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityLint", storages = [Storage("ansibility.xml")])
class SharedLintSettings : PersistentStateComponent<SharedLintSettings.StateBean> {
    data class Entry(
        val enabled: Boolean = true,
        val config: String? = null,
        val extraArgs: String = "",
        val offline: Boolean = true,
        val severity: LintSeverity = LintSeverity.YELLOW,
    )

    internal val tracker = com.intellij.openapi.util.SimpleModificationTracker()

    @Volatile
    internal var roots: Map<String, Entry> = emptyMap()
        private set

    internal fun put(key: String, entry: Entry) {
        val updated = if (entry == Entry()) roots - key else roots + (key to entry)
        if (updated == roots) return
        roots = updated
        tracker.incModificationCount()
    }

    override fun getState(): StateBean = StateBean().apply {
        roots = this@SharedLintSettings.roots.entries.sortedBy { it.key }.mapTo(ArrayList()) { (key, e) ->
            RootBean().apply {
                this.key = key
                enabled = e.enabled
                config = e.config
                extraArgs = e.extraArgs.takeIf { it.isNotBlank() }
                offline = e.offline
                severity = e.severity.name
            }
        }
    }

    override fun loadState(state: StateBean) {
        roots = state.roots.mapNotNull { b ->
            val key = b.key ?: return@mapNotNull null
            key to Entry(b.enabled, b.config?.takeIf { it.isNotBlank() }, b.extraArgs.orEmpty(), b.offline,
                LintSeverity.entries.firstOrNull { it.name == b.severity } ?: LintSeverity.YELLOW)
        }.toMap()
        tracker.incModificationCount()
    }

    class StateBean {
        @get:XCollection(propertyElementName = "roots", elementName = "root")
        var roots: MutableList<RootBean> = ArrayList()
    }

    @Tag("root")
    class RootBean {
        @get:Attribute("key") var key: String? = null
        @get:Attribute("enabled") var enabled: Boolean = true
        @get:Attribute("config") var config: String? = null
        @get:Attribute("extraArgs") var extraArgs: String? = null
        @get:Attribute("offline") var offline: Boolean = true
        @get:Attribute("severity") var severity: String? = null
    }
}

/** The machine-dependent part, in the workspace file (never committed). Use [LintSettings]. */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityPersonalLint", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PersonalLintSettings : PersistentStateComponent<PersonalLintSettings.StateBean> {
    data class Entry(val executor: LintExecutor = LintExecutor.AUTO, val executable: String? = null, val service: String? = null)

    internal val tracker = com.intellij.openapi.util.SimpleModificationTracker()

    @Volatile
    internal var roots: Map<String, Entry> = emptyMap()
        private set

    @Volatile
    internal var checkCommits: Boolean = false
        private set

    internal fun put(key: String, entry: Entry) {
        val updated = if (entry == Entry()) roots - key else roots + (key to entry)
        if (updated == roots) return
        roots = updated
        tracker.incModificationCount()
    }

    internal fun setCheckCommits(value: Boolean) {
        if (checkCommits == value) return
        checkCommits = value
        tracker.incModificationCount()
    }

    override fun getState(): StateBean = StateBean().apply {
        checkCommits = this@PersonalLintSettings.checkCommits
        roots = this@PersonalLintSettings.roots.entries.sortedBy { it.key }.mapTo(ArrayList()) { (key, e) ->
            RootBean().apply {
                this.key = key
                executor = e.executor.name
                executable = e.executable
                service = e.service
            }
        }
    }

    override fun loadState(state: StateBean) {
        checkCommits = state.checkCommits
        roots = state.roots.mapNotNull { b ->
            val key = b.key ?: return@mapNotNull null
            key to Entry(LintExecutor.entries.firstOrNull { it.name == b.executor } ?: LintExecutor.AUTO,
                b.executable?.takeIf { it.isNotBlank() }, b.service?.takeIf { it.isNotBlank() })
        }.toMap()
        tracker.incModificationCount()
    }

    class StateBean {
        @get:Attribute("checkCommits")
        var checkCommits: Boolean = false

        @get:XCollection(propertyElementName = "roots", elementName = "root")
        var roots: MutableList<RootBean> = ArrayList()
    }

    @Tag("root")
    class RootBean {
        @get:Attribute("key") var key: String? = null
        @get:Attribute("executor") var executor: String? = null
        @get:Attribute("executable") var executable: String? = null
        @get:Attribute("service") var service: String? = null
    }
}
