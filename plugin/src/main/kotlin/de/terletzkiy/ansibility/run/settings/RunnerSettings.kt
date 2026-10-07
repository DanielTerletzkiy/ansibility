package de.terletzkiy.ansibility.run.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import de.terletzkiy.ansibility.api.VaultSourceKind

/** The SSH jump host the connection goes through (`ProxyCommand ssh … -W %h:%p user@host`). A blank [user] is the remote user. */
data class JumpHost(
    val enabled: Boolean = false,
    val user: String = "",
    val host: String = "",
    /** Digits, or blank for ssh's default. */
    val port: String = "",
    val forwardAgent: Boolean = false,
    /** Further `ssh` arguments for the jump connection, as typed. */
    val extraArgs: String = "",
)

/**
 * Where a become password comes from: the kinds of a vault id's source ([VaultSourceKind.PROMPT] asks before the run
 * unless one was remembered). [location] is the password manager reference, the password file or the environment
 * variable; null for the IDE password store and the prompt.
 */
data class BecomeSource(val kind: VaultSourceKind, val location: String? = null)

/**
 * The runner settings of one root: everything a run needs that is not a choice of the run itself, so that no
 * `.env.local` is needed. They are personal (the remote user, the become password source), so they are stored in
 * the workspace file and never shared.
 */
data class RunnerRootSettings(
    /** `ansible_user` of every host; blank keeps the inventory's. */
    val remoteUser: String = "",
    val jumpHost: JumpHost = JumpHost(),
    /** `-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null`. */
    val skipHostKeyChecking: Boolean = false,
    /** The become password source by environment id; [ALL_ENVIRONMENTS] applies to every environment without its own. */
    val become: Map<String, BecomeSource> = emptyMap(),
    /** Values for the variables the Compose file interpolates (volume sources such as a known_hosts path). */
    val composeVariables: Map<String, String> = emptyMap(),
    /** Environment variables of the `ansible-playbook` process (passed into the container for a Compose run). */
    val environmentVariables: Map<String, String> = emptyMap(),
    /** Warn before a run (not in check mode) when the checkout is behind its upstream branch. */
    val checkFreshness: Boolean = true,
    /** The branch to compare with (`origin/main`, `main`); blank: the remote's default branch. */
    val freshnessBranch: String = "",
    /** Pass `GIT_URL`, `GIT_COMMIT`, `GIT_BRANCH` and `PROVISION_USER` to the run (report callbacks read them). */
    val runMetadata: Boolean = true,
) {
    /** The source for [environment]: its own, else the one for all environments; null when none is configured. */
    fun becomeSource(environment: String?): BecomeSource? = environment?.let { become[it] } ?: become[ALL_ENVIRONMENTS]

    /** The scope [becomeSource] took the source from: [environment] or [ALL_ENVIRONMENTS]. */
    fun becomeScope(environment: String?): String =
        if (environment != null && become.containsKey(environment)) environment else ALL_ENVIRONMENTS

    /** Whether the settings change how hosts are reached. */
    val changesConnection: Boolean get() = remoteUser.isNotBlank() || (jumpHost.enabled && jumpHost.host.isNotBlank()) || skipHostKeyChecking

    companion object {
        const val ALL_ENVIRONMENTS: String = "*"
        val DEFAULT = RunnerRootSettings()
    }
}

/**
 * The runner settings of the project's roots, keyed by `settings.RootKeys` keys, in the workspace file (personal: never
 * committed). [rootSettings] is safe to read from any thread; [update] replaces one root's entry.
 */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityRunner", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RunnerSettings : PersistentStateComponent<RunnerSettings.StateBean> {
    private val tracker = SimpleModificationTracker()

    @Volatile
    private var roots: Map<String, RunnerRootSettings> = emptyMap()

    val modificationTracker: ModificationTracker get() = tracker

    fun rootSettings(rootKey: String): RunnerRootSettings = roots[rootKey] ?: RunnerRootSettings.DEFAULT

    /** Replaces the settings of [rootKey] with [transform] applied to them; default settings remove the entry. */
    fun update(rootKey: String, transform: (RunnerRootSettings) -> RunnerRootSettings) {
        synchronized(this) {
            val new = transform(rootSettings(rootKey))
            val updated = if (new == RunnerRootSettings.DEFAULT) roots - rootKey else roots + (rootKey to new)
            if (updated == roots) return
            roots = updated
        }
        tracker.incModificationCount()
    }

    override fun getState(): StateBean = StateBean().apply {
        roots = this@RunnerSettings.roots.entries.sortedBy { it.key }.map { (key, settings) -> RootBean.of(key, settings) }.toMutableList()
    }

    override fun loadState(state: StateBean) {
        roots = state.roots.mapNotNull { bean -> bean.key?.let { it to bean.toSettings() } }.toMap()
        tracker.incModificationCount()
    }

    class StateBean {
        @get:XCollection(propertyElementName = "roots", elementName = "root")
        var roots: MutableList<RootBean> = ArrayList()
    }

    @Tag("root")
    class RootBean {
        @get:Attribute("key") var key: String? = null
        @get:Attribute("remoteUser") var remoteUser: String? = null
        @get:Attribute("jumpHost") var jumpEnabled: Boolean = false
        @get:Attribute("jumpUser") var jumpUser: String? = null
        @get:Attribute("jumpHostName") var jumpHost: String? = null
        @get:Attribute("jumpPort") var jumpPort: String? = null
        @get:Attribute("jumpForwardAgent") var jumpForwardAgent: Boolean = false
        @get:Attribute("jumpExtraArgs") var jumpExtraArgs: String? = null
        @get:Attribute("skipHostKeyChecking") var skipHostKeyChecking: Boolean = false
        @get:Attribute("checkFreshness") var checkFreshness: Boolean = true
        @get:Attribute("freshnessBranch") var freshnessBranch: String? = null
        @get:Attribute("runMetadata") var runMetadata: Boolean = true

        @get:XCollection(propertyElementName = "become", elementName = "source")
        var become: MutableList<BecomeBean> = ArrayList()

        @get:XCollection(propertyElementName = "variables", elementName = "variable")
        var variables: MutableList<VariableBean> = ArrayList()

        fun toSettings(): RunnerRootSettings = RunnerRootSettings(
            remoteUser = remoteUser.orEmpty(),
            jumpHost = JumpHost(jumpEnabled, jumpUser.orEmpty(), jumpHost.orEmpty(), jumpPort.orEmpty(), jumpForwardAgent, jumpExtraArgs.orEmpty()),
            skipHostKeyChecking = skipHostKeyChecking,
            become = become.mapNotNull { bean -> bean.toSource()?.let { bean.environment!! to it } }.toMap(LinkedHashMap()),
            composeVariables = variables.filter { it.scope == COMPOSE }.mapNotNull { it.pair() }.toMap(LinkedHashMap()),
            environmentVariables = variables.filter { it.scope == PROCESS }.mapNotNull { it.pair() }.toMap(LinkedHashMap()),
            checkFreshness = checkFreshness,
            freshnessBranch = freshnessBranch.orEmpty(),
            runMetadata = runMetadata,
        )

        companion object {
            fun of(key: String, settings: RunnerRootSettings): RootBean = RootBean().apply {
                this.key = key
                remoteUser = settings.remoteUser.ifEmpty { null }
                jumpEnabled = settings.jumpHost.enabled
                jumpUser = settings.jumpHost.user.ifEmpty { null }
                jumpHost = settings.jumpHost.host.ifEmpty { null }
                jumpPort = settings.jumpHost.port.ifEmpty { null }
                jumpForwardAgent = settings.jumpHost.forwardAgent
                jumpExtraArgs = settings.jumpHost.extraArgs.ifEmpty { null }
                skipHostKeyChecking = settings.skipHostKeyChecking
                checkFreshness = settings.checkFreshness
                freshnessBranch = settings.freshnessBranch.ifEmpty { null }
                runMetadata = settings.runMetadata
                become = settings.become.map { (environment, source) -> BecomeBean.of(environment, source) }.toMutableList()
                variables = (settings.composeVariables.map { VariableBean.of(COMPOSE, it.key, it.value) } +
                    settings.environmentVariables.map { VariableBean.of(PROCESS, it.key, it.value) }).toMutableList()
            }
        }
    }

    @Tag("source")
    class BecomeBean {
        @get:Attribute("environment") var environment: String? = null
        @get:Attribute("kind") var kind: String? = null
        @get:Attribute("location") var location: String? = null

        fun toSource(): BecomeSource? {
            if (environment.isNullOrEmpty()) return null
            val kind = VaultSourceKind.entries.firstOrNull { it.name == kind } ?: return null
            return BecomeSource(kind, location?.takeIf { it.isNotEmpty() })
        }

        companion object {
            fun of(environment: String, source: BecomeSource) = BecomeBean().apply {
                this.environment = environment
                kind = source.kind.name
                location = source.location
            }
        }
    }

    @Tag("variable")
    class VariableBean {
        @get:Attribute("scope") var scope: String? = null
        @get:Attribute("name") var name: String? = null
        @get:Attribute("value") var value: String? = null

        fun pair(): Pair<String, String>? = name?.takeIf { it.isNotEmpty() }?.let { it to value.orEmpty() }

        companion object {
            fun of(scope: String, name: String, value: String) = VariableBean().apply {
                this.scope = scope
                this.name = name
                this.value = value
            }
        }
    }

    companion object {
        private const val COMPOSE = "compose"
        private const val PROCESS = "process"

        fun getInstance(project: Project): RunnerSettings = project.service()
    }
}
