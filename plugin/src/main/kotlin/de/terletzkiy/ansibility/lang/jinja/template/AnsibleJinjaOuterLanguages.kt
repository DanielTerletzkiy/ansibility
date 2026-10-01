package de.terletzkiy.ansibility.lang.jinja.template

import com.intellij.lang.DependentLanguage
import com.intellij.lang.InjectableLanguage
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.psi.templateLanguages.TemplateLanguage
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleTemplatePaths
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings

/**
 * Chooses the outer language of an Ansible Jinja template (plan A.5 `OuterLanguageRules`), in this order:
 * 1. the user's choice in Template Data Languages ([TemplateDataLanguageMappings]: a per-file or per-directory
 *    mapping, or a file name pattern);
 * 2. the outer-language rules of the application settings ([JinjaSettings.outerLanguageRules]; by default nginx
 *    sites → Nginx, `logrotate`/`*.override.conf`/`rsyslog`/`keepalived.conf` → plain text, `Dockerfile` →
 *    Dockerfile, `haproxy.cfg` → plain text, then the inner-extension whitelist `yml`/`yaml`, `sh`, `json`, `ini`,
 *    `cnf`, `env`, `py`, `service`/`timer`), matched on the name without `.j2` and on the path relative to the
 *    Ansible root;
 * 3. plain text. Never HTML: a rule naming a language no installed plugin provides (Nginx and systemd come from
 *    third-party plugins) also falls back to plain text.
 *
 * A language qualifies as an outer language only if it has a parser definition and is not itself a template,
 * dependent or injectable language (the platform's own rule for template data languages).
 */
object AnsibleJinjaOuterLanguages {
    /** The outer language of the template [file] in [project] (null: no user mappings, e.g. for highlighting without a project). */
    fun templateDataLanguage(project: Project?, file: VirtualFile): Language {
        if (project != null && !project.isDisposed) {
            // the service is absent in bare (mock) applications, e.g. parser tests
            val mappings: TemplateDataLanguageMappings? = project.getService(TemplateDataLanguageMappings::class.java)
            mappings?.getMapping(file)?.takeIf(::isUsable)?.let { return it }
        }
        return ruleLanguage(file)
    }

    /** The outer language by the settings rules alone (steps 2 and 3). */
    fun ruleLanguage(file: VirtualFile, settings: JinjaSettings = currentSettings()): Language {
        val id = settings.outerLanguageId(file.name, AnsibleTemplatePaths.relativePath(file)) ?: return PlainTextLanguage.INSTANCE
        return usableLanguage(id) ?: PlainTextLanguage.INSTANCE
    }

    private fun currentSettings(): JinjaSettings = serviceOrNull<AnsibilityAppSettings>()?.settings?.jinja ?: JinjaSettings()

    /** The installed language with [id] if it can be an outer language, else null. */
    fun usableLanguage(id: String): Language? = Language.findLanguageByID(id)?.takeIf(::isUsable)

    /** Whether [language] can be the outer language of a template. */
    fun isUsable(language: Language): Boolean =
        isTemplateable(language) && LanguageParserDefinitions.INSTANCE.forLanguage(language) != null

    /** The platform's rule for template data languages (`TemplateDataLanguageMappings.getTemplateableLanguages`). */
    private fun isTemplateable(language: Language): Boolean {
        if (language == Language.ANY) return false
        if (language is TemplateLanguage || language is DependentLanguage || language is InjectableLanguage) return false
        return language.baseLanguage?.let(::isTemplateable) ?: true
    }
}
