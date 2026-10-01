package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot

/**
 * Converts `ansible-doc -t module -j` output into the module format of the bundled snapshots, so the same
 * [DocSnapshot] loader reads both. A line-by-line port of the module normalisation in
 * `tools/docgen/snapshot/generate.py` (`norm_plugin_doc` and its helpers): `suboptions` → `options`, dict
 * `choices` → value → paragraphs, string descriptions → lists, returns with `contains`, attributes, see-also
 * entries, deprecations, examples, and file names relative to site-packages or `ansible_collections`.
 */
object AnsibleDocNormalizer {
    /** Largest JSON-serialised return `sample` kept (as in the generator). */
    const val MAX_SAMPLE_CHARS: Int = 1500

    /** Modules whose extra keys are data rather than options. */
    private val ARBITRARY_KEY_MODULES = setOf("ansible.builtin.set_fact", "ansible.builtin.add_host")

    private const val COLLECTIONS_MARKER = "/ansible_collections/"

    /**
     * Normalised modules keyed by canonical FQCN, plus documentation aliases: a module whose `doc.module` names
     * another module (`ansible.builtin.systemd` documents `systemd_service`) is stored under that name, and the
     * requested name maps to it.
     */
    data class Modules(val modules: Map<String, Map<String, Any?>>, val aliases: Map<String, String>)

    /**
     * Normalises the parsed output of `ansible-doc -t module -j <fqcn…>` (`{fqcn: {doc, examples, metadata, return}}`).
     * [sitePackages] is the directory holding the `ansible` package; file names below it become relative.
     */
    fun modules(raw: Map<String, Any?>, sitePackages: String?): Modules {
        val modules = LinkedHashMap<String, Map<String, Any?>>()
        val aliases = LinkedHashMap<String, String>()
        for ((fqcn, value) in raw) {
            val entry = value.asObj()
            val doc = entry["doc"].asObj()
            if (doc.isEmpty()) continue
            val canonical = canonicalName(fqcn, doc)
            if (canonical != fqcn) {
                aliases[fqcn] = canonical
                if (canonical in raw || canonical in modules) continue
            }
            modules[canonical] = module(canonical, entry, sitePackages)
        }
        return Modules(modules, aliases)
    }

    /** The module a doc describes: `doc.collection` + `doc.module`, or [fqcn] itself. */
    private fun canonicalName(fqcn: String, doc: Map<String, Any?>): String {
        val short = fqcn.substringAfterLast('.')
        val docName = doc["module"] as? String
        val collection = (doc["collection"] as? String)?.takeIf { it.isNotEmpty() } ?: fqcn.substringBeforeLast('.', "")
        if (docName.isNullOrEmpty() || collection.isEmpty()) return fqcn
        val candidate = "$collection.$docName"
        return if (docName != short || candidate != fqcn) candidate else fqcn
    }

    /** `norm_plugin_doc(fqcn, entry, paths, "module")`. */
    fun module(fqcn: String, entry: Map<String, Any?>, sitePackages: String?): Map<String, Any?> {
        val doc = entry["doc"].asObj()
        val out = LinkedHashMap<String, Any?>()
        if (truthy(doc["short_description"])) out["short_description"] = pyStr(doc["short_description"]).trim()
        for (key in listOf("description", "notes", "requirements")) {
            val value = paragraphs(doc[key])
            if (value.isNotEmpty()) out[key] = value
        }
        if (notEmpty(doc["version_added"])) out["version_added"] = pyStr(doc["version_added"])
        deprecation(doc["deprecated"])?.let { out["deprecated"] = it }
        out["options"] = options(doc["options"])
        relativeFilename(doc["filename"], sitePackages)?.let { out["filename"] = it }
        attributes(doc["attributes"]).takeIf { it.isNotEmpty() }?.let { out["attributes"] = it }
        seeAlso(doc["seealso"]).takeIf { it.isNotEmpty() }?.let { out["seealso"] = it }
        (entry["examples"] as? String)?.takeIf { it.isNotBlank() }?.let { out["examples"] = it.trim('\n').trimEnd() }
        returns(entry["return"]).takeIf { it.isNotEmpty() }?.let { out["returns"] = it }
        if (truthy(doc["has_action"])) out["has_action"] = true
        if ((out["options"] as Map<*, *>).containsKey("free_form")) out["free_form"] = "free_form"
        if (fqcn in ARBITRARY_KEY_MODULES) out["accepts_arbitrary_keys"] = true
        return out
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** `paragraphs(value)`: a string becomes one paragraph, a list one per item; trailing newlines are dropped. */
    fun paragraphs(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value.trimEnd('\n'))
        is List<*> -> value.filterNotNull().map { pyStr(it).trimEnd('\n') }
        else -> listOf(pyStr(value))
    }

    private fun deprecation(value: Any?): Map<String, String>? {
        val d = value as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, String>()
        for (key in listOf("why", "alternative", "removed_in", "removed_at_date", "removed_from_collection")) {
            if (notEmpty(d[key])) out[key] = pyStr(d[key])
        }
        return out.ifEmpty { null }
    }

    private fun options(options: Any?): Map<String, Any?> {
        val map = options as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((name, spec) in map) {
            if (spec is Map<*, *>) out[pyStr(name)] = option(spec.asObj())
        }
        return out
    }

    private fun option(spec: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        spec["type"]?.let { out["type"] = pyStr(it) }
        spec["elements"]?.let { out["elements"] = pyStr(it) }
        if (truthy(spec["required"])) out["required"] = true
        spec["default"]?.let { out["default"] = it }
        when (val choices = spec["choices"]) {
            is Map<*, *> -> out["choices"] = choices.entries.associate { (k, v) -> pyStr(k) to paragraphs(v) }
            is List<*> -> out["choices"] = choices.toList()
        }
        (spec["aliases"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { aliases -> out["aliases"] = aliases.map(::pyStr) }
        paragraphs(spec["description"]).takeIf { it.isNotEmpty() }?.let { out["description"] = it }
        val nested = if (spec.containsKey("suboptions")) spec["suboptions"] else spec["options"]
        if (nested is Map<*, *>) out["options"] = options(nested)
        if (notEmpty(spec["version_added"])) out["version_added"] = pyStr(spec["version_added"])
        deprecation(spec["deprecated"])?.let { out["deprecated"] = it }
        if (truthy(spec["no_log"])) out["no_log"] = true
        return out
    }

    private fun returns(returns: Any?): Map<String, Any?> {
        val map = returns as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((name, value) in map) {
            val spec = value as? Map<*, *> ?: continue
            val item = LinkedHashMap<String, Any?>()
            paragraphs(spec["description"]).takeIf { it.isNotEmpty() }?.let { item["description"] = it }
            for (key in listOf("returned", "version_added")) {
                val v = spec[key]
                if (notEmpty(v)) item[key] = if (v is List<*>) v.joinToString(", ") { pyStr(it) } else pyStr(v)
            }
            spec["type"]?.let { item["type"] = pyStr(it) }
            spec["elements"]?.let { item["elements"] = pyStr(it) }
            val sample = spec["sample"]
            if (sample != null && pythonJsonLength(sample) <= MAX_SAMPLE_CHARS) item["sample"] = sample
            (spec["contains"] as? Map<*, *>)?.let { item["contains"] = returns(it) }
            out[pyStr(name)] = item
        }
        return out
    }

    private fun attributes(attributes: Any?): Map<String, Any?> {
        val map = attributes as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((name, value) in map) {
            val spec = value as? Map<*, *> ?: continue
            val item = LinkedHashMap<String, Any?>()
            item["support"] = pyStr(spec["support"] ?: "")
            val platforms = spec["platforms"]
            if (truthy(platforms)) item["platforms"] = if (platforms is List<*>) platforms.joinToString(", ") { pyStr(it) } else pyStr(platforms)
            val membership = spec["membership"]
            if (truthy(membership)) item["membership"] = if (membership is List<*>) membership.map(::pyStr) else listOf(pyStr(membership))
            paragraphs(spec["details"]).takeIf { it.isNotEmpty() }?.let { item["details"] = it }
            out[pyStr(name)] = item
        }
        return out
    }

    private fun seeAlso(entries: Any?): List<Map<String, Any?>> {
        val list = entries as? List<*> ?: return emptyList()
        return list.mapNotNull { value ->
            val entry = value as? Map<*, *> ?: return@mapNotNull null
            val item = LinkedHashMap<String, Any?>()
            for (key in listOf("module", "plugin", "plugin_type", "ref", "name", "link")) {
                if (truthy(entry[key])) item[key] = entry[key]
            }
            paragraphs(entry["description"]).takeIf { it.isNotEmpty() }?.let { item["description"] = it }
            item.ifEmpty { null }
        }
    }

    /** `Paths.rel`: relative to `ansible_collections/…` or to site-packages, else the base name. */
    fun relativeFilename(value: Any?, sitePackages: String?): String? {
        if (!truthy(value)) return null
        val path = pyStr(value)
        val marker = path.lastIndexOf(COLLECTIONS_MARKER)
        if (marker >= 0) return path.substring(marker + 1)
        val site = sitePackages?.trimEnd('/', '\\')
        if (!site.isNullOrEmpty() && (path.startsWith("$site/") || path.startsWith("$site\\"))) return path.substring(site.length + 1)
        return path.substringAfterLast('/').substringAfterLast('\\')
    }

    /**
     * The length of Python's `json.dumps(value, sort_keys=True)`: `", "` and `": "` separators, non-ASCII escaped
     * as `\uXXXX` (the generator's sample limit is measured that way).
     */
    fun pythonJsonLength(value: Any?): Int = when (value) {
        null -> 4
        true -> 4
        false -> 5
        is String -> 2 + value.sumOf(::escapedLength)
        is Number -> Json.write(value).length
        is Map<*, *> -> 2 + value.entries.sumOf { (k, v) -> pythonJsonLength(k.toString()) + 2 + pythonJsonLength(v) } + 2 * (value.size - 1).coerceAtLeast(0)
        is Collection<*> -> 2 + value.sumOf(::pythonJsonLength) + 2 * (value.size - 1).coerceAtLeast(0)
        else -> pythonJsonLength(value.toString())
    }

    private fun escapedLength(c: Char): Int = when {
        c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\u000C' -> 2
        c.code < 0x20 || c.code > 0x7e -> 6
        else -> 1
    }

    /** Python's `str()` for JSON values (`True`, not `true`). */
    private fun pyStr(value: Any?): String = when (value) {
        null -> "None"
        true -> "True"
        false -> "False"
        is String -> value
        else -> value.toString()
    }

    /** Python truthiness of a JSON value. */
    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    /** `value not in (None, "")`. */
    private fun notEmpty(value: Any?): Boolean = value != null && value != ""

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
}
