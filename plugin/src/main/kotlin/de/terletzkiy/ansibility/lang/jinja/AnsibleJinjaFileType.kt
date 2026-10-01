package de.terletzkiy.ansibility.lang.jinja

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.TemplateLanguageFileType
import javax.swing.Icon

/**
 * The file type of Ansible Jinja2 templates.
 *
 * It is registered without extensions or file name patterns on purpose. Which files are Ansible templates is decided
 * per Ansible root (every `.j2` inside a root, plus Jinja-bearing files under `roles/<role>/templates`), not globally, so
 * PyCharm's own `Jinja2` type keeps `*.j2` files outside Ansible roots (plan F2.1, done by
 * `lang.jinja.filetype.AnsibleJinjaFileTypeOverrider`). It is a [TemplateLanguageFileType]: the files are templates over
 * an outer language (`lang.jinja.template.AnsibleJinjaFileViewProvider`).
 */
object AnsibleJinjaFileType : LanguageFileType(AnsibleJinjaLanguage), TemplateLanguageFileType {
    /** The file type name, as in `<fileType name="…">`. Distinct from PyCharm's `Jinja2`. */
    const val NAME: String = "AnsibleJinja"

    override fun getName(): String = NAME

    override fun getDescription(): String = AnsibilityJinjaBundle.message("filetype.description")

    override fun getDisplayName(): String = AnsibilityJinjaBundle.message("filetype.display.name")

    override fun getDefaultExtension(): String = "j2"

    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}
