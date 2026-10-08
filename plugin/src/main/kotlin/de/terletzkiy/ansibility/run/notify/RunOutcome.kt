package de.terletzkiy.ansibility.run.notify

import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.events.StatusCounts
import de.terletzkiy.ansibility.run.events.TaskRun
import de.terletzkiy.ansibility.run.molecule.MoleculeCommand

/**
 * How a run ended, as far as its notification tells (plan amendment R19, D148): names and counts, never values. An
 * outcome holds no host or item message, result, item label, extra variable, environment or Compose variable; the
 * names of failed tasks, failed hosts and plays are the only texts taken from the run. Pure.
 */
sealed interface RunOutcome {
    /** Seconds from the start of the run's process to its end; null when unknown. */
    val seconds: Double?

    /** A playbook run (the whole playbook, one play or one role). [name] is the run's tab name ("site.yml [staging]"). */
    class Playbook(
        val name: String,
        override val seconds: Double?,
        val exitCode: Int,
        val stopped: Boolean,
        /** Whether events arrived from the run: without them only [exitCode] is known. */
        val hasEvents: Boolean,
        val check: Boolean,
        /** How many hosts the recap lists (else the results). */
        val hosts: Int,
        /** The hosts that failed or were unreachable, each with the task where it happened. */
        val failures: List<HostFailure>,
        val counts: StatusCounts,
        /** The names of the plays that ran, in order. */
        val plays: List<String>,
        /** The plays that matched no host. */
        val noHosts: List<String>,
        val limit: String?,
        /** The task that ran when the run was stopped ("nginx : Reload nginx"), or null. */
        val runningTask: String?,
        /**
         * Whether the run asked for events (its root's run view is on). Such a run without [hasEvents] failed before
         * ansible-playbook's first callback event (a syntax error, a limit that matches no host); without the view only
         * the exit code is known anyway.
         */
        val reportsEvents: Boolean = hasEvents,
    ) : RunOutcome

    /** A host that failed ([unreachable]: could not be reached) at [task] (null: unknown). */
    class HostFailure(val host: String, val task: String?, val unreachable: Boolean)

    /** A Molecule run of one role: [subject] is "web › default" or "web (all scenarios)". */
    class Molecule(
        val subject: String,
        val command: MoleculeCommand,
        override val seconds: Double?,
        val exitCode: Int,
        val stopped: Boolean,
        val hasEvents: Boolean,
        val stages: List<StageFact>,
        val counts: StatusCounts,
        /** Minutes until the instances are destroyed unless the user keeps them (a countdown started), or null. */
        val countdownMinutes: Int?,
        /** A stopped test: its instances are destroyed in a run of their own. */
        val destroyFollows: Boolean,
        /** A destroy the IDE started by itself (a countdown ran out, a test was stopped): it tells only of a failure. */
        val automatic: Boolean,
    ) : RunOutcome {
        /** A task failed because Molecule runs pass no vault secrets (D136, D137). */
        val missingVaultSecrets: Boolean get() = stages.any { it.missingVaultSecrets }

        /** Whether the stages come from more than one scenario (a run of all scenarios). */
        val scenarios: List<String> get() = stages.map { it.scenario }.distinct()
    }

    /**
     * One Molecule stage: its [status], the tasks that failed in it (with the hosts that failed them) and, for
     * `idempotence`, the tasks that changed on the second run.
     */
    class StageFact(
        val scenario: String,
        val action: String,
        val status: HostStatus,
        val failedTasks: List<TaskFailure>,
        val changedTasks: List<String>,
        val missingVaultSecrets: Boolean,
    )

    class TaskFailure(val task: String, val hosts: List<String>)

    /** A bulk Molecule run: one fact per role. [destroyed] is the key of the role whose instances a stop destroys. */
    class Batch(
        override val seconds: Double?,
        val stopped: Boolean,
        val units: List<UnitFact>,
        val destroyed: String?,
    ) : RunOutcome

    /**
     * One role of a bulk run: [failedAction] is the action of its first failed stage ("verify"), [failedScenario] that
     * stage's scenario when the role ran several (else null).
     */
    class UnitFact(
        val key: String,
        val name: String,
        val label: String,
        val state: UnitState,
        val failedAction: String?,
        val failedScenario: String?,
        val exitCode: Int?,
        /** Why it did not start (the preparation's message), or null. */
        val problem: String?,
        /** It failed because Molecule runs pass no vault secrets (D137). */
        val missingVaultSecrets: Boolean = false,
    )

    enum class UnitState { PASSED, FAILED, NOT_STARTED, STOPPED, NOT_RUN }

    /**
     * A run whose preparation failed: [name] is its tab name, or the subject of a Molecule run of [command]; [reason]
     * says why (the first line is the summary); [countdownMinutes] when the destroy countdown its start cancelled came
     * back (the notification carries its links).
     */
    class NotStarted(val name: String, val command: MoleculeCommand?, val reason: String, val countdownMinutes: Int? = null) : RunOutcome {
        override val seconds: Double? get() = null
    }
}

/** Builds [RunOutcome]s from a run's [RunModel]. Pure. */
object RunOutcomes {
    /**
     * The outcome of a playbook run; [model] is null when its run view was off, and holds no events when the run ended
     * before ansible-playbook sent its first one.
     */
    fun playbook(name: String, model: RunModel?, exitCode: Int, stopped: Boolean, seconds: Double?): RunOutcome.Playbook {
        if (model == null || !model.hasEvents) {
            return RunOutcome.Playbook(
                name, seconds, exitCode, stopped, false, false, 0, emptyList(), StatusCounts(), emptyList(), emptyList(), null, null,
                reportsEvents = model != null,
            )
        }
        val failures = model.failedHosts().map { host ->
            val task = failedTask(model, host)
            RunOutcome.HostFailure(host, task?.name, task?.hosts?.get(host)?.status == HostStatus.UNREACHABLE)
        }
        val hosts = model.stats.keys.ifEmpty { model.plays.flatMap { play -> play.tasks.flatMap { it.hosts.keys } }.toSet() }.size
        return RunOutcome.Playbook(
            name = name,
            seconds = seconds,
            exitCode = exitCode,
            stopped = stopped,
            hasEvents = true,
            check = model.start?.check == true,
            hosts = maxOf(hosts, failures.size),
            failures = failures,
            counts = model.counts,
            plays = model.plays.map { it.name },
            noHosts = model.plays.filter { it.noHostsMatched }.map { it.name },
            limit = model.start?.limit?.takeIf { it.isNotBlank() },
            runningTask = if (stopped) runningTask(model)?.name else null,
        )
    }

    /** The outcome of a Molecule run of [command] on [subject]; [model] is null when its run view was off. */
    fun molecule(
        subject: String,
        command: MoleculeCommand,
        model: RunModel?,
        exitCode: Int,
        stopped: Boolean,
        seconds: Double?,
        countdownMinutes: Int? = null,
        destroyFollows: Boolean = false,
        automatic: Boolean = false,
    ): RunOutcome.Molecule = RunOutcome.Molecule(
        subject = subject,
        command = command,
        seconds = seconds,
        exitCode = exitCode,
        stopped = stopped,
        hasEvents = model?.hasEvents == true,
        stages = model?.stages.orEmpty().map { stage ->
            val failed = stage.status.isFailure
            RunOutcome.StageFact(
                scenario = stage.scenario,
                action = stage.action,
                status = stage.status,
                // A rescued failure leaves a failed task in a stage that passed: only a failed stage names its tasks.
                failedTasks = if (!failed) emptyList() else stage.plays.flatMap { it.tasks }.filter { it.status.isFailure }.map { task ->
                    RunOutcome.TaskFailure(task.name, task.hosts.values.filter { it.status.isFailure }.map { it.host })
                },
                changedTasks = if (failed && stage.action == IDEMPOTENCE) stage.changedTasks.map { it.name } else emptyList(),
                missingVaultSecrets = failed && stage.missingVaultSecrets,
            )
        },
        counts = model?.counts ?: StatusCounts(),
        countdownMinutes = countdownMinutes,
        destroyFollows = destroyFollows,
        automatic = automatic,
    )

    /** The outcome of a bulk Molecule run from its units; [destroyed] is the key of the role a stop destroys. */
    fun batch(model: RunModel, stopped: Boolean, destroyed: String?): RunOutcome.Batch {
        val started = model.units.mapNotNull { it.started }.minOrNull()
        val ended = model.units.mapNotNull { it.ended }.maxOrNull() ?: model.lastEvent
        return RunOutcome.Batch(
            seconds = started?.let { (ended - it).coerceAtLeast(0.0) },
            stopped = stopped,
            units = model.units.map { unit ->
                val state = when {
                    unit.started == null -> RunOutcome.UnitState.NOT_RUN
                    unit.stopped -> RunOutcome.UnitState.STOPPED
                    unit.exitCode == RunModel.NOT_STARTED -> RunOutcome.UnitState.NOT_STARTED
                    unit.exitCode == 0 -> RunOutcome.UnitState.PASSED
                    else -> RunOutcome.UnitState.FAILED
                }
                val several = unit.stages.map { it.scenario }.distinct().size > 1
                val failedStage = unit.stages.firstOrNull { it.status.isFailure }
                RunOutcome.UnitFact(
                    unit.key, unit.name, unit.label, state, failedStage?.action, failedStage?.scenario?.takeIf { several }, unit.exitCode, unit.problem,
                    state == RunOutcome.UnitState.FAILED && unit.missingVaultSecrets,
                )
            },
            destroyed = destroyed,
        )
    }

    /**
     * The task "Show Failed Task" selects: for a playbook run the task of the first failed host, for a Molecule run
     * the first failed task of its first failed stage; null when none failed.
     */
    fun failedTask(model: RunModel): TaskRun? {
        model.stages.firstOrNull { it.status.isFailure }?.let { stage ->
            return stage.plays.flatMap { it.tasks }.firstOrNull { it.status.isFailure }
        }
        if (model.stages.isNotEmpty()) return null
        return model.failedHosts().firstNotNullOfOrNull { failedTask(model, it) }
    }

    /** The last task [host] failed (or was unreachable at): a failure rescued earlier does not count. */
    private fun failedTask(model: RunModel, host: String): TaskRun? =
        model.plays.flatMap { it.tasks }.lastOrNull { it.hosts[host]?.status?.isFailure == true }

    /** The task a stopped run ran: the last one a host still ran, else the last one. */
    private fun runningTask(model: RunModel): TaskRun? {
        val tasks = model.plays.flatMap { it.tasks }
        return tasks.lastOrNull { task -> task.hosts.values.any { it.status == HostStatus.RUNNING } } ?: tasks.lastOrNull()
    }

    private const val IDEMPOTENCE = "idempotence"
}
