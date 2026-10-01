package de.terletzkiy.ansibility.lang.jinja.injection

import com.intellij.openapi.util.Key
import de.terletzkiy.ansibility.api.JinjaContainer

/** Wraps a bare expression into an output tag (the expression-mode prefix). */
private const val OPEN_OUTPUT = "{{ "

/** Closes the output tag of [OPEN_OUTPUT] (the expression-mode suffix). */
private const val CLOSE_OUTPUT = " }}"

/**
 * How a YAML scalar's value is injected as Ansible Jinja (plan A.5 "Jinja inside YAML").
 *
 * Both modes inject the same language with the same (template-mode) parser: a bare expression is wrapped in
 * [EXPRESSION_PREFIX] and [EXPRESSION_SUFFIX], so offsets map back through the prefix and one grammar serves both.
 */
enum class JinjaInjectionMode(
    /** Text the injected fragment starts with before the scalar's value (not in the host). */
    val prefix: String?,
    /** Text the injected fragment ends with after the scalar's value (not in the host). */
    val suffix: String?,
    /** The [JinjaContainer] variable references in such a fragment report. */
    val container: JinjaContainer,
) {
    /** The value is a template: a scalar containing `{{` or `{%` (also an implicit-expression value that has them). */
    TEMPLATE(null, null, JinjaContainer.YAML_TEMPLATE),

    /** The value is one bare expression: `when`, `changed_when`, `failed_when`, `until`, `assert.that`, `debug.var`. */
    EXPRESSION(OPEN_OUTPUT, CLOSE_OUTPUT, JinjaContainer.YAML_EXPRESSION),
    ;

    /** The length of [prefix] (0 without one): the injected offset of the value's first character. */
    val prefixLength: Int get() = prefix?.length ?: 0

    /** The length of [suffix] (0 without one). */
    val suffixLength: Int get() = suffix?.length ?: 0

    companion object {
        /** Wraps a bare expression into an output tag. */
        const val EXPRESSION_PREFIX: String = OPEN_OUTPUT

        /** Closes the output tag opened by [EXPRESSION_PREFIX]. */
        const val EXPRESSION_SUFFIX: String = CLOSE_OUTPUT

        /** Stored on every injected Ansible Jinja file by the YAML injector: the mode it was injected in. */
        @JvmField
        val KEY: Key<JinjaInjectionMode> = Key.create("ansibility.jinja.injectionMode")
    }
}
