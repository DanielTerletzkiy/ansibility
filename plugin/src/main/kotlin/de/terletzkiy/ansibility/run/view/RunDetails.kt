package de.terletzkiy.ansibility.run.view

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.events.FileDiff
import de.terletzkiy.ansibility.run.events.HostRun
import de.terletzkiy.ansibility.run.events.ItemRun
import de.terletzkiy.ansibility.run.events.PlayRun
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.events.StageRun
import de.terletzkiy.ansibility.run.events.TaskRun
import de.terletzkiy.ansibility.run.events.UnitRun
import de.terletzkiy.ansibility.semantics.json.Json
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/** What the run view can start: runs of the same configuration, limited to hosts, from a task, or of one play or role. */
interface RunViewActions {
    /** Runs the playbook again on [hosts] only (`--limit`). */
    fun rerunHosts(hosts: List<String>)

    /** Runs the playbook again from [task] (`--start-at-task`). */
    fun startAt(task: String)

    /** Opens the run dialog for the play named [play] (or its role [role]). */
    fun runPart(play: String, role: String?)

    /** Whether host reruns, starts at a task and play or role runs apply (a playbook run, not a Molecule run). */
    val playbookActions: Boolean get() = true

    /** Runs Molecule's [action] of [scenario] again (of the batch's [unit], else of the run's role); only where [stageActions]. */
    fun runStage(scenario: String, action: String, unit: String?) = Unit

    val stageActions: Boolean get() = false

    /** Runs the units [keys] of a batch again (roles of a bulk Molecule run); only where [unitActions]. */
    fun runUnits(keys: List<String>) = Unit

    val unitActions: Boolean get() = false

    companion object {
        val NONE = object : RunViewActions {
            override fun rerunHosts(hosts: List<String>) = Unit
            override fun startAt(task: String) = Unit
            override fun runPart(play: String, role: String?) = Unit
        }
    }
}

/**
 * The details of the selected node: the run, a play, a task, a host's result or an item's, or the recap. Results show
 * as read-only JSON; a result with a `diff` offers the IDE's diff viewer; a task links to its source.
 */
class RunDetails(
    private val project: Project,
    private val model: RunModel,
    private val actions: RunViewActions,
    /** The host file of a path Ansible reports (a container path for a Compose run). */
    private val hostPath: (String) -> Path?,
) {
    /** The details of [node] (a model object; null: the run). */
    fun component(node: Any?, task: TaskRun?, play: PlayRun?, host: HostRun?): JComponent = panel {
        when (node) {
            is UnitRun -> unit(node)
            is StageRun -> stage(node)
            is PlayRun -> play(node)
            is TaskRun -> task(node, play)
            is HostRun -> host(node, task)
            is ItemRun -> item(node, task, host)
            RecapNode -> recap()
            else -> run()
        }
    }

    private fun Panel.run() {
        val start = model.start
        row { label(message("run.view.details.run")).bold() }
        if (model.stages.isEmpty()) start?.playbook?.let { row(message("run.view.details.playbook")) { label(it) } }
        start?.ansible?.let { row(message("run.view.details.ansible")) { label(it) } }
        val modes = listOfNotNull(
            message("run.view.details.check").takeIf { start?.check == true },
            message("run.view.details.diff").takeIf { start?.diff == true },
        )
        if (modes.isNotEmpty()) row(message("run.view.details.modes")) { label(modes.joinToString(", ")) }
        start?.limit?.let { row(message("run.view.details.limit")) { label(it) } }
        start?.tags?.filter { it != "all" }?.takeIf { it.isNotEmpty() }?.let { row(message("run.view.details.tags")) { label(it.joinToString(", ")) } }
        row(message("run.view.details.results")) { label(RunViewTexts.tally(model.counts).ifEmpty { "–" }) }
        model.exitCode?.let { row(message("run.view.details.exit")) { label(it.toString()) } }
        val failedUnits = model.units.filter { it.status.isFailure && !it.stopped }
        if (failedUnits.isNotEmpty()) {
            row(message("run.view.details.failed.units")) { label(failedUnits.joinToString(", ") { "${it.name} (${it.label})" }) }
            if (actions.unitActions && model.finished) row { button(message("run.view.action.rerun.failed.units")) { actions.runUnits(failedUnits.map { it.key }) } }
        }
        val failedStages = model.stages.filter { it.status.isFailure }
        if (failedStages.isNotEmpty() && model.units.isEmpty()) {
            row(message("run.view.details.failed.stages")) { label(failedStages.joinToString(", ") { message("run.view.stage", it.scenario, it.action) }) }
        }
        val failed = model.failedHosts()
        if (model.finished && failed.isNotEmpty() && actions.playbookActions) {
            row { button(message("run.view.action.rerun.failed.named", failed.joinToString(", "))) { actions.rerunHosts(failed) } }
        }
    }

    private fun Panel.unit(unit: UnitRun) {
        row { label(unit.name).bold() }
        row(message("run.view.details.unit.root")) { label(unit.label) }
        row(message("run.view.details.status")) {
            icon(RunViewTexts.unitIcon(unit, model.finished))
            label(if (unit.problem != null) message("run.view.unit.not.started") else RunViewTexts.unitStatus(unit, model.finished))
        }
        unit.problem?.let { problem -> row(message("run.view.details.problem")) { text(StringUtil.escapeXmlEntities(problem).replace("\n", "<br>")) } }
        if (unit.stages.isNotEmpty()) {
            val failed = unit.stages.filter { it.status.isFailure }
            row(message("run.view.details.unit.stages")) {
                label(if (failed.isEmpty()) message("run.view.unit.stages.passed", unit.stages.size) else failed.joinToString(", ") { message("run.view.stage", it.scenario, it.action) })
            }
        }
        row(message("run.view.details.results")) { label(RunViewTexts.tally(unit.counts).ifEmpty { "\u2013" }) }
        unit.started?.let { started -> row(message("run.view.details.duration")) { label(RunViewTexts.duration((unit.ended ?: model.lastEvent) - started)) } }
        unit.exitCode?.takeIf { it != RunModel.STOPPED && it != RunModel.NOT_STARTED }?.let { row(message("run.view.details.exit")) { label(it.toString()) } }
        if (actions.unitActions && unit.started != null && unit.ended != null) row { button(message("run.view.action.run.unit", unit.name)) { actions.runUnits(listOf(unit.key)) } }
    }

    private fun Panel.stage(stage: StageRun) {
        row { label(message("run.view.stage", stage.scenario, stage.action)).bold() }
        row(message("run.view.details.status")) { icon(RunViewTexts.icon(stage.status)); label(stage.result ?: RunViewTexts.status(stage.status)) }
        row(message("run.view.details.results")) { label(RunViewTexts.tally(stage.counts).ifEmpty { "\u2013" }) }
        row(message("run.view.details.duration")) { label(RunViewTexts.duration((stage.ended ?: model.lastEvent) - stage.started)) }
        if (stage.action == "idempotence" && stage.changedTasks.isNotEmpty()) {
            row(message("run.view.details.idempotence")) { label(stage.changedTasks.joinToString(", ") { it.name }) }
        }
        val unit = model.units.firstOrNull { stage in it.stages }
        if (actions.stageActions && (unit == null || unit.ended != null)) {
            row { button(message("run.view.action.run.stage", stage.action)) { actions.runStage(stage.scenario, stage.action, unit?.key) } }
        }
    }

    private fun Panel.play(play: PlayRun) {
        row { label(play.name.ifEmpty { message("run.view.play.unnamed") }).bold() }
        row(message("run.view.details.status")) { icon(RunViewTexts.icon(play.status)); label(RunViewTexts.status(play.status)) }
        row(message("run.view.details.hosts")) { label(play.hosts.joinToString(", ")) }
        row(message("run.view.details.results")) { label(RunViewTexts.tally(play.counts).ifEmpty { "–" }) }
        row(message("run.view.details.duration")) { label(RunViewTexts.duration(play.duration(model.lastEvent))) }
        if (play.batches > 1) row(message("run.view.details.batches")) { label(play.batches.toString()) }
        if (play.noHostsMatched) row { label(message("run.view.play.no.hosts")) }
        if (play.notified.isNotEmpty()) {
            row(message("run.view.details.notified")) {
                label(play.notified.groupBy({ it.first }, { it.second }).entries.joinToString("; ") { (handler, hosts) -> "$handler (${hosts.joinToString(", ")})" })
            }
        }
        play.path?.let { source(it) }
        if (play.name.isNotEmpty() && actions.playbookActions) row { button(message("run.view.action.run.play")) { actions.runPart(play.name, null) } }
    }

    private fun Panel.task(task: TaskRun, play: PlayRun?) {
        row { label(task.shortName).bold() }
        row(message("run.view.details.status")) { icon(RunViewTexts.icon(task.status)); label(RunViewTexts.status(task.status)) }
        task.role?.let { row(message("run.view.details.role")) { label(it) } }
        task.action?.let { row(message("run.view.details.action")) { label(it) } }
        if (task.tags.isNotEmpty()) row(message("run.view.details.tags")) { label(task.tags.joinToString(", ")) }
        row(message("run.view.details.results")) { label(RunViewTexts.tally(task.counts).ifEmpty { "–" }) }
        task.ended?.let { row(message("run.view.details.duration")) { label(RunViewTexts.duration(it - task.started)) } }
        task.path?.let { source(it) }
        if (actions.playbookActions) row {
            if (!task.handler) button(message("run.view.action.start.at")) { actions.startAt(task.name) }
            if (task.role != null && play != null && play.name.isNotEmpty()) button(message("run.view.action.run.role", task.role)) { actions.runPart(play.name, task.role) }
        }
    }

    private fun Panel.host(host: HostRun, task: TaskRun?) {
        row { label(host.host).bold() }
        row(message("run.view.details.status")) { icon(RunViewTexts.icon(host.status)); label(RunViewTexts.status(host.status)) }
        task?.let { row(message("run.view.details.task")) { label(it.name) } }
        host.delegatedTo?.let { row(message("run.view.details.delegated")) { label(it) } }
        if (host.retries > 0) row(message("run.view.details.retries")) { label(host.retries.toString()) }
        if (host.polls > 0) row(message("run.view.details.polls")) { label(host.polls.toString()) }
        host.duration?.let { row(message("run.view.details.duration")) { label(RunViewTexts.duration(it)) } }
        messageRow(host.message)
        diffs(host.diff, host.host, task)
        if (host.truncated) row { comment(message("run.view.details.truncated")) }
        result(host.result)
        if (host.status.isFailure && actions.playbookActions) row { button(message("run.view.action.rerun.host", host.host)) { actions.rerunHosts(listOf(host.host)) } }
    }

    private fun Panel.item(item: ItemRun, task: TaskRun?, host: HostRun?) {
        row { label(item.label).bold() }
        row(message("run.view.details.status")) { icon(RunViewTexts.icon(item.status)); label(RunViewTexts.status(item.status)) }
        host?.let { row(message("run.view.details.host")) { label(it.host) } }
        messageRow(item.message)
        diffs(item.diff, host?.host.orEmpty(), task)
        result(item.result)
    }

    private fun Panel.recap() {
        row { label(message("run.view.recap")).bold() }
        val columns = arrayOf("host", "ok", "changed", "unreachable", "failed", "skipped", "rescued", "ignored").map { message("run.view.recap.$it") }
        val rows = model.stats.map { (host, s) -> arrayOf<Any>(host, s.ok, s.changed, s.unreachable, s.failures, s.skipped, s.rescued, s.ignored) }
        val table = JBTable(object : DefaultTableModel(rows.toTypedArray(), columns.toTypedArray()) {
            override fun isCellEditable(row: Int, column: Int) = false
        })
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
    }

    private fun Panel.messageRow(text: String?) {
        if (text.isNullOrBlank()) return
        row(message("run.view.details.message")) { text(StringUtil.escapeXmlEntities(text.trim()).replace("\n", "<br>")).align(AlignX.FILL) }
    }

    private fun Panel.source(path: String) {
        val (file, line) = split(path)
        row(message("run.view.details.source")) {
            link("${Path.of(file).fileName}:$line") { navigate(path) }.comment(file)
        }
    }

    private fun Panel.diffs(diffs: List<FileDiff>, host: String, task: TaskRun?) {
        if (diffs.isEmpty()) return
        row {
            diffs.forEachIndexed { index, diff ->
                val label = diff.afterHeader ?: diff.beforeHeader ?: message("run.view.diff.unnamed", index + 1)
                button(message("run.view.action.show.diff", label.substringAfterLast('/'))) { showDiff(diff, host, task) }
            }
        }
    }

    private fun Panel.result(result: Map<String, Any?>?) {
        if (result == null) return
        val text = Json.write(result, pretty = true)
        val editor = EditorTextField(EditorFactory.getInstance().createDocument(text), project, JsonFileType.INSTANCE, true, false)
        editor.setOneLineMode(false)
        // Inside the scrolling details the editor gets its preferred height: its lines, up to a screenful.
        val lines = text.count { it == '\n' } + 1
        editor.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(18) * (lines.coerceAtMost(MAX_RESULT_LINES) + 1))
        row { label(message("run.view.details.result")); link(message("run.view.action.copy")) { CopyPasteManager.getInstance().setContents(StringSelection(text)) } }
        row { cell(editor).align(Align.FILL) }.resizableRow()
    }

    /** Opens the IDE's diff viewer for one file change of a result. */
    fun showDiff(diff: FileDiff, host: String, task: TaskRun?) {
        val factory = DiffContentFactory.getInstance()
        val title = message("run.view.diff.title", task?.shortName.orEmpty(), host)
        val request = if (diff.prepared != null && diff.before == null && diff.after == null) {
            SimpleDiffRequest(title, factory.create(project, "", PlainTextFileType.INSTANCE), factory.create(project, diff.prepared, PlainTextFileType.INSTANCE), "", diff.afterHeader ?: "")
        } else {
            SimpleDiffRequest(
                title,
                factory.create(project, diff.before.orEmpty()),
                factory.create(project, diff.after.orEmpty()),
                diff.beforeHeader ?: message("run.view.diff.before"),
                diff.afterHeader ?: message("run.view.diff.after"),
            )
        }
        DiffManager.getInstance().showDiff(project, request)
    }

    /** Opens `path:line` in the editor (through [hostPath] for a container path); false when there is no such file. */
    fun navigate(path: String): Boolean {
        val (file, line) = split(path)
        val local = hostPath(file) ?: return false
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(local) ?: return false
        OpenFileDescriptor(project, virtual, (line - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }

    companion object {
        private const val MAX_RESULT_LINES = 40

        /** `path:line` as path and line (line 1 when there is none). */
        fun split(path: String): Pair<String, Int> {
            val at = path.lastIndexOf(':')
            val line = if (at > 0) path.substring(at + 1).toIntOrNull() else null
            return if (line != null) path.substring(0, at) to line else path to 1
        }
    }
}
