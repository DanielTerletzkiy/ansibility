package de.terletzkiy.ansibility.run.events

/** Counts of host results by status. */
data class StatusCounts(val counts: Map<HostStatus, Int> = emptyMap()) {
    operator fun get(status: HostStatus): Int = counts[status] ?: 0

    val total: Int get() = counts.values.sum()

    /** The share of [status] in percent, rounded; 0 without results. */
    fun percent(status: HostStatus): Int = if (total == 0) 0 else Math.round(this[status] * 100.0 / total).toInt()

    operator fun plus(other: StatusCounts): StatusCounts =
        StatusCounts((counts.keys + other.counts.keys).associateWith { this[it] + other[it] })

    companion object {
        fun of(statuses: Iterable<HostStatus>): StatusCounts = StatusCounts(statuses.groupingBy { it }.eachCount())
    }
}

/** One loop item of a host's task result. */
class ItemRun(val label: String, val status: HostStatus, val message: String?, val diff: List<FileDiff>, val result: Map<String, Any?>?)

/** One host's run of one task: running until its result arrives. */
class HostRun(val host: String, val started: Double?) {
    var status: HostStatus = HostStatus.RUNNING
        internal set
    var duration: Double? = null
        internal set
    var message: String? = null
        internal set
    var diff: List<FileDiff> = emptyList()
        internal set
    var result: Map<String, Any?>? = null
        internal set
    var delegatedTo: String? = null
        internal set
    var truncated: Boolean = false
        internal set
    /** Failed attempts of an `until` loop before the result. */
    var retries: Int = 0
        internal set
    var polls: Int = 0
        internal set
    val items: MutableList<ItemRun> = ArrayList()
}

/** One task (or handler) of a play, with the hosts that ran it in the order they started or reported. */
class TaskRun(
    val id: String,
    val name: String,
    val role: String?,
    val action: String?,
    /** `path:line` as Ansible reports it (a container path for a Compose run). */
    val path: String?,
    val handler: Boolean,
    val loop: Boolean,
    val tags: List<String>,
    val started: Double,
) {
    val hosts: LinkedHashMap<String, HostRun> = LinkedHashMap()
    var ended: Double? = null
        internal set

    /** The worst status of the hosts: failed and unreachable before changed, ok, ignored and skipped; running while any runs. */
    val status: HostStatus get() = RunModel.worst(hosts.values.map { it.status })

    val counts: StatusCounts get() = StatusCounts.of(hosts.values.map { it.status })

    /** The task's name without the `role : ` prefix Ansible gives tasks of roles. */
    val shortName: String get() = if (role != null && name.startsWith("$role : ")) name.removePrefix("$role : ") else name
}

/** One play of the run; a `serial` play reports each batch again under the same id and is kept as one. */
class PlayRun(val id: String, val name: String, val hosts: List<String>, val path: String?, val started: Double) {
    val tasks: MutableList<TaskRun> = ArrayList()

    /** Handlers notified in the play, with the host that notified them, in order. */
    val notified: MutableList<Pair<String, String>> = ArrayList()

    /** The serial batches the play ran in (1 without `serial`). */
    var batches: Int = 1
        internal set
    var ended: Double? = null
        internal set
    var noHostsMatched: Boolean = false
        internal set

    val status: HostStatus get() = if (tasks.isEmpty()) HostStatus.SKIPPED else RunModel.worst(tasks.map { it.status })

    val counts: StatusCounts get() = tasks.fold(StatusCounts()) { sum, task -> sum + task.counts }

    /** Seconds from the play's start to its end (or [now] while it runs). */
    fun duration(now: Double): Double = (ended ?: now) - started
}

/**
 * One Molecule stage (`converge` of scenario `default`): the plays its `ansible-playbook` ran and the result Molecule
 * reported (`Successful`, `Failed`, `Skipped`, `Missing playbook …`; null while it runs, and always from a Molecule
 * older than 25, which reports none).
 */
class StageRun(val scenario: String, val action: String, val started: Double) {
    val plays: MutableList<PlayRun> = ArrayList()
    var result: String? = null
        internal set
    var ended: Double? = null
        internal set
    var stats: Map<String, HostStats> = emptyMap()
        internal set

    /** The run ended while this stage ran: no result came, and no recap of its playbook either. */
    var interrupted: Boolean = false
        internal set

    val status: HostStatus get() {
        val result = result ?: return when {
            interrupted -> HostStatus.FAILED
            ended == null -> HostStatus.RUNNING
            else -> plays.map { it.status }.filter { it != HostStatus.RUNNING }.let { if (it.isEmpty()) HostStatus.OK else RunModel.worst(it) }
        }
        return when {
            result.startsWith("Successful", ignoreCase = true) -> plays.map { it.status }.filter { !it.isFailure && it != HostStatus.RUNNING }
                .let { if (it.isEmpty()) HostStatus.OK else RunModel.worst(it) }
            result.startsWith("Failed", ignoreCase = true) -> HostStatus.FAILED
            else -> HostStatus.SKIPPED
        }
    }

    val counts: StatusCounts get() = plays.fold(StatusCounts()) { sum, play -> sum + play.counts }

    /** The tasks that changed: on `idempotence`, the ones that made the stage fail. */
    val changedTasks: List<TaskRun> get() = plays.flatMap { play -> play.tasks.filter { task -> task.hosts.values.any { it.status == HostStatus.CHANGED } } }
}

/**
 * One unit of a batch (a role of a bulk Molecule run): its stages and the plays outside them while it ran. Not started
 * yet ([started] null) it waits, or was not run when the batch ended before it.
 */
class UnitRun(val key: String, val name: String, val label: String) {
    val stages: MutableList<StageRun> = ArrayList()
    val plays: MutableList<PlayRun> = ArrayList()
    var started: Double? = null
        internal set
    var ended: Double? = null
        internal set
    var exitCode: Int? = null
        internal set

    /** Why it did not start (it could not be prepared), or null. */
    var problem: String? = null
        internal set

    /** Stopped by the user while it ran (its status is failed; it does not count among the failures). */
    val stopped: Boolean get() = exitCode == RunModel.STOPPED

    /** Like a test's: passed (exit code 0), failed, running, or skipped when it never started. */
    val status: HostStatus get() = when {
        started == null -> HostStatus.SKIPPED
        ended == null -> HostStatus.RUNNING
        exitCode != 0 -> HostStatus.FAILED
        else -> HostStatus.OK
    }

    val counts: StatusCounts get() = (stages.flatMap { it.plays } + plays).fold(StatusCounts()) { sum, play -> sum + play.counts }
}

/**
 * The run as plays → tasks → hosts → items, built from [RunEvent]s in arrival order; a Molecule run groups its plays by
 * [stages]. Molecule's stage lines come on stderr and the events on stdout, so the two arrive only roughly in step: a
 * stage's `Executed` line may overtake the last events of its playbook. The plays therefore go to stages by playbook
 * run: a run (its `start` event) belongs to the stage that started last, whose `Executing` line was written a whole
 * `ansible-playbook` start-up before it, and everything the run reports stays with that stage. Not thread-safe: one
 * thread applies events (the run view does so on the EDT) and reads the model; [modificationCount] tells views to
 * refresh.
 */
class RunModel {
    var start: RunEvent.Start? = null
        private set
    val plays: MutableList<PlayRun> = ArrayList()
    var stats: Map<String, HostStats> = emptyMap()
        private set
    var finished: Boolean = false
        private set
    var exitCode: Int? = null
        private set
    var lastEvent: Double = 0.0
        private set
    var modificationCount: Long = 0
        private set
    val includes: MutableList<RunEvent.Include> = ArrayList()

    /** The Molecule stages, in order (empty for a playbook run). */
    val stages: MutableList<StageRun> = ArrayList()

    /** The stage of the playbook run whose events arrive. */
    private var runStage: StageRun? = null

    /** The units of a batch, in order (empty for a single run); [currentUnit] runs now. */
    val units: MutableList<UnitRun> = ArrayList()
    private var currentUnit: UnitRun? = null

    private val tasks = HashMap<String, TaskRun>()
    private var currentPlay: PlayRun? = null
    private var currentTask: TaskRun? = null

    /** Whether any event arrived: without one the view falls back to the console. */
    val hasEvents: Boolean get() = start != null || plays.isNotEmpty() || stages.isNotEmpty() || units.isNotEmpty()

    fun unit(key: String): UnitRun? = units.firstOrNull { it.key == key }

    val status: HostStatus get() = if (plays.isEmpty()) HostStatus.RUNNING else worst(plays.map { it.status }).let {
        if (it == HostStatus.RUNNING && finished) HostStatus.FAILED else it
    }

    val counts: StatusCounts get() = plays.fold(StatusCounts()) { sum, play -> sum + play.counts }

    fun task(id: String): TaskRun? = tasks[id]

    fun apply(event: RunEvent) {
        lastEvent = maxOf(lastEvent, event.time)
        modificationCount++
        when (event) {
            is RunEvent.Start -> {
                // The next playbook (a Molecule stage's, or the next one on the command line): the previous one is over.
                endCurrentTask(event.time)
                currentPlay?.let { if (it.ended == null) it.ended = event.time }
                currentPlay = null
                start = event
                runStage = currentUnit.let { unit -> if (unit != null) unit.stages.lastOrNull() else stages.lastOrNull() }
            }
            is RunEvent.Play -> {
                endCurrentTask(event.time)
                val again = plays.lastOrNull()?.takeIf { it.id == event.id && event.id.isNotEmpty() }
                if (again != null) {
                    again.batches++
                    again.ended = null
                    currentPlay = again
                } else {
                    currentPlay?.ended = event.time
                    val play = PlayRun(event.id, event.name, event.hosts, event.path, event.time)
                    plays += play
                    runStage?.plays?.add(play) ?: currentUnit?.plays?.add(play)
                    currentPlay = play
                }
            }
            is RunEvent.Task -> {
                endCurrentTask(event.time)
                val play = event.play?.let { id -> plays.lastOrNull { it.id == id } } ?: currentPlay ?: implicitPlay(event.time)
                val known = tasks[event.id]?.takeIf { it in play.tasks }
                val task = known?.also { it.ended = null }
                    ?: TaskRun(event.id, event.name, event.role, event.action, event.path, event.handler, event.loop, event.tags, event.time).also {
                        play.tasks += it
                        tasks[it.id] = it
                    }
                currentTask = task
            }
            is RunEvent.HostStart -> tasks[event.task]?.let { task -> task.hosts.getOrPut(event.host) { HostRun(event.host, event.time) } }
            is RunEvent.Result -> {
                val task = tasks[event.task] ?: return
                val host = task.hosts.getOrPut(event.host) { HostRun(event.host, null) }
                if (event.item) {
                    host.items += ItemRun(event.itemLabel.orEmpty(), event.status, event.message, event.diff, event.result)
                } else {
                    host.status = event.status
                    host.duration = event.duration ?: host.started?.let { event.time - it }
                    host.message = event.message
                    host.diff = event.diff
                    host.result = event.result
                    host.delegatedTo = event.delegatedTo
                    host.truncated = event.truncated
                }
            }
            // ansible-core 2.18 numbers the failed attempt, 2.21 the next one: count the retries instead.
            is RunEvent.Retry -> tasks[event.task]?.hosts?.get(event.host)?.let { it.retries++ }
            // A poll runs a copy of the task with its own id: count it on the running task.
            is RunEvent.AsyncPoll -> (tasks[event.task] ?: currentTask)?.hosts?.get(event.host)?.let { it.polls++ }
            is RunEvent.Notify -> currentPlay?.notified?.add(event.handler to event.host)
            is RunEvent.Include -> includes += event
            is RunEvent.NoHosts -> if (!event.remaining) currentPlay?.noHostsMatched = true
            is RunEvent.Stats -> {
                stats = event.hosts
                runStage?.stats = event.hosts
                endCurrentTask(event.time)
                currentPlay?.ended = event.time
            }
            is RunEvent.Stage -> stage(event)
            is RunEvent.Units -> event.units.forEach { info -> if (unit(info.key) == null) units += UnitRun(info.key, info.name, info.label) }
            is RunEvent.UnitStart -> {
                val unit = unit(event.key) ?: return
                unit.started = event.time
                currentUnit = unit
                runStage = null
            }
            is RunEvent.UnitEnd -> {
                val unit = unit(event.key) ?: return
                unit.ended = event.time
                unit.exitCode = event.exitCode
                unit.problem = event.problem
                unit.stages.lastOrNull()?.let { if (it.ended == null) it.ended = event.time }
                endCurrentTask(event.time)
                currentPlay?.let { if (it.ended == null) it.ended = event.time }
                currentPlay = null
                if (currentUnit === unit) currentUnit = null
            }
            is RunEvent.Unknown -> Unit
        }
    }

    /** The process ended with [code]: hosts still running never reported (the run was stopped or crashed). */
    fun finish(code: Int?) {
        finished = true
        exitCode = code
        endCurrentTask(lastEvent)
        if (currentPlay?.ended == null) currentPlay?.ended = lastEvent
        stages.lastOrNull()?.takeIf { it.ended == null }?.let { stage ->
            stage.ended = lastEvent
            // Stopped (or failed outside a playbook) before its result; an older Molecule's last stage ends here too.
            if (stage.result == null && code != 0 && stage.stats.isEmpty()) stage.interrupted = true
        }
        // A unit still running when the batch ended was stopped.
        currentUnit?.let { unit ->
            unit.ended = lastEvent
            unit.exitCode = code?.takeIf { it != 0 } ?: STOPPED
            currentUnit = null
        }
        modificationCount++
    }

    /** Hosts that failed or were unreachable in the recap (else in the results), for "rerun failed hosts". */
    fun failedHosts(): List<String> {
        val recap = stats.filterValues { it.failures > 0 || it.unreachable > 0 }.keys
        if (recap.isNotEmpty() || stats.isNotEmpty()) return recap.toList()
        return plays.flatMap { play -> play.tasks.flatMap { task -> task.hosts.values.filter { it.status.isFailure }.map { it.host } } }.distinct()
    }

    /** A stage line: it comes from the other stream, so the plays and tasks are left to their own events. */
    private fun stage(event: RunEvent.Stage) {
        if (event.result == null) {
            // An older Molecule reports no results: its stage ends where the next one starts.
            stages.lastOrNull()?.let { if (it.ended == null) it.ended = event.time }
            val stage = StageRun(event.scenario, event.action, event.time)
            stages += stage
            currentUnit?.stages?.add(stage)
            return
        }
        val stage = stages.lastOrNull { it.scenario == event.scenario && it.action == event.action && it.result == null }
            ?: StageRun(event.scenario, event.action, event.time).also {
                stages += it
                currentUnit?.stages?.add(it)
            }
        stage.result = event.result
        stage.ended = event.time
    }

    private fun endCurrentTask(time: Double) {
        currentTask?.let { if (it.ended == null) it.ended = time }
        currentTask = null
    }

    private fun implicitPlay(time: Double): PlayRun = PlayRun("", "", emptyList(), null, time).also {
        plays += it
        currentPlay = it
    }

    companion object {
        /** The exit code of a unit the batch stopped. */
        const val STOPPED: Int = -1

        /** The exit code of a unit that could not start (not prepared, no process). */
        const val NOT_STARTED: Int = -2

        private val SEVERITY = listOf(
            HostStatus.RUNNING, HostStatus.FAILED, HostStatus.UNREACHABLE, HostStatus.CHANGED, HostStatus.OK, HostStatus.IGNORED, HostStatus.SKIPPED,
        )

        /** The status that sums up [statuses]: running first, then failures, changes, oks, ignored failures, skips. */
        fun worst(statuses: Collection<HostStatus>): HostStatus =
            statuses.minByOrNull { SEVERITY.indexOf(it) } ?: HostStatus.SKIPPED
    }
}
