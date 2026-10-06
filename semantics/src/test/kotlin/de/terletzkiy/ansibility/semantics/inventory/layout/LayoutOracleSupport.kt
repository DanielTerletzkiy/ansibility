package de.terletzkiy.ansibility.semantics.inventory.layout

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.inventory.InventoryDirectoryWalk
import de.terletzkiy.ansibility.semantics.inventory.InventoryFileSystem
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.InventoryParse
import de.terletzkiy.ansibility.semantics.inventory.InventoryParseOptions
import de.terletzkiy.ansibility.semantics.inventory.InventorySources
import de.terletzkiy.ansibility.semantics.inventory.SourceSpec
import de.terletzkiy.ansibility.semantics.inventory.YamlLoad
import de.terletzkiy.ansibility.semantics.precedence.HashBehaviour
import de.terletzkiy.ansibility.semantics.precedence.InventoryVarSources
import de.terletzkiy.ansibility.semantics.precedence.InventoryView
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEngine
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEntry
import de.terletzkiy.ansibility.semantics.precedence.VarSource
import de.terletzkiy.ansibility.semantics.precedence.oracle.FileLister
import de.terletzkiy.ansibility.semantics.precedence.oracle.MiniJson
import de.terletzkiy.ansibility.semantics.precedence.oracle.OracleCompare
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import java.io.File

/** An [InventoryFileSystem] over the local file system (test only). */
class LocalInventoryFiles : InventoryFileSystem<File> {
    override fun name(file: File): String = file.name

    override fun isDirectory(file: File): Boolean = file.isDirectory

    override fun children(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()

    override fun exists(file: File): Boolean = file.exists() && file.canRead()

    override fun text(file: File): String? = if (file.isFile) file.readText() else null

    override fun yaml(file: File): YamlLoad = try {
        YamlLoad.Loaded(YamlText.parse(file.readText()))
    } catch (e: Exception) {
        YamlLoad.Failed(e.message ?: e.javaClass.simpleName)
    }

    override fun isExecutable(file: File): Boolean = file.isFile && file.canExecute()
}

/**
 * The layout-oracle cases under `semantics/src/test/resources/layout-oracle/` (written by
 * `tools/docgen/layout-oracle/build_oracle.py`, expected records by `gen.py`). The source tree is read directly when
 * the tests run from the module directory, because Gradle's resource copy drops `~` backup files that the cases
 * contain on purpose.
 */
object LayoutOracleCases {
    val root: File by lazy {
        val source = File(System.getProperty("user.dir"), "src/test/resources/layout-oracle")
        if (source.isDirectory) source else File(requireNotNull(javaClass.getResource("/layout-oracle")) { "layout-oracle resources missing" }.toURI())
    }

    fun cases(): List<File> = root.listFiles().orEmpty().filter { File(it, "oracle.json").isFile }.sortedBy { it.name }

    /** The expected records of [case] per ansible-core version directory. */
    fun versions(case: File): List<File> = File(case, "expected").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }

    @Suppress("UNCHECKED_CAST")
    fun records(versionDir: File): List<Pair<String, Map<String, Any?>>> =
        versionDir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }
            .map { it.name.removeSuffix(".json") to MiniJson.parse(it.readText()) as Map<String, Any?> }
}

/**
 * The few `ansible.cfg` keys the oracle's `ansible-inventory` runs set, read as ansible-core reads them (test only:
 * the production reader of `[defaults] inventory` is R10-2's `CfgPathList`, checked against the `dump-*` records).
 */
class OracleCfg(private val file: File?) {
    private val values: Map<Pair<String, String>, String> = buildMap {
        var section = ""
        for (raw in file?.readLines().orEmpty()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1)
                continue
            }
            val sep = line.indexOfAny(charArrayOf('=', ':'))
            if (sep < 0) continue
            put(section to line.substring(0, sep).trim().lowercase(), line.substring(sep + 1).trim())
        }
    }

    private val dir: File? get() = file?.parentFile

    operator fun get(section: String, key: String): String? = values[section to key]

    /** `[defaults] inventory` as a pathlist: split on `,`, stripped, an empty entry is the cfg directory. */
    fun inventory(): List<File>? = get("defaults", "inventory")?.split(',')?.map { it.trim() }?.map { entry ->
        when {
            entry.isEmpty() -> dir!!
            entry.startsWith("/") -> File(entry)
            else -> File(dir, entry)
        }
    }

    fun list(section: String, key: String): List<String>? = get(section, key)?.split(',')?.map { it.trim() }

    /** `[inventory] ignore_extensions` wins over `[defaults] inventory_ignore_extensions`. */
    fun ignoreExtensions(): List<String>? = list("inventory", "ignore_extensions") ?: list("defaults", "inventory_ignore_extensions")

    fun varsPluginsEnabled(): Boolean = list("defaults", "vars_plugins_enabled")?.contains("host_group_vars") ?: true

    fun playbookDir(): File? = get("defaults", "playbook_dir")?.let { if (it.startsWith("/")) File(it) else File(dir, it) }
}

/**
 * One `ansible-inventory` record of the oracle, evaluated with the semantics API: the sources through
 * [InventorySources], the variables through [InventoryVarSources] and [PrecedenceEngine], the output compared as
 * ansible-inventory prints it.
 */
class OracleRun(private val case: File, private val record: Map<String, Any?>, neutralCwd: File) {
    private val project = File(case, "project")
    private val home = File(neutralCwd.parentFile, "home")
    private val cwd = if (record["cwd"] == "project") project else neutralCwd

    @Suppress("UNCHECKED_CAST")
    private val env: Map<String, String> = (record["env"] as Map<String, String>?).orEmpty().mapValues { subst(it.value) }

    @Suppress("UNCHECKED_CAST")
    private val args: List<String> = (record["args"] as List<String>).map(::subst)
    private val cfg = OracleCfg((record["cfg"] as String?)?.let { File(case, it) })
    val core: CoreVersion = requireNotNull(CoreVersion.parse(record["ansible_core"] as String))

    private fun subst(text: String) =
        text.replace("{project}", project.path).replace("{cwd}", cwd.path).replace("{home}", home.path)

    private fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args[it + 1] }

    private fun resolve(path: String, base: File) = if (path.startsWith("/")) File(path) else File(base, path)

    val isGraph: Boolean get() = "--graph" in args
    val isExport: Boolean get() = "--export" in args
    val host: String? get() = option("--host")

    /** `-i` (in order) > `ANSIBLE_INVENTORY` (cwd-relative pathlist) > `[defaults] inventory` > `/etc/ansible/hosts`. */
    val sources: List<SourceSpec<File>> = run {
        val cli = args.withIndex().filter { it.value == "-i" }.map { args[it.index + 1] }
        when {
            cli.isNotEmpty() -> cli.map { if (',' in it) SourceSpec.HostList(it) else SourceSpec.Path(resolve(it, cwd)) }
            env["ANSIBLE_INVENTORY"] != null -> env.getValue("ANSIBLE_INVENTORY").split(',').map { it.trim() }
                .map { SourceSpec.Path(if (it.isEmpty()) cwd else resolve(it, cwd)) }
            cfg.inventory() != null -> cfg.inventory()!!.map { SourceSpec.Path(it) }
            // The default /etc/ansible/hosts is absent in every generator run; never read the real one.
            else -> listOf(SourceSpec.Path(File(home, "etc-ansible-hosts-absent")))
        }
    }

    val options = InventoryParseOptions(core = core, walk = InventoryDirectoryWalk.Options(core, cfg.ignoreExtensions()))
    val parse: InventoryParse<File> = InventorySources.parseAll(sources, LocalInventoryFiles(), options)
    val graph: InventoryGraph get() = parse.graph

    /** `--playbook-dir` > `ANSIBLE_PLAYBOOK_DIR` (both cwd-relative) > `[defaults] playbook_dir` (cfg-relative). */
    private val basedir: File? = option("--playbook-dir")?.let { resolve(it, cwd) }
        ?: env["ANSIBLE_PLAYBOOK_DIR"]?.let { resolve(it, cwd) }
        ?: cfg.playbookDir()

    /** The play stage: the basedir; without `--export` the loader's basedir defaults to the cwd. */
    private val playDir: File? = basedir ?: if (isExport) null else cwd

    private val engine = PrecedenceEngine(
        env["ANSIBLE_HASH_BEHAVIOUR"]?.let { requireNotNull(HashBehaviour.parse(it)) } ?: HashBehaviour.REPLACE,
        env["ANSIBLE_PRECEDENCE"]?.let { PrecedenceEntry.parse(it).entries } ?: PrecedenceEntry.DEFAULT,
    )

    private val varSources: List<VarSource> by lazy {
        buildList {
            addAll(InventoryVarSources.inline(graph) { index -> parse.files[index].file?.path ?: "host list" })
            if (!cfg.varsPluginsEnabled()) return@buildList
            sources.forEachIndexed { index, spec ->
                val path = (spec as? SourceSpec.Path)?.file ?: return@forEachIndexed
                // get_vars_from_inventory_sources: a directory source itself, else the file's directory (even missing).
                addAll(adjacent(if (path.isDirectory) path else path.parentFile, playbookAdjacent = false, index))
            }
            playDir?.let { addAll(adjacent(it, playbookAdjacent = true, 0)) }
        }
    }

    private fun adjacent(dir: File, playbookAdjacent: Boolean, sourceIndex: Int) = InventoryVarSources.adjacent(
        graph,
        FileLister(dir),
        playbookAdjacent,
        load = { YamlText.parse(File(dir, it).readText()) },
        originId = { File(dir, it).path },
        sourceIndex = sourceIndex,
    )

    fun view(host: String): InventoryView = requireNotNull(engine.inventoryView(graph, host, varSources)) { "no host $host" }

    @Suppress("UNCHECKED_CAST")
    fun mismatches(): List<String> {
        val stdout = record["stdout"]
        val out = ArrayList<String>()
        when {
            isGraph -> {
                val actual = graphText(graph)
                if (actual != stdout) out += "graph: expected\n$stdout\ngot\n$actual"
            }
            host != null -> diffVars(host!!, stdout as Map<String, Any?>, out)
            else -> {
                val json = stdout as Map<String, Any?>
                structure(json, out)
                if (!isExport) {
                    val hostvars = (json["_meta"] as Map<String, Any?>?)?.get("hostvars") as Map<String, Any?>? ?: emptyMap()
                    for (h in graph.hosts.keys) diffVars(h, hostvars[h] as Map<String, Any?>? ?: emptyMap(), out)
                }
            }
        }
        return out
    }

    private fun diffVars(host: String, expected: Map<String, Any?>, out: MutableList<String>) {
        if (graph.host(host) == null) {
            out += "host $host: missing"
            return
        }
        val actual = view(host).values()
        for (key in expected.keys - actual.keys) out += "$host.$key: missing (expected ${expected[key]})"
        for (key in actual.keys - expected.keys) out += "$host.$key: unexpected ${actual[key]}"
        for (key in expected.keys.intersect(actual.keys)) OracleCompare.diff("$host.$key", expected[key], actual.getValue(key), out)
    }

    /** Groups, their direct hosts and children in order, and every host's transitive membership (`--list`). */
    @Suppress("UNCHECKED_CAST")
    private fun structure(json: Map<String, Any?>, out: MutableList<String>) {
        val hostvars = (json["_meta"] as Map<String, Any?>?)?.get("hostvars") as Map<String, Any?>? ?: emptyMap()
        val groupsJson = json.filterKeys { it != "_meta" }.mapValues { it.value as Map<String, Any?> }
        val expectedGroups = LinkedHashSet(groupsJson.keys)
        groupsJson.values.forEach { g -> (g["children"] as List<String>?)?.let { expectedGroups += it } }
        if (expectedGroups != graph.groups.keys) out += "groups: expected ${expectedGroups.sorted()} got ${graph.groups.keys.sorted()}"
        for (name in expectedGroups) {
            val group = graph.group(name) ?: continue
            val g = groupsJson[name].orEmpty()
            val children = g["children"] as List<String>? ?: emptyList()
            if (children != group.children) out += "group $name children: expected $children got ${group.children}"
            if (name != InventoryGraph.ALL) {
                val hosts = g["hosts"] as List<String>? ?: emptyList()
                if (hosts != group.hosts) out += "group $name hosts: expected $hosts got ${group.hosts}"
            }
        }
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
        }
    }

    companion object {
        /** `ansible-inventory --graph` (`InventoryCLI._graph_group`): children first, then the group's own hosts. */
        fun graphText(graph: InventoryGraph): String {
            val lines = ArrayList<String>()
            fun name(text: String, depth: Int) = if (depth > 0) "  |".repeat(depth) + "--" + text else text
            fun visit(group: String, depth: Int) {
                lines += name("@$group:", depth)
                val g = graph.group(group) ?: return
                for (child in g.children) visit(child, depth + 1)
                if (group != InventoryGraph.ALL) for (h in g.hosts) lines += name(h, depth + 1)
            }
            visit(InventoryGraph.ALL, 0)
            return lines.joinToString("\n") + "\n"
        }
    }
}
