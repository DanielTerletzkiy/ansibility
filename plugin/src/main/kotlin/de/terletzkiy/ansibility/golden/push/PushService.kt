package de.terletzkiy.ansibility.golden.push

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.compare.RoleCompare
import de.terletzkiy.ansibility.golden.compare.RoleDiffUi
import de.terletzkiy.ansibility.golden.sync.FileOp
import de.terletzkiy.ansibility.golden.sync.FileStamp
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleWriter
import de.terletzkiy.ansibility.golden.sync.SyncOps
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.run.molecule.MoleculeBatchLauncher
import de.terletzkiy.ansibility.run.molecule.MoleculeScenarios
import de.terletzkiy.ansibility.run.molecule.MoleculeSpec
import de.terletzkiy.ansibility.run.molecule.MoleculeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What a push did (plan amendment R24, D188–D190); for the caller and tests. */
class PushOutcome internal constructor(
    val status: Status,
    /** The writer's result per written key (the copy's role directory, or the roles directory a role is created in). */
    val results: Map<VirtualFile, WriteResult>,
    /** The notice shown when nothing was written, else null. */
    @get:Nls val message: String?,
    /** The notification shown after a push, else null. */
    val notification: Notification?,
    /** Undo of the push while its commands are on top of the global undo stack, else null. */
    val undo: PushUndo?,
    /** What "Run Molecule Tests" runs (empty: no such button). */
    val moleculeTargets: List<MoleculeTarget>,
) {
    enum class Status {
        /** The files were written (some roots may have had nothing to do). */
        PUSHED,

        /** Every ticked root was the same as the source already. */
        NOTHING_TO_DO,

        /** The user declined the confirmation of binary files. */
        CANCELLED,

        /** A precondition failed in some root (stale, unsaved, read-only, gone): nothing was written anywhere. */
        NOT_WRITTEN,

        /** A source file changed while the push read it, or the source is gone: nothing was written. */
        SOURCE_CHANGED,
    }

    override fun toString(): String = "PushOutcome($status, ${results.values.map { it.status }}, $message)"
}

/**
 * Push Role to Repos (plan amendment R24, D188–D191): makes ticked copies of a role byte-identical to a source copy,
 * and creates the role in ticked roots without it.
 *
 * - **Dialog** ([PushUi.choose]): rows from [PushRows], computed in the background while the dialog shows; nothing
 *   ticked at first.
 * - **Push** ([execute]): what the dialog showed, or nothing (S4): the source and every ticked copy are refreshed
 *   from disk (S10), and when any file of them changed since the dialog computed its rows, nothing is written ("open
 *   it again"). Else `SyncOps.mirror` over each row's plan (D189: changed files replaced, missing ones added, extra
 *   ones deleted when asked; sensitive files only when opted in, D191; `molecule/` always, U4; each source file read
 *   once for all roots, P5), then ONE `RoleWriter.multi` over all roots: every precondition first (any stale, unsaved,
 *   read-only, linked or vanished target writes nothing anywhere), then a Local History label and one global-undo
 *   command per root, named uniquely per push ("Push web to falcon (#3)", S2), which the platform undoes without
 *   asking (`DO_NOT_REQUEST_CONFIRMATION`): the notification's Undo asks once for all of them ([PushUndo]). An
 *   Edit › Undo of one repo's command is noticed: a notification names the repos still pushed and offers "Undo the
 *   Rest" (U5). A root without the role gets `<roles dir>/<role>` with every source file created.
 * - **Afterwards** (D190): a notification "Pushed web from golden to falcon, heron: 4 changed, 2 added, 1 deleted"
 *   with Undo ([PushUndo]), Run Molecule Tests (pushed copies with scenarios, when Molecule runs are on) and Commit…
 *   (the platform's commit UI, when the VCS module is there).
 * - **R23 checks** (X124): when a pushed copy's `defaults/`, `meta/argument_specs.y(a)ml` or `meta/main.y(a)ml`
 *   changed, R23's argument_specs default checks of those copies run in the background ([PushSpecChecks]) and their
 *   findings are one more line of the notification ("argument_specs checks: heron web: 1 spec default differs from the role
 *   default (ANS-S003)"); nothing when they are clean. The notification waits for them at most [specCheckBudget]; later
 *   findings come in a notification of their own.
 *
 * Never stages, commits or pushes in git, never decrypts, and logs no content (D190). R9's D42 is extended: writes
 * across roots happen only in Align and Push.
 */
@Service(Service.Level.PROJECT)
class PushService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    @Volatile
    private var latestUndo: PushUndo? = null

    /** The notification of the latest push (its Undo), and the one naming the repos an Edit › Undo left pushed (U5). */
    private var latestNotification: Notification? = null
    private var partialNotification: Notification? = null

    /** Numbers the pushes of this session, so every push's commands have names of their own (S2). */
    private val sequence = AtomicInteger()

    private val listening = AtomicBoolean()

    /** The model of the Push dialog for [source]: its rows start computing in the background now. */
    fun model(source: RoleCopy): PushModel = PushModel(
        project,
        source,
        scope.async {
            try {
                PushRows.compute(project, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PushRows.logFailure(e)
                throw e
            }
        },
    )

    /**
     * The whole push of [source]: the dialog ([PushUi.choose]) on the EDT, then [execute] in the background. Null when
     * the dialog was cancelled or nothing was ticked.
     */
    @RequiresEdt
    fun push(source: RoleCopy): Deferred<PushOutcome>? {
        val model = model(source)
        val choice = try {
            PushUi.getInstance().choose(project, model)
        } finally {
            if (!model.rows.isCompleted) model.rows.cancel()
        }
        if (choice == null || choice.rows.isEmpty()) return null
        return scope.async { executeReporting(source, choice) }
    }

    /** [execute], with any failure told and logged (its class only) instead of dropped with the result (S11). */
    private suspend fun executeReporting(source: RoleCopy, choice: PushChoice): PushOutcome = try {
        execute(source, choice)
    } catch (e: Throwable) {
        if (e is CancellationException || e is ControlFlowException) throw e
        LOG.warn("The push of a role failed (${e.javaClass.name})")
        onEdt { refused(PushOutcome.Status.NOT_WRITTEN, message("push.failed", source.name, source.root.displayName)) }
    }

    /**
     * The dialog's [Compare] of [row]: the source on the left, the copy on the right (`RoleCompare.chain`, shown through
     * `RoleDiffUi`) in the dialog's [modality], so the diff opens over the modal dialog instead of after it.
     */
    fun compare(source: RoleCopy, row: PushRow, modality: ModalityState): Job? {
        val copy = row.copy ?: return null
        return scope.launch(modality.asContextElement()) {
            val chain = RoleCompare.getInstance(project).chain(source, copy, null)
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val ui = RoleDiffUi.getInstance()
                chain.notice?.let { ui.inform(project, it) }
                chain.chain()?.let { ui.show(project, it) }
            }
        }
    }

    /** Pushes [source] to [choice]'s rows (see the class comment). Any context; never blocks the EDT. */
    suspend fun execute(source: RoleCopy, choice: PushChoice): PushOutcome {
        if (choice.rows.isEmpty()) return outcome(PushOutcome.Status.NOTHING_TO_DO)
        val prepared = try {
            withBackgroundProgress(project, message("push.progress", source.name, choice.rows.joinToString(", ") { it.name })) {
                prepare(source, choice)
            }
        } catch (e: SyncOps.SourceChanged) {
            val text = message("push.sourceChanged", source.name, source.root.displayName, list(e.relPaths))
            return onEdt { refused(PushOutcome.Status.SOURCE_CHANGED, text) }
        } catch (_: SourceGone) {
            return onEdt { refused(PushOutcome.Status.SOURCE_CHANGED, message("push.sourceGone", source.name, source.root.displayName)) }
        } catch (e: ChangedSinceDialog) {
            val status = if (e.source) PushOutcome.Status.SOURCE_CHANGED else PushOutcome.Status.NOT_WRITTEN
            return onEdt { refused(status, message("push.changedSinceDialog", source.name, e.where, list(e.relPaths))) }
        }
        val written = onEdt { write(source, prepared, choice.options) }
        val notification = written.outcome.notification ?: return written.outcome
        if (written.checks.isEmpty()) {
            onEdt { if (!project.isDisposed) PushUi.getInstance().notify(project, notification) }
            return written.outcome
        }
        // X124: the R23 checks of the pushed copies, in the service's scope so that a slow run still reports later.
        val checks = scope.async { PushSpecChecks.run(project, written.checks) }
        val findings = withTimeoutOrNull(specCheckBudget) { checks.await() }
        onEdt {
            if (project.isDisposed) return@onEdt
            if (findings != null) PushSpecChecks.line(findings)?.let { notification.setContent(notification.content + "<br>" + StringUtil.escapeXmlEntities(it)) }
            PushUi.getInstance().notify(project, notification)
        }
        if (findings == null) {
            scope.launch {
                val late = PushSpecChecks.line(checks.await()) ?: return@launch
                onEdt { if (!project.isDisposed) PushUi.getInstance().notify(project, notification(StringUtil.escapeXmlEntities(late), NotificationType.INFORMATION)) }
            }
        }
        return written.outcome
    }

    /** What the EDT step of a push did: the outcome (its notification not shown yet) and the copies to check (X124). */
    private class Written(val outcome: PushOutcome, val checks: List<PushSpecChecks.Target> = emptyList())

    // ---------------------------------------------------------------- preparing (background)

    /** The operations of one ticked root. */
    private class Prepared(val row: PushRow, val key: VirtualFile, val ops: List<FileOp>, val binaries: List<String>)

    private class SourceGone : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** A file of [where] (the [source] side's root, or a ticked repo) changed since the dialog computed its rows (S4). */
    private class ChangedSinceDialog(val where: String, val source: Boolean, val relPaths: List<String>) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * The operations of every ticked row, from the plan the dialog showed (S4): after a refresh from disk (S10), a file
     * of the source or of a ticked copy that changed since then stops the push ([ChangedSinceDialog]); each source
     * file is read once for all roots (P5).
     */
    private suspend fun prepare(source: RoleCopy, choice: PushChoice): List<Prepared> {
        if (!readAction { source.dir.isValid && source.dir.isDirectory }) throw SourceGone()
        refresh(listOf(source.dir) + choice.rows.mapNotNull { it.copy?.dir }, recursive = true)
        refresh(choice.rows.mapNotNull { row -> row.rolesDir.takeIf { row.copy == null } }, recursive = false)
        val deleteExtra = choice.options.deleteExtra
        val shared = SyncOps.SourceBytes()
        val checked = HashSet<RoleFilePlan>()
        return choice.rows.map { row ->
            val plan = row.plan.withSensitive(choice.options.includeSensitive)
            // A source without any file would delete everything in the target: never.
            if (plan.sourceFileCount == 0) throw SourceGone()
            if (checked.add(row.plan)) checkUnchanged(source, row)
            hookForTests?.afterPlan(row)
            val copy = row.copy
            if (copy != null) {
                Prepared(row, copy.dir, SyncOps.mirror(project, plan, deleteExtra, shared), binaries(plan, deleteExtra))
            } else {
                val rolesDir = row.rolesDir ?: error("a row without the role has a roles directory")
                // Every source file below `<roles dir>/<role>`: the role directory is created with them, in the same command.
                val ops = SyncOps.mirror(project, plan, deleteExtra = false, shared = shared).map { op ->
                    val create = op as FileOp.Create
                    FileOp.Create("${source.name}/${create.relPath}", create.content, create.executable, FileStamp.ABSENT)
                }
                Prepared(row, rolesDir, ops, emptyList())
            }
        }
    }

    /** [ChangedSinceDialog] when a file of [row]'s plan changed on either side since the dialog computed it (S4). */
    private suspend fun checkUnchanged(source: RoleCopy, row: PushRow) {
        val changes = row.plan.changesSince(project)
        if (changes.source.isNotEmpty()) throw ChangedSinceDialog(source.root.displayName, true, changes.source)
        if (changes.target.isNotEmpty()) throw ChangedSinceDialog(row.name, false, changes.target)
    }

    /**
     * Brings the VFS up to date with the disk below [dirs] (S10: a `git checkout` while the dialog was open), so the
     * check and the writer see the files as they are; a roles directory a role is created in needs its own listing
     * only ([recursive] false). Synchronous, on a background thread, outside any modal dialog (the refresh's events
     * need the EDT in a non-modal state).
     */
    private suspend fun refresh(dirs: List<VirtualFile>, recursive: Boolean) = withContext(Dispatchers.IO) {
        val local = dirs.filter { it.isValid && it.isInLocalFileSystem }.distinct()
        if (local.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, recursive, true, *local.toTypedArray())
    }

    /** The binary files whose old content Undo cannot restore: changed ones, and deleted ones with [deleteExtra]. */
    private fun binaries(plan: RoleFilePlan, deleteExtra: Boolean): List<String> =
        plan.included.filter { it.binary && (it.kind == PlanKind.CHANGED || (deleteExtra && it.kind == PlanKind.ONLY_IN_TARGET)) }.map { it.relPath }

    // ---------------------------------------------------------------- writing (EDT)

    private fun write(source: RoleCopy, prepared: List<Prepared>, options: PushOptions): Written {
        if (project.isDisposed) return Written(outcome(PushOutcome.Status.CANCELLED))
        val ui = PushUi.getInstance()
        val binaries = prepared.flatMap { p -> p.binaries.map { message("push.rootPath", p.row.name, it) } }
        if (binaries.isNotEmpty() && !ui.confirmBinary(project, source.name, binaries)) return Written(outcome(PushOutcome.Status.CANCELLED))
        hookForTests?.beforeWrite()

        unsavedTargets(prepared)?.let { return Written(refused(PushOutcome.Status.NOT_WRITTEN, it)) }
        val perRoot = LinkedHashMap<VirtualFile, List<FileOp>>()
        val names = HashMap<VirtualFile, String>()
        for (p in prepared) {
            perRoot[p.key] = p.ops
            names[p.key] = p.row.name
        }
        // S2: this push's own command names ("Push web to falcon (#3)"), so Undo never takes another push's command.
        val number = sequence.incrementAndGet().toString()
        val results = RoleWriter.multi(
            project,
            perRoot,
            labelPrefix = message("push.label", source.name, source.root.displayName),
            // D190: the notification's Undo asks once for every root (PushUndo), so the platform must not ask per root.
            confirmation = UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
            nameOf = { names[it] ?: it.name },
            commandOf = { name -> message("push.command", source.name, name, number) },
        )

        if (results.values.any { it.status in BLOCKING }) {
            val reasons = prepared.filter { results[it.key]?.status in BLOCKING && results[it.key]?.status != WriteResult.Status.NOT_RUN }
                .joinToString("\n") { message("push.rootReason", it.row.name, results.getValue(it.key).message.orEmpty()) }
            return Written(refused(PushOutcome.Status.NOT_WRITTEN, message("push.notWritten", source.name, reasons), results))
        }
        val outcome = finish(source, prepared, results, options)
        val checks = if (outcome.status == PushOutcome.Status.PUSHED) specCheckTargets(source, prepared, results) else emptyList()
        // The checks read PSI in the background: commit the documents the push wrote first.
        if (checks.isNotEmpty()) PsiDocumentManager.getInstance(project).commitAllDocuments()
        return Written(outcome, checks)
    }

    /**
     * X124: the pushed copies whose `defaults/`, `meta/argument_specs.y(a)ml` or `meta/main.y(a)ml` changed (written,
     * created or deleted), in push order. EDT.
     */
    private fun specCheckTargets(source: RoleCopy, prepared: List<Prepared>, results: Map<VirtualFile, WriteResult>): List<PushSpecChecks.Target> =
        prepared.mapNotNull { p ->
            val result = results[p.key]?.takeIf { it.applied } ?: return@mapNotNull null
            val prefix = if (p.row.copy == null) "${source.name}/" else ""
            val changed = (result.written + result.created + result.deleted).map { it.removePrefix(prefix) }
            if (changed.none(PushSpecChecks::triggers)) return@mapNotNull null
            val dir = p.row.copy?.dir ?: p.key.findChild(source.name) ?: return@mapNotNull null
            PushSpecChecks.Target(p.row.name, source.name, dir)
        }

    /**
     * The target files with unsaved changes (D190): a push would replace what the user typed there, so they count as
     * stale, and nothing is written anywhere. Null when there are none.
     */
    @Nls
    private fun unsavedTargets(prepared: List<Prepared>): String? {
        val documents = FileDocumentManager.getInstance()
        val unsaved = prepared.flatMap { p ->
            p.ops.filter { it !is FileOp.Create }.mapNotNull { op ->
                p.key.findFileByRelativePath(op.relPath)?.takeIf { it.isValid && documents.isFileModified(it) }?.let { message("push.rootPath", p.row.name, op.relPath) }
            }
        }
        if (unsaved.isEmpty()) return null
        return message("push.unsaved", unsaved.size, list(unsaved))
    }

    /** The notification after the writer ran in every root (D190), not shown yet ([execute] shows it). EDT. */
    private fun finish(source: RoleCopy, prepared: List<Prepared>, results: Map<VirtualFile, WriteResult>, options: PushOptions): PushOutcome {
        val applied = prepared.filter { p -> results[p.key]?.let { it.applied && it.changedAnything } == true }
        val same = prepared.filter { results[it.key]?.status == WriteResult.Status.NOTHING_TO_DO }
        val failed = prepared.flatMap { p ->
            results[p.key]?.skipped.orEmpty().filter { it.reason == WriteResult.SkipReason.FAILED }.map { message("push.rootPath", p.row.name, it.relPath) }
        }
        if (applied.isEmpty() && failed.isEmpty()) {
            val text = message("push.notification.same", source.name, same.joinToString(", ") { it.row.name }, source.root.displayName)
            val notification = notification(StringUtil.escapeXmlEntities(text), NotificationType.INFORMATION)
            return PushOutcome(PushOutcome.Status.NOTHING_TO_DO, results, null, notification, null, emptyList())
        }
        val all = applied.map { results.getValue(it.key) }
        val lines = ArrayList<String>()
        if (applied.isNotEmpty()) {
            val summary = listOfNotNull(
                all.sumOf { it.written.size }.takeIf { it > 0 }?.let { message("writer.summary.written", it) },
                all.sumOf { it.created.size }.takeIf { it > 0 }?.let { message("writer.summary.created", it) },
                all.sumOf { it.deleted.size }.takeIf { it > 0 }?.let { message("writer.summary.deleted", it) },
            ).joinToString(", ")
            lines += message("push.notification.pushed", source.name, source.root.displayName, applied.joinToString(", ") { it.row.name }, summary)
            if (same.isNotEmpty()) lines += message("push.notification.unchanged", same.joinToString(", ") { it.row.name })
        }
        val notUndoable = all.sumOf { it.notUndoable.size }
        if (notUndoable > 0) lines += message("push.notification.notUndoable", notUndoable)
        if (failed.isNotEmpty()) lines += message("push.notification.failed", list(failed))
        if (!options.includeSensitive && prepared.any { it.row.counts(options).excluded > 0 }) lines += message("push.notification.sensitive")

        latestUndo?.current = false
        partialNotification?.expire()
        partialNotification = null
        val labels = applied.map { message("push.label", source.name, source.root.displayName) + " " + it.row.name }
        val undo = PushUndo.capture(
            project, applied.map { results.getValue(it.key).commandName }, applied.map { it.row.name }, labels,
        ) { names -> message("push.undo.question", source.name, names.joinToString(", ")) }
        latestUndo = undo
        val molecule = moleculeTargets(source, applied)
        val type = if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
        val notification = notification(lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }, type)
        latestNotification = notification
        if (undo != null) {
            listenForEditorUndo()
            notification.addAction(NotificationAction.create(message("push.action.undo")) { _, n -> undo(undo, n) })
        } else if (applied.isNotEmpty()) {
            notification.addAction(NotificationAction.createSimple(message("push.action.history")) { showLocalHistory() })
        }
        if (molecule.isNotEmpty()) {
            notification.addAction(NotificationAction.createSimple(message("push.action.molecule")) { MoleculeBatchLauncher.run(project, molecule) })
        }
        if (applied.isNotEmpty() && ActionManager.getInstance().getAction(COMMIT_ACTION_ID) != null) {
            notification.addAction(NotificationAction.createSimple(message("push.action.commit")) { invokeAction(COMMIT_ACTION_ID) })
        }
        val status = if (applied.isEmpty()) PushOutcome.Status.NOT_WRITTEN else PushOutcome.Status.PUSHED
        return PushOutcome(status, results, null, notification, undo, molecule)
    }

    /** "Run Molecule Tests": the pushed copies with a scenario, when Molecule runs are on (D140, D152). */
    private fun moleculeTargets(source: RoleCopy, applied: List<Prepared>): List<MoleculeTarget> {
        if (!MoleculeScenarios.runsTests(project)) return emptyList()
        return applied.mapNotNull { p ->
            val dir = p.row.copy?.dir ?: p.key.findChild(source.name) ?: return@mapNotNull null
            if (!MoleculeScenarios.hasScenarios(dir)) return@mapNotNull null
            MoleculeTarget(MoleculeSpec(dir.path), source.name, p.row.name)
        }
    }

    /**
     * The notification's Undo (and "Undo the Rest", U5): reverts what is still pushed while it is on top; otherwise
     * offers Local History.
     */
    private fun undo(undo: PushUndo, notification: Notification) {
        val labels = undo.labels
        when (undo.undo()) {
            PushUndo.Result.UNDONE -> expireAll(undo, notification)
            PushUndo.Result.DECLINED -> Unit
            PushUndo.Result.NOT_ON_TOP -> {
                expireAll(undo, notification)
                cannotUndo(message("push.undo.notOnTop", list(labels)))
            }
            PushUndo.Result.INCOMPLETE -> {
                expireAll(undo, notification)
                cannotUndo(message("push.undo.incomplete", list(labels)))
            }
        }
    }

    /** Expires [notification] and, for the latest push, its result and "Undo the Rest" notifications. */
    private fun expireAll(undo: PushUndo, notification: Notification) {
        notification.expire()
        if (undo === latestUndo) {
            latestNotification?.expire()
            partialNotification?.expire()
            partialNotification = null
        }
    }

    /**
     * U5: Edit › Undo (Ctrl+Z) reverts one repo's command of a push at a time, without a question. The platform runs
     * an undo as a command named after what it undoes, so a [CommandListener] sees "Undo Push web to tern (#3)" finish
     * while the undo is in progress (public API: `UndoManager.isUndoInProgress`). Subscribed once, after the first push.
     */
    private fun listenForEditorUndo() {
        if (!listening.compareAndSet(false, true)) return
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandFinished(event: CommandEvent) {
                if (event.project != project || project.isDisposed) return
                val undo = latestUndo?.takeIf { it.current } ?: return
                val name = event.commandName ?: return
                val manager = UndoManager.getInstance(project)
                val isUndo = when {
                    manager.isUndoInProgress -> true
                    manager.isRedoInProgress -> false
                    else -> return
                }
                if (undo.observe(name, isUndo)) editorUndone(undo)
            }
        })
    }

    /**
     * After an Edit › Undo or Redo of some of [undo]'s commands: "Undid the push to tern only; falcon, heron are still
     * pushed" with "Undo the Rest" (U5), replacing the previous such notification. Nothing when no repo or every repo
     * was undone. EDT.
     */
    private fun editorUndone(undo: PushUndo) {
        partialNotification?.expire()
        partialNotification = null
        val undone = undo.undoneNames
        val still = undo.remainingNames
        if (undone.isEmpty()) return
        if (still.isEmpty()) {
            undo.current = false
            latestNotification?.expire()
            return
        }
        val text = message("push.undo.partial", undone.joinToString(", "), still.joinToString(", "), still.size)
        val notification = notification(StringUtil.escapeXmlEntities(text), NotificationType.INFORMATION)
        notification.addAction(NotificationAction.create(message("push.action.undoRest")) { _, n -> undo(undo, n) })
        partialNotification = notification
        PushUi.getInstance().notify(project, notification)
    }

    override fun dispose() = Unit

    private fun cannotUndo(@Nls text: String) {
        val notification = notification(StringUtil.escapeXmlEntities(text), NotificationType.WARNING)
        notification.addAction(NotificationAction.createSimpleExpiring(message("push.action.history")) { showLocalHistory() })
        PushUi.getInstance().notify(project, notification)
    }

    /** Local History of the project, where the label of each pushed root ("Before pushing web from golden to falcon") is the revert point. */
    private fun showLocalHistory() {
        invokeAction(LOCAL_HISTORY_ACTION_ID)
    }

    /** Runs the platform action [id] with the project's data context, when it is registered and enabled. */
    private fun invokeAction(id: String): Boolean {
        if (project.isDisposed) return false
        val action = ActionManager.getInstance().getAction(id) ?: return false
        val event = AnActionEvent.createEvent(
            action, SimpleDataContext.getProjectContext(project), action.templatePresentation.clone(), ActionPlaces.NOTIFICATION, ActionUiKind.NONE, null,
        )
        ActionUtil.updateAction(action, event)
        if (!event.presentation.isEnabled) return false
        ActionUtil.performAction(action, event)
        return true
    }

    // ---------------------------------------------------------------- results

    /** Tells [text] (nothing was written) and returns the outcome. EDT. */
    private fun refused(status: PushOutcome.Status, @Nls text: String, results: Map<VirtualFile, WriteResult> = emptyMap()): PushOutcome {
        if (!project.isDisposed) PushUi.getInstance().inform(project, text)
        return PushOutcome(status, results, text, null, null, emptyList())
    }

    /** [block] on the EDT, outside any modal dialog. */
    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { block() }

    private fun outcome(status: PushOutcome.Status) = PushOutcome(status, emptyMap(), null, null, null, emptyList())

    private fun notification(@Nls content: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance().getNotificationGroup(PushUi.NOTIFICATION_GROUP).createNotification(content, type)

    private val WriteResult.changedAnything: Boolean get() = written.isNotEmpty() || created.isNotEmpty() || deleted.isNotEmpty()

    @Nls
    private fun list(paths: List<String>): String =
        if (paths.size <= MAX_LISTED) paths.joinToString(", ")
        else message("writer.list.more", paths.take(MAX_LISTED).joinToString(", "), paths.size - MAX_LISTED)

    /** Test hooks between the steps of [execute] ([hookForTests]). */
    interface Hook {
        /** After the plan of [row] (background), before its operations are read. */
        fun afterPlan(row: PushRow) {}

        /** On the EDT, right before the writer runs. */
        fun beforeWrite() {}
    }

    @TestOnly
    @Volatile
    internal var hookForTests: Hook? = null

    /** How long the notification waits for the R23 checks of the pushed copies (X124) before it shows without them. */
    @Volatile
    internal var specCheckBudget: Duration = SPEC_CHECK_BUDGET

    companion object {
        private val LOG = logger<PushService>()

        /** The platform's commit UI (vcs-impl `VcsActions.xml`); absent without the VCS module. */
        const val COMMIT_ACTION_ID: String = "CheckinProject"

        /** The project's Local History (lvcs-impl). */
        const val LOCAL_HISTORY_ACTION_ID: String = "LocalHistory.ShowProjectHistory"

        private const val MAX_LISTED = 5

        /** The default of [specCheckBudget]. */
        val SPEC_CHECK_BUDGET: Duration = 5.seconds

        /** Writer statuses that stop the whole push (nothing written anywhere). */
        private val BLOCKING = setOf(
            WriteResult.Status.STALE, WriteResult.Status.READ_ONLY, WriteResult.Status.INVALID_TARGET, WriteResult.Status.NOT_RUN,
        )

        fun getInstance(project: Project): PushService = project.service()
    }
}
