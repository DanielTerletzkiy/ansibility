package de.terletzkiy.ansibility.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersionDetector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the project settings to the services that depend on them:
 * - installs [TargetVersionDetector.overrideFor] from [AnsibilityProjectSettings.targetCoreOverride] and re-installs
 *   it when a target changes (assigning the hook invalidates the detector's cache);
 * - refreshes the workspace structure ([AnsibleWorkspace.refreshStructure]) when the detached-root rule or the ignored
 *   paths change; the workspace reads those path settings itself at query time. The Molecule settings
 *   ([MoleculeSettings], plan amendment R20) change no structure: the run UI and the navigation filters read them per
 *   request, and the restart below redraws the ▶ of scenario files;
 * - resets the JSON schema mappings ([JsonSchemaService.reset], public in 262) when the SchemaStore exclusion is
 *   toggled, so the catalog schemas are re-assigned to the task and playbook files at once;
 * - restarts highlighting after any change of the effective project settings, since severities and semantics
 *   depend on them.
 *
 * [install] runs from [AnsibilitySettingsActivity] when the project opens; calling it again only re-installs the hook.
 */
@Service(Service.Level.PROJECT)
class AnsibilitySettingsWiring(private val project: Project) : Disposable {
    private val installed = AtomicBoolean()

    /** Installs the target-version hook and, the first time, starts following settings changes. */
    fun install() {
        if (installed.compareAndSet(false, true)) {
            project.messageBus.connect(this).subscribe(
                AnsibilitySettingsListener.TOPIC,
                object : AnsibilitySettingsListener {
                    override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {
                        settingsChanged(old, new)
                    }
                },
            )
        }
        applyTargetOverrides()
    }

    /** (Re-)installs the target-version hook; the detector recomputes its versions on the next query. */
    fun applyTargetOverrides() {
        if (project.isDisposed) return
        val settings = AnsibilityProjectSettings.getInstance(project)
        TargetVersionDetector.getInstance(project).overrideFor = { root -> settings.targetCoreOverride(root) }
    }

    private fun settingsChanged(old: ProjectSettings, new: ProjectSettings) {
        if (project.isDisposed || old == new) return
        if (targetCores(old) != targetCores(new)) applyTargetOverrides()
        if (structuralPaths(old.paths) != structuralPaths(new.paths)) {
            AnsibleWorkspace.getInstance(project).refreshStructure()
        }
        if (old.paths.schemaStoreExclusion != new.paths.schemaStoreExclusion) {
            JsonSchemaService.Impl.get(project).reset()
        }
        DaemonCodeAnalyzer.getInstance(project).restart(RESTART_REASON)
    }

    override fun dispose() {}

    companion object {
        private const val RESTART_REASON = "Ansibility project settings changed"

        fun getInstance(project: Project): AnsibilitySettingsWiring = project.service()

        /** The explicit targets by root key; only they feed the detector. */
        internal fun targetCores(settings: ProjectSettings): Map<String, String> =
            settings.roots.mapNotNull { (key, root) -> root.targetCore?.let { key to it } }.toMap()

        /** The path settings that change which roots exist or which files they contain. */
        internal fun structuralPaths(paths: PathSettings): List<Any> =
            listOf(paths.detachedRule, paths.extraIgnoredPaths)
    }
}

/**
 * After the project opened (`postStartupActivity`): binds the application settings to the runtime hooks
 * ([AnsibilityRuntimeBinding.ensureApplied], once per application) and installs [AnsibilitySettingsWiring].
 */
class AnsibilitySettingsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AnsibilityRuntimeBinding.getInstance().ensureApplied()
        AnsibilitySettingsWiring.getInstance(project).install()
    }
}
