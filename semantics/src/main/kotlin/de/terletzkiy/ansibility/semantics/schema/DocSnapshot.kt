package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.YValueJson
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.GZIPInputStream

/** Documentation of a filter, test or lookup plugin (Jinja2's own filters and tests included). */
data class PluginDoc(
    val fqcn: String,
    val kind: PluginKind,
    val shortDescription: String? = null,
    val description: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    /** `_input` is the filtered/tested value, `_terms` a lookup's positional terms. */
    val options: Map<String, OptionSpec> = emptyMap(),
    /** Option names that may be passed positionally, in order. */
    val positional: List<String> = emptyList(),
    /** `_value` (filters, tests) or `_raw` (lookups). */
    val returns: Map<String, ReturnSpec> = emptyMap(),
    val versionAdded: String? = null,
    val deprecated: Deprecation? = null,
    val filename: String? = null,
    /** A Jinja2 builtin (`default`, `map`, `defined` …) documented from its docstring. */
    val jinjaBuiltin: Boolean = false,
    val source: String = "unknown",
)

/** A module lookup: how the name routed, and the documentation of [RouteResolution.canonical] when the snapshot has it. */
data class ModuleLookup(val resolution: RouteResolution, val doc: ModuleDoc?)

/** A filter/test/lookup lookup, like [ModuleLookup]. */
data class PluginLookup(val resolution: RouteResolution, val doc: PluginDoc?)

/**
 * A bundled documentation snapshot (`plugin/src/main/resources/ansible-data/core-*.json.gz`, produced by
 * `tools/docgen/snapshot/generate.py`), loaded into the schema model.
 *
 * Normalisation applied on top of the generator's (plan A.11):
 * - module and plugin options become [OptionSpec]s with [SpecOrigin.ModuleDoc] (`suboptions` are already `options`);
 * - an object-valued `choices` becomes [Choices.Described];
 * - return type `complex` (a nested structure described by `contains`) becomes [OptionType.Dict];
 * - [ModuleDoc.attributes] maps each attribute to its `support` value (`full`, `partial`, `none`, `N/A`),
 *   or to its platforms for the `platform` attribute (`posix`);
 * - [ModuleDoc.seeAlso] holds one Ansible-markup string per entry (`M(ansible.builtin.copy)`,
 *   `P(ansible.builtin.file#lookup)`, `L(label,url)`, `R(label,ref)`), followed by ` – description` when there is
 *   one, so [de.terletzkiy.ansibility.semantics.markup.AnsibleDocMarkup] renders it like any description;
 * - [KeywordDoc.type] is derived from `isa` (`string` → str, `class` → dict, `percent` → null).
 */
class DocSnapshot private constructor(
    /** The ansible-core version the snapshot was generated from, e.g. `2.18.8`. */
    val core: String,
    /** Human-readable origin, e.g. "ansible-core 2.18.8 + pinned collections (bundled)". */
    val label: String,
    /** Collection name → version, `ansible.builtin` included. */
    val collections: Map<String, String>,
    /** Canonical module FQCN → documentation. Aliases and redirects resolve through [module]. */
    val modules: Map<String, ModuleDoc>,
    val keywords: Map<String, KeywordDoc>,
    val filters: Map<String, PluginDoc>,
    val tests: Map<String, PluginDoc>,
    val lookups: Map<String, PluginDoc>,
    val routing: Routing,
) {
    /** [core] as a comparable version. */
    val coreVersion: CoreVersion? get() = CoreVersion.parse(core)

    /** Resolves [name] (FQCN, short or `ansible.legacy.` name) through routing and returns the canonical docs. */
    fun module(name: String): ModuleLookup {
        val resolution = routing.resolve(name, PluginKind.MODULE)
        val doc = modules[resolution.canonical]?.let { if (it.fqcn == resolution.requested) it else it.copy(fqcn = resolution.requested) }
        return ModuleLookup(resolution, doc)
    }

    /** Filter, test or lookup docs through routing. */
    fun plugin(name: String, kind: PluginKind): PluginLookup {
        require(kind != PluginKind.MODULE) { "use module() for modules" }
        val resolution = routing.resolve(name, kind)
        val docs = when (kind) {
            PluginKind.FILTER -> filters
            PluginKind.TEST -> tests
            else -> lookups
        }
        return PluginLookup(resolution, docs[resolution.canonical])
    }

    /** A playbook keyword; any `with_<lookup>` loop resolves to the `with_<lookup>` entry. */
    fun keyword(name: String): KeywordDoc? =
        keywords[name] ?: if (name.startsWith("with_") && name.length > 5) keywords[WITH_LOOKUP] else null

    companion object {
        /** The snapshot format this loader reads. */
        const val FORMAT: Int = 1

        /** The hand-made keyword entry standing for every `with_<lookup>` loop. */
        const val WITH_LOOKUP: String = "with_<lookup>"

        /** Loads a snapshot from a stream; gzip is detected from the magic bytes. The stream is closed. */
        fun load(input: InputStream, source: String? = null): DocSnapshot {
            val buffered = BufferedInputStream(input)
            buffered.mark(2)
            val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val stream = if (gzipped) GZIPInputStream(buffered, 64 * 1024) else buffered
            return InputStreamReader(stream, Charsets.UTF_8).use { load(it, source) }
        }

        /** Loads a snapshot from JSON text. */
        fun load(reader: Reader, source: String? = null): DocSnapshot = fromJson(Json.parseObject(reader), source)

        /**
         * Builds a snapshot from its parsed JSON. [source] labels every doc ([ModuleDoc.source]); it defaults to
         * "bundled <core>".
         *
         * @throws IllegalArgumentException when the snapshot's format is newer than [FORMAT].
         */
        fun fromJson(root: Map<String, Any?>, source: String? = null): DocSnapshot {
            val format = (root["format"] as? Number)?.toInt() ?: 0
            require(format <= FORMAT) { "Unsupported doc snapshot format $format (this build reads $FORMAT)" }
            val core = root["core"] as? String ?: "unknown"
            val label = source ?: "bundled $core"
            val loader = Loader(label)
            return DocSnapshot(
                core = core,
                label = root["label"] as? String ?: "ansible-core $core",
                collections = root.obj("collections").mapValues { it.value.toString() },
                modules = root.obj("modules").mapValues { (fqcn, doc) -> loader.module(fqcn, doc.asObj()) },
                keywords = root.obj("keywords").mapValues { (name, doc) -> loader.keyword(name, doc.asObj()) },
                filters = root.obj("filters").mapValues { (fqcn, doc) -> loader.plugin(fqcn, PluginKind.FILTER, doc.asObj()) },
                tests = root.obj("tests").mapValues { (fqcn, doc) -> loader.plugin(fqcn, PluginKind.TEST, doc.asObj()) },
                lookups = root.obj("lookups").mapValues { (fqcn, doc) -> loader.plugin(fqcn, PluginKind.LOOKUP, doc.asObj()) },
                routing = loader.routing(root.obj("routing")),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ JSON → model

@Suppress("UNCHECKED_CAST")
private fun Any?.asObj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key].asObj()

private fun Map<String, Any?>.str(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v
    else -> v.toString()
}

private fun Map<String, Any?>.strings(key: String): List<String> = when (val v = this[key]) {
    null -> emptyList()
    is List<*> -> v.mapNotNull { it?.toString() }
    else -> listOf(v.toString())
}

private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

private class Loader(private val source: String) {
    fun module(fqcn: String, doc: Map<String, Any?>): ModuleDoc {
        val origin = SpecOrigin.ModuleDoc(fqcn, source)
        return ModuleDoc(
            fqcn = fqcn,
            canonicalFqcn = fqcn,
            shortDescription = doc.str("short_description"),
            description = doc.strings("description"),
            notes = doc.strings("notes"),
            options = options(doc.obj("options"), origin),
            returns = returns(doc.obj("returns")),
            attributes = doc.obj("attributes").mapValues { (_, a) ->
                val attribute = a.asObj()
                attribute.str("platforms") ?: attribute.str("support").orEmpty()
            },
            requirements = doc.strings("requirements"),
            deprecated = deprecation(doc["deprecated"]),
            seeAlso = (doc["seealso"] as? List<*>).orEmpty().mapNotNull { seeAlso(it.asObj()) },
            examples = doc.str("examples"),
            freeForm = doc.str("free_form"),
            acceptsArbitraryKeys = doc.flag("accepts_arbitrary_keys"),
            filename = doc.str("filename"),
            versionAdded = doc.str("version_added"),
            source = source,
        )
    }

    fun plugin(fqcn: String, kind: PluginKind, doc: Map<String, Any?>): PluginDoc = PluginDoc(
        fqcn = fqcn,
        kind = kind,
        shortDescription = doc.str("short_description"),
        description = doc.strings("description"),
        notes = doc.strings("notes"),
        options = options(doc.obj("options"), SpecOrigin.ModuleDoc(fqcn, source)),
        positional = doc.strings("positional"),
        returns = returns(doc.obj("returns")),
        versionAdded = doc.str("version_added"),
        deprecated = deprecation(doc["deprecated"]),
        filename = doc.str("filename"),
        jinjaBuiltin = doc.flag("jinja_builtin"),
        source = source,
    )

    fun keyword(name: String, doc: Map<String, Any?>): KeywordDoc {
        val isa = doc.str("isa")
        return KeywordDoc(
            name = name,
            appliesTo = doc.strings("applies_to").toCollection(LinkedHashSet()),
            type = isa?.let(::keywordType),
            isa = isa,
            listOf = if (doc.containsKey("listof")) doc.strings("listof") else null,
            template = when (doc.str("template")) {
                "implicit" -> TemplateMode.IMPLICIT
                "static" -> TemplateMode.STATIC
                else -> TemplateMode.EXPLICIT
            },
            description = doc.strings("description"),
            default = when (val d = doc["default"]) {
                null -> null
                is String -> d
                else -> Json.write(d)
            },
        )
    }

    fun routing(routing: Map<String, Any?>): Routing {
        val sections = LinkedHashMap<String, Map<String, RouteEntry>>()
        for ((section, entries) in routing) {
            if (section == "aliases") continue
            sections[section] = entries.asObj().mapValues { (_, e) ->
                val entry = e.asObj()
                RouteEntry(entry.str("redirect"), notice(entry["deprecation"]), notice(entry["tombstone"]))
            }
        }
        val aliases = routing.obj("aliases").obj("modules").mapValues { it.value.toString() }
        return Routing(sections, aliases)
    }

    private fun options(options: Map<String, Any?>, origin: SpecOrigin): Map<String, OptionSpec> =
        options.mapValues { (name, spec) -> option(name, spec.asObj(), origin) }

    private fun option(name: String, spec: Map<String, Any?>, origin: SpecOrigin): OptionSpec = OptionSpec(
        name = name,
        type = OptionType.parse(spec.str("type")),
        elements = spec.str("elements")?.let(OptionType::parse),
        required = spec.flag("required"),
        default = if (spec.containsKey("default")) YValueJson.fromJson(spec["default"]) else null,
        choices = when (val c = spec["choices"]) {
            is List<*> -> Choices.Values(c.map(YValueJson::fromJson))
            is Map<*, *> -> Choices.Described(c.map { (value, text) -> YValueJson.fromJson(value.toString()) to paragraphs(text) })
            else -> null
        },
        aliases = spec.strings("aliases"),
        description = spec.strings("description"),
        options = if (spec.containsKey("options")) options(spec.obj("options"), origin) else null,
        versionAdded = spec.str("version_added"),
        deprecated = deprecation(spec["deprecated"]),
        noLog = spec.flag("no_log"),
        origin = origin,
    )

    private fun returns(returns: Map<String, Any?>): Map<String, ReturnSpec> =
        returns.mapValues { (name, spec) -> returnSpec(name, spec.asObj()) }

    private fun returnSpec(name: String, spec: Map<String, Any?>): ReturnSpec = ReturnSpec(
        name = name,
        type = returnType(spec.str("type")),
        elements = spec.str("elements")?.let(::returnType),
        description = spec.strings("description"),
        returned = spec.str("returned"),
        sample = when (val s = spec["sample"]) {
            null -> null
            is String -> s
            else -> Json.write(s)
        },
        contains = if (spec.containsKey("contains")) returns(spec.obj("contains")) else null,
    )

    private fun returnType(text: String?): OptionType = if (text == "complex") OptionType.Dict else OptionType.parse(text)

    private fun keywordType(isa: String): OptionType? = when (isa) {
        "string" -> OptionType.Str
        "class" -> OptionType.Dict
        "percent" -> null
        else -> OptionType.parse(isa).takeUnless { it is OptionType.Invalid }
    }

    private fun deprecation(value: Any?): Deprecation? {
        val d = value as? Map<*, *> ?: return null
        fun text(key: String) = d[key]?.toString()
        return Deprecation(
            why = text("why"),
            alternative = text("alternative"),
            removedIn = text("removed_in") ?: text("removed_at_date"),
            removedFromCollection = text("removed_from_collection"),
        )
    }

    private fun notice(value: Any?): RouteNotice? {
        val n = value as? Map<*, *> ?: return null
        return RouteNotice(n["warning_text"]?.toString(), n["removal_version"]?.toString(), n["removal_date"]?.toString())
    }

    private fun paragraphs(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapNotNull { it?.toString() }
        else -> listOf(value.toString())
    }

    /** One see-also entry as an Ansible-markup string (see [DocSnapshot]). */
    private fun seeAlso(entry: Map<String, Any?>): String? {
        val label = when {
            entry["module"] != null -> "M(${entry["module"]})"
            entry["plugin"] != null -> "P(${entry["plugin"]}#${entry["plugin_type"] ?: "module"})"
            entry["link"] != null -> "L(${macroText(entry.str("name") ?: entry.str("link")!!)},${entry["link"]})"
            entry["ref"] != null -> "R(${macroText(entry.str("name") ?: entry.str("ref")!!)},${entry["ref"]})"
            else -> return null
        }
        val description = entry.strings("description").joinToString(" ")
        return if (description.isEmpty()) label else "$label – $description"
    }

    /** Text that is safe inside an unescaped two-argument macro (no `,` or `)`). */
    private fun macroText(text: String): String = text.replace(',', ' ').replace(')', ']').replace('(', '[')
}
