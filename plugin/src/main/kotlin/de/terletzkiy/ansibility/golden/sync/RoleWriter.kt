package de.terletzkiy.ansibility.golden.sync

import com.intellij.history.LocalHistory
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.TrailingSpacesStripper
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.model.role.RoleCatalog
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * What [RoleWriter] did to one target role directory. Paths are relative to [target]; no content is ever held.
 */
class WriteResult internal constructor(
    val target: VirtualFile,
    val status: Status,
    /** Why nothing was written ([Status.STALE], [Status.READ_ONLY], [Status.INVALID_TARGET], [Status.NOT_RUN]); else null. */
    @get:Nls val message: String?,
    /** Existing files whose content was replaced. */
    val written: List<String>,
    /** Files created (with their missing parent directories). */
    val created: List<String>,
    /** Files deleted. */
    val deleted: List<String>,
    /** Operations that were not carried out, with the reason. */
    val skipped: List<Skip>,
    /**
     * Paths of [written], [created] and [deleted] that Edit › Undo cannot restore: binary content and exact bytes
     * written through the VFS, and creations or deletions of files Local History does not track. Git can.
     */
    val notUndoable: List<String>,
    /** The paths whose file changed since the plan ([Status.STALE]). */
    val stale: List<String>,
    /** The paths that stayed read-only ([Status.READ_ONLY]). */
    val readOnly: List<String>,
    /** The Local History label set before writing ([LocalHistory.putSystemLabel]), or null when nothing was written. */
    val labelId: String?,
    /** The undoable command's name ("Align web in golden"). */
    @get:Nls val commandName: String,
) {
    enum class Status {
        /** The operations were carried out (some may be [skipped]). */
        APPLIED,

        /** No operation was left to carry out (none given, or all [skipped] before writing). */
        NOTHING_TO_DO,

        /**
         * A target file changed since the plan, or a path is a folder on one side and a file on the other (a file
         * cannot replace a folder): nothing was written.
         */
        STALE,

        /** A target file is read-only and was not made writable: nothing was written. */
        READ_ONLY,

        /**
         * The target is no directory (deleted meanwhile), it is (or lies below) a symbolic link, or [RoleWriter.multi]
         * got two roots that are the same folder on disk: nothing was written.
         */
        INVALID_TARGET,

        /** [RoleWriter.multi]: another root failed its preconditions, so nothing was written here either. */
        NOT_RUN,
    }

    enum class SkipReason {
        /**
         * The path is never written (D191): `.git`, `__pycache__`, `.DS_Store`, `*.pyc`, an ignored path, a path
         * through a symbolic link or a link itself (dangling or not), `..`.
         */
        NEVER_TOUCHED,

        /** A second operation on the same path. */
        DUPLICATE,

        /** A [FileOp.Write] or [FileOp.Delete] without [FileOp.expected] whose file does not exist, or is a directory. */
        NOT_FOUND,

        /** A [FileOp.Create] without [FileOp.expected] whose file exists already. */
        EXISTS,

        /**
         * The write failed (an I/O error, a read-only document, any other error) or did not run because an error
         * stopped the command; logged with the path and the error class only.
         */
        FAILED,
    }

    class Skip(val relPath: String, val reason: SkipReason) {
        override fun toString(): String = "$relPath: $reason"
    }

    /** Whether the operations were carried out. */
    val applied: Boolean get() = status == Status.APPLIED

    /** "3 written, 2 created, 1 deleted" (only the non-zero parts; "nothing changed" when all are zero). */
    @Nls
    fun summary(): String {
        val parts = listOfNotNull(
            written.size.takeIf { it > 0 }?.let { message("writer.summary.written", it) },
            created.size.takeIf { it > 0 }?.let { message("writer.summary.created", it) },
            deleted.size.takeIf { it > 0 }?.let { message("writer.summary.deleted", it) },
        )
        return if (parts.isEmpty()) message("writer.summary.nothing") else parts.joinToString(", ")
    }

    override fun toString(): String =
        "WriteResult($status, written=$written, created=$created, deleted=$deleted, skipped=$skipped, notUndoable=$notUndoable, stale=$stale, readOnly=$readOnly)"
}


/**
 * The one way the golden features write role files (plan amendment R24: Align D187, Push D190; R9's D42 extended):
 * explicit, labelled and undoable.
 *
 * **Preconditions**, checked before anything is written; any failure writes nothing:
 * - **the target** is a directory of its root, reached without a symbolic link ([RoleLinks.linkedTo]; D191: never
 *   write or delete through a link), and in [multi] no two roots are the same folder on disk
 *   ([WriteResult.Status.INVALID_TARGET]);
 * - every path is normalised and allowed (D191: never `.git`, `__pycache__`, `.DS_Store`, `*.pyc`, an ignored path, a
 *   path through a symbolic link or a link itself; those operations are [WriteResult.SkipReason.NEVER_TOUCHED]);
 * - **stale target**: each operation's [FileOp.expected] stamp must still match the file, and a file never replaces
 *   a folder ([WriteResult.Status.STALE]);
 * - **read-only**: `ReadonlyStatusHandler.ensureFilesWritable` for the files to write and delete (it may ask to make
 *   them writable; [WriteResult.Status.READ_ONLY] when some stay read-only).
 *
 * **Writing**: a Local History system label ([LocalHistory.putSystemLabel], the revert point), a Local History action
 * around ONE `WriteCommandAction` with global undo per target root (so Edit › Undo, from anywhere, reverts the whole
 * step). The command's [UndoConfirmationPolicy] is the caller's; the default is the `WriteCommandAction` builder's
 * own, [UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION]: Edit › Undo reverts the step without a question (Push
 * asks once itself before its Undo; [UndoConfirmationPolicy.REQUEST_CONFIRMATION] or `DEFAULT` make the platform ask
 * before undoing each command):
 * - text through the file's document (`setText`, then `saveDocument`, with the trailing-space stripper off for that
 *   save): undoable; [OpContent.Bytes] also change the byte order mark and line separator undoably, and when the saved
 *   bytes still differ (a charset that cannot represent them) the exact bytes are written through the VFS;
 * - new files with `VfsUtil.createDirectoryIfMissing` and `createChildData`, then their content; deletions with
 *   `delete`: Undo restores both through Local History, for files Local History tracks (local files, not excluded);
 * - binary content and bytes no document can hold through `setBinaryContent`, which Undo cannot restore (a VFS
 *   content overwrite is not recorded, and Local History keeps no binary content): such paths are listed in
 *   [WriteResult.notUndoable]. Which way a file goes is decided before its document is loaded, and a document that is
 *   loaded already reloads as an undoable change (`UndoUtil.forceUndoIn`), so such a write never makes the whole
 *   command refuse Undo (S3);
 * - the executable bit: [FileOp.Write.executable] / [FileOp.Create.executable] set after the write, once the files are
 *   flushed and refreshed (`LocalFileSystem.refreshNioFiles`), through `java.nio` POSIX permissions (never through a
 *   link); null keeps it. Undo restores the old modes of written files (an undoable action in the same command).
 *
 * An error while writing never escapes: the operation is [WriteResult.SkipReason.FAILED] and the others still run
 * (S11), so a caller always gets a result to report.
 *
 * Never stages, commits or pushes anything, never decrypts, and logs paths and error classes only.
 *
 * **Threading**: EDT only ([apply] and [multi] are `@RequiresEdt`). Read the content in the background first (the
 * plan, `SyncOps`), then call these with everything in memory.
 */
object RoleWriter {
    private val LOG = logger<RoleWriter>()

    /**
     * Applies [ops] to the role directory [target] as one undoable command named [commandName], after the Local
     * History label [label] ("Before aligning web in golden"). [confirmation] is the command's undo confirmation
     * (default: none, as the `WriteCommandAction` builder's default). EDT.
     */
    fun apply(
        project: Project,
        target: VirtualFile,
        ops: List<FileOp>,
        @Nls label: String,
        @Nls commandName: String = label,
        confirmation: UndoConfirmationPolicy = UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
    ): WriteResult {
        ThreadingAssertions.assertEventDispatchThread()
        val prepared = prepare(project, target, ops, commandName)
        prepared.failure?.let { return it }
        readOnlyFailure(project, listOf(prepared))?.let { return it.getValue(prepared) }
        return execute(project, prepared, label, confirmation)
    }

    /**
     * [apply] for several target roots (Push): the preconditions of every root are checked first (one read-only
     * question for all of them, and no two roots may be the same folder on disk), and when any root fails them nothing
     * is written anywhere (that root gets its status, the others [WriteResult.Status.NOT_RUN]). Then each root gets its
     * own label ("[labelPrefix] falcon") and its own undoable command ([commandOf] of the root's name, by default
     * "[commandPrefix] falcon"), in the map's order, so Undo reverts the last root first. [nameOf] names a root
     * (default: the catalog root's display name). [confirmation] applies to every command (default: none, as for
     * [apply]): without one the caller asks once for all of them (Push's Undo), and Edit › Undo reverts the most recent
     * root's command without a question. EDT.
     */
    fun multi(
        project: Project,
        perRoot: Map<VirtualFile, List<FileOp>>,
        @Nls labelPrefix: String,
        @Nls commandPrefix: String = labelPrefix,
        confirmation: UndoConfirmationPolicy = UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
        nameOf: (VirtualFile) -> String = { defaultName(project, it) },
        commandOf: (String) -> @Nls String = { name -> "$commandPrefix $name" },
    ): Map<VirtualFile, WriteResult> {
        ThreadingAssertions.assertEventDispatchThread()
        val prepared = perRoot.map { (root, ops) -> prepare(project, root, ops, commandOf(nameOf(root))) }
        sameFolders(prepared, nameOf)
        val failed = prepared.firstOrNull { it.failure != null }
        val readOnly = if (failed == null) readOnlyFailure(project, prepared) else null
        if (failed != null || readOnly != null) {
            return prepared.associate { p ->
                p.target to (p.failure ?: readOnly?.get(p) ?: p.result(WriteResult.Status.NOT_RUN, message("writer.notRun")))
            }
        }
        return prepared.associate { p -> p.target to execute(project, p, "$labelPrefix ${nameOf(p.target)}", confirmation) }
    }

    // ---------------------------------------------------------------- preconditions

    /** The operations of one root after validation, with the result of a failed precondition. */
    private class Prepared(val target: VirtualFile, val ops: List<FileOp>, val skipped: List<WriteResult.Skip>, @Nls val commandName: String) {
        var failure: WriteResult? = null

        fun result(status: WriteResult.Status, @Nls message: String?, stale: List<String> = emptyList(), readOnly: List<String> = emptyList()) =
            WriteResult(target, status, message, emptyList(), emptyList(), emptyList(), skipped, emptyList(), stale, readOnly, null, commandName)
    }

    private fun prepare(project: Project, target: VirtualFile, ops: List<FileOp>, @Nls commandName: String): Prepared {
        val skipped = ArrayList<WriteResult.Skip>()
        if (!target.isValid || !target.isDirectory) {
            return Prepared(target, emptyList(), skipped, commandName).also {
                it.failure = it.result(WriteResult.Status.INVALID_TARGET, message("writer.invalidTarget", target.presentableUrl))
            }
        }
        RoleLinks.linkedTo(project, target)?.let { real ->
            return Prepared(target, emptyList(), skipped, commandName).also {
                it.failure = it.result(WriteResult.Status.INVALID_TARGET, message("writer.linkedTarget", target.presentableUrl, real))
            }
        }
        val deletes = ops.filterIsInstance<FileOp.Delete>().mapTo(HashSet()) { it.relPath }
        val seen = HashSet<String>()
        val valid = ArrayList<FileOp>()
        val stale = ArrayList<String>()
        val conflicts = ArrayList<String>()
        for (op in ops) {
            val path = GoldenTargets.normalize(op.relPath)
            if (path == null || path != op.relPath || !RoleFiles.isWritablePath(path) || neverTouched(project, target, path)) {
                skipped += WriteResult.Skip(op.relPath, WriteResult.SkipReason.NEVER_TOUCHED)
                continue
            }
            if (!seen.add(path)) {
                skipped += WriteResult.Skip(path, WriteResult.SkipReason.DUPLICATE)
                continue
            }
            val file = target.findFileByRelativePath(path)
            if (op !is FileOp.Delete && replacesFolder(target, path, file, deletes)) {
                conflicts += path
                continue
            }
            val expected = op.expected
            if (expected != null) {
                if (FileStamp.of(file) != expected) stale += path else valid += op
                continue
            }
            val exists = file != null && file.isValid && !file.isDirectory
            when {
                op is FileOp.Create && file != null -> skipped += WriteResult.Skip(path, WriteResult.SkipReason.EXISTS)
                op !is FileOp.Create && !exists -> skipped += WriteResult.Skip(path, WriteResult.SkipReason.NOT_FOUND)
                else -> valid += op
            }
        }
        val prepared = Prepared(target, valid, skipped, commandName)
        if (conflicts.isNotEmpty()) {
            prepared.failure = prepared.result(WriteResult.Status.STALE, message("writer.typeConflict", conflicts.size, pathList(conflicts)), stale = conflicts)
        } else if (stale.isNotEmpty()) {
            prepared.failure = prepared.result(WriteResult.Status.STALE, message("writer.stale", stale.size, pathList(stale)), stale = stale)
        }
        return prepared
    }

    /**
     * S8: whether writing or creating the file [relPath] would need to replace a folder: [file] is a folder, or a
     * folder on its way is a file that no Delete of the same operations removes first.
     */
    private fun replacesFolder(target: VirtualFile, relPath: String, file: VirtualFile?, deletes: Set<String>): Boolean {
        if (file != null && file.isValid && file.isDirectory) return true
        var current = target
        for (prefix in RoleFiles.prefixes(relPath).dropLast(1)) {
            val child = current.findChild(prefix.substringAfterLast('/')) ?: return false
            if (!child.isDirectory) return prefix !in deletes
            current = child
        }
        return false
    }

    /**
     * D191 for a path that passed [RoleFiles.isWritablePath]: an ignored path of the project, or a path that is a
     * symbolic link or runs through one (VFS and file system: a dangling link the VFS does not list counts too).
     */
    private fun neverTouched(project: Project, target: VirtualFile, relPath: String): Boolean {
        val segments = relPath.split('/')
        var current: VirtualFile = target
        for (segment in segments) {
            current = current.findChild(segment) ?: break
            if (RoleFiles.isLink(current)) return true
        }
        target.toNioPathOrNull()?.let { base ->
            var path = base
            for (segment in segments) {
                path = path.resolve(segment)
                if (Files.isSymbolicLink(path)) return true
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) break
            }
        }
        return RoleFiles.isIgnoredPath(project, target, relPath)
    }

    /** S1: roots that are the same folder on disk (or one inside the other) once links are resolved fail both. */
    private fun sameFolders(prepared: List<Prepared>, nameOf: (VirtualFile) -> String) {
        val candidates = prepared.filter { it.failure == null }
        val (first, second, real) = RoleLinks.overlapping(candidates.map { it.target }) ?: return
        val text = message("writer.sameFolder", nameOf(first), nameOf(second), real)
        for (p in candidates) {
            if (p.target == first || p.target == second) p.failure = p.result(WriteResult.Status.INVALID_TARGET, text)
        }
    }

    /** `ensureFilesWritable` over the existing files of every root; the failures by root, or null when all are writable. */
    private fun readOnlyFailure(project: Project, prepared: List<Prepared>): Map<Prepared, WriteResult>? {
        val filesByRoot = prepared.associateWith { p ->
            p.ops.filter { it !is FileOp.Create }.mapNotNull { p.target.findFileByRelativePath(it.relPath) }
        }
        val files = filesByRoot.values.flatten()
        if (files.isEmpty()) return null
        val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files)
        if (!status.hasReadonlyFiles()) return null
        val readOnly = status.readonlyFiles.toSet()
        return prepared.associateWith { p ->
            val mine = filesByRoot.getValue(p).filter { it in readOnly }.mapNotNull { GoldenTargets.relativePath(p.target, it) }
            if (mine.isEmpty()) p.result(WriteResult.Status.NOT_RUN, message("writer.notRun"))
            else p.result(WriteResult.Status.READ_ONLY, message("writer.readOnly", mine.size, pathList(mine)), readOnly = mine)
        }
    }

    // ---------------------------------------------------------------- writing

    private class Outcome(val target: VirtualFile) {
        val written = ArrayList<String>()
        val created = ArrayList<String>()
        val deleted = ArrayList<String>()
        val skipped = ArrayList<WriteResult.Skip>()
        val notUndoable = ArrayList<String>()
        val modes = ArrayList<Pair<VirtualFile, Boolean>>()
        val createdFiles = HashSet<VirtualFile>()

        fun done(relPath: String): Boolean = relPath in written || relPath in created || relPath in deleted || skipped.any { it.relPath == relPath }
    }

    private fun execute(project: Project, prepared: Prepared, @Nls label: String, confirmation: UndoConfirmationPolicy): WriteResult {
        if (prepared.ops.isEmpty()) return prepared.result(WriteResult.Status.NOTHING_TO_DO, null)
        val outcome = Outcome(prepared.target)
        outcome.skipped += prepared.skipped
        val history = LocalHistory.getInstance()
        val labelId = history.putSystemLabel(project, label).id
        val action = history.startAction(prepared.commandName)
        try {
            WriteCommandAction.writeCommandAction(project)
                .withName(prepared.commandName)
                .withGlobalUndo()
                .withUndoConfirmationPolicy(confirmation)
                .shouldRecordActionForActiveDocument(false)
                .run<RuntimeException> {
                    for (op in prepared.ops) {
                        try {
                            beforeOpForTests?.invoke(op)
                            perform(project, prepared.target, op, outcome)
                        } catch (e: Throwable) {
                            // A read-only document, a vetoed save, an I/O error or any other error: the other
                            // operations still run, the result says so (S11). A cancellation stops the rest.
                            failed(op.relPath, e, outcome)
                            if (e is ControlFlowException) break
                        }
                    }
                    ModeUndo.register(project, outcome.modes, outcome.createdFiles)
                }
        } catch (e: Throwable) {
            LOG.warn("Writing ${prepared.target.path} stopped (${e.javaClass.name})")
        } finally {
            action.finish()
        }
        // Whatever an error left undone is reported, never dropped (S11).
        for (op in prepared.ops) if (!outcome.done(op.relPath)) outcome.skipped += WriteResult.Skip(op.relPath, WriteResult.SkipReason.FAILED)
        applyModes(outcome)
        return WriteResult(
            prepared.target, WriteResult.Status.APPLIED, null, outcome.written, outcome.created, outcome.deleted,
            outcome.skipped, outcome.notUndoable, emptyList(), emptyList(), labelId, prepared.commandName,
        )
    }

    private fun failed(relPath: String, e: Throwable, outcome: Outcome) {
        LOG.warn("Cannot write $relPath in ${outcome.target.path} (${e.javaClass.name})")
        outcome.skipped += WriteResult.Skip(relPath, WriteResult.SkipReason.FAILED)
    }

    /** One operation, inside the command. */
    private fun perform(project: Project, target: VirtualFile, op: FileOp, outcome: Outcome) {
        val history = LocalHistory.getInstance()
        when (op) {
            is FileOp.Write -> {
                val file = target.findFileByRelativePath(op.relPath)?.takeIf { it.isValid && !it.isDirectory }
                    ?: return run { outcome.skipped += WriteResult.Skip(op.relPath, WriteResult.SkipReason.NOT_FOUND) }
                val undoable = write(project, file, op.content)
                outcome.written += op.relPath
                if (!undoable) outcome.notUndoable += op.relPath
                op.executable?.let { outcome.modes += file to it }
            }
            is FileOp.Create -> {
                val dir = op.relPath.substringBeforeLast('/', "")
                val parent = if (dir.isEmpty()) target else VfsUtil.createDirectoryIfMissing(target, dir)
                    ?: throw IOException("cannot create $dir")
                val file = parent.createChildData(this, op.relPath.substringAfterLast('/'))
                writeNew(project, file, op.content)
                outcome.created += op.relPath
                outcome.createdFiles += file
                if (!history.isUnderControl(file)) outcome.notUndoable += op.relPath
                op.executable?.let { outcome.modes += file to it }
            }
            is FileOp.Delete -> {
                val file = target.findFileByRelativePath(op.relPath)?.takeIf { it.isValid && !it.isDirectory }
                    ?: return run { outcome.skipped += WriteResult.Skip(op.relPath, WriteResult.SkipReason.NOT_FOUND) }
                // Local History keeps no binary content, so Undo could bring the file back but not its bytes.
                val restorable = history.isUnderControl(file) && !RoleFiles.isBinary(file, null)
                // Local History takes a deleted file's content from the VFS content cache: load it there first, or
                // Undo brings back a file without its content (role files are rarely opened before a push).
                if (restorable) file.contentsToByteArray(true)
                file.delete(this)
                outcome.deleted += op.relPath
                if (!restorable) outcome.notUndoable += op.relPath
            }
        }
    }

    /**
     * Replaces [file]'s content; true when Undo restores it (it went through the document). Which way the content goes
     * is decided before a document is loaded (S3): bytes no document can hold never load one. Write action.
     */
    private fun write(project: Project, file: VirtualFile, content: OpContent): Boolean {
        val documents = FileDocumentManager.getInstance()
        when (content) {
            is OpContent.Text -> {
                val document = if (file.fileType.isBinary) null else documents.getDocument(file)
                if (document == null) {
                    setBytes(file, content.text.toByteArray(file.charset))
                    return false
                }
                replace(project, file, document, StringUtil.convertLineSeparators(content.text), null)
                return true
            }
            is OpContent.Bytes -> {
                val rewrite = if (file.fileType.isBinary) null else rewriteOf(file, content.bytes)
                val document = rewrite?.let { documents.getDocument(file) }
                if (rewrite == null || document == null) {
                    setBytes(file, content.bytes)
                    return false
                }
                replace(project, file, document, rewrite.text, rewrite.form)
                if (savedExactly(file, content.bytes)) return true
                setBytes(file, content.bytes)
                return false
            }
        }
    }

    /**
     * Writes [bytes] through the VFS without making the command refuse Undo (S3): a document of [file] that is loaded
     * reloads from the new bytes inside the command, and that reload is recorded as an undoable change
     * (`UndoUtil.forceUndoIn`) instead of a non-undoable one, which would make the whole command non-undoable. With no
     * document loaded nothing is recorded at all. Write action.
     */
    private fun setBytes(file: VirtualFile, bytes: ByteArray) {
        if (FileDocumentManager.getInstance().getCachedDocument(file) == null) file.setBinaryContent(bytes)
        else UndoUtil.forceUndoIn(file) { file.setBinaryContent(bytes) }
    }

    /** The content of a file created in this command (Undo deletes the file, whatever it holds). Write action. */
    private fun writeNew(project: Project, file: VirtualFile, content: OpContent) {
        when (content) {
            is OpContent.Bytes -> file.setBinaryContent(content.bytes)
            is OpContent.Text -> {
                val document = if (file.fileType.isBinary) null else FileDocumentManager.getInstance().getDocument(file)
                if (document == null) file.setBinaryContent(content.text.toByteArray(file.charset))
                else replace(project, file, document, StringUtil.convertLineSeparators(content.text), null)
            }
        }
    }

    /**
     * [document] gets [text] (LF separators) and [file] the [form] (byte order mark, line separator) undoably, then
     * the document is saved, with the trailing-space stripper off so the bytes are what was asked for. Inside a command.
     */
    private fun replace(project: Project, file: VirtualFile, document: Document, text: String, form: FileForm?) {
        if (!document.isWritable) throw IOException("read-only document")
        if (form != null) {
            UndoManager.getInstance(project).undoableActionPerformed(FileFormUndo(document, file, FileForm.of(file), form))
            form.applyTo(file)
        }
        document.setText(text)
        StripperHold.during(listOf(file)) { FileDocumentManager.getInstance().saveDocument(document) }
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) throw IOException("not saved")
    }

    /** Whether [file] holds exactly [bytes] (the VFS has the saved content at once, before the disk). */
    private fun savedExactly(file: VirtualFile, bytes: ByteArray): Boolean = try {
        file.contentsToByteArray().contentEquals(bytes)
    } catch (_: IOException) {
        false
    }

    /** The byte order mark and line separator a file is saved with (its document holds the text, with LF). */
    private class FileForm(val bom: ByteArray?, val separator: String?) {
        fun applyTo(file: VirtualFile) {
            file.bom = bom
            file.detectedLineSeparator = separator
        }

        companion object {
            fun of(file: VirtualFile) = FileForm(file.bom, file.detectedLineSeparator)
        }
    }

    /** Undo and redo of a [FileForm] change, in the command of the text change it goes with. */
    private class FileFormUndo(document: Document, private val file: VirtualFile, private val before: FileForm, private val after: FileForm) :
        BasicUndoableAction(document) {
        override fun undo() = before.applyTo(file)

        override fun redo() = after.applyTo(file)
    }

    private class Rewrite(val text: String, val form: FileForm)

    /**
     * [bytes] as document text and form for [file]: decodable in the file's charset (a UTF-8 byte order mark kept as
     * the form's), with one kind of line separator. Null when the document cannot reproduce them (binary, mixed
     * separators, another charset): the caller writes the bytes through the VFS.
     */
    private fun rewriteOf(file: VirtualFile, bytes: ByteArray): Rewrite? {
        val charset = file.charset
        val bom = if (charset == Charsets.UTF_8 && RoleFiles.startsWith(bytes, RoleFiles.UTF8_BOM)) RoleFiles.UTF8_BOM.copyOf() else null
        val decoded = RoleFiles.decodeText(bytes, charset) ?: return null
        val crlf = decoded.windowed(2).count { it == "\r\n" }
        val cr = decoded.count { it == '\r' } - crlf
        val lf = decoded.count { it == '\n' } - crlf
        val separator = when {
            listOf(crlf, cr, lf).count { it > 0 } > 1 -> return null
            crlf > 0 -> "\r\n"
            cr > 0 -> "\r"
            lf > 0 -> "\n"
            else -> file.detectedLineSeparator
        }
        return Rewrite(StringUtil.convertLineSeparators(decoded), FileForm(bom, separator))
    }

    /**
     * S12:Undo and redo of the executable bits the command sets (they are set after the command, once the files are
     * flushed): undo puts back the modes the written files had, redo sets the new ones again. Created files are left
     * alone on undo (Undo deletes them). Never through a symbolic link.
     */
    private class ModeUndo(files: List<VirtualFile>, private val changes: List<Change>) : BasicUndoableAction(*files.toTypedArray()) {
        class Change(val path: Path, val before: Set<PosixFilePermission>?, val after: Set<PosixFilePermission>)

        override fun undo() = changes.forEach { change -> change.before?.let { set(change.path, it) } }

        override fun redo() = changes.forEach { set(it.path, it.after) }

        private fun set(path: Path, permissions: Set<PosixFilePermission>) {
            try {
                if (!Files.isSymbolicLink(path) && Files.exists(path)) Files.setPosixFilePermissions(path, permissions)
            } catch (e: IOException) {
                LOG.warn("Cannot restore the mode of $path (${e.javaClass.name})")
            } catch (_: UnsupportedOperationException) {
                // No POSIX modes on this file system.
            }
        }

        companion object {
            /**
             * Records the mode changes of [modes] in the current command (only files whose mode really changes; the
             * [created] ones have no mode to go back to: Undo deletes them).
             */
            fun register(project: Project, modes: List<Pair<VirtualFile, Boolean>>, created: Set<VirtualFile>) {
                val changes = ArrayList<Change>()
                val files = ArrayList<VirtualFile>()
                for ((file, executable) in modes) {
                    val path = file.toNioPathOrNull() ?: continue
                    val before = try {
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: IOException) {
                        continue
                    } catch (_: UnsupportedOperationException) {
                        continue
                    }
                    val after = withExecutable(before, executable)
                    if (after == before) continue
                    changes += Change(path, before.takeIf { file !in created }, after)
                    files += file
                }
                if (changes.isNotEmpty()) UndoManager.getInstance(project).undoableActionPerformed(ModeUndo(files, changes))
            }
        }
    }

    /** Sets the executable bits after the command, once the async writes are flushed (a refresh flushes them). */
    private fun applyModes(outcome: Outcome) {
        val local = outcome.modes.mapNotNull { (file, executable) -> file.toNioPathOrNull()?.let { it to executable } }
        if (local.isEmpty()) return
        LocalFileSystem.getInstance().refreshNioFiles(local.map { it.first })
        for ((path, executable) in local) {
            try {
                setExecutable(path, executable)
            } catch (e: IOException) {
                LOG.warn("Cannot set the mode of $path (${e.javaClass.name})")
            } catch (_: UnsupportedOperationException) {
                // No POSIX modes on this file system.
            }
        }
    }

    /** chmod +x (owner, and group/others where they may read) or -x; never through a symbolic link. */
    internal fun setExecutable(path: Path, executable: Boolean) {
        if (Files.isSymbolicLink(path)) return
        val before = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        val after = withExecutable(before, executable)
        if (after != before) Files.setPosixFilePermissions(path, after)
    }

    private fun withExecutable(before: Set<PosixFilePermission>, executable: Boolean): Set<PosixFilePermission> {
        val after = before.toMutableSet()
        if (executable) {
            after += PosixFilePermission.OWNER_EXECUTE
            if (PosixFilePermission.GROUP_READ in before) after += PosixFilePermission.GROUP_EXECUTE
            if (PosixFilePermission.OTHERS_READ in before) after += PosixFilePermission.OTHERS_EXECUTE
        } else {
            after -= setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
        }
        return after
    }

    private fun defaultName(project: Project, root: VirtualFile): String =
        RoleCatalog.getInstance(project).copyOf(root)?.root?.displayName ?: root.parent?.name ?: root.name

    @Nls
    private fun pathList(paths: List<String>): String =
        if (paths.size <= MAX_LISTED) paths.joinToString(", ")
        else message("writer.list.more", paths.take(MAX_LISTED).joinToString(", "), paths.size - MAX_LISTED)

    private const val MAX_LISTED = 3

    /** Runs before each operation inside the command (tests: an operation that fails with any error). */
    @TestOnly
    @Volatile
    internal var beforeOpForTests: ((FileOp) -> Unit)? = null
}

/**
 * Keeps the platform's trailing-space stripper (and its "ensure a line feed at the end") off for files while golden
 * code writes them or a golden window may save them (plan amendment R24, S6): `TrailingSpacesStripper.setEnabled` is
 * one flag per file, so nested holds are counted here and the flag comes back only when the last hold ends.
 */
internal object StripperHold {
    private val holds = HashMap<VirtualFile, Int>()

    fun hold(files: Collection<VirtualFile>) = synchronized(holds) {
        for (file in files) {
            val count = holds[file] ?: 0
            holds[file] = count + 1
            if (count == 0) TrailingSpacesStripper.setEnabled(file, false)
        }
    }

    fun release(files: Collection<VirtualFile>) = synchronized(holds) {
        for (file in files) {
            val count = holds[file] ?: continue
            if (count <= 1) {
                holds.remove(file)
                TrailingSpacesStripper.setEnabled(file, true)
            } else {
                holds[file] = count - 1
            }
        }
    }

    inline fun <T> during(files: Collection<VirtualFile>, block: () -> T): T {
        hold(files)
        try {
            return block()
        } finally {
            release(files)
        }
    }
}
