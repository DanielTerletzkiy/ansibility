package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import org.jetbrains.yaml.YAMLFileType

/**
 * The user's `*.j2` mapping in platform tests (plan D8). Since M5 the Ansible Jinja file type overrider claims `.j2`
 * inside Ansible roots over any extension mapping, so a template "of any file type" needs "Keep YAML for .j2":
 * [withJ2As] maps `*.j2` to the given type with that setting on, or, for [AnsibleJinjaFileType], keeps the claim on over
 * a `*.j2 → YAML` mapping (the D8 default the overrider must beat).
 */
object J2Mappings {
    /** Runs [action] with `.j2` files inside roots typed as [type]; restores the mapping and the settings afterwards. */
    fun withJ2As(type: FileType, action: () -> Unit) {
        val manager = FileTypeManager.getInstance()
        val mapped = if (type == AnsibleJinjaFileType) YAMLFileType.YML else type
        val previous = manager.getFileTypeByExtension("j2")
        val settings = AnsibilityAppSettings.getInstance()
        val before = settings.settings
        WriteAction.runAndWait<Throwable> { manager.associateExtension(mapped, "j2") }
        settings.update { it.copy(jinja = it.jinja.copy(keepYamlForJ2 = type != AnsibleJinjaFileType)) }
        settle()
        try {
            action()
        } finally {
            settings.update { before }
            settle()
            if (previous != mapped) {
                WriteAction.runAndWait<Throwable> {
                    manager.removeAssociatedExtension(mapped, "j2")
                    if (previous.name != "UNKNOWN") manager.associateExtension(previous, "j2")
                }
            }
        }
    }

    /** Runs the scheduled file type refresh and waits for the re-indexing it starts. */
    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReadyInAllOpenedProjects()
    }
}
