package de.terletzkiy.ansibility.lang.jinja.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/**
 * Text attribute keys of the Jinja layer. Each falls back to a standard key, so every colour scheme gives sensible
 * colours without defining them; users can change them under Editor › Color Scheme › Ansible Jinja2.
 */
object AnsibleJinjaHighlighterColors {
    /** `{{`, `}}`, `{%`, `%}` including the whitespace markers `-` and `+`. */
    @JvmField val DELIMITER: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_DELIMITER", DefaultLanguageHighlighterColors.METADATA)

    /** Statement, clause and expression keywords, and the constants `true`, `false`, `none`. */
    @JvmField val KEYWORD: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

    @JvmField val IDENTIFIER: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)

    /** Filter names after `|`, including every segment of an FQCN filter such as `ansible.builtin.splitext`. */
    @JvmField val FILTER: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_FILTER", DefaultLanguageHighlighterColors.FUNCTION_CALL)

    /** Test names after `is` / `is not`. Defaults to the filter colour. */
    @JvmField val TEST: TextAttributesKey = createTextAttributesKey("ANSIBLE_JINJA_TEST", FILTER)

    @JvmField val STRING: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_STRING", DefaultLanguageHighlighterColors.STRING)

    @JvmField val NUMBER: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_NUMBER", DefaultLanguageHighlighterColors.NUMBER)

    /** Arithmetic, comparison, `~`, `=`, `|` and `:`. */
    @JvmField val OPERATOR: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)

    @JvmField val PARENTHESES: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)

    @JvmField val BRACKETS: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)

    /** `{` and `}` of dict literals (not the tag delimiters). */
    @JvmField val BRACES: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_BRACES", DefaultLanguageHighlighterColors.BRACES)

    @JvmField val DOT: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_DOT", DefaultLanguageHighlighterColors.DOT)

    @JvmField val COMMA: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_COMMA", DefaultLanguageHighlighterColors.COMMA)

    /** `{# … #}`. */
    @JvmField val COMMENT: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)

    /** The verbatim body of `{% raw %}…{% endraw %}`. */
    @JvmField val RAW_TEXT: TextAttributesKey = createTextAttributesKey("ANSIBLE_JINJA_RAW_TEXT", HighlighterColors.TEXT)

    @JvmField val BAD_CHARACTER: TextAttributesKey =
        createTextAttributesKey("ANSIBLE_JINJA_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
}
