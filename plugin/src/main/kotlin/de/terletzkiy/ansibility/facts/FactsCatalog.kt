package de.terletzkiy.ansibility.facts

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import de.terletzkiy.ansibility.semantics.json.Json
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * One key of the facts schema (plan X10): a fact, a key inside a dict fact, or a key of the elements of a list of
 * dicts (`mounts[0].fstype`). Also used for the keys of dict-valued magic variables (`ansible_version.full`).
 */
class FactSpec(
    val name: String,
    /** `str`, `int`, `float`, `bool`, `list`, `dict` or `raw`. */
    val type: String,
    /** The element type of a `list`, when documented. */
    val elements: String?,
    /** Plain English text from the bundled data. */
    val description: String,
    /** Keys of a `dict`, or of the elements of a `list` of `dict`s; empty when free-form (`env`, `cmdline`). */
    val keys: Map<String, FactSpec>,
    /** The module that sets the fact when it is not `ansible.builtin.setup` (`ansible.builtin.package_facts`). */
    val setBy: String?,
) {
    /** `str`, `list[dict]` …, as completion shows types. */
    val typeText: String get() = if (type == "list" && elements != null) "list[$elements]" else type

    override fun toString(): String = "FactSpec($name: $typeText)"
}

/** A special variable of ansible-core (`hostvars`, `inventory_hostname`, `omit`, `ansible_managed` …). */
class MagicVar(
    val name: String,
    val type: String,
    val elements: String?,
    val description: String,
    /** Only defined while a template is rendered (`ansible_managed`, `template_path` …). */
    val templateOnly: Boolean,
    /** A connection (behavioural inventory) variable such as `ansible_host`, usually set in the inventory. */
    val connection: Boolean,
    /** The replacement advice when the variable is deprecated (`play_hosts`), else null. */
    val deprecated: String?,
    /** Documented keys of a dict value (`ansible_version`). */
    val keys: Map<String, FactSpec>,
) {
    val typeText: String get() = if (type == "list" && elements != null) "list[$elements]" else type

    override fun toString(): String = "MagicVar($name: $typeText)"
}

/**
 * The bundled facts schema and special-variable table (plan A8, X10), read once from
 * `/ansible-data/facts/facts.json` and `/ansible-data/facts/magic.json`. The data is immutable, so the catalog is a
 * plain lazily initialised object; a broken resource is logged and gives empty tables rather than failing
 * completion.
 *
 * - [facts]: the common Linux facts of `ansible.builtin.setup`, plus the facts `package_facts`, `service_facts` and
 *   `getent` put under `ansible_facts`;
 * - [injected]: the legacy `ansible_<fact>` names (`INJECT_FACTS_AS_VARS`, on by default) of the setup facts;
 * - [magicVars]: special, template-only and connection variables.
 */
object FactsCatalog {
    const val FACTS_RESOURCE: String = "/ansible-data/facts/facts.json"
    const val MAGIC_RESOURCE: String = "/ansible-data/facts/magic.json"

    /** The prefix of the injected fact names. */
    const val INJECTED_PREFIX: String = "ansible_"

    private val LOG = logger<FactsCatalog>()

    /** Top-level facts by name (`distribution_release`, `default_ipv4` …), sorted by name. */
    val facts: Map<String, FactSpec> by lazy { load(FACTS_RESOURCE, "facts") { name, value -> factOf(name, value) } }

    /** Special variables by name, sorted by name. */
    val magicVars: Map<String, MagicVar> by lazy { load(MAGIC_RESOURCE, "vars") { name, value -> magicOf(name, value) } }

    /**
     * The injected names of the setup facts (`ansible_distribution_release` → `distribution_release`). Facts that
     * other modules set are only reachable through `ansible_facts`, as the module docs describe them.
     */
    val injected: Map<String, FactSpec> by lazy {
        facts.values.filter { it.setBy == null }.associateByTo(sortedMapOf()) { INJECTED_PREFIX + it.name }
    }

    /**
     * The fact at [path] below `ansible_facts` (`["default_ipv4", "address"]`); an integer segment below a list
     * addresses an element and is skipped. Null when the schema does not document it.
     */
    fun fact(path: List<String>): FactSpec? {
        if (path.isEmpty()) return null
        var current = facts[path.first()] ?: return null
        for (segment in path.drop(1)) {
            if (current.type == "list" && segment.toIntOrNull() != null) continue
            current = current.keys[segment] ?: return null
        }
        return current
    }

    private fun <T> load(resource: String, member: String, convert: (String, Map<String, Any?>) -> T): Map<String, T> = try {
        val stream = FactsCatalog::class.java.getResourceAsStream(resource) ?: error("missing resource $resource")
        val document = InputStreamReader(stream, StandardCharsets.UTF_8).use(Json::parseObject)
        @Suppress("UNCHECKED_CAST")
        val entries = document[member] as? Map<String, Any?> ?: error("$resource has no '$member' object")
        entries.entries.mapNotNull { (name, value) -> (value as? Map<*, *>)?.let { name to convert(name, it.stringKeys()) } }
            .toMap(sortedMapOf())
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.error("Cannot read the bundled facts data $resource; fact and special-variable completion stays empty", e)
        emptyMap()
    }

    private fun factOf(name: String, value: Map<String, Any?>): FactSpec = FactSpec(
        name = name,
        type = value["type"] as? String ?: "raw",
        elements = value["elements"] as? String,
        description = value["description"] as? String ?: "",
        keys = keysOf(value),
        setBy = value["setBy"] as? String,
    )

    private fun magicOf(name: String, value: Map<String, Any?>): MagicVar = MagicVar(
        name = name,
        type = value["type"] as? String ?: "raw",
        elements = value["elements"] as? String,
        description = value["description"] as? String ?: "",
        templateOnly = value["templateOnly"] == true,
        connection = value["connection"] == true,
        deprecated = value["deprecated"] as? String,
        keys = keysOf(value),
    )

    private fun keysOf(value: Map<String, Any?>): Map<String, FactSpec> {
        val keys = value["keys"] as? Map<*, *> ?: return emptyMap()
        return keys.entries.mapNotNull { (key, sub) ->
            val name = key as? String ?: return@mapNotNull null
            (sub as? Map<*, *>)?.let { name to factOf(name, it.stringKeys()) }
        }.toMap(sortedMapOf())
    }

    private fun Map<*, *>.stringKeys(): Map<String, Any?> = entries.associate { (k, v) -> k.toString() to v }
}
