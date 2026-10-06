package de.terletzkiy.ansibility.api

import com.intellij.openapi.util.TextRange

/**
 * Any position inside templated text no other area classifies (plan amendment R11, F11.1): the literal part of
 * `dest: "/etc/alloy/{{ item | basename }}"`, a `{{`, an operator. [text] is the Jinja as Ansible templates it (the
 * decoded YAML scalar, or one `{{ }}`/`{% %}` span of a template file); [expression] for implicit-expression values
 * (`when:`), which render to their value. [loopApplies] is false for the task keys Ansible templates before the loop
 * (`name:`, `loop:`, `with_*`), where `item` is not defined yet.
 */
data class TemplatedValueSite(
    val text: String,
    val container: JinjaContainer,
    val expression: Boolean,
    val loopApplies: Boolean,
    override val range: TextRange,
    /** Template text the value renders inside: earlier `{% set %}` tags and enclosing loops (first item), with [postlude]. */
    val prelude: String = "",
    val postlude: String = "",
) : AnsibleSite

/**
 * A `{% %}` statement tag (plan amendment R11, F11.3): [keyword] (`if`, `for`, `endfor` …) and [body], the tag's text
 * after the keyword. On an `else`/`elif`/`end…` tag, [opener] is the keyword and body of the tag it belongs to.
 * [openerOffset] is the host offset of that opener (of the tag itself otherwise), where its expressions render.
 */
data class JinjaBlockSite(
    val keyword: String,
    val body: String,
    val opener: Pair<String, String>?,
    val container: JinjaContainer,
    val openerOffset: Int,
    override val range: TextRange,
    /** As [TemplatedValueSite.prelude], for the opener's position. */
    val prelude: String = "",
    val postlude: String = "",
) : AnsibleSite
