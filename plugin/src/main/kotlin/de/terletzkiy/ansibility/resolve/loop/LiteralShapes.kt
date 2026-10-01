package de.terletzkiy.ansibility.resolve.loop

import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * The shape of a literal YAML value as an [OptionSpec] tree (plan F1.7, X09 literal lists): mappings become `dict`s
 * whose keys are `options`, sequences `list`s with the union of their items as `elements`/`options`, scalars their
 * YAML 1.1 type. Only keys and types are kept, never values, so a `!vault` value is just a `str`.
 *
 * Jinja strings and nulls are `raw` (their type is only known at runtime). Unions of different types are `raw`,
 * except `int` with `float`, which widens to `float`. Nesting is cut at [MAX_DEPTH].
 */
object LiteralShapes {
    /** Deeper literal structure is typed `raw`. */
    const val MAX_DEPTH: Int = 6

    /** The shape of [value], named [name]. */
    fun of(name: String, value: YValue, depth: Int = 0): OptionSpec = when (value) {
        is YMap -> if (depth >= MAX_DEPTH) raw(name) else OptionSpec(name, OptionType.Dict, options = optionsOf(value, depth))
        is YSeq -> sequenceShape(name, value.items, depth)
        is YScalar -> OptionSpec(name, scalarType(value))
        is YVault -> OptionSpec(name, OptionType.Str)
        is YEmpty -> raw(name)
    }

    /**
     * The element shape of a list with [items], named [name]; null for an empty list. With [flatten] (the
     * `with_items` rule) nested sequences contribute their own items.
     */
    fun elementOf(name: String, items: List<YValue>, flatten: Boolean = false): OptionSpec? {
        val flat = if (flatten) items.flatMap { (it as? YSeq)?.items ?: listOf(it) } else items
        return flat.map { of(name, it, 1) }.reduceOrNull { a, b -> union(a, b) }
    }

    /** The union of two shapes of the same name: merged `options` for dicts, `raw` for incompatible types. */
    fun union(a: OptionSpec, b: OptionSpec): OptionSpec {
        ProgressManager.checkCanceled()
        if (a.type == OptionType.Raw) return a
        if (b.type == OptionType.Raw) return b.copy(name = a.name)
        if (a.type != b.type) {
            val numbers = setOf(OptionType.Int, OptionType.Float)
            return if (a.type in numbers && b.type in numbers) OptionSpec(a.name, OptionType.Float) else raw(a.name)
        }
        val elements = when {
            a.elements == b.elements -> a.elements
            a.elements == null -> b.elements
            b.elements == null -> a.elements
            else -> OptionType.Raw
        }
        val options = mergeOptions(a.options, b.options)
        return a.copy(elements = elements, options = options)
    }

    private fun sequenceShape(name: String, items: List<YValue>, depth: Int): OptionSpec {
        if (depth >= MAX_DEPTH) return OptionSpec(name, OptionType.List)
        val element = items.map { of(name, it, depth + 1) }.reduceOrNull { a, b -> union(a, b) }
        return OptionSpec(
            name,
            OptionType.List,
            elements = element?.type,
            options = element?.options.takeIf { element?.type == OptionType.Dict },
        )
    }

    private fun optionsOf(map: YMap, depth: Int): Map<String, OptionSpec> {
        val result = LinkedHashMap<String, OptionSpec>()
        for (entry in map.entries) {
            val key = entry.key.text
            val shape = of(key, entry.value, depth + 1)
            result[key] = result[key]?.let { union(it, shape) } ?: shape
        }
        return result
    }

    private fun mergeOptions(a: Map<String, OptionSpec>?, b: Map<String, OptionSpec>?): Map<String, OptionSpec>? {
        if (a == null) return b
        if (b == null) return a
        val result = LinkedHashMap(a)
        for ((key, spec) in b) result[key] = result[key]?.let { union(it, spec) } ?: spec
        return result
    }

    private fun scalarType(scalar: YScalar): OptionType = when (scalar.resolved) {
        is Resolved.Str -> if (scalar.tag != "!unsafe" && JinjaBearing.hasTemplateMarkers(scalar.text)) OptionType.Raw else OptionType.Str
        is Resolved.Int -> OptionType.Int
        is Resolved.Float -> OptionType.Float
        is Resolved.Bool -> OptionType.Bool
        is Resolved.Timestamp -> OptionType.Str
        Resolved.Null, Resolved.Unloadable -> OptionType.Raw
    }

    private fun raw(name: String) = OptionSpec(name, OptionType.Raw)
}
