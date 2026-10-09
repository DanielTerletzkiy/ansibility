package de.terletzkiy.ansibility.golden.align

import com.intellij.diff.merge.ConflictType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeTexts
import de.terletzkiy.ansibility.golden.sync.FileOp
import de.terletzkiy.ansibility.golden.sync.FileStamp
import de.terletzkiy.ansibility.golden.sync.OpContent
import de.terletzkiy.ansibility.golden.sync.PlanEntry
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleFiles
import de.terletzkiy.ansibility.golden.sync.RoleWriter
import de.terletzkiy.ansibility.golden.sync.SyncOps
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.role.RoleCopy
import org.jetbrains.annotations.Nls
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** What Align merges (plan amendment R24, D184): [source] into [target]. */
data class AlignRequest(
    /** The copy that is changed (golden for Merge into Golden). */
    val target: RoleCopy,
    /** The copy merged in (golden for Align with Golden). */
    val source: RoleCopy,
    /** Include key material and whole-file vaults, accepted whole only (D185; off by default). */
    val includeSensitive: Boolean = false,
)

/** The side an accept keeps: "Accept Yours" is the target, "Accept Theirs" the source. */
enum class AlignSide { TARGET, SOURCE }

/** How a row of the merge workspace was resolved. */
enum class AlignResolution {
    /** Merge…: the result of the three-way viewer. */
    MERGED,

    /** Accept Theirs: the source's file (written, created or the target's deleted). */
    TAKEN_FROM_SOURCE,

    /** Accept Yours: the target stays as it is. */
    KEPT,
}

/** A failure the merge workspace shows instead of writing (a stale or read-only target, a changed source). */
class AlignException(@Nls message: String) : Exception(message) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The three sides of a row for the merge viewer (plan amendment R24, D186: the target is the base, so every
 * difference is an incoming change from the source). A missing side is an empty array, never null. Key and vault
 * files have three empty sides: their content is never read for a viewer.
 */
class AlignRevisions internal constructor(
    /** "Yours": the target's bytes as the user sees them (an unsaved document as saving would write it). */
    val target: ByteArray,
    /** The base: a copy of [target]. */
    val base: ByteArray,
    /** "Theirs": the source's bytes. */
    val source: ByteArray,
    val conflictType: ConflictType,
) {
    override fun toString(): String = "AlignRevisions(${target.size}, ${base.size}, ${source.size}, $conflictType)"
}

/**
 * Stands in for a row's file in the merge window when the window must never write to the real file: files only the
 * source has (there is no target file yet), binary files and key or vault files. In memory only; its path is where
 * the file is (or would be) in the target, so the window shows and groups it there. Whatever a merge window writes
 * to it stays in memory; the session then writes the real file through [RoleWriter].
 */
class AlignPlaceholder internal constructor(
    /** The path below the target role directory. */
    val relPath: String,
    name: String,
    private val parentDir: VirtualFile?,
) : LightVirtualFile(name, FileTypeManager.getInstance().getFileTypeByFileName(relPath.substringAfterLast('/')), "") {
    override fun getParent(): VirtualFile? = parentDir

    override fun toString(): String = "AlignPlaceholder($relPath)"

    companion object {
        /**
         * The placeholder of [relPath] below [targetDir]: its parent is the deepest existing directory on the way, and
         * its name the rest of the path ("default/verify.yml" below "molecule" when `molecule/default` is missing).
         */
        fun of(targetDir: VirtualFile, relPath: String): AlignPlaceholder {
            val segments = relPath.split('/')
            var parent: VirtualFile = targetDir
            var used = 0
            for (segment in segments.dropLast(1)) {
                val child = parent.findChild(segment)?.takeIf { it.isDirectory } ?: break
                parent = child
                used++
            }
            return AlignPlaceholder(relPath, segments.drop(used).joinToString("/"), parent)
        }
    }
}

/**
 * One differing path in the merge workspace (plan amendment R24, D185).
 *
 * [file] is what the window lists: the target's real file for a changed text file (Merge… writes its document), an
 * [AlignPlaceholder] otherwise.
 */
class AlignRow internal constructor(val entry: PlanEntry, val file: VirtualFile) {
    val relPath: String get() = entry.relPath

    val kind: PlanKind get() = entry.kind

    /**
     * Accepted whole only (D185): files on one side only, binary files, key and vault files. The window treats them
     * as binary, offers no Merge…, and never writes them itself.
     */
    val acceptWholeOnly: Boolean get() = isAcceptWholeOnly(entry)

    /** Merge… is offered: a changed text file that is no key or vault file. */
    val canMerge: Boolean get() = !acceptWholeOnly

    override fun toString(): String = "AlignRow($relPath, $kind${if (acceptWholeOnly) ", whole" else ""})"

    companion object {
        fun isAcceptWholeOnly(entry: PlanEntry): Boolean = entry.kind != PlanKind.CHANGED || entry.binary || entry.sensitive
    }
}

/** What the window resolved, by resolution, and what it left open (the end-of-align notification, D187). */
class AlignOutcome internal constructor(val merged: Int, val taken: Int, val kept: Int, val open: Int) {
    override fun toString(): String = "AlignOutcome(merged=$merged, taken=$taken, kept=$kept, open=$open)"
}

/** The texts of one alignment: the window, its viewer and its notification (plan amendment R24, D185). */
class AlignTexts internal constructor(private val request: AlignRequest, private val leftOut: Int, private val caseConflicts: Int = 0) {
    private val role: String get() = request.target.name
    private val target: String get() = request.target.root.displayName
    private val source: String get() = request.source.root.displayName

    /** "Align web: golden ← falcon". */
    @get:Nls
    val dialogTitle: String get() = message("align.dialog.title", role, target, source)

    /**
     * "Target golden · source falcon · 2 key/vault files left out", plus "· 1 path differs only in case (left out)"
     * when the target's file system ignores case and the copies name a path differently (S9).
     */
    @get:Nls
    val description: String
        get() {
            val main = if (leftOut > 0) message("align.dialog.description.leftOut", target, source, leftOut) else message("align.dialog.description", target, source)
            return if (caseConflicts > 0) main + " · " + message("align.dialog.description.caseConflicts", caseConflicts) else main
        }

    @get:Nls
    val targetPanel: String get() = message("align.panel.target", target)

    @get:Nls
    val resultPanel: String get() = message("align.panel.result")

    @get:Nls
    val sourcePanel: String get() = message("align.panel.source", source)

    /** [targetPanel] with the target file's last change when known ("golden (target) · 2026-09-12 · alice", D183). */
    @Nls
    fun targetPanel(change: LastChange?): String = withChange(targetPanel, change)

    /** [sourcePanel] with the source file's last change when known ("falcon (source) · 2026-09-12 · alice", D183). */
    @Nls
    fun sourcePanel(change: LastChange?): String = withChange(sourcePanel, change)

    private fun withChange(@Nls panel: String, change: LastChange?): String = when {
        change == null -> panel
        // R25: the golden mirror's side is labelled ("fetched commit", "older than the fetched history").
        change.isUpperBound -> LastChangeTexts.side(panel, change)
        else -> message("align.panel.lastChange", panel, DATE.format(change.date), change.author)
    }

    @get:Nls
    val targetColumn: String get() = message("align.column.target")

    @get:Nls
    val sourceColumn: String get() = message("align.column.source")

    /** "Before aligning web in golden": the Local History label, the revert point (D187). */
    @get:Nls
    val label: String get() = message("align.label", role, target)

    /** "Align web in golden": the undoable command. */
    @get:Nls
    val command: String get() = message("align.command", role, target)

    /** "web › tasks/main.yml: golden ← falcon". */
    @Nls
    fun mergeWindowTitle(relPath: String): String = message("align.merge.title", role, relPath, target, source)

    /** The Target column of [row]: what the target holds. */
    @Nls
    fun targetStatus(row: AlignRow): String = withSensitive(
        row,
        when (row.kind) {
            PlanKind.CHANGED -> changed(row)
            PlanKind.ONLY_IN_SOURCE -> message("align.status.missing")
            PlanKind.ONLY_IN_TARGET -> message("align.status.onlyInTarget")
        },
    )

    /** The Source column of [row]: what the source holds. */
    @Nls
    fun sourceStatus(row: AlignRow): String = withSensitive(
        row,
        when (row.kind) {
            PlanKind.CHANGED -> changed(row)
            PlanKind.ONLY_IN_SOURCE -> message("align.status.onlyInSource")
            PlanKind.ONLY_IN_TARGET -> message("align.status.missing")
        },
    )

    private fun changed(row: AlignRow): String = if (row.entry.binary) message("align.status.binary") else message("align.status.changed")

    private fun withSensitive(row: AlignRow, text: String): String = if (row.entry.sensitive) message("align.status.contentNotShown", text) else text

    /** U8: "Nothing aligned in web (golden)": the window closed without merging or taking anything. */
    @get:Nls
    val nothingAligned: String get() = message("align.summary.nothingAligned", role, target)

    /** "Aligned web in golden: 3 merged, 2 taken from falcon, 1 kept". */
    @Nls
    fun summary(outcome: AlignOutcome): String {
        val parts = listOfNotNull(
            outcome.merged.takeIf { it > 0 }?.let { message("align.summary.merged", it) },
            outcome.taken.takeIf { it > 0 }?.let { message("align.summary.taken", it, source) },
            outcome.kept.takeIf { it > 0 }?.let { message("align.summary.kept", it) },
            outcome.open.takeIf { it > 0 }?.let { message("align.summary.open", it) },
        )
        return message("align.summary", role, target, if (parts.isEmpty()) message("align.summary.nothing") else parts.joinToString(", "))
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
    }
}

/**
 * One alignment of [request] (plan amendment R24, D184–D187): the rows of the merge workspace and what accepting,
 * merging and resolving them does. The window (the platform's Conflicts dialog through the optional VCS module, or
 * the fallback list) only calls this; nothing here touches VCS classes.
 *
 * - **Rows** are [plan]'s included entries (key and vault files only with [AlignRequest.includeSensitive]).
 * - **Accept Theirs** ([accept] with [AlignSide.SOURCE]) writes through [RoleWriter] with [SyncOps]' operations
 *   (changed: the source's bytes; only in source: created; only in target: deleted), each carrying the plan's stamp of
 *   the target file, so a target changed meanwhile, a read-only file or a changed source stops it with a message
 *   ([AlignException]) and writes nothing. One undoable command per accept.
 * - **Accept Yours** writes nothing.
 * - **Merge…** ([revisions]) gives the viewer the target as base and "yours", the source as "theirs"; the viewer
 *   writes the target's document, and [resolved] records it.
 * - Nothing is staged or committed, nothing is decrypted, and no content is logged.
 */
class AlignSession internal constructor(
    val project: Project,
    val request: AlignRequest,
    val plan: RoleFilePlan,
) {
    val target: RoleCopy get() = request.target
    val source: RoleCopy get() = request.source

    val texts: AlignTexts = AlignTexts(request, plan.excluded.size, plan.caseConflicts.size)

    /** The rows, in path order. */
    val rows: List<AlignRow> = plan.included.map { entry ->
        val file = entry.targetFile?.takeIf { !AlignRow.isAcceptWholeOnly(entry) } ?: AlignPlaceholder.of(plan.target, entry.relPath)
        AlignRow(entry, file)
    }

    private val byFile: Map<VirtualFile, AlignRow> = rows.associateBy { it.file }
    private val byPath: Map<String, AlignRow> = rows.associateBy { it.relPath }
    private val resolutions = ConcurrentHashMap<String, AlignResolution>()
    private val written: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loaded = ConcurrentHashMap<String, AlignRevisions>()

    /** The Local History label set before the window opened (the revert point, D187), or null before that. */
    @Volatile
    var labelId: String? = null
        internal set

    /** The row the window lists as [file], or null. */
    fun row(file: VirtualFile): AlignRow? = byFile[file]

    /** The row of [relPath], or null. */
    fun row(relPath: String): AlignRow? = byPath[relPath]

    /** How [row] was resolved so far, or null while it is open. */
    fun resolution(row: AlignRow): AlignResolution? = resolutions[row.relPath]

    /** The rows still open. */
    val openRows: List<AlignRow> get() = rows.filter { !resolutions.containsKey(it.relPath) }

    val outcome: AlignOutcome
        get() {
            val values = rows.mapNotNull { resolutions[it.relPath] }
            return AlignOutcome(
                merged = values.count { it == AlignResolution.MERGED },
                taken = values.count { it == AlignResolution.TAKEN_FROM_SOURCE },
                kept = values.count { it == AlignResolution.KEPT },
                open = rows.size - values.size,
            )
        }

    /**
     * The source files of the rows that have unsaved changes (D187, U11: Align stops until they are saved or
     * discarded, so what is merged is what is on disk), by path. Read action or EDT.
     */
    fun unsavedSources(): List<String> {
        val documents = FileDocumentManager.getInstance()
        return rows.filter { row -> row.entry.sourceFile?.let { it.isValid && documents.isFileModified(it) } == true }.map { it.relPath }
    }

    /** The real target files the window may write itself (changed text rows; never a placeholder). */
    internal val windowFiles: List<VirtualFile> get() = rows.map { it.file }.filter { it !is AlignPlaceholder }

    // ---------------------------------------------------------------- the viewer

    /**
     * The three sides of [row] for the merge viewer (D186: base = target). The first call checks that neither side
     * changed since the plan ([AlignException] otherwise) and keeps the answer, so the viewer and its model always get
     * the same sides, also after the window changed the target. Key and vault files get three empty sides: their
     * content is never read for a viewer. Background thread.
     */
    fun revisions(row: AlignRow): AlignRevisions {
        loaded[row.relPath]?.let { return it }
        val revisions = runReadActionBlocking { readRevisions(row) }
        return loaded.putIfAbsent(row.relPath, revisions) ?: revisions
    }

    private fun readRevisions(row: AlignRow): AlignRevisions {
        val entry = row.entry
        checkUnchanged(listOf(row))
        val type = when (entry.kind) {
            PlanKind.CHANGED -> ConflictType.DEFAULT
            // Git's naming: "theirs deleted" is MODIFIED_DELETED, "yours deleted" DELETED_MODIFIED.
            PlanKind.ONLY_IN_TARGET -> ConflictType.MODIFIED_DELETED
            PlanKind.ONLY_IN_SOURCE -> ConflictType.DELETED_MODIFIED
        }
        if (entry.sensitive) return AlignRevisions(EMPTY, EMPTY, EMPTY, type)
        val targetBytes = entry.targetFile?.let { bytesOf(it, target) } ?: EMPTY
        val sourceBytes = entry.sourceFile?.let { bytesOf(it, source) } ?: EMPTY
        return AlignRevisions(targetBytes, targetBytes.copyOf(), sourceBytes, type)
    }

    private fun bytesOf(file: VirtualFile, copy: RoleCopy): ByteArray =
        RoleFiles.bytes(project, file) ?: throw AlignException(message("align.unreadable", file.name, copy.root.displayName))

    /** [AlignException] when a target or source file of [rows] changed since the plan. Read action. */
    private fun checkUnchanged(rows: List<AlignRow>) {
        for (row in rows) {
            if (!loaded.containsKey(row.relPath) && FileStamp.of(row.entry.targetFile) != row.entry.targetStamp) {
                throw AlignException(message("align.stale.target", row.relPath, target.root.displayName))
            }
            if (FileStamp.of(row.entry.sourceFile) != row.entry.sourceStamp) {
                throw AlignException(message("align.stale.source", row.relPath, source.root.displayName, target.root.displayName))
            }
        }
    }

    // ---------------------------------------------------------------- accepting

    /**
     * Accepts [rows] whole: [AlignSide.SOURCE] writes the source's files into the target through [RoleWriter] (one
     * undoable command), [AlignSide.TARGET] writes nothing. Rows already written are skipped. Throws [AlignException]
     * and writes nothing when a precondition fails; rows written before a later operation failed stay written (and
     * resolved), and the exception names the others.
     *
     * Any thread: on a background thread (the Conflicts dialog's modal progress) the operations are read there and
     * written on the EDT with the caller's modality; on the EDT they are read under a modal progress first.
     */
    fun accept(rows: List<AlignRow>, side: AlignSide) {
        if (side == AlignSide.TARGET) return
        val todo = rows.filter { it.relPath !in written }
        if (todo.isEmpty()) return
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            val ops = runWithModalProgressBlocking(project, message("align.progress.reading", target.name)) { opsOf(todo) }
            write(todo, ops)
            return
        }
        val ops = runBlockingMaybeCancellable { opsOf(todo) }
        var failure: AlignException? = null
        application.invokeAndWait({
            try {
                write(todo, ops)
            } catch (e: AlignException) {
                failure = e
            }
        }, ModalityState.defaultModalityState())
        failure?.let { throw it }
    }

    /**
     * The operations that take the source for [rows]: [SyncOps.mirror] over those entries (the plan's stamps; a
     * changed source fails). A changed text file the viewer already read (its sides are kept) is guarded by its
     * content instead: when the target still holds what the viewer was given, its stamp now is expected, because the
     * window may have saved the very same text again.
     */
    private suspend fun opsOf(rows: List<AlignRow>): List<FileOp> {
        val entries = rows.map { it.entry }
        val subPlan = RoleFilePlan(plan.source, plan.target, plan.options, entries, plan.sourceFileCount, plan.targetFileCount)
        val ops = try {
            SyncOps.mirror(project, subPlan, deleteExtra = true)
        } catch (e: SyncOps.SourceChanged) {
            throw AlignException(message("align.stale.source", e.relPaths.joinToString(", "), source.root.displayName, target.root.displayName))
        }
        if (rows.none { loaded.containsKey(it.relPath) }) return ops
        return readAction {
            ops.map { op -> rebased(op) }
        }
    }

    private fun rebased(op: FileOp): FileOp {
        val row = byPath[op.relPath] ?: return op
        // Key and vault files keep the plan's stamp: their content is not read again for a comparison.
        if (row.entry.sensitive) return op
        val seen = loaded[op.relPath] ?: return op
        val file = row.entry.targetFile?.takeIf { it.isValid } ?: return op
        val now = RoleFiles.bytes(project, file) ?: return op
        if (!now.contentEquals(seen.target)) return op
        val stamp = FileStamp.of(file)
        return when (op) {
            is FileOp.Write -> FileOp.Write(op.relPath, op.content, op.executable, stamp)
            is FileOp.Delete -> FileOp.Delete(op.relPath, stamp)
            is FileOp.Create -> op
        }
    }

    /** Writes [ops] for [rows] on the EDT and records what was written. */
    private fun write(rows: List<AlignRow>, ops: List<FileOp>) {
        val result = RoleWriter.apply(project, plan.target, ops, texts.label, texts.command)
        when (result.status) {
            WriteResult.Status.APPLIED, WriteResult.Status.NOTHING_TO_DO -> Unit
            else -> throw AlignException(result.message ?: message("align.failed", rows.joinToString(", ") { it.relPath }, target.root.displayName))
        }
        val done = (result.written + result.created + result.deleted).toSet()
        for (row in rows) {
            if (row.relPath in done) {
                written += row.relPath
                resolutions[row.relPath] = AlignResolution.TAKEN_FROM_SOURCE
            }
        }
        val failed = rows.filter { it.relPath !in done }
        if (failed.isNotEmpty()) throw AlignException(message("align.failed", failed.joinToString(", ") { it.relPath }, target.root.displayName))
    }

    // ---------------------------------------------------------------- resolving

    /**
     * The window resolved [rows] as [resolution]. A whole-only row resolved as [AlignResolution.TAKEN_FROM_SOURCE]
     * that was not written yet (a merge window chose a side; it wrote only the placeholder) is written now; when that
     * fails, the row stays open and the failure is shown. Any thread.
     */
    fun resolved(rows: List<AlignRow>, resolution: AlignResolution) {
        for (row in rows) {
            if (resolution == AlignResolution.TAKEN_FROM_SOURCE && row.acceptWholeOnly && row.relPath !in written) {
                try {
                    accept(listOf(row), AlignSide.SOURCE)
                } catch (e: AlignException) {
                    showError(e)
                    continue
                }
            }
            resolutions[row.relPath] = resolution
        }
    }

    // ---------------------------------------------------------------- after the window

    /**
     * Saves the documents the window changed (merged or taken rows it wrote itself), while the trailing-space stripper
     * is still held off for them (S6: a merge result is saved as the user left it). EDT, while the window's files are
     * held.
     */
    @RequiresEdt
    internal fun saveWindowEdits() {
        val documents = FileDocumentManager.getInstance()
        val edited = rows.filter { row -> row.relPath !in written && resolutions[row.relPath].let { it == AlignResolution.MERGED || it == AlignResolution.TAKEN_FROM_SOURCE } }
            .mapNotNull { row -> row.file.takeIf { it !is AlignPlaceholder && it.isValid }?.let(documents::getCachedDocument) }
            .filter(documents::isDocumentUnsaved)
        if (edited.isNotEmpty()) ApplicationManager.getApplication().runWriteAction { edited.forEach(documents::saveDocument) }
    }

    /**
     * S6: rows resolved by taking the source that the window wrote itself (Accept on a changed text row goes through
     * the platform's merge model on the target document; the fallback's merge viewer closed with "Accept Right"): the
     * document kept the target's line separators, byte order mark and executable bit, and the trailing-space stripper
     * may have run. Where the target's bytes or executable bit still differ from the source's, the source's exact
     * bytes and executable bit are written through [RoleWriter], in one more command of the same name (after the same
     * Local History label text). A source changed since the plan writes nothing and says so. EDT.
     */
    @RequiresEdt
    internal fun finishTaken() {
        val todo = rows.filter { it.kind == PlanKind.CHANGED && !it.acceptWholeOnly && it.relPath !in written && resolutions[it.relPath] == AlignResolution.TAKEN_FROM_SOURCE }
        if (todo.isEmpty()) return
        try {
            val ops = runWithModalProgressBlocking(project, message("align.progress.reading", target.name)) { exactOps(todo) }
            if (ops.isEmpty()) return
            val paths = ops.mapTo(HashSet()) { it.relPath }
            write(todo.filter { it.relPath in paths }, ops)
        } catch (e: AlignException) {
            showError(e)
        }
    }

    /** The writes that give [rows] the source's exact bytes and executable bit, where they still differ. Background. */
    private suspend fun exactOps(rows: List<AlignRow>): List<FileOp> = rows.mapNotNull { row ->
        readAction {
            val entry = row.entry
            val sourceFile = entry.sourceFile
            if (sourceFile == null || FileStamp.of(sourceFile) != entry.sourceStamp) {
                throw AlignException(message("align.stale.source", row.relPath, source.root.displayName, target.root.displayName))
            }
            val targetFile = entry.targetFile?.takeIf { it.isValid && !it.isDirectory } ?: return@readAction null
            val sourceBytes = bytesOf(sourceFile, source)
            val same = RoleFiles.bytes(project, targetFile)?.contentEquals(sourceBytes) == true
            if (same && RoleFiles.isExecutable(targetFile) == entry.sourceExecutable) return@readAction null
            // The target is what the window just wrote: its stamp now is the expected one.
            FileOp.Write(row.relPath, OpContent.Bytes(sourceBytes), entry.sourceExecutable, FileStamp.of(targetFile))
        }
    }

    private fun showError(e: AlignException) {
        val show = { AlignUi.getInstance().error(project, message("align.error.title", target.name), e.message.orEmpty()) }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) show() else application.invokeAndWait(show, ModalityState.defaultModalityState())
    }

    override fun toString(): String = "AlignSession(${target.name}: ${target.root.displayName} ← ${source.root.displayName}, ${rows.size} rows)"

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
