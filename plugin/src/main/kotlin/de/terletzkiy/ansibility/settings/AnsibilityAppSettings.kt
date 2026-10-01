package de.terletzkiy.ansibility.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Application-level Ansibility settings, stored in `ansibility.xml` in the IDE config directory.
 *
 * [settings] is an immutable snapshot that is safe to read from any thread (inspections, the file type overrider,
 * background doc refresh). [update] replaces it atomically, bumps [modificationTracker] and publishes
 * [AnsibilityAppSettingsListener.TOPIC] when the value changed. So does a reload of the stored state (settings sync),
 * but not the initial load.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilitySettings", storages = [Storage("ansibility.xml")], category = SettingsCategory.PLUGINS)
class AnsibilityAppSettings : PersistentStateComponent<AnsibilityAppSettings.StateBean> {
    private val lock = Any()
    private val tracker = SimpleModificationTracker()

    @Volatile
    private var current: AppSettings = AppSettings.DEFAULT

    @Volatile
    private var loaded = false

    /** The current settings. */
    val settings: AppSettings
        get() = current

    /** Bumped on every change; caches that depend on application settings use it as a dependency. */
    val modificationTracker: ModificationTracker
        get() = tracker

    /** Replaces the settings with [transform] applied to the current value. Returns the new value. */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val (old, new) = synchronized(lock) {
            val old = current
            val new = transform(old)
            current = new
            old to new
        }
        if (old != new) changed(old, new)
        return new
    }

    override fun getState(): StateBean = StateBean.of(current)

    override fun loadState(state: StateBean) {
        val (old, new) = synchronized(lock) {
            val old = current
            current = state.toSettings()
            old to current
        }
        val reload = loaded
        loaded = true
        if (reload && old != new) changed(old, new)
    }

    override fun noStateLoaded() {
        loaded = true
    }

    private fun changed(old: AppSettings, new: AppSettings) {
        tracker.incModificationCount()
        ApplicationManager.getApplication().messageBus.syncPublisher(AnsibilityAppSettingsListener.TOPIC).appSettingsChanged(old, new)
    }

    /**
     * The XML form of [AppSettings]. Values equal to the defaults are not written. The rule list is stored only when
     * [customOuterLanguageRules] is set, so users who never edited the table get improved defaults with plugin
     * updates (and an emptied table stays empty).
     */
    class StateBean {
        var ansibleDocPath: String? = null
        var ansibleInventoryPath: String? = null
        var ansiblePath: String? = null
        var localTargetGuess: Boolean = true
        var docsWebBase: DocsWebBase = DocsWebBase.TARGET_VERSIONED
        var docsCustomUrl: String? = null
        var moduleNavigation: ModuleNavigationTarget = ModuleNavigationTarget.WEB_DOCS
        var backgroundDocRefresh: Boolean = true
        var claimJ2InsideRoots: Boolean = true
        var keepYamlForJ2: Boolean = false
        var treatJinjaTemplatesUnderTemplatesDir: Boolean = true
        var deferToPyCharmJinja: Boolean = false

        var customOuterLanguageRules: Boolean = false

        @get:XCollection(style = XCollection.Style.v2)
        var outerLanguageRules: MutableList<RuleBean> = ArrayList()
        var autoCloseDelimiters: Boolean = true
        var autoInsertEndTags: Boolean = true
        var conflictNotifications: Boolean = true
        var hideOtherAnsibleCompletions: Boolean = false

        fun toSettings(): AppSettings = AppSettings(
            executables = ExecutableSettings(
                ansibleDoc = ansibleDocPath.nonBlank(),
                ansibleInventory = ansibleInventoryPath.nonBlank(),
                ansible = ansiblePath.nonBlank(),
                localTargetGuess = localTargetGuess,
            ),
            docs = DocsSettings(
                webBase = docsWebBase,
                customWebBaseUrl = docsCustomUrl.orEmpty(),
                moduleNavigation = moduleNavigation,
                backgroundRefresh = backgroundDocRefresh,
            ),
            jinja = JinjaSettings(
                claimJ2InsideRoots = claimJ2InsideRoots,
                keepYamlForJ2 = keepYamlForJ2,
                treatJinjaTemplatesUnderTemplatesDir = treatJinjaTemplatesUnderTemplatesDir,
                deferToPyCharmJinja = deferToPyCharmJinja,
                outerLanguageRules = if (customOuterLanguageRules) {
                    outerLanguageRules.map { OuterLanguageRule(it.pattern, it.language) }
                } else {
                    JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES
                },
                autoCloseDelimiters = autoCloseDelimiters,
                autoInsertEndTags = autoInsertEndTags,
            ),
            coexistence = CoexistenceSettings(
                conflictNotifications = conflictNotifications,
                hideOtherAnsibleCompletions = hideOtherAnsibleCompletions,
            ),
        )

        companion object {
            fun of(settings: AppSettings): StateBean = StateBean().apply {
                ansibleDocPath = settings.executables.ansibleDoc.nonBlank()
                ansibleInventoryPath = settings.executables.ansibleInventory.nonBlank()
                ansiblePath = settings.executables.ansible.nonBlank()
                localTargetGuess = settings.executables.localTargetGuess
                docsWebBase = settings.docs.webBase
                docsCustomUrl = settings.docs.customWebBaseUrl.nonBlank()
                moduleNavigation = settings.docs.moduleNavigation
                backgroundDocRefresh = settings.docs.backgroundRefresh
                claimJ2InsideRoots = settings.jinja.claimJ2InsideRoots
                keepYamlForJ2 = settings.jinja.keepYamlForJ2
                treatJinjaTemplatesUnderTemplatesDir = settings.jinja.treatJinjaTemplatesUnderTemplatesDir
                deferToPyCharmJinja = settings.jinja.deferToPyCharmJinja
                customOuterLanguageRules = settings.jinja.outerLanguageRules != JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES
                outerLanguageRules = if (customOuterLanguageRules) {
                    settings.jinja.outerLanguageRules.mapTo(ArrayList()) { RuleBean(it.pattern, it.languageId) }
                } else {
                    ArrayList()
                }
                autoCloseDelimiters = settings.jinja.autoCloseDelimiters
                autoInsertEndTags = settings.jinja.autoInsertEndTags
                conflictNotifications = settings.coexistence.conflictNotifications
                hideOtherAnsibleCompletions = settings.coexistence.hideOtherAnsibleCompletions
            }
        }
    }

    /** One stored outer-language rule. */
    @Tag("rule")
    class RuleBean() {
        @get:Attribute("pattern")
        var pattern: String = ""

        @get:Attribute("language")
        var language: String = ""

        constructor(pattern: String, language: String) : this() {
            this.pattern = pattern
            this.language = language
        }

        override fun equals(other: Any?): Boolean = other is RuleBean && other.pattern == pattern && other.language == language

        override fun hashCode(): Int = 31 * pattern.hashCode() + language.hashCode()
    }

    companion object {
        fun getInstance(): AnsibilityAppSettings = service()
    }
}

internal fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
