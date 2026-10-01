package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.YMap

/** Builds evaluators over variables written as YAML, the way the plugin's resolver reads them from vars files. */
internal object TypeflowTestSupport {
    val CORE_218: CoreSemantics = CoreSemantics(CoreVersion(2, 18, 8))
    val CORE_221: CoreSemantics = CoreSemantics(CoreVersion(2, 21, 4))

    /**
     * An evaluator whose variables are the keys of [vars] (a YAML mapping; a key repeated in [more] gets several
     * definitions). Labels are `vars.yml:<line>`; names in [secrets] are secret, names in [runtimeOnly] have a
     * definition without a readable value.
     */
    fun evaluator(
        vars: String,
        core: CoreSemantics = CORE_218,
        jinja2Native: Boolean = false,
        maxDepth: Int = JinjaTypeEvaluator.DEFAULT_MAX_DEPTH,
        more: List<String> = emptyList(),
        secrets: Set<String> = emptySet(),
        runtimeOnly: Set<String> = emptySet(),
    ): JinjaTypeEvaluator {
        val files = (listOf(vars) + more).map { it.trimIndent() }
        val definitions = HashMap<String, MutableList<VariableDefinition>>()
        files.forEachIndexed { index, text ->
            if (text.isBlank()) return@forEachIndexed
            val map = YamlText.parse(text) as YMap
            for (entry in map.entries) {
                val line = text.substring(0, entry.key.range!!.start).count { it == '\n' } + 1
                val file = if (index == 0) "vars.yml" else "more$index.yml"
                definitions.getOrPut(entry.key.text) { ArrayList() } +=
                    VariableDefinition("$file:$line", entry.value.takeUnless { entry.key.text in runtimeOnly }, entry.key.text in secrets)
            }
        }
        return JinjaTypeEvaluator(TemplatingRules(core, jinja2Native), TestJinjaTokenizer, { definitions[it] }, maxDepth)
    }

    /** The type names of [value] (`int`, `str|list` …), `unknown` or `vault`. */
    fun describe(value: AValue): String = when {
        value.unknown -> "unknown"
        value.vault -> "vault"
        else -> value.types.joinToString("|") { it.pyName }
    }
}
