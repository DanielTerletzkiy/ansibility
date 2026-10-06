package de.terletzkiy.ansibility.vault.identity

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import de.terletzkiy.ansibility.api.VaultSourceKind

/**
 * One vault id configured explicitly for a root (step 1 of the discovery chain, F7.9): a label and where its secret
 * comes from. [location] is a password file or script path (root-relative, absolute or `~/…`), the key of a
 * PasswordSafe entry in Ansibility's namespace (`team#prod` becomes "… Ansibility Vault — team#prod"; null: the
 * root's own entry for the label), or an environment variable name; null for a prompt. Never a secret.
 */
data class ExplicitIdentity(val label: String, val kind: VaultSourceKind, val location: String? = null)

/**
 * The shared vault settings of one root (plan amendment R7/R8, F7.9 "What is stored where": ids, kinds, root-relative
 * paths, the env → id mapping). Nothing here is secret, so it may be committed with the project.
 */
data class VaultRootSettings(
    /** Explicit ids, tried before everything Ansible's configuration and the conventions discover. */
    val identities: List<ExplicitIdentity> = emptyList(),
    /** The env → id mapping for encrypting files of an environment, by environment id; the key `*` matches every one. */
    val environmentIdentities: Map<String, String> = emptyMap(),
    /** D31: decrypted values of unlocked ids may feed the analysis (derived facts only). Off by default. */
    val analyzeDecryptedValues: Boolean = false,
) {
    /** The id the env → id mapping gives a file of [environment] (exact name first, then `*`), or null. */
    fun identityForEnvironment(environment: String?): String? =
        environment?.let { environmentIdentities[it] } ?: environmentIdentities[ANY_ENVIRONMENT]

    /**
     * The id for a file that belongs to all of [environments] (shared inventory vars belong to several): the one id
     * they all map to, or null when they map to different ids or one of them maps to none (plan amendment R10, R10-11).
     */
    fun identityForEnvironments(environments: List<String>): String? {
        if (environments.isEmpty()) return identityForEnvironment(null)
        return environments.map(::identityForEnvironment).distinct().singleOrNull()
    }

    /** Whether [environments] map to more than one id (or some map and some do not), so no mapping applies. */
    fun mappingsDiffer(environments: List<String>): Boolean =
        environments.size > 1 && environments.map(::identityForEnvironment).distinct().size > 1

    /** These settings with the mapping entry of [old] moved to [new]. */
    fun renameEnvironment(old: String, new: String): VaultRootSettings {
        val id = environmentIdentities[old] ?: return this
        if (old == new) return this
        val renamed = LinkedHashMap<String, String>()
        for ((env, value) in environmentIdentities) if (env != old) renamed[env] = value
        renamed[new] = id
        return copy(environmentIdentities = renamed)
    }

    companion object {
        const val ANY_ENVIRONMENT: String = "*"
        val DEFAULT = VaultRootSettings()
    }
}

/**
 * Project-level vault settings in `.idea/ansibility-vault.xml`, keyed by `settings.RootKeys` keys (so they are valid
 * for every clone). They hold only what may be shared; secrets, consents and script trust live elsewhere (DEV.md
 * rule 12: an entry in a project file never authorises reading a source, it only names it).
 *
 * [rootSettings] is safe to read from any thread; [update] replaces one root's entry and bumps [modificationTracker].
 */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityVault", storages = [Storage("ansibility-vault.xml")])
class VaultProjectSettings : PersistentStateComponent<VaultProjectSettings.StateBean> {
    private val tracker = SimpleModificationTracker()

    @Volatile
    private var roots: Map<String, VaultRootSettings> = emptyMap()

    /** Bumped on every change. */
    val modificationTracker: ModificationTracker get() = tracker

    /** Decrypted tabs are encrypted into the real file only by an explicit save (Cmd+S), never by autosave. Off by default. */
    @Volatile
    var encryptOnlyOnExplicitSave: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            tracker.incModificationCount()
        }

    /** The settings of the root stored under [rootKey], or the defaults. */
    fun rootSettings(rootKey: String): VaultRootSettings = roots[rootKey] ?: VaultRootSettings.DEFAULT

    /** Replaces the settings of [rootKey] with [transform] applied to them; default settings remove the entry. */
    fun update(rootKey: String, transform: (VaultRootSettings) -> VaultRootSettings) {
        synchronized(this) {
            val new = transform(rootSettings(rootKey))
            val updated = if (new == VaultRootSettings.DEFAULT) roots - rootKey else roots + (rootKey to new)
            if (updated == roots) return
            roots = updated
        }
        tracker.incModificationCount()
    }

    /** Moves the env → id mapping of environment [old] of root [rootKey] to [new] (a renamed environment, F10.6). */
    fun renameEnvironment(rootKey: String, old: String, new: String) = update(rootKey) { it.renameEnvironment(old, new) }

    override fun getState(): StateBean = StateBean().apply {
        encryptOnlyOnExplicitSave = this@VaultProjectSettings.encryptOnlyOnExplicitSave
        roots = this@VaultProjectSettings.roots.entries.sortedBy { it.key }.map { (key, settings) -> RootBean.of(key, settings) }.toMutableList()
    }

    override fun loadState(state: StateBean) {
        roots = state.roots.mapNotNull { bean -> bean.key?.let { it to bean.toSettings() } }.toMap()
        encryptOnlyOnExplicitSave = state.encryptOnlyOnExplicitSave
        tracker.incModificationCount()
    }

    /** XML form of the settings. */
    class StateBean {
        @get:Attribute("encryptOnlyOnExplicitSave")
        var encryptOnlyOnExplicitSave: Boolean = false

        @get:XCollection(propertyElementName = "roots", elementName = "root")
        var roots: MutableList<RootBean> = ArrayList()
    }

    /** XML form of one root's [VaultRootSettings]. */
    @Tag("root")
    class RootBean {
        @get:Attribute("key")
        var key: String? = null

        @get:Attribute("analyzeDecryptedValues")
        var analyzeDecryptedValues: Boolean = false

        @get:XCollection(propertyElementName = "identities", elementName = "identity")
        var identities: MutableList<IdentityBean> = ArrayList()

        @get:MapAnnotation(surroundWithTag = false, entryTagName = "environment", keyAttributeName = "name", valueAttributeName = "identity")
        var environmentIdentities: MutableMap<String, String> = LinkedHashMap()

        fun toSettings(): VaultRootSettings = VaultRootSettings(
            identities = identities.mapNotNull { it.toIdentity() },
            environmentIdentities = LinkedHashMap(environmentIdentities),
            analyzeDecryptedValues = analyzeDecryptedValues,
        )

        companion object {
            fun of(key: String, settings: VaultRootSettings): RootBean = RootBean().apply {
                this.key = key
                analyzeDecryptedValues = settings.analyzeDecryptedValues
                identities = settings.identities.map(IdentityBean::of).toMutableList()
                environmentIdentities = LinkedHashMap(settings.environmentIdentities)
            }
        }
    }

    /** XML form of one [ExplicitIdentity]. */
    @Tag("identity")
    class IdentityBean {
        @get:Attribute("label")
        var label: String? = null

        @get:Attribute("kind")
        var kind: String? = null

        @get:Attribute("location")
        var location: String? = null

        fun toIdentity(): ExplicitIdentity? {
            val label = label?.takeIf { it.isNotEmpty() } ?: return null
            val kind = VaultSourceKind.entries.firstOrNull { it.name == kind } ?: return null
            return ExplicitIdentity(label, kind, location?.takeIf { it.isNotEmpty() })
        }

        companion object {
            fun of(identity: ExplicitIdentity): IdentityBean = IdentityBean().apply {
                label = identity.label
                kind = identity.kind.name
                location = identity.location
            }
        }
    }

    companion object {
        fun getInstance(project: Project): VaultProjectSettings = project.service()
    }
}
