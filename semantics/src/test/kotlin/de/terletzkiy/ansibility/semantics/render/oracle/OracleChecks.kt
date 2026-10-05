package de.terletzkiy.ansibility.semantics.render.oracle

import de.terletzkiy.ansibility.semantics.json.Json
import java.io.File

/**
 * One comparison of the oracle: what ansible-core produced for one output of a case on one core.
 *
 * Paths of the playbook directory appear as `<pb>` and memory addresses as `0x...` in every expected value (the
 * generator normalises them), so a runner prints those placeholders the same way.
 */
class OracleCheck(
    /**
     * Unique within a case and core: `file:<path>`, `task:<n>:name`, `task:<n>:<host>:<field>`,
     * `task:<n>:<host>:<item>:<field>`, `expr:<id>:<field>` or `regex:<id>`.
     */
    val id: String,
    val subject: CheckSubject,
    val expected: Expected,
    /** The order of the result depends on the hash seed: a [Outcome.Known] must carry an [OrderMark]. */
    val orderVaries: Boolean = false,
) {
    override fun toString(): String = "$id = $expected" + if (orderVaries) " (order varies)" else ""
}

/** What a check is about: the input a runner renders to produce the outcome. */
sealed interface CheckSubject {
    /** A file the playbook wrote, relative to the run's `out/` directory (`lf/int_nl.txt`). */
    data class OutputFile(val path: String) : CheckSubject

    /**
     * One field of task [index] (0-based over the whole run, plays in order, `pre_tasks` and role tasks included) as
     * the json callback reported it. [task] is the name ansible-core 2.21 printed (for messages); [host] and [item]
     * (the loop item index) are null for [TaskField.NAME].
     */
    data class Task(
        val index: Int,
        val play: String,
        val task: String,
        val host: String?,
        val item: Int?,
        val field: TaskField,
    ) : CheckSubject

    /** One field of an expression-oracle row: the Jinja text [expr] between `{{` and `}}`. */
    data class Expression(val id: String, val expr: String, val field: ExpressionField) : CheckSubject

    /** `re.compile(pattern, flags).sub(replacement, input, count)`, with `I`/`M` in [flags]. */
    data class RegexSub(
        val id: String,
        val pattern: String,
        val flags: String,
        val input: String,
        val replacement: String,
        val count: Int,
    ) : CheckSubject
}

/** The fields of a task result. */
enum class TaskField(val json: String) {
    /** The task's display name: templated before the loop ("name mode"). */
    NAME("name"),

    /** The loop item's value. */
    ITEM("item"),

    /** Succeeded, failed (with ansible-core's message) or skipped. */
    RESULT("result"),

    /** `debug`'s `msg`. */
    MSG("msg"),

    /** The facts a `set_fact` stored (`discovered_interpreter_python` removed). */
    FACTS("facts"),
}

/** The fields of an expression row. */
enum class ExpressionField(val json: String) {
    /** The text of a template file `{{ expr }}` (no trailing newline): Jinja string escapes active. */
    TEMPLATE("template"),

    /** The value `set_fact: {r: "{{ expr }}"}` stored, as the json callback printed it (dict keys sorted). */
    ARG("arg"),

    /** `type_debug` of the expression inside Jinja. */
    TYPE("type"),
}

/** What ansible-core produced. */
sealed interface Expected {
    /** The bytes of a written file. */
    class Bytes(val bytes: ByteArray) : Expected {
        override fun toString(): String = "bytes ${Json.write(String(bytes, Charsets.UTF_8))}"
    }

    /** Text: a rendered template, or a regex result. */
    data class Text(val text: String) : Expected {
        override fun toString(): String = "text ${Json.write(text)}"
    }

    /** A JSON value as [Json.parse] reads it (Long/BigInteger, Double, String, Boolean, null, List, Map). */
    data class Value(val value: Any?) : Expected {
        override fun toString(): String = "value " + runCatching { Json.write(value) }.getOrElse { value.toString() }
    }

    /** ansible-core failed with [message] (whitespace collapsed, at most 400 characters). */
    data class Failure(val message: String) : Expected

    /** The task succeeded. */
    data object Succeeded : Expected

    /** The task was skipped (a false condition). */
    data object Skipped : Expected

    /** No core proves the result (it depends on the controller's Python): a renderer must abstain. */
    data class Unprovable(val reason: String) : Expected
}

/** Builds the checks of a case for one core from its expected files. */
internal object OracleChecks {
    /** The maximal length of a failure message the generator keeps. */
    const val MESSAGE_LIMIT: Int = 400

    fun of(case: OracleCase, core: OracleCore): List<OracleCheck> {
        val checks = when (case.kind) {
            OracleCaseKind.FILES -> files(case, core, File(case.dir, "expected")) + tasks(case, core, File(case.dir, "expected"))
            OracleCaseKind.EXPRESSIONS -> expressions(case, core)
            OracleCaseKind.HASH_SEEDS -> hashSeeds(case, core)
            OracleCaseKind.REGEX -> regex(case, core)
        }
        val duplicates = checks.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "${case.id} on $core: duplicate check ids $duplicates" }
        return checks
    }

    private fun files(case: OracleCase, core: OracleCore, expected: File): List<OracleCheck> {
        val common = File(expected, "common")
        val own = File(expected, core.label)
        val missing = extraInfo(expected, core)["missing"].strings().toSet()
        val paths = (relativeFiles(common) - missing) + relativeFiles(own)
        return paths.sorted().map { path ->
            val file = File(own, path).takeIf { it.isFile } ?: File(common, path)
            OracleCheck("file:$path", CheckSubject.OutputFile(path), Expected.Bytes(file.readBytes()))
        }
    }

    private fun tasks(case: OracleCase, core: OracleCore, expected: File): List<OracleCheck> {
        val path = File(expected, "tasks.json")
        if (!path.isFile) return emptyList()
        val out = ArrayList<OracleCheck>()
        json(path).list().forEachIndexed { index, raw ->
            val entry = raw.map()
            val play = entry["play"] as String
            val display = entry["task"] as String
            val hosts = (if (core.label in entry) entry[core.label] else entry["both"])?.map() ?: return@forEachIndexed
            val name = (entry["task_${core.label}"] ?: display) as String
            out += OracleCheck("task:$index:name", CheckSubject.Task(index, play, display, null, null, TaskField.NAME), Expected.Value(name))
            for ((host, result) in hosts) {
                val r = result.map()
                val items = r["items"]
                if (items == null) {
                    out += resultChecks("task:$index:$host", r) { field -> CheckSubject.Task(index, play, display, host, null, field) }
                } else {
                    items.list().forEachIndexed { i, item ->
                        val itemResult = item.map()
                        val prefix = "task:$index:$host:$i"
                        out += OracleCheck(
                            "$prefix:item",
                            CheckSubject.Task(index, play, display, host, i, TaskField.ITEM),
                            Expected.Value(itemResult["item"]),
                        )
                        out += resultChecks(prefix, itemResult) { field -> CheckSubject.Task(index, play, display, host, i, field) }
                    }
                }
            }
        }
        check(case.kind != OracleCaseKind.FILES || out.isNotEmpty()) { "${case.id}: tasks.json has no task for $core" }
        return out
    }

    private fun resultChecks(prefix: String, r: Map<String, Any?>, subject: (TaskField) -> CheckSubject): List<OracleCheck> {
        val result = when {
            "error" in r -> Expected.Failure(r["error"] as String)
            r["skipped"] == true -> Expected.Skipped
            else -> Expected.Succeeded
        }
        val out = mutableListOf(OracleCheck("$prefix:result", subject(TaskField.RESULT), result))
        if ("msg" in r) out += OracleCheck("$prefix:msg", subject(TaskField.MSG), Expected.Value(r["msg"]))
        if ("facts" in r) out += OracleCheck("$prefix:facts", subject(TaskField.FACTS), Expected.Value(r["facts"]))
        return out
    }

    private fun expressions(case: OracleCase, core: OracleCore): List<OracleCheck> {
        val rows = json(File(case.dir, "expected/expressions.json")).list()
        val ids = rows.map { it.map()["id"] as String }.toSet()
        val unknown = case.orderVaries.keys - ids
        check(unknown.isEmpty()) { "${case.id}: case.json order_varies names unknown rows $unknown" }
        val out = ArrayList<OracleCheck>()
        for (raw in rows) {
            val row = raw.map()
            val id = row["id"] as String
            val expr = row["expr"] as String
            val r = (if (core.label in row) row[core.label] else row["both"]).map()
            val orderVaries = id in case.orderVaries
            fun add(field: ExpressionField, expected: Expected) {
                out += OracleCheck("expr:$id:${field.json}", CheckSubject.Expression(id, expr, field), expected, orderVaries)
            }
            when {
                "template_error" in r -> add(ExpressionField.TEMPLATE, Expected.Failure(r["template_error"] as String))
                r["template"] != null -> add(ExpressionField.TEMPLATE, Expected.Text(r["template"] as String))
            }
            when {
                "arg_error" in r -> add(ExpressionField.ARG, Expected.Failure(r["arg_error"] as String))
                "arg" in r -> add(ExpressionField.ARG, Expected.Value(r["arg"]))
            }
            if ("jinja_type" in r) add(ExpressionField.TYPE, Expected.Value(r["jinja_type"]))
        }
        return out
    }

    /** Seed 1 gives the expected values; a check whose value differs under seed 2 is marked [OracleCheck.orderVaries]. */
    private fun hashSeeds(case: OracleCase, core: OracleCore): List<OracleCheck> {
        val seeds = case.dir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("expected_hashseed_") }
            .sortedBy { it.name }
        check(seeds.size >= 2) { "${case.id}: expected two hash-seed directories, found ${seeds.map { it.name }}" }
        val first = tasks(case, core, seeds[0])
        val others = seeds.drop(1).map { dir -> tasks(case, core, dir).associateBy { it.id } }
        return first.map { check ->
            val alternatives = others.map { requireNotNull(it[check.id]) { "${case.id}: ${check.id} is missing under one seed" }.expected }
            if (alternatives.all { it == check.expected }) {
                check
            } else {
                val members = (check.expected as? Expected.Value)?.value as? List<*>
                check(members != null && alternatives.all { sameMembers(members, (it as? Expected.Value)?.value as? List<*>) }) {
                    "${case.id}: ${check.id} differs between hash seeds by more than its order"
                }
                OracleCheck(check.id, check.subject, check.expected, orderVaries = true)
            }
        }
    }

    private fun regex(case: OracleCase, core: OracleCore): List<OracleCheck> {
        val key = pythonKey(core.python)
        return json(File(case.dir, "expected/comparison.json")).list().map { raw ->
            val row = raw.map()
            val id = row["id"] as String
            // The harness (tools/docgen/render-oracle/regex/py_regex.py) replaces at most twice in the row count_2.
            val subject = CheckSubject.RegexSub(
                id, row["pattern"] as String, row["flags"] as String, row["input"] as String, row["replacement"] as String,
                if (id == "count_2") 2 else 0,
            )
            val pythons = row.keys.filter { it.startsWith("python_") }.sorted()
            check(key in pythons) { "${case.id}: $id has no result for Python ${core.python} ($key)" }
            val results = pythons.map { regexResult(row[it].map()) }
            val expected = if (results.distinct().size > 1) {
                Expected.Unprovable(
                    "the result depends on the controller's Python, which the core does not fix: " +
                        pythons.zip(results).joinToString("; ") { (k, r) -> "${k.removePrefix("python_").replace('_', '.')}: $r" },
                )
            } else {
                regexResult(row[key].map())
            }
            OracleCheck("regex:$id", subject, expected)
        }
    }

    private fun regexResult(r: Map<String, Any?>): Expected = when {
        "ok" in r -> Expected.Text(r["ok"] as String)
        "err" in r -> Expected.Failure(r["err"] as String)
        else -> error("regex result without ok or err: $r")
    }

    /** `3.14.7` → `python_3_14`, the key comparison.json uses. */
    fun pythonKey(python: String): String = "python_" + python.split('.').take(2).joinToString("_")

    private fun sameMembers(a: List<*>, b: List<*>?): Boolean = b != null && OracleValues.sameMembers(a, b)

    private fun extraInfo(expected: File, core: OracleCore): Map<String, Any?> {
        if (core.primary) return emptyMap()
        val summary = File(expected, "summary.json")
        val extra = (json(summary).map()["extra"] as Map<*, *>?)?.get(core.label)
        return requireNotNull(extra) { "$summary has no overlay of ${core.label}" }.map()
    }

    private fun relativeFiles(dir: File): Set<String> =
        if (!dir.isDirectory) emptySet()
        else dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()

    private fun json(file: File): Any? = OracleValues.parsePythonJson(file.readText())

    @Suppress("UNCHECKED_CAST")
    private fun Any?.map(): Map<String, Any?> = this as? Map<String, Any?> ?: error("expected a JSON object, got $this")

    private fun Any?.list(): List<Any?> = this as? List<Any?> ?: error("expected a JSON array, got $this")

    private fun Any?.strings(): List<String> = (this as List<*>?).orEmpty().map { it as String }
}
