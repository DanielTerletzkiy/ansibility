package de.terletzkiy.ansibility.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * The project's Ansibility settings (plan "Coexistence & settings", project level; D17).
 *
 * They are stored in the **workspace file** (per user, not in VCS). When "Share with team" is on
 * ([isSharedWithTeam]), the settings live in `.idea/ansibility.xml` instead ([AnsibilitySharedProjectSettings]),
 * so a teammate who clones the repo gets them, and sharing is on for them too. Turning sharing off copies the shared
 * settings back into the workspace file and empties the shared file.
 *
 * [settings] is an immutable snapshot, safe to read from any thread. Every change publishes
 * [AnsibilitySettingsListener.projectSettingsChanged] with the old and new effective settings and bumps
 * [modificationTracker], except a change of the role-drift settings alone ([DriftSettings], plan amendment R24,
 * D177), which bumps only [driftModificationTracker]: the golden root re-tiers the drift without a rescan.
 */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AnsibilityProjectSettings(private val project: Project) : PersistentStateComponent<ProjectSettingsBean> {
    private val lock = Any()
    private val tracker = SimpleModificationTracker()
    private val driftTracker = SimpleModificationTracker()

    @Volatile
    private var local: ProjectSettings = ProjectSettings.DEFAULT

    @Volatile
    private var loaded = false

    private val shared: AnsibilitySharedProjectSettings by lazy(LazyThreadSafetyMode.PUBLICATION) { project.service() }

    /** The effective settings: the shared copy while sharing is on, the workspace copy otherwise. */
    val settings: ProjectSettings
        get() = shared.sharedSettings ?: local

    /**
     * Bumped on every change of the effective settings except one of [ProjectSettings.drift] alone; a dependency for
     * caches that read them. Caches that read the drift settings depend on [driftModificationTracker] too.
     */
    val modificationTracker: ModificationTracker
        get() = tracker

    /** Bumped whenever [ProjectSettings.drift] changes (the golden root, Ignore molecule/); see [modificationTracker]. */
    val driftModificationTracker: ModificationTracker
        get() = driftTracker

    /** Whether the settings are shared with the team through `.idea/ansibility.xml`. */
    val isSharedWithTeam: Boolean
        get() = shared.sharedSettings != null

    /** The settings of [root]. */
    fun rootSettings(root: AnsibleRoot): RootSettings = settings.root(RootKeys.keyOf(project, root.dir))

    /** The settings of the root whose directory is [dir]. */
    fun rootSettings(dir: VirtualFile): RootSettings = settings.root(RootKeys.keyOf(project, dir))

    /**
     * The explicit target ansible-core of [root], or null for Auto. A NESTED_PLAYBOOK root on Auto follows its
     * parent's explicit target, the same way detection lets it inherit the parent's Dockerfile pins.
     */
    fun targetCoreOverride(root: AnsibleRoot): CoreVersion? {
        val current = settings
        current.root(RootKeys.keyOf(project, root.dir)).targetCoreVersion?.let { return it }
        val parent = root.parentDir?.takeIf { root.kind == RootKind.NESTED_PLAYBOOK } ?: return null
        return current.root(RootKeys.keyOf(project, parent)).targetCoreVersion
    }

    /** Whether [file] matches one of the extra ignored-path globs (relative to the project directory). */
    fun isIgnored(file: VirtualFile): Boolean {
        val relative = RootKeys.relativePath(project, file) ?: return false
        return settings.paths.isIgnored(relative)
    }

    /**
     * [isIgnored] for walks over many files: the current globs and the project directory are resolved once, so later
     * settings changes do not affect the returned matcher.
     */
    fun ignoredPathMatcher(): (VirtualFile) -> Boolean {
        val paths = settings.paths
        val base = RootKeys.projectDir(project)
        if (base == null || paths.extraIgnoredPaths.isEmpty()) return { false }
        return { file -> RootKeys.relativePath(base, file)?.let(paths::isIgnored) == true }
    }

    /** Replaces the effective settings with [transform] applied to them. Returns the new settings. */
    fun update(transform: (ProjectSettings) -> ProjectSettings): ProjectSettings {
        val (old, new) = synchronized(lock) {
            val old = settings
            val new = transform(old).normalized()
            if (shared.sharedSettings != null) shared.sharedSettings = new else local = new
            old to new
        }
        if (old != new) changed(old, new)
        return new
    }

    /** Replaces the settings of the root stored under [key]. */
    fun updateRoot(key: String, transform: (RootSettings) -> RootSettings): ProjectSettings =
        update { it.withRoot(key, transform(it.root(key))) }

    /**
     * Turns team sharing on or off. On: the current settings are copied into `.idea/ansibility.xml`. Off: the shared
     * settings are copied into the workspace file and the shared file is emptied. The effective settings stay the same.
     */
    fun setSharedWithTeam(share: Boolean) {
        synchronized(lock) {
            val current = settings
            if (share == isSharedWithTeam) return
            if (share) {
                shared.sharedSettings = current
            } else {
                local = current
                shared.sharedSettings = null
            }
        }
        tracker.incModificationCount()
    }

    override fun getState(): ProjectSettingsBean = ProjectSettingsBean().apply { fill(local) }

    override fun loadState(state: ProjectSettingsBean) {
        val reload = loaded
        loaded = true
        if (!reload) {
            local = state.toSettings()
            return
        }
        val (old, new) = synchronized(lock) {
            val old = settings
            local = state.toSettings()
            old to settings
        }
        if (old != new) changed(old, new)
    }

    override fun noStateLoaded() {
        loaded = true
    }

    /** Called by [AnsibilitySharedProjectSettings] when its file was reloaded (e.g. after a VCS update). */
    internal fun sharedStateReloaded(oldShared: ProjectSettings?, newShared: ProjectSettings?) {
        val old = oldShared ?: local
        val new = newShared ?: local
        if (old != new) changed(old, new)
    }

    private fun changed(old: ProjectSettings, new: ProjectSettings) {
        if (!old.differsOnlyInDrift(new)) tracker.incModificationCount()
        if (old.drift != new.drift) driftTracker.incModificationCount()
        if (!project.isDisposed) project.messageBus.syncPublisher(AnsibilitySettingsListener.TOPIC).projectSettingsChanged(old, new)
    }

    companion object {
        fun getInstance(project: Project): AnsibilityProjectSettings = project.service()
    }
}

/**
 * The team-shared copy of [AnsibilityProjectSettings], stored in `.idea/ansibility.xml`. It holds settings only
 * while sharing is on; otherwise its state is the default and the platform writes nothing. Use
 * [AnsibilityProjectSettings]; this component is its storage.
 */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilitySharedSettings", storages = [Storage("ansibility.xml")])
class AnsibilitySharedProjectSettings(private val project: Project) : PersistentStateComponent<SharedProjectSettingsBean> {
    @Volatile
    private var loaded = false

    /** The shared settings, or null while sharing is off. */
    @Volatile
    internal var sharedSettings: ProjectSettings? = null

    override fun getState(): SharedProjectSettingsBean = SharedProjectSettingsBean().apply {
        val current = sharedSettings ?: return@apply
        shared = true
        fill(current)
    }

    override fun loadState(state: SharedProjectSettingsBean) {
        val old = sharedSettings
        val new = if (state.shared) state.toSettings() else null
        sharedSettings = new
        val reload = loaded
        loaded = true
        if (reload && old != new) project.serviceIfCreated<AnsibilityProjectSettings>()?.sharedStateReloaded(old, new)
    }

    override fun noStateLoaded() {
        loaded = true
    }
}
