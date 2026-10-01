package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings

/**
 * Makes Ansible templates `AnsibleJinja` files (plan F2.1, D8, A.9), while the file type itself has no extensions:
 * - every `*.j2` file inside Ansible content ([AnsibleTemplatePaths.isInsideAnsibleContent]); `*.j2` elsewhere keeps
 *   the type the IDE gives it (PyCharm's `Jinja2`, a user mapping, plain text);
 * - files without `.j2` below `roles/<role>/templates/` whose first 64 KB contain `{{`, `{%` or `{#`
 *   ([JinjaContentProbe]); plain files there keep their type.
 *
 * `.jinja` and `.jinja2` files are never claimed. The platform asks overriders before extension mappings, so this
 * beats a user's `*.j2 → YAML` mapping inside roots, while a per-file "Override File Type" (the platform's own
 * overrider, which runs first) still wins.
 *
 * The answer is a pure function of the VFS and of the application settings; [AnsibleJinjaFileTypeRefresh] reports
 * changes of either to the platform.
 */
class AnsibleJinjaFileTypeOverrider : FileTypeOverrider, DumbAware {
    override fun getOverriddenFileType(file: VirtualFile): FileType? =
        if (AnsibleJinjaFileTypeRules.claims(file, currentSettings())) AnsibleJinjaFileType else null

    private fun currentSettings(): JinjaSettings {
        val application = ApplicationManager.getApplication() ?: return DEFAULT
        if (application.isDisposed) return DEFAULT
        return application.serviceOrNull<AnsibilityAppSettings>()?.settings?.jinja ?: DEFAULT
    }

    private companion object {
        val DEFAULT = JinjaSettings()
    }
}

/** The claim rules of [AnsibleJinjaFileTypeOverrider], separated for tests and for the refresh logic. */
object AnsibleJinjaFileTypeRules {
    /** PyCharm Professional's Jinja2 language, which "Defer .j2 to PyCharm Jinja2" leaves `.j2` files to. */
    private const val PYCHARM_JINJA_LANGUAGE = "Jinja2"

    /** Whether [file] is an Ansible Jinja template under [settings]. */
    fun claims(file: VirtualFile, settings: JinjaSettings): Boolean {
        if (file.isDirectory) return false
        val name = file.name
        return if (isJ2(name)) {
            claimsJ2(settings) && AnsibleTemplatePaths.isInsideAnsibleContent(file)
        } else {
            settings.treatJinjaTemplatesUnderTemplatesDir &&
                !isJinjaExtension(name) &&
                AnsibleTemplatePaths.isUnderRoleTemplates(file) &&
                !isKnownBinary(name) &&
                JinjaContentProbe.containsJinja(file)
        }
    }

    /**
     * Whether `.j2` files inside roots are claimed: "claim .j2 inside roots" on, "Keep YAML for .j2" off, and "Defer
     * .j2 to PyCharm Jinja2" off or without effect because PyCharm's Jinja2 is not installed (WebStorm, PhpStorm).
     */
    fun claimsJ2(settings: JinjaSettings): Boolean =
        settings.claimJ2InsideRoots && !settings.keepYamlForJ2 && !(settings.deferToPyCharmJinja && pyCharmJinjaInstalled())

    /** The settings that decide which files are claimed or how they parse; a change re-types the files. */
    fun fileTypeInputs(settings: JinjaSettings): List<Any> =
        listOf(claimsJ2(settings), settings.treatJinjaTemplatesUnderTemplatesDir, settings.outerLanguageRules)

    fun isJ2(name: String): Boolean = name.endsWith(J2) && name.length > J2.length

    private fun isJinjaExtension(name: String): Boolean = name.endsWith(".jinja") || name.endsWith(".jinja2")

    /** A name the IDE maps to a binary type (images, archives); unknown names are text candidates. */
    private fun isKnownBinary(name: String): Boolean {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
        return type.isBinary && type != UnknownFileType.INSTANCE
    }

    private fun pyCharmJinjaInstalled(): Boolean = Language.findLanguageByID(PYCHARM_JINJA_LANGUAGE) != null

    private const val J2 = ".j2"
}
