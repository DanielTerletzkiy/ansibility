package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker

/** Which tree of the Ansible documentation site Ctrl+B opens (plan D11). */
sealed interface DocsBase {
    /** The Ansible package that ships the root's target core (core 2.18 → `ansible/11/`); `latest` when the target is unknown. */
    data object TargetVersioned : DocsBase

    /** Always `https://docs.ansible.com/ansible/latest/`. */
    data object Latest : DocsBase

    /** A fixed root such as a mirror or one package version; [url] ends with `/`. */
    data class Custom(val url: String) : DocsBase

    companion object {
        /**
         * The system property read while [AnsibleRuntimeOptions.docsBaseOverride] is null, i.e. before the settings are
         * bound (`settings.AnsibilityRuntimeBinding` binds the D11 setting when the first project opens).
         */
        const val PROPERTY: String = "ansibility.docs.base"

        private val PACKAGE_VERSION = Regex("""\d+(\.\d+)?""")

        /**
         * Reads a setting value: empty, `target` or `versioned` → [TargetVersioned]; `latest` → [Latest]; a package
         * version such as `12` → that tree; an `http(s)` URL → [Custom]. Anything else falls back to [TargetVersioned].
         */
        fun parse(text: String?): DocsBase {
            val value = text?.trim().orEmpty()
            return when {
                value.isEmpty() || value == "target" || value == "versioned" || value == "pinned" -> TargetVersioned
                value == "latest" -> Latest
                PACKAGE_VERSION.matches(value) -> Custom("${DocUrls.SITE}$value/")
                value.startsWith("https://") || value.startsWith("http://") -> Custom(if (value.endsWith("/")) value else "$value/")
                else -> TargetVersioned
            }
        }
    }
}

/**
 * Application-wide runtime options. `settings.AnsibilityRuntimeBinding` assigns them from the application settings
 * when the first project opens and after every settings change (the web docs base D11, the background doc refresh D14,
 * the local target probe D10 and the executable paths). Until then they hold the defaults of the plan: versioned web
 * docs, background doc refresh on, local target probe on. Processes are never started from unit tests unless a test
 * turns the switches on; the binding leaves them alone there.
 */
@Service(Service.Level.APP)
class AnsibleRuntimeOptions {
    private val changes = SimpleModificationTracker()

    /** Bumped whenever an option changes; caches built from options depend on it. */
    val tracker: ModificationTracker get() = changes

    /** The docs tree chosen in the settings; null means "read [DocsBase.PROPERTY]" (default: [DocsBase.TargetVersioned]). */
    @Volatile
    var docsBaseOverride: DocsBase? = null
        set(value) {
            field = value
            changes.incModificationCount()
        }

    /** The effective docs tree. */
    val docsBase: DocsBase get() = docsBaseOverride ?: DocsBase.parse(System.getProperty(DocsBase.PROPERTY))

    /** D14: run `ansible-doc` in the background and use the local install's docs. Off: no process, no local docs. */
    @Volatile
    var localDocRefresh: Boolean = !ApplicationManager.getApplication().isUnitTestMode
        set(value) {
            field = value
            changes.incModificationCount()
        }

    /** D10 step 4: probe `ansible --version` when no root pins a version. */
    @Volatile
    var localTargetProbe: Boolean = !ApplicationManager.getApplication().isUnitTestMode
        set(value) {
            field = value
            changes.incModificationCount()
        }

    /**
     * The executable chosen in the settings for a tool: a path to the tool, to a sibling Ansible tool in the same
     * directory, or to a directory holding the tools. Null (the default) means "look it up".
     */
    @Volatile
    var explicitExecutable: (AnsibleTool) -> String? = { null }
        set(value) {
            field = value
            changes.incModificationCount()
        }

    companion object {
        fun getInstance(): AnsibleRuntimeOptions = service()
    }
}
