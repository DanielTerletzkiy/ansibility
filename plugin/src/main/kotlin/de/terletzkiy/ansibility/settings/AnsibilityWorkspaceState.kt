package de.terletzkiy.ansibility.settings

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
import de.terletzkiy.ansibility.api.AnsibleRoot

/** Which environment of a root hover, completion and effective values look at (D12, 🟣 CLAUDE X40). */
sealed interface EnvironmentChoice {
    /** Every environment, one result per environment (the default, D12). */
    data object All : EnvironmentChoice

    /** One environment, `environments/<name>`. */
    data class Named(val name: String) : EnvironmentChoice
}

/**
 * The active evaluation context of one root (🟣 CLAUDE X13/X39/X40).
 *
 * [play] is an opaque play reference chosen by the play-graph features (recommended form:
 * `<playbook path relative to the root>#<play index>`); the play decides the playbook directory.
 */
data class RootContext(
    val environment: EnvironmentChoice = EnvironmentChoice.All,
    val host: String? = null,
    val play: String? = null,
) {
    companion object {
        val DEFAULT = RootContext()
    }
}

/**
 * Per-user workspace state, immutable: the active [RootContext] of each root (keyed by [RootKeys]), whether the
 * context follows the selected editor's root (R8 F8.1, project-wide), and the workspace scope (R9 F9.1:
 * `all | current-root | named:<scopeId> | roots:<RootKey,…>`; it narrows lists, never resolution).
 */
data class WorkspaceState(
    val roots: Map<String, RootContext> = emptyMap(),
    val followEditor: Boolean = true,
    val scope: String = SCOPE_ALL,
) {
    fun root(key: String): RootContext = roots[key] ?: RootContext.DEFAULT

    fun withRoot(key: String, context: RootContext): WorkspaceState =
        copy(roots = if (context == RootContext.DEFAULT) roots - key else roots + (key to context))

    companion object {
        const val SCOPE_ALL = "all"
        val DEFAULT = WorkspaceState()
    }
}

/**
 * Per-user state of the project, stored in the workspace file: the active environment per root ("All" by
 * default), and an optional active host and play (plan "Coexistence & settings", workspace level).
 *
 * Reads are lock-free snapshots; every change bumps [modificationTracker] and publishes
 * [AnsibilitySettingsListener.workspaceStateChanged].
 */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityWorkspaceState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AnsibilityWorkspaceState(private val project: Project) : PersistentStateComponent<AnsibilityWorkspaceState.StateBean> {
    private val lock = Any()
    private val tracker = SimpleModificationTracker()

    @Volatile
    private var current: WorkspaceState = WorkspaceState.DEFAULT

    @Volatile
    private var loaded = false

    /** The whole state. */
    val snapshot: WorkspaceState
        get() = current

    /** Bumped on every change. */
    val modificationTracker: ModificationTracker
        get() = tracker

    /** The active context of [root]. */
    fun context(root: AnsibleRoot): RootContext = current.root(RootKeys.keyOf(project, root.dir))

    /** The active environment of [root]. */
    fun environment(root: AnsibleRoot): EnvironmentChoice = context(root).environment

    fun setEnvironment(root: AnsibleRoot, environment: EnvironmentChoice) {
        updateContext(root) { it.copy(environment = environment) }
    }

    fun setHost(root: AnsibleRoot, host: String?) {
        updateContext(root) { it.copy(host = host.nonBlank()) }
    }

    fun setPlay(root: AnsibleRoot, play: String?) {
        updateContext(root) { it.copy(play = play.nonBlank()) }
    }

    /** Replaces the context of [root] with [transform] applied to it. */
    fun updateContext(root: AnsibleRoot, transform: (RootContext) -> RootContext) {
        val key = RootKeys.keyOf(project, root.dir)
        update { it.withRoot(key, transform(it.root(key))) }
    }

    /** Replaces the whole state. */
    fun update(transform: (WorkspaceState) -> WorkspaceState) {
        val (old, new) = synchronized(lock) {
            val old = current
            current = transform(old)
            old to current
        }
        if (old != new) changed(old, new)
    }

    override fun getState(): StateBean = StateBean.of(current)

    override fun loadState(state: StateBean) {
        val (old, new) = synchronized(lock) {
            val old = current
            current = state.toState()
            old to current
        }
        val reload = loaded
        loaded = true
        if (reload && old != new) changed(old, new)
    }

    override fun noStateLoaded() {
        loaded = true
    }

    private fun changed(old: WorkspaceState, new: WorkspaceState) {
        tracker.incModificationCount()
        if (!project.isDisposed) project.messageBus.syncPublisher(AnsibilitySettingsListener.TOPIC).workspaceStateChanged(old, new)
    }

    /** The XML form of [WorkspaceState]. */
    class StateBean {
        @get:XCollection(style = XCollection.Style.v2)
        var roots: MutableList<RootContextBean> = ArrayList()

        @get:Attribute("followEditor")
        var followEditor: Boolean = true

        @get:Attribute("scope")
        var scope: String? = null

        fun toState(): WorkspaceState = WorkspaceState(
            followEditor = followEditor,
            scope = scope.nonBlank() ?: WorkspaceState.SCOPE_ALL,
            roots = roots.filter { it.path.isNotBlank() }.associate { bean ->
                bean.path to RootContext(
                    environment = bean.environment.nonBlank()?.let { EnvironmentChoice.Named(it) } ?: EnvironmentChoice.All,
                    host = bean.host.nonBlank(),
                    play = bean.play.nonBlank(),
                )
            }.filterValues { it != RootContext.DEFAULT },
        )

        companion object {
            fun of(state: WorkspaceState): StateBean = StateBean().apply {
                followEditor = state.followEditor
                scope = state.scope.takeIf { it != WorkspaceState.SCOPE_ALL }
                roots = state.roots.entries.sortedBy { it.key }.mapTo(ArrayList()) { (key, context) ->
                    RootContextBean().apply {
                        path = key
                        environment = (context.environment as? EnvironmentChoice.Named)?.name
                        host = context.host
                        play = context.play
                    }
                }
            }
        }
    }

    /** One root's stored context; a null environment means All. */
    @Tag("root")
    class RootContextBean {
        @get:Attribute("path")
        var path: String = ""

        @get:Attribute("environment")
        var environment: String? = null

        @get:Attribute("host")
        var host: String? = null

        @get:Attribute("play")
        var play: String? = null
    }

    companion object {
        fun getInstance(project: Project): AnsibilityWorkspaceState = project.service()
    }
}
