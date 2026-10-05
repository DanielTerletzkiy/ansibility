package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.render.oracle.CheckSubject
import de.terletzkiy.ansibility.semantics.render.oracle.ExpressionField
import de.terletzkiy.ansibility.semantics.render.oracle.OracleCase
import de.terletzkiy.ansibility.semantics.render.oracle.OracleCheck
import de.terletzkiy.ansibility.semantics.render.oracle.OracleCore
import de.terletzkiy.ansibility.semantics.render.oracle.OracleRunner
import de.terletzkiy.ansibility.semantics.render.oracle.Outcome
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.typeflow.TestJinjaTokenizer
import de.terletzkiy.ansibility.semantics.yaml.YMap
import java.io.File

/**
 * The expression cases (`04_value_rendering`, `05_filters_tests_operators` …): every row ran as a template file
 * `templates/e/<id>.j2`, as `set_fact: {r: "{{ expr }}"}` and as `debug: msg="{{ (expr) | type_debug }}"`, with the
 * variables of `vars.json`.
 */
object ExpressionOracleRunner : OracleRunner {
    override fun run(case: OracleCase, core: OracleCore, checks: List<OracleCheck>): Map<String, Outcome> {
        val vars = HashMap<String, Binding>()
        for (name in listOf("vars.json", "vars.yml")) {
            val file = File(case.inputDir, name)
            if (!file.isFile) continue
            val map = YamlText.parse(file.readText()) as YMap
            for (entry in map.entries) vars[entry.key.text] = Binding.Yaml(entry.value, name, json = name.endsWith(".json"))
        }
        val scope = RenderScope { name -> vars[name] ?: Binding.Undefined(proven = true) }
        val native = case.id.endsWith("_jinja2_native")
        val out = LinkedHashMap<String, Outcome>()
        for (check in checks) {
            val subject = check.subject as? CheckSubject.Expression ?: continue
            out[check.id] = when (subject.field) {
                ExpressionField.TEMPLATE -> {
                    val file = File(case.inputDir, "templates/e/${subject.id}.j2")
                    if (!file.isFile) continue
                    val options = RenderOptions(RenderMode.TEMPLATE_FILE, core.version, jinja2Native = native)
                    val tree = TemplateTree.parse(file.readText(), TestJinjaTokenizer, options.env)
                    text(TemplateRenderer(TestJinjaTokenizer, scope, options).renderTemplate(tree))
                }
                ExpressionField.ARG -> {
                    val options = RenderOptions(RenderMode.YAML_VALUE, core.version, jinja2Native = native)
                    val outcome = value(TemplateRenderer(TestJinjaTokenizer, scope, options).renderValue("{{ ${subject.expr} }}"))
                    // set_fact (classic templating) stores boolean-looking strings as booleans
                    val stored = (outcome as? Outcome.Known)?.value as? String
                    if (!options.native && !native && stored != null && stored.lowercase() in setOf("true", "false", "yes", "no")) {
                        Outcome.Known(stored.lowercase() in setOf("true", "yes"))
                    } else {
                        outcome
                    }
                }
                ExpressionField.TYPE -> {
                    // type_debug of a failing expression names 2.19+'s error marker types; the preview never shows those.
                    val options = RenderOptions(RenderMode.YAML_VALUE, core.version, jinja2Native = native)
                    val rendered = TemplateRenderer(TestJinjaTokenizer, scope, options).renderValue("{{ (${subject.expr}) | type_debug }}")
                    if (rendered.errors.isNotEmpty()) Outcome.Placeholder("type of a failing expression") else value(rendered)
                }
            }
        }
        return out
    }

    /** Whether a render fails is compared; the wording of the error is ansible-core's own and not reproduced. */
    private fun text(rendered: Rendered): Outcome = when {
        rendered.errors.isNotEmpty() -> Outcome.Failed()
        rendered.complete -> Outcome.Known(rendered.text)
        else -> Outcome.Placeholder("${rendered.placeholders} placeholders")
    }

    private fun value(rendered: Rendered): Outcome {
        if (rendered.errors.isNotEmpty()) return Outcome.Failed()
        val value = rendered.value ?: return Outcome.Placeholder("no value")
        if (RValues.holeIn(value) != null) return Outcome.Placeholder("hole")
        return try {
            Outcome.Known(json(value))
        } catch (e: NotJson) {
            Outcome.Placeholder("not JSON-shaped: ${e.message}")
        }
    }

    private class NotJson(type: String) : RuntimeException(type)

    /** The value as the json callback prints it. */
    private fun json(value: RValue): Any? = when (value) {
        RValue.None -> null
        is RValue.Bool -> value.value
        is RValue.Int -> if (value.value.bitLength() < 64) value.value.toLong() else value.value
        is RValue.Float -> value.value
        is RValue.Str -> value.value
        is RValue.List -> value.items.map(::json)
        is RValue.Tuple -> value.items.map(::json)
        is RValue.Dict -> value.entries.associate { (k, v) -> RValues.str(k, true) to json(v) }
        else -> throw NotJson(value.typeName)
    }
}
