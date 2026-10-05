package de.terletzkiy.ansibility.inspections.types

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * PSI edits of `meta/argument_specs.yml` for the spec quick fixes (🟣 CLAUDE X79, X80): find an option, add a
 * sub-option, switch `elements` to `dict` with inferred `options`. New text is indented like its siblings (the
 * file's own indentation step, 2 when it cannot be told), so the result is the YAML a person would write.
 */
internal object SpecEdits {
    private const val DEFAULT_STEP = 2

    /**
     * The mapping of the option at [optionPath] (the top-level option name, then sub-option names) of [entryPoint]
     * in [spec]: `argument_specs.<entryPoint>.options.<a>.options.<b>…`. Null when the spec has no such option.
     */
    fun optionMapping(spec: YAMLFile, entryPoint: String, optionPath: List<String>): YAMLMapping? {
        val top = YamlPaths.topLevelValue(spec) as? YAMLMapping ?: return null
        val entry = (top.getKeyValueByKey("argument_specs")?.value as? YAMLMapping)?.getKeyValueByKey(entryPoint)?.value
        var mapping = entry as? YAMLMapping ?: return null
        for (name in optionPath) {
            val options = mapping.getKeyValueByKey("options")?.value as? YAMLMapping ?: return null
            mapping = options.getKeyValueByKey(name)?.value as? YAMLMapping ?: return null
        }
        return mapping
    }

    /** Adds sub-option [name] with [fields] (`type: list`, `elements: str` …) to the `options:` of [option], creating it. */
    fun addSubOption(option: YAMLMapping, name: String, fields: List<Pair<String, String>>) {
        addSubOptions(option, linkedMapOf(name to fields))
    }

    /** Sets `elements: dict` on [option] and adds [subOptions] (name → fields) to its `options:`. */
    fun makeElementsDict(option: YAMLMapping, subOptions: Map<String, List<Pair<String, String>>>) {
        option.putKeyValue(YAMLElementGenerator.getInstance(option.project).createYamlKeyValue("elements", "dict"))
        addSubOptions(option, subOptions)
    }

    fun addSubOptions(option: YAMLMapping, subOptions: Map<String, List<Pair<String, String>>>) {
        val project = option.project
        val optionIndent = YAMLUtil.getIndentToThisElement(option)
        val existing = option.getKeyValueByKey("options")?.value as? YAMLMapping
        if (existing != null) {
            val indent = YAMLUtil.getIndentToThisElement(existing)
            val step = (indent - optionIndent).takeIf { it > 0 } ?: DEFAULT_STEP
            for ((name, fields) in subOptions) {
                existing.putKeyValue(keyValue(project, indent, subOptionText(indent, step, name, fields)))
            }
            return
        }
        val step = stepAbove(option, optionIndent)
        val text = buildString {
            append(" ".repeat(optionIndent)).append("options:")
            for ((name, fields) in subOptions) append('\n').append(subOptionText(optionIndent + step, step, name, fields))
        }
        option.putKeyValue(keyValue(project, optionIndent, text))
    }

    /** The indentation step of the mapping that holds [option] (its key's column minus the parent's). */
    private fun stepAbove(option: YAMLMapping, optionIndent: Int): Int {
        val parent = (option.parent as? YAMLKeyValue)?.parentMapping ?: return DEFAULT_STEP
        return (optionIndent - YAMLUtil.getIndentToThisElement(parent)).takeIf { it > 0 } ?: DEFAULT_STEP
    }

    private fun subOptionText(indent: Int, step: Int, name: String, fields: List<Pair<String, String>>): String = buildString {
        append(" ".repeat(indent)).append(keyText(name)).append(':')
        for ((key, value) in fields) append('\n').append(" ".repeat(indent + step)).append(key).append(": ").append(value)
    }

    /** The key-value written in [text], whose first line is indented by [indent] (deeper lines are absolute). */
    private fun keyValue(project: Project, indent: Int, text: String): YAMLKeyValue {
        val body = if (text.startsWith(" ".repeat(indent))) text else " ".repeat(indent) + text
        val dummy = YAMLElementGenerator.getInstance(project).createDummyYamlWithText(body)
        return YAMLUtil.getTopLevelKeys(dummy).first()
    }

    /** A mapping key as YAML text: plain when that loads as the same string, double-quoted otherwise. */
    fun keyText(name: String): String = if (isSafePlain(name)) name else quoted(name)

    /** [text] as a double-quoted YAML scalar. */
    fun quoted(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /** True when [text] can be written as a plain scalar that YAML 1.1 loads as this very string. */
    fun isSafePlain(text: String): Boolean {
        if (text.isEmpty() || text != text.trim() || '\n' in text) return false
        if (text.first() in PLAIN_INDICATORS || ": " in text || " #" in text || text.endsWith(":")) return false
        return Yaml11Resolver.resolvePlain(text) == Resolved.Str(text)
    }

    private const val PLAIN_INDICATORS = "-?:,[]{}#&*!|>'\"%@`"

    // ------------------------------------------------------------------------------------------------ type inference

    /** The spec type name a value suggests: `str`, `int`, `float`, `bool`, `list`, `dict`; null when unknown (templates, null). */
    fun typeOf(value: YValue): String? = when (value) {
        is YVault -> "str"
        is YEmpty -> null
        is YSeq -> "list"
        is YMap -> "dict"
        is YScalar -> if (SpecValidator.containsJinja(value)) {
            null
        } else {
            when (value.resolved) {
                is Resolved.Str, is Resolved.Timestamp -> "str"
                is Resolved.Int -> "int"
                is Resolved.Float -> "float"
                is Resolved.Bool -> "bool"
                Resolved.Null, Resolved.Unloadable -> null
            }
        }
    }

    /** One type for several values: their common type, `float` for ints and floats, `raw` when they disagree or none is known. */
    fun join(types: Collection<String?>): String {
        val known = types.filterNotNull().toSet()
        return when {
            known.size == 1 -> known.single()
            known == setOf("int", "float") -> "float"
            else -> "raw"
        }
    }

    /** `type` (and `elements` for a list whose items agree) for a new option holding [value]. */
    fun fieldsFor(value: YValue?): List<Pair<String, String>> {
        if (value is YSeq) {
            val elements = join(value.items.map(::typeOf))
            return if (value.items.isEmpty() || elements == "raw") listOf("type" to "list") else listOf("type" to "list", "elements" to elements)
        }
        return listOf("type" to join(listOf(value?.let(::typeOf))))
    }
}
