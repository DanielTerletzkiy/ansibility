package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * A problem found while reading an argument spec. Parsing never throws; it keeps whatever it can use.
 *
 * [path] locates the node: the entry point first, then option names (`["main", "servers", "port"]`),
 * and finally the offending spec key when there is one (`"type"`).
 */
data class SpecIssue(
    val path: List<String>,
    val message: String,
    val range: SourceRange? = null,
)

/** The entry points of one argument spec document, plus the problems found while reading it. */
data class ArgSpecParseResult(
    val entryPoints: Map<String, ArgumentSpec>,
    val issues: List<SpecIssue>,
)

/**
 * Reads a role's `meta/argument_specs.yml` (or the `argument_specs` key of `meta/main.yml`) into [ArgumentSpec]s,
 * the way ansible-core loads it: the document is passed through unchanged to `ArgumentSpecValidator`, and
 * `ansible-doc -t role -j` prints it as-is.
 *
 * Normalisation, in line with [OptionSpec]:
 * - a missing `type` is `str`; an unknown type name becomes [OptionType.Invalid] and is reported;
 * - `description` may be a string or a list; folded and literal block scalars lose their trailing line breaks;
 * - `choices` may be a list ([Choices.Values]) or a mapping value → description ([Choices.Described]);
 * - `required` and `no_log` follow Python truthiness (a non-empty string is true), non-booleans are reported;
 * - `options: {}` is kept as an empty map (no sub-key is allowed), a missing or empty `options` is `null` (free-form).
 */
object ArgSpecParser {
    /** Option keys understood by ansible-core's argument spec machinery (and `ansible-doc`). */
    val OPTION_KEYS: Set<String> = setOf(
        "type", "elements", "required", "default", "choices", "aliases", "description", "options", "version_added",
        "no_log", "deprecated", "apply_defaults", "fallback", "removed_in_version", "removed_at_date",
        "removed_from_collection", "deprecated_aliases", "mutually_exclusive", "required_together",
        "required_one_of", "required_if", "required_by",
    )

    /** Entry point keys documented for role argument specs. */
    val ENTRY_POINT_KEYS: Set<String> = setOf(
        "short_description", "description", "author", "options", "version_added", "notes", "seealso", "attributes",
        "requirements", "mutually_exclusive", "required_together", "required_one_of", "required_if", "required_by",
    )

    /**
     * Parses a whole spec document. [root] is the file's top-level mapping (it must contain `argument_specs`).
     * [roleName] fills [SpecOrigin.RoleSpec] on every option; without it the origin stays [SpecOrigin.Unknown].
     */
    fun parse(root: YValue?, roleName: String? = null): ArgSpecParseResult {
        val issues = mutableListOf<SpecIssue>()
        if (root !is YMap) {
            issues += SpecIssue(emptyList(), "The spec file must be a mapping with an 'argument_specs' key", root?.range)
            return ArgSpecParseResult(emptyMap(), issues)
        }
        val specs = root["argument_specs"]
        if (specs == null) {
            issues += SpecIssue(emptyList(), "Missing 'argument_specs' key", root.range)
            return ArgSpecParseResult(emptyMap(), issues)
        }
        return parseEntryPoints(specs, roleName, issues)
    }

    /** Parses the value of `argument_specs` (a mapping of entry point name → entry point). */
    fun parseEntryPoints(specs: YValue, roleName: String? = null): ArgSpecParseResult =
        parseEntryPoints(specs, roleName, mutableListOf())

    private fun parseEntryPoints(specs: YValue, roleName: String?, issues: MutableList<SpecIssue>): ArgSpecParseResult {
        if (specs is YEmpty) return ArgSpecParseResult(emptyMap(), issues)
        if (specs !is YMap) {
            issues += SpecIssue(emptyList(), "'argument_specs' must be a mapping of entry points", specs.range)
            return ArgSpecParseResult(emptyMap(), issues)
        }
        val result = LinkedHashMap<String, ArgumentSpec>()
        for (entry in specs.entries) {
            val name = entry.key.text
            result[name] = parseEntryPoint(name, entry.value, roleName, issues)
        }
        return ArgSpecParseResult(result, issues)
    }

    private fun parseEntryPoint(name: String, value: YValue, roleName: String?, issues: MutableList<SpecIssue>): ArgumentSpec {
        val path = listOf(name)
        if (value is YEmpty) return ArgumentSpec(name)
        if (value !is YMap) {
            issues += SpecIssue(path, "Entry point '$name' must be a mapping", value.range)
            return ArgumentSpec(name)
        }
        for (entry in value.entries) {
            if (entry.key.text !in ENTRY_POINT_KEYS) {
                issues += SpecIssue(path + entry.key.text, "Unknown entry point key '${entry.key.text}'", entry.key.range)
            }
        }
        val shortDescription = value["short_description"]?.let { scalarText(it, path + "short_description", issues) }
        val description = value["description"]?.let { paragraphs(it, path + "description", issues) }.orEmpty()
        val context = Context(roleName, name, issues)
        val options = when (val raw = value["options"]) {
            null, is YEmpty -> emptyMap()
            else -> parseOptions(raw, path, context) ?: emptyMap()
        }
        return ArgumentSpec(name, shortDescription, description, options)
    }

    private class Context(val roleName: String?, val entryPoint: String, val issues: MutableList<SpecIssue>) {
        fun origin(): SpecOrigin = if (roleName != null) SpecOrigin.RoleSpec(roleName, entryPoint) else SpecOrigin.Unknown
    }

    /** Parses an `options` mapping; `null` when [value] is not a mapping (reported). */
    private fun parseOptions(value: YValue, path: List<String>, context: Context): Map<String, OptionSpec>? {
        if (value !is YMap) {
            context.issues += SpecIssue(path + "options", "'options' must be a mapping of option names", value.range)
            return null
        }
        val result = LinkedHashMap<String, OptionSpec>()
        for (entry in value.entries) {
            val name = entry.key.text
            result[name] = parseOption(name, entry.value, path + name, context)
        }
        return result
    }

    private fun parseOption(name: String, value: YValue, path: List<String>, context: Context): OptionSpec {
        val issues = context.issues
        if (value !is YMap) {
            issues += SpecIssue(path, "Option '$name' must be a mapping", value.range)
            return OptionSpec(name = name, origin = context.origin())
        }
        for (entry in value.entries) {
            if (entry.key.text !in OPTION_KEYS) {
                issues += SpecIssue(path + entry.key.text, "Unknown option key '${entry.key.text}'", entry.key.range)
            }
        }
        val type = typeOf(value["type"], path + "type", issues) ?: OptionType.Str
        val elements = typeOf(value["elements"], path + "elements", issues)
        val options = when (val raw = value["options"]) {
            null, is YEmpty -> null
            else -> parseOptions(raw, path, context)
        }
        return OptionSpec(
            name = name,
            type = type,
            elements = elements,
            required = value["required"]?.let { flag(it, path + "required", issues) } ?: false,
            default = value["default"],
            choices = value["choices"]?.let { choices(it, path + "choices", issues) },
            aliases = value["aliases"]?.let { aliases(it, path + "aliases", issues) }.orEmpty(),
            description = value["description"]?.let { paragraphs(it, path + "description", issues) }.orEmpty(),
            options = options,
            versionAdded = value["version_added"]?.let { scalarText(it, path + "version_added", issues) },
            deprecated = value["deprecated"]?.let { deprecation(it, path + "deprecated", issues) },
            noLog = value["no_log"]?.let { flag(it, path + "no_log", issues) } ?: false,
            origin = context.origin(),
        )
    }

    private fun typeOf(value: YValue?, path: List<String>, issues: MutableList<SpecIssue>): OptionType? {
        if (value == null || value is YEmpty) return null
        if (value !is YScalar) {
            issues += SpecIssue(path, "'${path.last()}' must be a type name", value.range)
            return OptionType.Invalid(describe(value))
        }
        val type = OptionType.parse(value.text)
        if (type is OptionType.Invalid) {
            issues += SpecIssue(path, "Unknown type '${value.text}' (ansible-core accepts ${KNOWN_TYPES.joinToString()})", value.range)
        }
        return type
    }

    private val KNOWN_TYPES = listOf("str", "bool", "int", "float", "list", "dict", "path", "raw", "jsonarg", "json", "bytes", "bits")

    /** Python truthiness of a spec flag; anything but a YAML boolean is reported. */
    private fun flag(value: YValue, path: List<String>, issues: MutableList<SpecIssue>): Boolean {
        val truthy = pythonTruthy(value)
        val isBool = value is YScalar && value.resolved is Resolved.Bool
        if (!isBool) issues += SpecIssue(path, "'${path.last()}' should be a boolean (read as ${if (truthy) "true" else "false"})", value.range)
        return truthy
    }

    private fun choices(value: YValue, path: List<String>, issues: MutableList<SpecIssue>): Choices? = when (value) {
        is YSeq -> Choices.Values(value.items)
        is YMap -> Choices.Described(value.entries.map { it.key to paragraphs(it.value, path + it.key.text, issues, required = false) })
        is YEmpty -> null
        else -> {
            issues += SpecIssue(path, "'choices' must be a list (or a mapping of value to description)", value.range)
            null
        }
    }

    private fun aliases(value: YValue, path: List<String>, issues: MutableList<SpecIssue>): List<String> = when (value) {
        is YSeq -> value.items.mapNotNull { item ->
            (item as? YScalar)?.text ?: run {
                issues += SpecIssue(path, "Aliases must be strings", item.range)
                null
            }
        }
        is YScalar -> {
            issues += SpecIssue(path, "'aliases' must be a list", value.range)
            listOf(value.text)
        }
        is YEmpty -> emptyList()
        else -> {
            issues += SpecIssue(path, "'aliases' must be a list", value.range)
            emptyList()
        }
    }

    private fun deprecation(value: YValue, path: List<String>, issues: MutableList<SpecIssue>): Deprecation? {
        if (value !is YMap) {
            issues += SpecIssue(path, "'deprecated' must be a mapping", value.range)
            return null
        }
        fun text(key: String) = value[key]?.let { scalarText(it, path + key, issues) }
        return Deprecation(
            why = text("why"),
            alternative = text("alternative"),
            removedIn = text("removed_in") ?: text("removed_at_date"),
            removedFromCollection = text("removed_from_collection"),
        )
    }

    /** Description paragraphs: a string is one paragraph, a list gives one paragraph per item. */
    private fun paragraphs(value: YValue, path: List<String>, issues: MutableList<SpecIssue>, required: Boolean = true): List<String> =
        when (value) {
            is YScalar -> listOf(paragraph(value))
            is YSeq -> value.items.mapNotNull { item ->
                when (item) {
                    is YScalar -> paragraph(item)
                    is YEmpty -> null
                    else -> {
                        issues += SpecIssue(path, "Description items must be strings", item.range)
                        null
                    }
                }
            }
            is YEmpty -> emptyList()
            else -> {
                if (required) issues += SpecIssue(path, "'description' must be a string or a list of strings", value.range)
                emptyList()
            }
        }

    private fun paragraph(scalar: YScalar): String =
        if (scalar.style == ScalarStyle.FOLDED || scalar.style == ScalarStyle.LITERAL) scalar.text.trimEnd('\n') else scalar.text

    private fun scalarText(value: YValue, path: List<String>, issues: MutableList<SpecIssue>): String? = when (value) {
        is YScalar -> paragraph(value)
        is YEmpty -> null
        else -> {
            issues += SpecIssue(path, "'${path.last()}' must be a string", value.range)
            null
        }
    }

    /** Python `bool(value)` for a loaded YAML value. */
    fun pythonTruthy(value: YValue?): Boolean = when (value) {
        null, is YEmpty -> false
        is YVault -> true
        is YSeq -> value.items.isNotEmpty()
        is YMap -> value.entries.isNotEmpty()
        is YScalar -> when (val r = value.resolved) {
            Resolved.Null -> false
            is Resolved.Bool -> r.value
            is Resolved.Int -> r.value.signum() != 0
            is Resolved.Float -> r.value != 0.0
            is Resolved.Str -> r.value.isNotEmpty()
            is Resolved.Timestamp, Resolved.Unloadable -> true
        }
    }

    private fun describe(value: YValue): String = when (value) {
        is YMap -> "mapping"
        is YSeq -> "list"
        is YVault -> "vault"
        is YEmpty -> "null"
        is YScalar -> value.text
    }
}
