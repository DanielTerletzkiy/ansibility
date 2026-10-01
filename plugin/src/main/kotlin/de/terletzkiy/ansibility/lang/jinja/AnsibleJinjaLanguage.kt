package de.terletzkiy.ansibility.lang.jinja

import com.intellij.lang.Language
import com.intellij.psi.templateLanguages.TemplateLanguage

private const val LANGUAGE_ID = "AnsibleJinja"

/**
 * The Jinja2 dialect Ansible renders: Jinja core plus the `do` and `loopcontrols` extensions, with the default
 * delimiters (plan A.5).
 *
 * The ID is `AnsibleJinja` and never `Jinja2`: language IDs are global, and PyCharm Professional registers `Jinja2`
 * itself. The language is a [TemplateLanguage] because `.j2` files are templates over an outer language (nginx,
 * YAML, Dockerfile, plain text …); inside YAML scalars it is injected instead.
 */
object AnsibleJinjaLanguage : Language(LANGUAGE_ID), TemplateLanguage {
    /** The language ID, for `language="…"` attributes and [Language.findLanguageByID]. */
    const val ID: String = LANGUAGE_ID

    override fun getDisplayName(): String = AnsibilityJinjaBundle.message("language.display.name")

    /** Jinja names, keywords and filter names are case sensitive (`True` and `true` are both constants, `Foo` is not `foo`). */
    override fun isCaseSensitive(): Boolean = true
}
