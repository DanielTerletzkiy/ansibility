package de.terletzkiy.ansibility.semantics.precedence.oracle

import de.terletzkiy.ansibility.semantics.inventory.DirEntry
import de.terletzkiy.ansibility.semantics.inventory.DirectoryLister
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.Py
import de.terletzkiy.ansibility.semantics.inventory.YamlInventoryParser
import de.terletzkiy.ansibility.semantics.precedence.InventoryVarSources
import de.terletzkiy.ansibility.semantics.precedence.InventoryView
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEngine
import de.terletzkiy.ansibility.semantics.precedence.VarSource
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import java.io.File
import java.math.BigInteger

/** A [DirectoryLister] over the local file system. */
class FileLister(private val root: File) : DirectoryLister {
    override fun list(relativePath: String): List<DirEntry>? {
        val dir = if (relativePath.isEmpty()) root else File(root, relativePath)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.map { DirEntry(it.name, it.isDirectory) }
    }
}

/**
 * What `ansible-inventory -i <inventoryFile> --playbook-dir <playbookDir> --list` evaluates, built with the
 * semantics API only: the parsed inventory, its inline vars and both `group_vars`/`host_vars` trees.
 */
class InventoryFixture(val inventoryFile: File, val playbookDir: File?, val engine: PrecedenceEngine = PrecedenceEngine()) {
    val graph: InventoryGraph = YamlInventoryParser.parse(YamlText.parse(inventoryFile.readText()))

    val sources: List<VarSource> = buildList {
        addAll(InventoryVarSources.inline(graph) { inventoryFile.path })
        addAll(adjacent(inventoryFile.parentFile, playbookAdjacent = false))
        if (playbookDir != null) addAll(adjacent(playbookDir, playbookAdjacent = true))
    }

    fun view(host: String): InventoryView = requireNotNull(engine.inventoryView(graph, host, sources)) { "no host $host" }

    private fun adjacent(dir: File, playbookAdjacent: Boolean) = InventoryVarSources.adjacent(
        graph,
        FileLister(dir),
        playbookAdjacent,
        load = { YamlText.parse(File(dir, it).readText()) },
        originId = { File(dir, it).path },
    )
}

/** Compares the engine's results with `ansible-inventory --list` output. */
object OracleCompare {
    /** Every mismatch between [fixture] and the parsed `--list` JSON [expected], as readable lines. */
    @Suppress("UNCHECKED_CAST")
    fun mismatches(fixture: InventoryFixture, expected: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        val graph = fixture.graph
        val hostvars = (expected["_meta"] as Map<String, Any?>?)?.get("hostvars") as Map<String, Any?>? ?: emptyMap()
        val groupsJson = expected.filterKeys { it != "_meta" }.mapValues { it.value as Map<String, Any?> }

        // Groups: every group appears as a key or as somebody's child; direct hosts and children in order.
        val expectedGroups = LinkedHashSet<String>(groupsJson.keys)
        groupsJson.values.forEach { g -> (g["children"] as List<String>?)?.let { expectedGroups += it } }
        if (expectedGroups != graph.groups.keys) out += "groups: expected ${expectedGroups.sorted()} got ${graph.groups.keys.sorted()}"
        for (name in expectedGroups) {
            val group = graph.group(name) ?: continue
            val json = groupsJson[name].orEmpty()
            val children = json["children"] as List<String>? ?: emptyList()
            if (children != group.children) out += "group $name children: expected $children got ${group.children}"
            if (name != InventoryGraph.ALL) {
                val hosts = json["hosts"] as List<String>? ?: emptyList()
                if (hosts != group.hosts) out += "group $name hosts: expected $hosts got ${group.hosts}"
            }
        }

        // Hosts and their transitive membership.
        val direct = HashMap<String, MutableSet<String>>()
        for ((name, g) in groupsJson) for (h in g["hosts"] as List<String>? ?: emptyList()) direct.getOrPut(h) { HashSet() } += name
        val parents = HashMap<String, MutableSet<String>>()
        for ((name, g) in groupsJson) for (c in g["children"] as List<String>? ?: emptyList()) parents.getOrPut(c) { HashSet() } += name
        fun closure(start: Set<String>): Set<String> {
            val seen = HashSet(start)
            var frontier = start.toList()
            while (frontier.isNotEmpty()) frontier = frontier.flatMap { parents[it].orEmpty() }.filter { seen.add(it) }
            return seen + InventoryGraph.ALL
        }
        val expectedHosts = direct.keys + hostvars.keys
        if (expectedHosts != graph.hosts.keys) out += "hosts: expected ${expectedHosts.sorted()} got ${graph.hosts.keys.sorted()}"
        for (host in expectedHosts) {
            val node = graph.host(host) ?: continue
            val groups = closure(direct[host].orEmpty())
            if (groups != node.groups.toSet()) out += "host $host groups: expected ${groups.sorted()} got ${node.groups.sorted()}"
            val vars = hostvars[host] as Map<String, Any?>? ?: emptyMap()
            diffMap(host, vars, fixture.view(host).values(), out)
        }
        return out
    }

    private fun diffMap(path: String, expected: Map<String, Any?>, actual: Map<String, YValue>, out: MutableList<String>) {
        for (key in expected.keys - actual.keys) out += "$path.$key: missing (expected ${expected[key]})"
        for (key in actual.keys - expected.keys) out += "$path.$key: unexpected ${actual[key]}"
        for (key in expected.keys.intersect(actual.keys)) diff("$path.$key", expected[key], actual.getValue(key), out)
    }

    @Suppress("UNCHECKED_CAST")
    fun diff(path: String, expected: Any?, actual: YValue, out: MutableList<String>) {
        fun mismatch() {
            out += "$path: expected $expected got $actual"
        }
        when (expected) {
            is Map<*, *> -> {
                val map = expected as Map<String, Any?>
                when {
                    map.keys == setOf("__ansible_vault") -> if (actual !is YVault) mismatch()
                    map.keys == setOf("__ansible_unsafe") ->
                        if (!(actual is YScalar && actual.tag == "!unsafe" && actual.text == map["__ansible_unsafe"])) mismatch()
                    actual is YMap -> diffMap(path, map, Py.dict(actual).entries.associate { (_, e) -> jsonKey(e.key) to e.value }, out)
                    else -> mismatch()
                }
            }
            is List<*> -> {
                if (actual !is YSeq || actual.items.size != expected.size) return mismatch()
                expected.forEachIndexed { i, item -> diff("$path[$i]", item, actual.items[i], out) }
            }
            null -> if (!Py.isNone(actual)) mismatch()
            else -> {
                val resolved = (actual as? YScalar)?.resolved ?: return mismatch()
                val same = when (expected) {
                    is String -> resolved == Resolved.Str(expected) ||
                        (resolved is Resolved.Timestamp && isoFormat(resolved.text) == expected)
                    is BigInteger -> resolved == Resolved.Int(expected)
                    is Double -> resolved is Resolved.Float && resolved.value.equals(expected)
                    is Boolean -> resolved == Resolved.Bool(expected)
                    else -> false
                }
                if (!same) mismatch()
            }
        }
    }

    /** The JSON object key Python's `json.dumps` writes for a YAML mapping key. */
    private fun jsonKey(key: YScalar): String = when (val r = key.resolved) {
        is Resolved.Str -> r.value
        is Resolved.Int -> r.value.toString()
        is Resolved.Bool -> r.value.toString()
        Resolved.Null -> "null"
        is Resolved.Float -> r.value.toString()
        else -> key.text
    }

    /** `date.isoformat()` / `datetime.isoformat()` for the timestamp spellings used in fixtures. */
    private fun isoFormat(text: String): String =
        if (text.length == 10) text else text.replaceFirst(Regex("[Tt ]+"), "T")
}
