package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics

/**
 * How the target ansible-core turns a rendered template into a value (plan A.5 `TemplatingRules`), measured with
 * `Templar.template` in 2.18.8 (with and without `jinja2_native`) and 2.21.4:
 *
 * - [Mode.CLASSIC] (≤ 2.18, `jinja2_native` off, `CoreSemantics.stringifyNonBareTemplates`): a template that is
 *   exactly `{{ name }}` keeps the variable's bool, int, float or None value; everything else is rendered to a
 *   string (`ansible_eval_concat`), which is then `literal_eval`'d only when it starts with `[`/`{` or is
 *   `True`/`False`. A variable whose value is itself a template renders to a string (`{{ a }}` with `a: '{{ i }}'`
 *   is `'5'`), and so does a date. Filters returning `NativeJinjaText` (`string`, `to_json`, `to_yaml` …) stay strings.
 * - [Mode.LEGACY_NATIVE] (≤ 2.18 with `jinja2_native`): native values, but every rendered string goes through
 *   `literal_eval` (`'42'` becomes 42), so a string's final type is unknown unless it is `NativeJinjaText`.
 * - [Mode.NATIVE] (2.19+): a single expression keeps its native value at every hop; any template with more than one
 *   node (text plus an expression, several expressions) is a string. Templates with statements may return a native
 *   value (`{% if b %}{{ i }}{% endif %}` is an int).
 *
 * @property core the target core's version flags
 * @property jinja2Native `ansible.cfg` `jinja2_native` (ignored, and deprecated, from 2.19 on)
 */
data class TemplatingRules(val core: CoreSemantics = CoreSemantics.PINNED, val jinja2Native: Boolean = false) {
    /** The templating behaviour of the target. */
    enum class Mode { CLASSIC, LEGACY_NATIVE, NATIVE }

    val mode: Mode
        get() = when {
            !core.stringifyNonBareTemplates -> Mode.NATIVE
            jinja2Native -> Mode.LEGACY_NATIVE
            else -> Mode.CLASSIC
        }

    override fun toString(): String = "TemplatingRules(${core.version}, $mode)"
}
