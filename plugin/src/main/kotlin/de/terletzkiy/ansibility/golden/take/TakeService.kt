package de.terletzkiy.ansibility.golden.take

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.sync.FileOp
import de.terletzkiy.ansibility.golden.sync.PlanEntry
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleWriter
import de.terletzkiy.ansibility.golden.sync.SyncOps
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

/** Which way a single-file take goes (plan amendment R24, X121). */
enum class TakeDirection {
    /** "Take Golden's Version": golden's file replaces this copy's (created when only golden has it, deleted when golden has none). */
    FROM_GOLDEN,

    /** "Take This into Golden": this copy's file replaces golden's (created there, or deleted from golden). */
    INTO_GOLDEN,
}

/** What a single-file take did (plan amendment R24, X121); for the caller and tests. */
class TakeOutcome internal constructor(
    val status: Status,
    /** The writer's result when it ran, else null. */
    val result: WriteResult?,
    /** What the user was told (a notice or the status-bar note), or null. */
    @get:Nls val message: String?,
) {
    enum class Status {
        /** The file was written, created or deleted. */
        TAKEN,

        /** The path does not differ (or no longer exists on either side): nothing to take. */
        SAME,

        /** The user declined the delete or the key/vault confirmation. */
        DECLINED,

        /** No golden copy, the golden copy itself, no file path: nothing done. */
        NOT_APPLICABLE,

        /** A precondition failed (a changed, unsaved or read-only file, a changed source): nothing was written. */
        NOT_WRITTEN,
    }

    override fun toString(): String = "TakeOutcome($status, ${result?.status}, $message)"
}

/**
 * "Take Golden's Version" and "Take This into Golden" (plan amendment R24, X121): one file of a role copy, one way,
 * no dialog (except the two confirmations below), undoable.
 *
 * 1. **Plan** in the background: both role directories refreshed from disk first (S10), then
 *    [RoleFilePlan.computePath] for exactly that path, `molecule/` included (the user picked the file), key and vault
 *    files included (they are confirmed next). Nothing differs: a notice.
 * 2. **Confirm** on the EDT: a delete (the destination has a file the other side does not have) always asks; writing or
 *    creating a key or vault file asks too, and it is then copied as it is, never decrypted or shown. A file with unsaved changes is not
 *    deleted (save or discard first); a write over unsaved changes goes through the document, so Undo restores them.
 * 3. **Write**: `SyncOps.mirror` over the one-entry plan (byte-identical, the source's executable bit, the stamps of
 *    the plan), then `RoleWriter.apply` to the destination role directory: a Local History label ("Before taking
 *    tasks/main.yml from golden into falcon"), ONE undoable command ("Take tasks/main.yml from golden into falcon"),
 *    stale and read-only checks first. Binary content and binary deletions cannot be undone (git can); the note says
 *    so.
 *
 * Never stages or commits, never decrypts, logs no content. R9's D42 is extended to these two actions: a take writes
 * the other root's file only on this explicit, labelled request.
 */
@Service(Service.Level.PROJECT)
class TakeService(private val project: Project, private val scope: CoroutineScope) {
    /** "Take Golden's Version" of [target] (a file path in a copy that has a golden copy). */
    fun takeGoldens(target: GoldenTarget): Job = scope.launch { take(target, TakeDirection.FROM_GOLDEN) }

    /** "Take This into Golden" of [target]. */
    fun takeIntoGolden(target: GoldenTarget): Job = scope.launch { take(target, TakeDirection.INTO_GOLDEN) }

    /** Takes [target]'s file in [direction] (see the class comment). Any context; never blocks the EDT. */
    suspend fun take(target: GoldenTarget, direction: TakeDirection): TakeOutcome {
        val relPath = GoldenTargets.normalize(target.relPath) ?: return TakeOutcome(TakeOutcome.Status.NOT_APPLICABLE, null, null)
        val golden = readAction { RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) }
        if (golden == null || golden.dir == target.copy.dir) return TakeOutcome(TakeOutcome.Status.NOT_APPLICABLE, null, null)
        val source = if (direction == TakeDirection.FROM_GOLDEN) golden else target.copy
        val destination = if (direction == TakeDirection.FROM_GOLDEN) target.copy else golden
        val texts = TakeTexts(direction, relPath, source, destination)

        val plan = withBackgroundProgress(project, texts.progress) {
            withContext(Dispatchers.IO) {
                val dirs = listOf(source.dir, destination.dir).filter { it.isValid && it.isInLocalFileSystem }
                if (dirs.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *dirs.toTypedArray())
            }
            RoleFilePlan.computePath(project, source.dir, destination.dir, relPath, PlanOptions(ignoreMolecule = false, includeSensitive = true))
        }
        val entry = plan.entries.singleOrNull() ?: return onEdt { told(TakeOutcome.Status.SAME, texts.same, null) }

        val refusal = onEdt { confirm(entry, texts) }
        if (refusal != null) return refusal

        val ops = try {
            SyncOps.mirror(project, plan, deleteExtra = true)
        } catch (_: SyncOps.SourceChanged) {
            return onEdt { told(TakeOutcome.Status.NOT_WRITTEN, texts.sourceChanged, null) }
        }
        return onEdt { write(destination, ops, entry, texts) }
    }

    /** The confirmations and the unsaved-delete guard; null to go on, else the outcome. EDT. */
    private fun confirm(entry: PlanEntry, texts: TakeTexts): TakeOutcome? {
        if (project.isDisposed) return TakeOutcome(TakeOutcome.Status.NOT_APPLICABLE, null, null)
        val ui = TakeUi.getInstance()
        if (entry.kind == PlanKind.ONLY_IN_TARGET) {
            // A delete copies no content: its own question is the explicit confirmation, for key and vault files too.
            val file = entry.targetFile
            if (file != null && file.isValid && FileDocumentManager.getInstance().isFileModified(file)) {
                return told(TakeOutcome.Status.NOT_WRITTEN, texts.unsaved, null)
            }
            if (!ui.confirmDelete(project, texts.title, texts.delete(entry.binary))) return TakeOutcome(TakeOutcome.Status.DECLINED, null, null)
        } else if (entry.sensitive && !ui.confirmSensitive(project, texts.title, texts.sensitive)) {
            return TakeOutcome(TakeOutcome.Status.DECLINED, null, null)
        }
        return null
    }

    /** One undoable command in the destination role directory, then the note. EDT. */
    private fun write(destination: RoleCopy, ops: List<FileOp>, entry: PlanEntry, texts: TakeTexts): TakeOutcome {
        if (project.isDisposed) return TakeOutcome(TakeOutcome.Status.NOT_APPLICABLE, null, null)
        val result = RoleWriter.apply(project, destination.dir, ops, texts.label, texts.command)
        if (!result.applied) {
            val reason = result.message
            return told(TakeOutcome.Status.NOT_WRITTEN, if (reason != null) texts.notWritten(reason) else texts.failed, result)
        }
        if (result.skipped.any { it.reason == WriteResult.SkipReason.FAILED }) {
            return told(TakeOutcome.Status.NOT_WRITTEN, texts.failed, result)
        }
        val ui = TakeUi.getInstance()
        val done = if (entry.kind == PlanKind.ONLY_IN_TARGET) texts.deleted else texts.taken
        if (result.notUndoable.isNotEmpty()) {
            val text = texts.notUndoable(done)
            ui.inform(project, text)
            return TakeOutcome(TakeOutcome.Status.TAKEN, result, text)
        }
        ui.done(project, done)
        return TakeOutcome(TakeOutcome.Status.TAKEN, result, done)
    }

    /** Tells [text] (a notice) and returns the outcome. EDT. */
    private fun told(status: TakeOutcome.Status, @Nls text: String, result: WriteResult?): TakeOutcome {
        if (!project.isDisposed) TakeUi.getInstance().inform(project, text)
        return TakeOutcome(status, result, text)
    }

    /** [block] on the EDT, outside any modal dialog. */
    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { block() }

    companion object {
        fun getInstance(project: Project): TakeService = project.service()
    }
}

/** The texts of one take: "tasks/main.yml", from [source] into [destination]. */
internal class TakeTexts(val direction: TakeDirection, val relPath: String, val source: RoleCopy, val destination: RoleCopy) {
    private val from: String get() = source.root.displayName
    private val into: String get() = destination.root.displayName

    @get:Nls
    val title: String
        get() = message(if (direction == TakeDirection.FROM_GOLDEN) "action.take.goldens.menu" else "action.take.into.menu")

    @get:Nls
    val progress: String get() = message("take.progress", relPath, from, into)

    /** The Local History label, the revert point. */
    @get:Nls
    val label: String get() = message("take.label", relPath, from, into)

    /** The undoable command ("Undo Take tasks/main.yml from golden into falcon"). */
    @get:Nls
    val command: String get() = message("take.command", relPath, from, into)

    @get:Nls
    val same: String get() = message("take.same", relPath, from, into)

    @get:Nls
    val taken: String get() = message("take.done", relPath, from, into)

    @get:Nls
    val deleted: String get() = message("take.done.deleted", relPath, into, from)

    @get:Nls
    val unsaved: String get() = message("take.unsaved", relPath, into)

    @get:Nls
    val sourceChanged: String get() = message("take.sourceChanged", relPath, from)

    @get:Nls
    val failed: String get() = message("take.failed", relPath, into)

    @Nls
    fun notWritten(@Nls reason: String): String = message("take.notWritten", into, reason)

    @Nls
    fun notUndoable(@Nls done: String): String = message("take.notUndoable", done)

    /** The delete question: [relPath] exists only in the destination. */
    @Nls
    fun delete(binary: Boolean): String = message(if (binary) "take.delete.binary" else "take.delete", relPath, into, from)

    /** The key/vault question (a write or a creation: the content is copied as it is). */
    @get:Nls
    val sensitive: String get() = message("take.sensitive", relPath, from, into)
}

/** Button texts of the default [TakeUi]. */
internal object AnsibilityTakeTexts {
    @Nls
    fun deleteButton(): String = message("take.delete.ok")

    @Nls
    fun takeButton(): String = message("take.sensitive.ok")
}
