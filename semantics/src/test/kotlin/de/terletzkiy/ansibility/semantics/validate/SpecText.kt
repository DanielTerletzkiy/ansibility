package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.schema.Choices
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.SpecOrigin
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Test helper: builds [OptionSpec]s from argument-spec YAML (the subset the validator reads: type, elements,
 * required, default, choices as list or mapping, aliases, options, no_log), the way `ArgSpecParser` will.
 */
object SpecText {
    /** One option spelled as a YAML flow or block mapping, e.g. `{type: int, choices: [1, 2]}`. */
    fun option(name: String, yaml: String, origin: SpecOrigin = SpecOrigin.Unknown): OptionSpec =
        option(name, YamlText.parse("s: $yaml").let { (it as YMap)["s"]!! }, origin)

    /** The `options:` mapping of a role entry point written as YAML. */
    fun options(yaml: String, origin: SpecOrigin = SpecOrigin.Unknown): Map<String, OptionSpec> =
        options(YamlText.parse(yaml) as YMap, origin)

    /** The `options:` mapping of a role entry point. */
    fun options(map: YMap, origin: SpecOrigin = SpecOrigin.Unknown): Map<String, OptionSpec> =
        map.entries.associate { it.key.text to option(it.key.text, it.value, origin) }

    private fun option(name: String, node: YValue, origin: SpecOrigin): OptionSpec {
        val map = node as? YMap ?: YMap(emptyList())
        fun text(key: String): String? = (map[key] as? YScalar)?.text
        fun flag(key: String): Boolean = ((map[key] as? YScalar)?.resolved as? Resolved.Bool)?.value == true
        val choices = when (val c = map["choices"]) {
            is YSeq -> Choices.Values(c.items)
            is YMap -> Choices.Described(c.entries.map { it.key to listOf((it.value as? YScalar)?.text.orEmpty()) })
            else -> null
        }
        val options = when (val o = map["options"]) {
            is YMap -> o.entries.associate { it.key.text to option(it.key.text, it.value, origin) }
            else -> null
        }
        return OptionSpec(
            name = name,
            type = OptionType.parse(text("type")),
            elements = text("elements")?.let { OptionType.parse(it) },
            required = flag("required"),
            default = if (map.keys.contains("default")) map["default"] ?: YEmpty() else null,
            choices = choices,
            aliases = (map["aliases"] as? YSeq)?.items?.map { (it as YScalar).text }.orEmpty(),
            options = options,
            noLog = flag("no_log"),
            origin = origin,
        )
    }
}
