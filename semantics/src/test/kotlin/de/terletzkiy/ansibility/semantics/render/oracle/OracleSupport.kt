package de.terletzkiy.ansibility.semantics.render.oracle

import de.terletzkiy.ansibility.semantics.json.Json
import java.io.File

/**
 * `render-oracle/supported.json`: which cases the pure renderer renders, on which cores, with which runner. Each WU
 * that makes a case render adds or extends its entry; a case × core without an entry is listed as unsupported.
 *
 * ```json
 * {"supported": [{"case": "03_trailing_newlines", "cores": ["2.18", "2.21"],
 *                 "runner": "de.terletzkiy.ansibility.semantics.render.template.TemplateOracleRunner",
 *                 "min_matched": {"2.18": 48, "2.21": 48}, "note": "R11-5"}]}
 * ```
 *
 * `min_matched` is the coverage floor per core (matched checks); it may only be raised.
 */
class OracleSupport private constructor(val entries: List<Entry>) {

    /** One supported case: [runner] renders it on [cores], with at least [minMatched] matched checks per core. */
    class Entry(
        val case: String,
        val cores: List<String>,
        val runner: String,
        val minMatched: Map<String, Int>,
        val note: String?,
    ) {
        /** A new instance of [runner]: a Kotlin `object` or a class with a no-argument constructor. */
        fun newRunner(): OracleRunner {
            val type = Class.forName(runner)
            val instance = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
                ?: type.getDeclaredConstructor().newInstance()
            return instance as? OracleRunner ?: throw IllegalArgumentException("$runner is not an OracleRunner")
        }
    }

    /** The entry that supports [case] on [core], or null. */
    fun entry(case: String, core: String): Entry? = entries.firstOrNull { it.case == case && core in it.cores }

    /** The problems of the file against [corpus]: unknown cases, cores and runners, duplicates, floors without a core. */
    fun problems(corpus: OracleCorpus): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<Pair<String, String>>()
        for (e in entries) {
            val case = corpus.cases.firstOrNull { it.id == e.case }
            if (case == null) {
                out += "${e.case}: no such case"
                continue
            }
            if (e.cores.isEmpty()) out += "${e.case}: no cores"
            for (core in e.cores) {
                if (case.cores.none { it.label == core }) out += "${e.case}: not measured on $core"
                if (!seen.add(e.case to core)) out += "${e.case} × $core: listed twice"
            }
            for (core in e.minMatched.keys - e.cores.toSet()) out += "${e.case}: min_matched for $core, which the entry does not list"
            for ((core, n) in e.minMatched) if (n < 0) out += "${e.case}: min_matched $n for $core"
            runCatching { e.newRunner() }.onFailure { out += "${e.case}: runner ${e.runner}: $it" }
        }
        return out
    }

    companion object {
        /** File name inside the oracle's resource directory. */
        const val FILE = "supported.json"

        fun load(root: File = OracleCorpus.resourceRoot()): OracleSupport = parse(File(root, FILE).readText())

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): OracleSupport {
            val doc = Json.parseObject(text)
            val unknownKeys = doc.keys - setOf("comment", "supported")
            require(unknownKeys.isEmpty()) { "$FILE: unknown keys $unknownKeys" }
            val entries = (doc["supported"] as List<Map<String, Any?>>).map { e ->
                val unknown = e.keys - setOf("case", "cores", "runner", "min_matched", "note")
                require(unknown.isEmpty()) { "$FILE: ${e["case"]} has unknown keys $unknown" }
                Entry(
                    case = e["case"] as String,
                    cores = e["cores"] as List<String>,
                    runner = e["runner"] as String,
                    minMatched = (e["min_matched"] as Map<String, Any?>?).orEmpty().mapValues { (_, v) -> (v as Number).toInt() },
                    note = e["note"] as String?,
                )
            }
            return OracleSupport(entries)
        }
    }
}
