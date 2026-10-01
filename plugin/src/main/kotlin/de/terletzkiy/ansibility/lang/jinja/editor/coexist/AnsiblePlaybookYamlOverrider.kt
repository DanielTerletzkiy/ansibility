package de.terletzkiy.ansibility.lang.jinja.editor.coexist

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleJinjaFileTypeRefresh
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleTemplatePaths
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.AnsibilityAppSettingsListener
import de.terletzkiy.ansibility.settings.AppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings
import org.jetbrains.yaml.YAMLFileType

/**
 * Keeps `*-playbook.yml` and `*-playbook.yaml` files YAML in Ansible projects (plan X05). PyCharm Professional's
 * bundled `Jinja2` file type claims these names by pattern, and patterns beat the `yaml` extension, so without this
 * `docker-compose.ansible-playbook.yaml` compose files next to Ansible roots open as Jinja2:
 * - a docker-compose file (`docker-compose*` or `compose*`) next to an Ansible root (a child directory with
 *   `ansible.cfg`, as `repos/<team>/ansible`) or inside Ansible content is always YAML: compose files are never
 *   templates;
 * - any other such file inside Ansible content (a playbook named `site-playbook.yml`) is YAML too, unless "Defer
 *   .j2 to PyCharm Jinja2" is on: every Ansibility feature of a playbook needs it to be YAML.
 *
 * Nothing changes when the name already maps to YAML, outside Ansible projects, for template files (the Ansible
 * Jinja overrider runs first and claims them), or when the user set a per-file type (the platform's overrider runs
 * first). Like every file type overrider it is a pure function of the file's name, its ancestors and the application
 * settings: the file types are refreshed together with those of the Ansible Jinja templates (an `ansible.cfg` that
 * appears or disappears re-types every file), and [AnsiblePlaybookYamlSettingsListener] re-types them when "Defer .j2
 * to PyCharm Jinja2" changes.
 */
class AnsiblePlaybookYamlOverrider : FileTypeOverrider, DumbAware {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (file.isDirectory || !isPlaybookName(file.name)) return null
        val byName = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        if (byName == YAMLFileType.YML || byName == UnknownFileType.INSTANCE || byName.isBinary) return null
        return if (keepsYaml(file, currentSettings())) YAMLFileType.YML else null
    }

    private fun currentSettings(): JinjaSettings {
        val application = ApplicationManager.getApplication() ?: return DEFAULT
        if (application.isDisposed) return DEFAULT
        return application.serviceOrNull<AnsibilityAppSettings>()?.settings?.jinja ?: DEFAULT
    }

    companion object {
        private val DEFAULT = JinjaSettings()

        /** Directory children inspected for an Ansible root next to a compose file. */
        private const val MAX_SIBLINGS = 200

        /** `*-playbook.yml` or `*-playbook.yaml`, the names PyCharm's Jinja2 type claims. */
        fun isPlaybookName(name: String): Boolean =
            (name.endsWith("-playbook.yml") || name.endsWith("-playbook.yaml")) && name.indexOf("-playbook.") > 0

        /** A docker-compose file name: `docker-compose…` or `compose…`. */
        fun isComposeName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("docker-compose") || lower.startsWith("compose")
        }

        /** Whether the `*-playbook.y*ml` [file] stays YAML under [settings] (see the class description). */
        fun keepsYaml(file: VirtualFile, settings: JinjaSettings): Boolean = when {
            isComposeName(file.name) -> AnsibleTemplatePaths.isInsideAnsibleContent(file) || isNextToAnsibleRoot(file.parent)
            else -> !settings.deferToPyCharmJinja && AnsibleTemplatePaths.isInsideAnsibleContent(file)
        }

        /** [dir] or one of its child directories holds an `ansible.cfg`. */
        private fun isNextToAnsibleRoot(dir: VirtualFile?): Boolean {
            if (dir == null || !dir.isDirectory) return false
            if (hasAnsibleCfg(dir)) return true
            return dir.children.asSequence().take(MAX_SIBLINGS).any { it.isDirectory && hasAnsibleCfg(it) }
        }

        private fun hasAnsibleCfg(dir: VirtualFile): Boolean = dir.findChild(AnsibleLayout.ANSIBLE_CFG)?.isDirectory == false
    }
}

/**
 * `applicationListeners` on [AnsibilityAppSettingsListener.TOPIC]: "Defer .j2 to PyCharm Jinja2" is an input of
 * [AnsiblePlaybookYamlOverrider], so a change re-types every file. The request is coalesced with the template
 * refresh the same change may cause.
 */
class AnsiblePlaybookYamlSettingsListener : AnsibilityAppSettingsListener {
    override fun appSettingsChanged(old: AppSettings, new: AppSettings) {
        if (old.jinja.deferToPyCharmJinja != new.jinja.deferToPyCharmJinja) {
            AnsibleJinjaFileTypeRefresh.scheduleFileTypesChange(REASON)
        }
    }

    private companion object {
        const val REASON = "Ansibility: Defer .j2 to PyCharm Jinja2 changed (*-playbook.y*ml files)"
    }
}
