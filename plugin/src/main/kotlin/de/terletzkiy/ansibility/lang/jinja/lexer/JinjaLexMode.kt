package de.terletzkiy.ansibility.lang.jinja.lexer

/** What kind of input an [AnsibleJinjaLexer] reads. */
enum class JinjaLexMode {
    /**
     * A template: outer text with `{{ … }}`, `{% … %}` and `{# … #}` tags. Used for `.j2` files and for YAML scalars
     * that contain `{{` or `{%`.
     */
    TEMPLATE,

    /**
     * One bare expression without delimiters, as Ansible reads the values of `when`, `changed_when`, `failed_when`,
     * `until`, `assert.that` and `debug.var`. `{{`, `}}` and `%}` have no special meaning here.
     */
    EXPRESSION,
}
