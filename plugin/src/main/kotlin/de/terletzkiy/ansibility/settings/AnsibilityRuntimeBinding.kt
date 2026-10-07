package de.terletzkiy.ansibility.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import de.terletzkiy.ansibility.runtime.AnsibleRuntimeOptions
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.runtime.DocsBase
import de.terletzkiy.ansibility.runtime.LocalDocPrefetcher
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds the application settings to the hooks of [AnsibleRuntimeOptions], so the runtime never reads settings
 * itself:
 * - `docsBaseOverride` from the D11 web base ([DocsSettings.webBase], [DocsSettings.customWebBaseUrl]);
 * - `localDocRefresh` from the D14 background refresh ([DocsSettings.backgroundRefresh]);
 * - `localTargetProbe` from the D10 local guess ([ExecutableSettings.localTargetGuess]);
 * - `explicitExecutable` from the executable paths ([ExecutableSettings.pathFor]).
 *
 * [apply] assigns only the hooks whose value changed, since every assignment bumps [AnsibleRuntimeOptions.tracker]
 * and with it the doc and target-version caches. It runs once when the first project opens ([ensureApplied], from
 * [AnsibilitySettingsActivity]) and again after every settings change ([RuntimeSettingsListener], registered for
 * [AnsibilityAppSettingsListener.TOPIC]). Turning the background refresh on schedules a module-doc prefetch in every
 * open project.
 *
 * In unit-test mode the two process switches are left alone ([bindsProcessSwitches]), so tests never start
 * `ansible` or `ansible-doc` because the settings default to on; tests that cover the binding turn it on.
 */
@Service(Service.Level.APP)
class AnsibilityRuntimeBinding {
    private val applied = AtomicBoolean()

    @Volatile
    private var boundExecutables: ExecutableSettings? = null

    /** Whether [apply] also binds the switches that start processes (`localDocRefresh`, `localTargetProbe`). */
    @Volatile
    internal var bindsProcessSwitches: Boolean = !ApplicationManager.getApplication().isUnitTestMode
        @TestOnly set

    /** Applies the current settings unless they were applied before. */
    fun ensureApplied() {
        if (applied.compareAndSet(false, true)) apply(AnsibilityAppSettings.getInstance().settings)
    }

    /** Pushes [settings] into the runtime hooks. */
    fun apply(settings: AppSettings) {
        applied.set(true)
        val options = AnsibleRuntimeOptions.getInstance()
        val base = docsBase(settings.docs)
        if (options.docsBaseOverride != base) options.docsBaseOverride = base
        val executables = settings.executables
        if (boundExecutables != executables) {
            boundExecutables = executables
            options.explicitExecutable = executableHook(executables)
        }
        if (!bindsProcessSwitches) return
        if (options.localTargetProbe != executables.localTargetGuess) options.localTargetProbe = executables.localTargetGuess
        val refresh = settings.docs.backgroundRefresh
        if (options.localDocRefresh != refresh) {
            options.localDocRefresh = refresh
            if (refresh) prefetchOpenProjects()
        }
    }

    /** Forgets what was bound, so the next [ensureApplied] or [apply] assigns every hook again. */
    @TestOnly
    internal fun resetForTests() {
        applied.set(false)
        boundExecutables = null
    }

    private fun prefetchOpenProjects() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) LocalDocPrefetcher.getInstance(project).schedule()
        }
    }

    companion object {
        fun getInstance(): AnsibilityRuntimeBinding = service()

        /** The runtime docs tree for the D11 setting. A custom base without a URL falls back to `latest`, as the settings page says. */
        fun docsBase(docs: DocsSettings): DocsBase = when (docs.webBase) {
            DocsWebBase.TARGET_VERSIONED -> DocsBase.TargetVersioned
            DocsWebBase.LATEST -> DocsBase.Latest
            DocsWebBase.CUSTOM -> docs.customWebBaseUrl.trim().takeIf { it.isNotEmpty() }
                ?.let { DocsBase.Custom(if (it.endsWith("/")) it else "$it/") }
                ?: DocsBase.Latest
        }

        /** The `explicitExecutable` hook for [executables]: each tool's own path, else a sibling of another configured tool. */
        fun executableHook(executables: ExecutableSettings): (AnsibleTool) -> String? = { tool ->
            when (tool) {
                AnsibleTool.ANSIBLE -> executables.pathFor(executables.ansible)
                AnsibleTool.ANSIBLE_DOC -> executables.pathFor(executables.ansibleDoc)
                AnsibleTool.ANSIBLE_INVENTORY -> executables.pathFor(executables.ansibleInventory)
                AnsibleTool.ANSIBLE_PLAYBOOK -> executables.pathFor(executables.ansible)
            }
        }
    }
}

/** Re-applies [AnsibilityRuntimeBinding] after every application settings change (declared in `ansibility-settings.xml`). */
class RuntimeSettingsListener : AnsibilityAppSettingsListener {
    override fun appSettingsChanged(old: AppSettings, new: AppSettings) {
        AnsibilityRuntimeBinding.getInstance().apply(new)
    }
}
