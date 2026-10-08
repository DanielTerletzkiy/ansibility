package de.terletzkiy.ansibility.run.view

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.events.StatusCounts
import de.terletzkiy.ansibility.run.events.UnitRun
import java.util.Locale
import javax.swing.Icon

/** Icons and texts of the run view: one icon per [HostStatus], durations, counts. */
object RunViewTexts {
    private val changed: Icon by lazy { IconUtil.colorize(AllIcons.Actions.Edit, JBColor(0xB57E00, 0xE5B03B)) }

    fun icon(status: HostStatus): Icon = when (status) {
        HostStatus.RUNNING -> AnimatedIcon.Default.INSTANCE
        HostStatus.OK -> AllIcons.RunConfigurations.TestPassed
        HostStatus.CHANGED -> changed
        HostStatus.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
        HostStatus.IGNORED -> AllIcons.RunConfigurations.TestIgnored
        HostStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
        HostStatus.UNREACHABLE -> AllIcons.RunConfigurations.TestError
    }

    fun status(status: HostStatus): String = message("run.view.status.${status.name.lowercase()}")

    /** The icon of a batch unit: not run yet before it started, else its status. */
    fun unitIcon(unit: UnitRun, finished: Boolean): Icon = when {
        unit.started == null -> if (finished) AllIcons.RunConfigurations.TestSkipped else AllIcons.RunConfigurations.TestNotRan
        unit.stopped -> AllIcons.RunConfigurations.TestTerminated
        else -> icon(unit.status)
    }

    /** Whether a unit's row says its [unitStatus] (not yet run, preparing, not passed) rather than only its counts. */
    fun showsUnitStatus(unit: UnitRun): Boolean = unit.started == null || (unit.exitCode ?: 0) != 0 || (unit.ended == null && unit.preparing != null)

    /** "Waiting" (or "Not run" once the batch ended), "Preparing: …", "Stopped", "Exit code 2", else the status. */
    fun unitStatus(unit: UnitRun, finished: Boolean): String {
        val exitCode = unit.exitCode
        val preparing = unit.preparing
        return when {
            unit.started == null -> message(if (finished) "run.view.unit.not.run" else "run.view.unit.waiting")
            preparing != null && unit.ended == null -> message("run.prepare.phase", preparing)
            exitCode == RunModel.STOPPED -> message("run.view.unit.stopped")
            exitCode == RunModel.NOT_STARTED -> unit.problem?.let { message("run.view.unit.not.started.why", firstLine(it)) } ?: message("run.view.unit.not.started")
            exitCode != null && exitCode != 0 -> message("run.view.unit.exit", exitCode)
            else -> status(unit.status)
        }
    }

    /** "0.4 s", "12 s", "10m 6s", "1h 2m". */
    fun duration(seconds: Double?): String {
        if (seconds == null || seconds < 0) return ""
        return when {
            seconds < 10 -> String.format(Locale.ROOT, "%.1f s", seconds)
            seconds < 60 -> "${seconds.toInt()} s"
            seconds < 3600 -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
            else -> "${(seconds / 3600).toInt()}h ${((seconds % 3600) / 60).toInt()}m"
        }
    }

    /** "OK 27% · Changed 68% · Skipped 5%": the statuses present, in the order of the recap. */
    fun shares(counts: StatusCounts): String =
        ORDER.filter { counts[it] > 0 }.joinToString(" · ") { "${status(it)} ${counts.percent(it)}%" }

    /** "3 ok · 1 changed · 1 failed": the statuses present. */
    fun tally(counts: StatusCounts): String =
        ORDER.filter { counts[it] > 0 }.joinToString(" · ") { "${counts[it]} ${status(it).lowercase()}" }

    /** The first line of [text], at most [limit] characters. */
    fun firstLine(text: String?, limit: Int = 160): String {
        val line = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length > limit) line.take(limit) + "…" else line
    }

    private val ORDER = listOf(
        HostStatus.OK, HostStatus.CHANGED, HostStatus.FAILED, HostStatus.UNREACHABLE, HostStatus.IGNORED, HostStatus.SKIPPED, HostStatus.RUNNING,
    )
}
