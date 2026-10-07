package de.terletzkiy.ansibility.run.events

import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.JsonParseException

/** How one host (or loop item) ended a task; [RUNNING] while it has not reported yet. */
enum class HostStatus {
    RUNNING, OK, CHANGED, SKIPPED, IGNORED, FAILED, UNREACHABLE;

    /** Failed and unreachable stop the host; an ignored failure does not. */
    val isFailure: Boolean get() = this == FAILED || this == UNREACHABLE

    companion object {
        fun of(text: String?): HostStatus = when (text) {
            "ok" -> OK
            "changed" -> CHANGED
            "skipped" -> SKIPPED
            "ignored" -> IGNORED
            "failed" -> FAILED
            "unreachable" -> UNREACHABLE
            else -> RUNNING
        }
    }
}

/** One file change of a result's `diff`: the before and after texts with their headers, or a prepared unified diff. */
data class FileDiff(val beforeHeader: String?, val afterHeader: String?, val before: String?, val after: String?, val prepared: String?)

/** The `PLAY RECAP` counts of one host. */
data class HostStats(val ok: Int, val changed: Int, val unreachable: Int, val failures: Int, val skipped: Int, val rescued: Int, val ignored: Int)

/**
 * The events of the Ansibility callback (`ansible/callback/ansibility_events.py`, schema 1), one per stderr line. Ids
 * are Ansible's play and task uuids; [time] is the epoch seconds the callback stamped.
 */
sealed interface RunEvent {
    val time: Double

    data class Start(
        override val time: Double,
        val playbook: String?,
        val ansible: String?,
        val check: Boolean,
        val diff: Boolean,
        val limit: String?,
        val tags: List<String>,
        val skipTags: List<String>,
    ) : RunEvent

    data class Play(override val time: Double, val id: String, val name: String, val hosts: List<String>, val path: String?) : RunEvent

    data class Task(
        override val time: Double,
        val id: String,
        val play: String?,
        val name: String,
        val role: String?,
        val action: String?,
        val path: String?,
        val handler: Boolean,
        val loop: Boolean,
        val tags: List<String>,
    ) : RunEvent

    data class HostStart(override val time: Double, val task: String, val host: String) : RunEvent

    /** A host's result of a task ([item] false), or of one loop item ([item] true, labelled [itemLabel]). */
    data class Result(
        override val time: Double,
        val item: Boolean,
        val task: String,
        val host: String,
        val status: HostStatus,
        val changed: Boolean,
        val itemLabel: String?,
        val items: Int?,
        val duration: Double?,
        val delegatedTo: String?,
        val message: String?,
        val diff: List<FileDiff>,
        /** The censored, size-capped result dictionary; null when the event was too large and lost it. */
        val result: Map<String, Any?>?,
        val truncated: Boolean,
    ) : RunEvent

    data class Retry(override val time: Double, val task: String, val host: String, val attempt: Int?, val retries: Int?, val message: String?) : RunEvent

    data class Notify(override val time: Double, val handler: String, val host: String) : RunEvent

    data class Include(override val time: Double, val file: String?, val hosts: List<String>, val task: String?) : RunEvent

    data class NoHosts(override val time: Double, val play: String?, val remaining: Boolean) : RunEvent

    data class AsyncPoll(override val time: Double, val task: String, val host: String) : RunEvent

    data class Stats(override val time: Double, val hosts: Map<String, HostStats>) : RunEvent

    /**
     * A Molecule stage from Molecule's own log (`[default > converge] Executing`, `… Executed: Successful`), not from
     * the callback: [result] is null when the stage starts.
     */
    data class Stage(override val time: Double, val scenario: String, val action: String, val result: String?) : RunEvent

    /**
     * The units a batch runs one after another (the roles of a bulk Molecule run), from the IDE, not the callback:
     * [Units] lists them before the first starts, [UnitStart] and [UnitEnd] frame each one's process.
     */
    data class Units(override val time: Double, val units: List<UnitInfo>) : RunEvent

    data class UnitStart(override val time: Double, val key: String) : RunEvent

    /** [problem]: why the unit did not start (it could not be prepared), or null. */
    data class UnitEnd(override val time: Double, val key: String, val exitCode: Int, val problem: String? = null) : RunEvent

    /** A unit of a batch: [key] identifies it (a role directory), [name] and [label] are shown ("web", "falcon"). */
    data class UnitInfo(val key: String, val name: String, val label: String)

    /** An event of a newer schema or kind, kept so that nothing breaks. */
    data class Unknown(override val time: Double, val name: String) : RunEvent
}

/** Turns the JSON of one event into a [RunEvent]; null for anything that is not an event object. Pure. */
object RunEventDecoder {
    fun decode(json: String): RunEvent? {
        val map = try {
            Json.parse(json) as? Map<*, *>
        } catch (_: JsonParseException) {
            null
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return decode(map as Map<String, Any?>)
    }

    fun decode(map: Map<String, Any?>): RunEvent? {
        val kind = map["e"] as? String ?: return null
        val time = (map["t"] as? Number)?.toDouble() ?: 0.0
        return when (kind) {
            "start" -> RunEvent.Start(
                time, map.text("playbook"), map.text("ansible"), map.flag("check"), map.flag("diff"), map.text("limit"),
                map.strings("tags"), map.strings("skip_tags"),
            )
            "play" -> RunEvent.Play(time, map.text("id") ?: return null, map.text("name").orEmpty(), map.strings("hosts"), map.text("path"))
            "task" -> RunEvent.Task(
                time, map.text("id") ?: return null, map.text("play"), map.text("name").orEmpty(), map.text("role"), map.text("action"),
                map.text("path"), map.flag("handler"), map.flag("loop"), map.strings("tags"),
            )
            "host_start" -> RunEvent.HostStart(time, map.text("task") ?: return null, map.text("host") ?: return null)
            "result", "item" -> RunEvent.Result(
                time = time,
                item = kind == "item",
                task = map.text("task") ?: return null,
                host = map.text("host") ?: return null,
                status = HostStatus.of(map.text("status")),
                changed = map.flag("changed"),
                itemLabel = map["item"]?.let(::label),
                items = (map["items"] as? Number)?.toInt(),
                duration = (map["duration"] as? Number)?.toDouble(),
                delegatedTo = map.text("delegated_to"),
                message = map.text("msg"),
                diff = (map["diff"] as? List<*>).orEmpty().mapNotNull(::fileDiff),
                result = map.objectAt("result"),
                truncated = map.flag("truncated"),
            )
            "retry" -> RunEvent.Retry(
                time, map.text("task") ?: return null, map.text("host") ?: return null,
                (map["attempt"] as? Number)?.toInt(), (map["retries"] as? Number)?.toInt(), map.text("msg"),
            )
            "notify" -> RunEvent.Notify(time, map.text("handler") ?: return null, map.text("host") ?: return null)
            "include" -> RunEvent.Include(time, map.text("file"), map.strings("hosts"), map.text("task"))
            "no_hosts", "no_hosts_remaining" -> RunEvent.NoHosts(time, map.text("play"), kind == "no_hosts_remaining")
            "async_poll" -> RunEvent.AsyncPoll(time, map.text("task") ?: return null, map.text("host") ?: return null)
            "stats" -> RunEvent.Stats(
                time,
                (map["hosts"] as? Map<*, *>).orEmpty().entries.mapNotNull { (host, value) ->
                    val counts = value as? Map<*, *> ?: return@mapNotNull null
                    fun n(key: String) = (counts[key] as? Number)?.toInt() ?: 0
                    host.toString() to HostStats(n("ok"), n("changed"), n("unreachable"), n("failures"), n("skipped"), n("rescued"), n("ignored"))
                }.toMap(LinkedHashMap()),
            )
            else -> RunEvent.Unknown(time, kind)
        }
    }

    /** How a loop item is shown: a string as is, anything else as compact JSON. */
    fun label(value: Any?): String = value as? String ?: Json.write(value)

    private fun fileDiff(value: Any?): FileDiff? {
        val map = value as? Map<*, *> ?: return null
        fun text(key: String) = map[key]?.let { it as? String ?: Json.write(it) }
        return FileDiff(text("before_header"), text("after_header"), text("before"), text("after"), text("prepared"))
    }

    private fun Map<String, Any?>.text(key: String): String? = when (val value = this[key]) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

    private fun Map<String, Any?>.strings(key: String): List<String> = when (val value = this[key]) {
        is List<*> -> value.mapNotNull { it?.toString() }
        is String -> listOf(value)
        else -> emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectAt(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
}
