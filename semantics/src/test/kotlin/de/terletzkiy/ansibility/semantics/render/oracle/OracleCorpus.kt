package de.terletzkiy.ansibility.semantics.render.oracle

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * The render oracle: the golden cases under `render-oracle/` (test resources), produced by running real ansible-core
 * with `tools/docgen/render-oracle/` and never edited by hand.
 *
 * Every case directory holds `input/` (the playbook, inventory, templates and vars that ran), `case.json` (title,
 * pins, kind and the measured versions) and its expected outputs. A version is resolved per check, see
 * [OracleCase.checks]:
 * - an output file is `expected/<label>/<path>`, or else `expected/common/<path>` unless `summary.json` lists it as
 *   missing for that version (only extra versions can miss common files);
 * - a `tasks.json` or `expressions.json` entry is its `<label>` key, or else its `both` key; a `<label>` key that is
 *   null means the version did not run that task; a task name is `task_<label>`, or else `task`.
 *
 * The primary versions (2.21.4 and 2.18.8) define `common` and `both`; the extra versions of spike S-R1 (2.19.x,
 * 2.20.x) are overlays that only record where they differ.
 */
class OracleCorpus private constructor(val root: File, val cases: List<OracleCase>) {

    /** The case named [id]; fails with the list of known cases otherwise. */
    fun case(id: String): OracleCase =
        cases.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no oracle case $id; known: ${cases.map { it.id }}")

    companion object {
        /** Resource directory of the oracle. */
        const val RESOURCE = "/render-oracle"

        /** Loads every case directory (a directory with a `case.json`) under [root], sorted by name. */
        fun load(root: File = resourceRoot()): OracleCorpus {
            val dirs = root.listFiles().orEmpty().filter { File(it, "case.json").isFile }.sortedBy { it.name }
            return OracleCorpus(root, dirs.map { OracleCase.load(it) })
        }

        /** The oracle's directory on the test classpath; skips the calling test when the (git-ignored) corpus is absent. */
        fun resourceRoot(): File {
            val url = OracleCorpus::class.java.getResource(RESOURCE)
            assumeTrue(url != null) { "render oracle resources missing (git-ignored); skipped" }
            return File(url!!.toURI())
        }
    }
}

/** How a case's expected outputs are stored, which decides the kinds of its checks. */
enum class OracleCaseKind(val json: String) {
    /** Rendered files (`expected/<common|label>/…`) and per-task results (`expected/tasks.json`). */
    FILES("files"),

    /** One row per Jinja expression (`expected/expressions.json`): template text, task-argument value, type. */
    EXPRESSIONS("expressions"),

    /** Per-task results under two hash seeds (`expected_hashseed_1/`, `expected_hashseed_2/`). */
    HASH_SEEDS("hash_seeds"),

    /** Python `re.sub` results per Python version (`expected/comparison.json`), not per core. */
    REGEX("regex");

    companion object {
        fun of(json: String): OracleCaseKind = entries.firstOrNull { it.json == json } ?: error("unknown case kind $json")
    }
}

/**
 * One ansible-core line a case was measured on: [label] is the version directory and key (`2.21`), [version] the
 * exact core, [python] the controller's Python. [primary] versions define `common`/`both`.
 */
class OracleCore(
    val label: String,
    val version: CoreVersion,
    val python: String,
    val jinja2: String,
    val where: String,
    val primary: Boolean,
) {
    override fun toString(): String = "$label ($version)"
}

/** One golden case. Checks are computed per core on first use and kept. */
class OracleCase private constructor(
    val id: String,
    val dir: File,
    val title: String,
    val kind: OracleCaseKind,
    val pins: List<String>,
    /** The measured versions, primary ones first, in `case.json` order. */
    val cores: List<OracleCore>,
    /** Expression ids whose result order depends on the hash seed, with the reason. */
    val orderVaries: Map<String, String>,
) {
    /** What ran: the playbook, inventory, templates and vars. */
    val inputDir: File get() = File(dir, "input")

    private val checksByCore = HashMap<String, List<OracleCheck>>()

    /** The core labelled [label] (`2.18`). */
    fun core(label: String): OracleCore =
        cores.firstOrNull { it.label == label } ?: throw IllegalArgumentException("$id was not measured on $label; cores: $cores")

    /** The input files, relative to [inputDir], sorted. */
    fun inputFiles(): List<String> =
        inputDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(inputDir).invariantSeparatorsPath }.sorted().toList()

    /** The text of the input file at [path] (UTF-8, newlines as written). */
    fun inputText(path: String): String = File(inputDir, path).readText()

    /** Every comparison this case defines for [core], in a stable order. */
    @Synchronized
    fun checks(core: OracleCore): List<OracleCheck> {
        require(core in cores) { "$id was not measured on $core" }
        return checksByCore.getOrPut(core.label) { OracleChecks.of(this, core) }
    }

    override fun toString(): String = id

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun load(dir: File): OracleCase {
            val meta = Json.parseObject(File(dir, "case.json").readText())
            val id = meta["case"] as String
            require(id == dir.name) { "${dir.name}/case.json names the case $id" }
            val primary = (meta["primary"] as List<String>)
            val extra = (meta["extra"] as List<String>?).orEmpty()
            val versions = meta["versions"] as Map<String, Map<String, Any?>>
            val cores = (primary + extra).map { label ->
                val v = requireNotNull(versions[label]) { "$id: case.json has no version $label" }
                val core = requireNotNull(CoreVersion.parse(v["core"] as String)) { "$id: bad core version ${v["core"]}" }
                require(core.toString() == v["core"]) { "$id: core version ${v["core"]} is not major.minor.patch" }
                require(label == "${core.major}.${core.minor}") { "$id: version $label is ansible-core $core" }
                OracleCore(label, core, v["python"] as String, v["jinja2"] as String, v["where"] as String, label in primary)
            }
            return OracleCase(
                id = id,
                dir = dir,
                title = meta["title"] as String,
                kind = OracleCaseKind.of(meta["kind"] as String),
                pins = meta["pins"] as List<String>,
                cores = cores,
                orderVaries = (meta["order_varies"] as Map<String, String>?).orEmpty(),
            )
        }
    }
}
