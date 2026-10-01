package de.terletzkiy.ansibility.lang.jinja.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import de.terletzkiy.ansibility.lang.jinja.AnsibilityJinjaBundle
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import javax.swing.Icon

/** Editor › Color Scheme › Ansible Jinja2: the keys of [AnsibleJinjaHighlighterColors] with a sample template. */
class AnsibleJinjaColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = AnsibilityJinjaBundle.message("color.settings.display.name")

    override fun getIcon(): Icon = AnsibleJinjaFileType.icon

    override fun getHighlighter(): SyntaxHighlighter = AnsibleJinjaSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    internal companion object {
        private fun descriptor(key: String, attributes: TextAttributesKey) =
            AttributesDescriptor(AnsibilityJinjaBundle.lazyMessage(key), attributes)

        val DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
            descriptor("color.settings.delimiter", AnsibleJinjaHighlighterColors.DELIMITER),
            descriptor("color.settings.keyword", AnsibleJinjaHighlighterColors.KEYWORD),
            descriptor("color.settings.identifier", AnsibleJinjaHighlighterColors.IDENTIFIER),
            descriptor("color.settings.filter", AnsibleJinjaHighlighterColors.FILTER),
            descriptor("color.settings.test", AnsibleJinjaHighlighterColors.TEST),
            descriptor("color.settings.string", AnsibleJinjaHighlighterColors.STRING),
            descriptor("color.settings.number", AnsibleJinjaHighlighterColors.NUMBER),
            descriptor("color.settings.operator", AnsibleJinjaHighlighterColors.OPERATOR),
            descriptor("color.settings.parentheses", AnsibleJinjaHighlighterColors.PARENTHESES),
            descriptor("color.settings.brackets", AnsibleJinjaHighlighterColors.BRACKETS),
            descriptor("color.settings.braces", AnsibleJinjaHighlighterColors.BRACES),
            descriptor("color.settings.dot", AnsibleJinjaHighlighterColors.DOT),
            descriptor("color.settings.comma", AnsibleJinjaHighlighterColors.COMMA),
            descriptor("color.settings.comment", AnsibleJinjaHighlighterColors.COMMENT),
            descriptor("color.settings.raw.text", AnsibleJinjaHighlighterColors.RAW_TEXT),
            descriptor("color.settings.bad.character", AnsibleJinjaHighlighterColors.BAD_CHARACTER),
        )

        val DEMO_TEXT: String = """
            |{#- Rendered by ansible.builtin.template -#}
            |# {{ ansible_managed }}
            |{% set ns = namespace(found=false, options={'http2': true}) %}
            |{% for key, value in nginx_sites | dictsort if value.enabled is defined %}
            |server {
            |    listen {{ item.floating.ip }}:{{ item.floating.port | default(443) | int }};
            |    server_name {{ value.hostnames | join(' ') ~ ".example.test" }};
            |{%   if loop.last and value.ssl is not none %}
            |    ssl_certificate /etc/ssl/{{ value.ssl['cert_file'] }};
            |{%     set ns.found = true %}
            |{%   endif %}
            |    keepalive_timeout {{ (value.timeout * 1.5) | round | int }};
            |    root {{ value.path.split('/')[:-1] | join('/') if value.path else '/srv' }};
            |}
            |{% endfor %}
            |{% raw %}{{ .Labels.host }}{% endraw %}
            |{{ app_path | ansible.builtin.basename }}
            """.trimMargin() + "\n"
    }
}
