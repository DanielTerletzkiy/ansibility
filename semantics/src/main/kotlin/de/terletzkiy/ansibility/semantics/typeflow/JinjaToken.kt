package de.terletzkiy.ansibility.semantics.typeflow

/**
 * The token kinds [JinjaTemplate] reads: a PSI-free mirror of the plugin's Jinja lexer (`AnsibleJinjaLexer`), which
 * :semantics cannot see. The plugin converts its tokens into [JinjaToken]s; whitespace inside tags is not passed on.
 */
enum class JinjaTokenKind {
    /** Outer text between tags, copied to the output (whitespace control is applied by [JinjaTemplate]). */
    TEXT,

    /** The body of `{% raw %}…{% endraw %}`, copied to the output verbatim. */
    RAW_TEXT,

    /** A whole `{# … #}` comment, delimiters and whitespace markers included. */
    COMMENT,

    /** `{{`, `{{-` or `{{+`. */
    VAR_START,

    /** `}}` or `-}}`. */
    VAR_END,

    /** `{%`, `{%-` or `{%+`. */
    BLOCK_START,

    /** `%}`, `-%}` or `+%}`. */
    BLOCK_END,

    /** A variable, attribute, keyword-argument, filter or test name (one dotted segment of an FQCN filter). */
    NAME,

    /**
     * A keyword as written: `and`, `or`, `not`, `in`, `is`, `if`, `else`, `true`/`True`, `false`/`False`,
     * `none`/`None`, or a statement keyword after `{%`.
     */
    KEYWORD,

    /** A quoted string literal, quotes included. */
    STRING,

    /** An integer literal (`42`, `1_000`, `0x1F`, `0o17`, `0b101`). */
    INTEGER,

    /** A float literal (`1.5`, `2e10`). */
    FLOAT,

    /** An operator or punctuation; [JinjaToken.text] says which (`|`, `~`, `.`, `(`, `==` …). */
    OPERATOR,

    /** A character Jinja's own lexer rejects. */
    BAD,
}

/** One token of a template; [start] is its offset in the template text. */
data class JinjaToken(val kind: JinjaTokenKind, val text: String, val start: Int) {
    /** The offset just after the token. */
    val end: Int get() = start + text.length
}

/** Produces the tokens of a template text (in the plugin: the Jinja lexer in TEMPLATE mode). */
fun interface JinjaTokenizer {
    /** The tokens of [text], in order, without whitespace inside tags. */
    fun tokenize(text: String): List<JinjaToken>
}
