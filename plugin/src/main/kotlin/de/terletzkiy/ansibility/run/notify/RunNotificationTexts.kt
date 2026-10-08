package de.terletzkiy.ansibility.run.notify

import com.intellij.notification.NotificationType
import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.StatusCounts
import de.terletzkiy.ansibility.run.molecule.MoleculeCommand
import de.terletzkiy.ansibility.run.notify.RunOutcome.UnitState
import de.terletzkiy.ansibility.run.view.RunViewTexts

/** How a run ended, for the notification's type (and the user's choice to hear of passed runs). */
enum class RunNotificationKind(val type: NotificationType) {
    PASSED(NotificationType.INFORMATION),
    FAILED(NotificationType.ERROR),
    STOPPED(NotificationType.WARNING),
    NOTHING_RAN(NotificationType.WARNING),
    NOT_STARTED(NotificationType.ERROR),
}

/**
 * What a run's notification says: a plain [title] and [lines] (shown as escaped HTML in the balloon), and [summary],
 * the text of the operating system's notification: counts only, since the notification centre keeps it.
 */
class RunNotificationText(val title: String, val lines: List<String>, val summary: String, val kind: RunNotificationKind) {
    val htmlTitle: String get() = StringUtil.escapeXmlEntities(title)

    val htmlContent: String get() = lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
}

/**
 * The texts of a run's end notification (plan amendment R19, D148): exactly what happened, from the [RunOutcome]'s
 * names and counts. Names are cut at [NAME_LIMIT] characters and lists at [LIST_LIMIT] entries ("and 2 more"); exit
 * codes are not put into words (ansible-core's codes are ambiguous: the console says what failed). Pure.
 */
object RunNotificationTexts {
    const val NAME_LIMIT = 80
    const val LIST_LIMIT = 3

    fun of(outcome: RunOutcome): RunNotificationText = when (outcome) {
        is RunOutcome.Playbook -> playbook(outcome)
        is RunOutcome.Molecule -> molecule(outcome)
        is RunOutcome.Batch -> batch(outcome)
        is RunOutcome.NotStarted -> notStarted(outcome)
    }

    /** The first line of [text], cut at [NAME_LIMIT]. */
    fun name(text: String?): String = RunViewTexts.firstLine(text, NAME_LIMIT)

    /** [lines], the first [LIST_LIMIT] of them followed by "and N more" when there are more. */
    fun capped(lines: List<String>): List<String> =
        if (lines.size <= LIST_LIMIT) lines else lines.take(LIST_LIMIT) + message("run.notification.more", lines.size - LIST_LIMIT)

    /** "a, b, c and 2 more". */
    fun inline(items: List<String>): String =
        if (items.size <= LIST_LIMIT) items.joinToString(", ") else items.take(LIST_LIMIT).joinToString(", ") + " " + message("run.notification.more", items.size - LIST_LIMIT)

    // ------------------------------------------------------------------ playbook runs

    private fun playbook(outcome: RunOutcome.Playbook): RunNotificationText {
        val name = name(outcome.name)
        val duration = RunViewTexts.duration(outcome.seconds)
        val tally = tally(outcome.counts, outcome.check)
        return when {
            outcome.stopped -> {
                val where = outcome.runningTask?.let { message("run.notification.stopped.in", duration, name(it)) } ?: message("run.notification.stopped.after", duration)
                val soFar = tally.takeIf { it.isNotEmpty() }?.let { message("run.notification.so.far", it) }
                text(message("run.notification.playbook.stopped", name), listOfNotNull(where, soFar), joined(soFar, duration), RunNotificationKind.STOPPED)
            }
            !outcome.hasEvents && outcome.exitCode == 0 ->
                text(message("run.notification.playbook.passed", name), listOf(duration), duration, RunNotificationKind.PASSED)
            // The run view is on, but ansible-playbook ended before its first event: before any play ran.
            !outcome.hasEvents && outcome.reportsEvents -> before(name, outcome.exitCode, duration)
            !outcome.hasEvents -> text(
                message("run.notification.playbook.exit", name, outcome.exitCode),
                listOf(joined(duration, message("run.notification.no.view"))),
                joined(message("run.notification.exit.code", outcome.exitCode), duration),
                RunNotificationKind.FAILED,
            )
            outcome.failures.isNotEmpty() -> {
                val failed = outcome.failures.size
                val lines = capped(outcome.failures.map(::failure)) + joined(hostsOk(outcome.hosts - failed), duration)
                text(
                    message("run.notification.playbook.failed", name, failed, outcome.hosts),
                    lines,
                    joined(message("run.notification.hosts.failed", failed), hostsOk(outcome.hosts - failed), duration),
                    RunNotificationKind.FAILED,
                )
            }
            outcome.exitCode != 0 && outcome.plays.isEmpty() -> before(name, outcome.exitCode, duration)
            outcome.exitCode != 0 -> text(
                message("run.notification.playbook.error", name),
                listOf(joined(message("run.notification.exit.after.play", outcome.exitCode, playName(outcome.plays.last())), duration)),
                joined(message("run.notification.exit.code", outcome.exitCode), duration),
                RunNotificationKind.FAILED,
            )
            outcome.counts.total == 0 && outcome.noHosts.isNotEmpty() -> {
                val lines = capped(outcome.noHosts.map { play ->
                    if (outcome.limit != null) message("run.notification.no.hosts.limit", playName(play), name(outcome.limit)) else message("run.notification.no.hosts.line", playName(play))
                })
                text(message("run.notification.playbook.no.hosts", name), lines, message("run.notification.nothing.ran"), RunNotificationKind.NOTHING_RAN)
            }
            else -> {
                val first = joined(hosts(outcome.hosts), tally, duration)
                val lines = listOf(if (outcome.check) message("run.notification.check.line", first) else first) +
                    capped(outcome.noHosts.map { message("run.notification.no.hosts.some", playName(it)) }) +
                    listOfNotNull(outcome.limit?.let { message("run.notification.limit", name(it)) })
                val title = if (outcome.check) "run.notification.playbook.check" else "run.notification.playbook.passed"
                text(message(title, name), lines, first, RunNotificationKind.PASSED)
            }
        }
    }

    /** "site.yml [staging]: failed before any play ran" with the exit code and the console. */
    private fun before(name: String, exitCode: Int, duration: String): RunNotificationText = text(
        message("run.notification.playbook.before", name),
        listOf(joined(message("run.notification.exit.console", exitCode), duration)),
        joined(message("run.notification.exit.code", exitCode), duration),
        RunNotificationKind.FAILED,
    )

    private fun failure(failure: RunOutcome.HostFailure): String {
        val host = name(failure.host)
        val task = failure.task?.let(::name)
        return when {
            task == null -> message(if (failure.unreachable) "run.notification.host.unreachable" else "run.notification.host.failed", host)
            failure.unreachable -> message("run.notification.host.unreachable.at", host, task)
            else -> message("run.notification.host.failed.at", host, task)
        }
    }

    private fun playName(play: String): String = name(play).ifEmpty { message("run.view.play.unnamed") }

    // ------------------------------------------------------------------ Molecule runs

    private fun molecule(outcome: RunOutcome.Molecule): RunNotificationText {
        val subject = name(outcome.subject)
        val command = message("run.notification.molecule.command.${outcome.command.id}")
        val duration = RunViewTexts.duration(outcome.seconds)
        val countdown = outcome.countdownMinutes?.let { message("run.notification.molecule.countdown", it) }
        val tally = tally(outcome.counts, false)
        return when {
            outcome.stopped -> {
                val stage = outcome.stages.lastOrNull()?.action
                val where = when {
                    stage != null && outcome.destroyFollows -> message("run.notification.molecule.stopped.cleanup", stage, duration)
                    stage != null -> message("run.notification.molecule.stopped.during", stage, duration)
                    outcome.destroyFollows -> message("run.notification.molecule.stopped.after.cleanup", duration)
                    else -> message("run.notification.stopped.after", duration)
                }
                text(message("run.notification.molecule.stopped", command, subject), listOfNotNull(where, countdown), joined(stage, duration), RunNotificationKind.STOPPED)
            }
            outcome.command == MoleculeCommand.DESTROY && outcome.exitCode == 0 ->
                text(message("run.notification.molecule.destroy.passed", subject), listOf(joined(message("run.notification.molecule.destroyed"), duration)), duration, RunNotificationKind.PASSED)
            outcome.command == MoleculeCommand.DESTROY -> text(
                message("run.notification.molecule.destroy.failed", subject),
                listOf(message("run.notification.molecule.destroy.exit", outcome.exitCode)),
                joined(message("run.notification.exit.code", outcome.exitCode), duration),
                RunNotificationKind.FAILED,
            )
            outcome.exitCode == 0 -> {
                val first = joined(stagesPassed(outcome.stages.size).takeIf { outcome.stages.size > 1 }, tally, duration)
                text(message("run.notification.molecule.passed", command, subject), listOfNotNull(first, countdown), first, RunNotificationKind.PASSED)
            }
            else -> {
                val several = outcome.scenarios.size > 1
                val failedStages = outcome.stages.filter { it.status.isFailure }
                val failures = capped(failedStages.flatMap { stageLines(it, several) })
                    .ifEmpty { listOf(message("run.notification.molecule.exit", outcome.exitCode)) }
                val passedStages = outcome.stages.count { it.status != HostStatus.RUNNING && !it.status.isFailure }.takeIf { it > 0 }?.let(::stagesPassed)
                val passed = if (several) {
                    val failedScenarios = failedStages.map { it.scenario }.toSet()
                    outcome.scenarios.filter { it !in failedScenarios }.takeIf { it.isNotEmpty() }?.let { message("run.notification.molecule.scenarios.passed", inline(it.map(::name))) }
                } else {
                    passedStages
                }
                val vault = message("run.view.details.no.vault.molecule").takeIf { outcome.missingVaultSecrets }
                text(
                    message("run.notification.molecule.failed", command, subject),
                    failures + listOfNotNull(joined(passed, duration).takeIf { it.isNotEmpty() }, vault, countdown),
                    // Counts only: no task, host or scenario name leaves the IDE.
                    joined(message("run.notification.stages.failed", failedStages.size).takeIf { failedStages.isNotEmpty() }, passedStages, duration)
                        .ifEmpty { message("run.notification.exit.code", outcome.exitCode) },
                    RunNotificationKind.FAILED,
                )
            }
        }
    }

    /** What one failed stage says: the tasks that changed on the second run (idempotence), the failed tasks, or that it failed. */
    private fun stageLines(stage: RunOutcome.StageFact, several: Boolean): List<String> {
        val label = stageLabel(stage.action, stage.scenario.takeIf { several })
        if (stage.changedTasks.isNotEmpty()) {
            val tasks = inline(stage.changedTasks.map { message("run.notification.task.quoted", name(it)) })
            return listOf(message("run.notification.molecule.idempotence", label, stage.changedTasks.size, tasks))
        }
        if (stage.failedTasks.isEmpty()) return listOf(message("run.notification.molecule.stage.failed", label))
        return stage.failedTasks.map { message("run.notification.molecule.task.failed", label, name(it.task), inline(it.hosts.map(::name))) }
    }

    private fun stagesPassed(count: Int): String = message("run.notification.molecule.stages.passed", count)

    /** "verify", or "volatile › verify" when the run had several scenarios ([scenario]); each name cut on its own. */
    private fun stageLabel(action: String, scenario: String?): String =
        if (scenario == null) name(action) else message("run.view.stage", name(scenario), name(action))

    // ------------------------------------------------------------------ bulk Molecule runs

    private fun batch(outcome: RunOutcome.Batch): RunNotificationText {
        val units = outcome.units
        val total = units.size
        val passed = units.count { it.state == UnitState.PASSED }
        val failed = units.filter { it.state == UnitState.FAILED || it.state == UnitState.NOT_STARTED }
        val stoppedUnits = units.count { it.state == UnitState.STOPPED }
        val notRun = units.count { it.state == UnitState.NOT_RUN }
        val duration = RunViewTexts.duration(outcome.seconds)
        // The role a Stop ended counts on its own: it ran (its instances may be destroyed), but did not finish.
        val counts = if (stoppedUnits > 0) message("run.notification.batch.tally.stopped", passed, failed.size, stoppedUnits, notRun)
        else message("run.notification.batch.tally", passed, failed.size, notRun)
        val vault = message("run.view.details.no.vault.molecule").takeIf { failed.any { it.missingVaultSecrets } }
        return when {
            outcome.stopped -> {
                val stopped = units.firstOrNull { it.state == UnitState.STOPPED }?.let { unit ->
                    val key = if (unit.key == outcome.destroyed) "run.notification.batch.stopped.destroy" else "run.notification.batch.stopped.during"
                    message(key, name(unit.name), name(unit.label))
                }
                text(
                    // The roles that started, as the console's summary counts them: the stopped one and those that did not start too.
                    message("run.notification.batch.stopped", total - notRun, total),
                    listOfNotNull(stopped) + capped(failed.map(::unitFailure)) + listOfNotNull(vault) + joined(counts, duration),
                    joined(counts, duration),
                    RunNotificationKind.STOPPED,
                )
            }
            failed.isNotEmpty() -> text(
                message("run.notification.batch.failed", failed.size, total),
                capped(failed.map(::unitFailure)) + listOfNotNull(vault) + joined(message("run.notification.batch.passed.count", passed), duration),
                joined(counts, duration),
                RunNotificationKind.FAILED,
            )
            else -> text(
                message("run.notification.batch.passed", total),
                listOf(joined(inline(units.map { name(it.name) }), duration)),
                joined(counts, duration),
                RunNotificationKind.PASSED,
            )
        }
    }

    private fun unitFailure(unit: RunOutcome.UnitFact): String {
        val name = name(unit.name)
        val label = name(unit.label)
        return when {
            unit.state == UnitState.NOT_STARTED -> unit.problem?.let { message("run.notification.batch.unit.not.started.why", name, label, RunViewTexts.firstLine(it)) }
                ?: message("run.notification.batch.unit.not.started", name, label)
            unit.failedAction != null -> message("run.notification.batch.unit.failed", name, label, stageLabel(unit.failedAction, unit.failedScenario))
            else -> message("run.notification.batch.unit.exit", name, label, unit.exitCode ?: -1)
        }
    }

    // ------------------------------------------------------------------ runs that did not start

    private fun notStarted(outcome: RunOutcome.NotStarted): RunNotificationText {
        val title = outcome.command?.let { message("run.notification.molecule.not.started", message("run.notification.molecule.command.${it.id}"), name(outcome.name)) }
            ?: message("run.notification.playbook.not.started", name(outcome.name))
        val countdown = outcome.countdownMinutes?.let { message("run.notification.molecule.countdown", it) }
        return text(title, listOfNotNull(RunViewTexts.firstLine(outcome.reason), countdown), message("run.notification.not.started.summary"), RunNotificationKind.NOT_STARTED)
    }

    // ------------------------------------------------------------------ parts

    /** "4 hosts". */
    private fun hosts(count: Int): String = message("run.notification.hosts", count)

    /** "2 hosts ok". */
    private fun hostsOk(count: Int): String = message("run.notification.hosts.ok", count.coerceAtLeast(0))

    /** "37 ok · 12 changed · 5 skipped" ("12 would change" in check mode): the statuses present. */
    private fun tally(counts: StatusCounts, check: Boolean): String {
        if (!check) return RunViewTexts.tally(counts)
        return TALLY_ORDER.filter { counts[it] > 0 }.joinToString(SEPARATOR) { status ->
            if (status == HostStatus.CHANGED) message("run.notification.would.change", counts[status]) else "${counts[status]} ${RunViewTexts.status(status).lowercase()}"
        }
    }

    /** The non-empty [parts], joined with " · ". */
    private fun joined(vararg parts: String?): String = parts.filterNot { it.isNullOrEmpty() }.joinToString(SEPARATOR)

    private fun text(title: String, lines: List<String>, summary: String, kind: RunNotificationKind) =
        RunNotificationText(title, lines.filter { it.isNotEmpty() }, summary, kind)

    private const val SEPARATOR = " · "

    private val TALLY_ORDER = listOf(
        HostStatus.OK, HostStatus.CHANGED, HostStatus.FAILED, HostStatus.UNREACHABLE, HostStatus.IGNORED, HostStatus.SKIPPED, HostStatus.RUNNING,
    )
}
