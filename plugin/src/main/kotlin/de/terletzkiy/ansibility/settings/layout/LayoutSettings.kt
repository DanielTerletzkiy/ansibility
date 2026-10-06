package de.terletzkiy.ansibility.settings.layout

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.RootKeys

/** One environment of a custom inventory list: its id and its sources in `-i` order, root-relative and `/`-separated. */
data class LayoutInventory(val name: String, val sources: List<String>, val isDefault: Boolean = false)

/** The layout overrides of one root; null fields are Auto. */
data class LayoutOverride(
    /** Custom inventories replacing the detected ones; null is Auto. */
    val inventories: List<LayoutInventory>? = null,
    /** One environment per file for a cfg inventory that is a single directory (D49); null is Auto (off). */
    val onePerFile: Boolean? = null,
    /** Custom roles directories, root-relative, replacing `<root>/roles` and `roles_path`; null is Auto. */
    val rolesPath: List<String>? = null,
) {
    val isEmpty: Boolean get() = inventories == null && onePerFile == null && rolesPath == null

    /** This override with every Auto field taken from [fallback]. */
    fun orElse(fallback: LayoutOverride?): LayoutOverride =
        if (fallback == null) this
        else LayoutOverride(inventories ?: fallback.inventories, onePerFile ?: fallback.onePerFile, rolesPath ?: fallback.rolesPath)
}

/** Where the overrides of a root are stored (D54). */
enum class LayoutStorage {
    /** `.idea/ansibility-layout.xml`: commit it to share. */
    PROJECT,

    /** Per user, outside the project directory. */
    ONLY_ME,
}

/** The effective layout settings of one root: the personal override wins per field over the shared one (D54). */
data class EffectiveLayoutSettings(val override: LayoutOverride, val storage: LayoutStorage, val follow: Boolean) {
    companion object {
        val AUTO = EffectiveLayoutSettings(LayoutOverride(), LayoutStorage.PROJECT, follow = false)
    }
}

/**
 * The Layout settings (plan amendment R10, F10.6): per root, custom inventories and the one-env-per-file switch,
 * stored for the team ([SharedLayoutSettings], `.idea/ansibility-layout.xml`) or for this user only
 * ([PersonalLayoutSettings], outside the project), plus the personal follow switch for machine-dependent cfg paths.
 * Every change bumps [modificationTracker] and refreshes the workspace structure, so the layout, the roots, the
 * models and the UI follow without a restart.
 */
@Service(Service.Level.PROJECT)
class LayoutSettings(private val project: Project) {
    private val tracker = SimpleModificationTracker()

    val modificationTracker: ModificationTracker get() = tracker

    private val shared: SharedLayoutSettings get() = project.service()
    private val personal: PersonalLayoutSettings get() = project.service()

    fun of(rootDir: VirtualFile): EffectiveLayoutSettings = of(RootKeys.keyOf(project, rootDir))

    fun of(key: String): EffectiveLayoutSettings {
        val mine = personal.overrides[key]
        val team = shared.overrides[key]
        val follow = key in personal.follow
        if (mine == null && team == null && !follow) return EffectiveLayoutSettings.AUTO
        val storage = if (mine != null && !mine.isEmpty) LayoutStorage.ONLY_ME else LayoutStorage.PROJECT
        return EffectiveLayoutSettings((mine ?: LayoutOverride()).orElse(team), storage, follow)
    }

    /** Stores [override] for the root [key] in [storage], removing it from the other storage. */
    fun update(key: String, override: LayoutOverride, storage: LayoutStorage, follow: Boolean) {
        val before = of(key)
        val stored = override.takeUnless { it.isEmpty }
        when (storage) {
            LayoutStorage.PROJECT -> {
                shared.put(key, stored)
                personal.put(key, null)
            }
            LayoutStorage.ONLY_ME -> {
                personal.put(key, stored)
                shared.put(key, null)
            }
        }
        personal.setFollow(key, follow)
        if (of(key) != before) changed()
    }

    internal fun changed() {
        tracker.incModificationCount()
        if (!project.isDisposed) AnsibleWorkspace.getInstance(project).refreshStructure()
    }

    companion object {
        fun getInstance(project: Project): LayoutSettings = project.service()
    }
}

/** The team-shared layout overrides, in `.idea/ansibility-layout.xml`. Use [LayoutSettings]. */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityLayout", storages = [Storage("ansibility-layout.xml")])
class SharedLayoutSettings(private val project: Project) : PersistentStateComponent<LayoutSettingsBean> {
    @Volatile
    internal var overrides: Map<String, LayoutOverride> = emptyMap()
        private set

    @Volatile
    private var loaded = false

    internal fun put(key: String, override: LayoutOverride?) {
        overrides = if (override == null) overrides - key else overrides + (key to override)
    }

    override fun getState(): LayoutSettingsBean = LayoutSettingsBean.of(overrides, emptySet())

    override fun loadState(state: LayoutSettingsBean) {
        val old = overrides
        overrides = state.overrides()
        val reload = loaded
        loaded = true
        if (reload && old != overrides) project.service<LayoutSettings>().changed()
    }

    override fun noStateLoaded() {
        loaded = true
    }
}

/** This user's layout overrides and follow switches, stored outside the project directory. Use [LayoutSettings]. */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityPersonalLayout", storages = [Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE)])
class PersonalLayoutSettings : PersistentStateComponent<LayoutSettingsBean> {
    @Volatile
    internal var overrides: Map<String, LayoutOverride> = emptyMap()
        private set

    @Volatile
    internal var follow: Set<String> = emptySet()
        private set

    internal fun put(key: String, override: LayoutOverride?) {
        overrides = if (override == null) overrides - key else overrides + (key to override)
    }

    internal fun setFollow(key: String, on: Boolean) {
        follow = if (on) follow + key else follow - key
    }

    override fun getState(): LayoutSettingsBean = LayoutSettingsBean.of(overrides, follow)

    override fun loadState(state: LayoutSettingsBean) {
        overrides = state.overrides()
        follow = state.roots.filter { it.follow }.mapTo(HashSet()) { it.path }
    }
}

/** The XML form of the layout overrides of every root. */
class LayoutSettingsBean {
    @get:XCollection(style = XCollection.Style.v2)
    var roots: MutableList<RootLayoutBean> = ArrayList()

    fun overrides(): Map<String, LayoutOverride> = roots.filter { it.path.isNotBlank() }
        .associate { it.path to it.toOverride() }
        .filterValues { !it.isEmpty }

    companion object {
        fun of(overrides: Map<String, LayoutOverride>, follow: Set<String>): LayoutSettingsBean = LayoutSettingsBean().apply {
            roots = (overrides.keys + follow).sorted().mapTo(ArrayList()) { key ->
                RootLayoutBean.of(key, overrides[key] ?: LayoutOverride(), key in follow)
            }
        }
    }
}

@Tag("root")
class RootLayoutBean {
    @get:Attribute("path")
    var path: String = ""

    @get:Attribute("custom")
    var custom: Boolean = false

    @get:Attribute("onePerFile")
    var onePerFile: Boolean? = null

    @get:Attribute("follow")
    var follow: Boolean = false

    @get:XCollection(style = XCollection.Style.v2)
    var inventories: MutableList<InventoryBean> = ArrayList()

    @get:Attribute("customRoles")
    var customRoles: Boolean = false

    @get:XCollection(style = XCollection.Style.v2, propertyElementName = "rolesPath", elementName = "dir")
    var rolesPath: MutableList<String> = ArrayList()

    fun toOverride(): LayoutOverride = LayoutOverride(
        inventories = if (custom) inventories.map { LayoutInventory(it.name, it.sources.map(String::trim).filter(String::isNotEmpty), it.isDefault) } else null,
        onePerFile = onePerFile,
        rolesPath = if (customRoles) rolesPath.map(String::trim).filter(String::isNotEmpty) else null,
    )

    companion object {
        fun of(key: String, override: LayoutOverride, follow: Boolean): RootLayoutBean = RootLayoutBean().apply {
            path = key
            custom = override.inventories != null
            onePerFile = override.onePerFile
            this.follow = follow
            inventories = override.inventories.orEmpty().mapTo(ArrayList()) { InventoryBean.of(it) }
            customRoles = override.rolesPath != null
            rolesPath = override.rolesPath.orEmpty().toMutableList()
        }
    }
}

@Tag("inventory")
class InventoryBean {
    @get:Attribute("name")
    var name: String = ""

    @get:Attribute("default")
    var isDefault: Boolean = false

    @get:XCollection(style = XCollection.Style.v2, elementName = "source")
    var sources: MutableList<String> = ArrayList()

    companion object {
        fun of(inventory: LayoutInventory): InventoryBean = InventoryBean().apply {
            name = inventory.name
            isDefault = inventory.isDefault
            sources = inventory.sources.toMutableList()
        }
    }
}
