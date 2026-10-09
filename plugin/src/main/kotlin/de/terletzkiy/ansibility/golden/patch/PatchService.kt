package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.sync.PlanEntry
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleFiles
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Copy as Patch for Golden…" (plan amendment R25, X126): a unified diff that turns the golden copy of a role into
 * this copy, for a merge request in the golden repository (the way upstream when golden is a read-only mirror, D198).
 *
 * 1. **Files**, in the background (both role directories refreshed from disk first): the plan from this copy to
 *    golden ([RoleFilePlan], drift's skip rules). A copy row offers every differing file, `molecule/` only when drift
 *    does not ignore it ("Ignore molecule/"); a selected file is offered alone, a selected folder with what differs below
 *    it, `molecule/` included either way (the user picked it).
 * 2. **Dialog** ([PatchUi.choose]): the files, "Include key and vault files" (off: they are left out and named in the
 *    patch's note), Copy to Clipboard or Save as .patch….
 * 3. **Patch** ([GitPatchWriter]): paths relative to the golden repository ([GoldenPatchBase]: `a/roles/web/…`), so
 *    `git apply` works there. Golden's side is its content on disk (what git sees); this copy's side is what the user
 *    sees (an unsaved document as saving would write it). New files as `new file mode`, files only golden has as
 *    `deleted file mode`, a changed executable bit as `old mode`/`new mode`. Binary files are noted
 *    ("Binary files … differ") in the note before the first diff, which `git apply` skips; their content is never in
 *    the patch. Key and vault files go in only when included, as they are: never decrypted.
 * 4. **Output**: the clipboard ([CopyPasteManager]) or a file ([PatchUi.saveTarget]), then a short notification.
 *
 * Writes nothing into a role, never runs git, logs no content.
 */
@Service(Service.Level.PROJECT)
class PatchService(private val project: Project, private val scope: CoroutineScope) {
    /** "Copy as Patch for Golden…" of [target] (a role copy, or a file or folder in it, with a golden copy). */
    fun copyAsPatch(target: GoldenTarget): Job = scope.launch { run(target) }

    /** Runs the whole flow for [target] (see the class comment). Any context; never blocks the EDT. */
    suspend fun run(target: GoldenTarget): PatchOutcome {
        val request = prepare(target) ?: return PatchOutcome(PatchOutcome.Status.NOT_APPLICABLE, null, null)
        val model = request.model
        val texts = PatchTexts(model)
        if (model.rows.isEmpty()) return onEdt { told(PatchOutcome.Status.SAME, texts.same(request.relPath), null) }
        if (model.diffCount(includeSensitive = true) == 0) return onEdt { told(PatchOutcome.Status.NOTHING_TO_PATCH, texts.onlyBinary(), null) }

        val choice = onEdt { if (project.isDisposed) null else PatchUi.getInstance().choose(project, model) }
            ?: return PatchOutcome(PatchOutcome.Status.CANCELLED, null, null)
        val patch = build(request, choice.includeSensitive)
        if (patch.fileCount == 0) return onEdt { told(PatchOutcome.Status.NOTHING_TO_PATCH, texts.nothingInPatch(), patch) }
        return when (choice.output) {
            PatchOutput.CLIPBOARD -> onEdt { copy(patch, texts) }
            PatchOutput.FILE -> save(patch, texts)
        }
    }

    /** The files [target] offers, or null when it has no golden copy other than itself. Background, with progress. */
    internal suspend fun prepare(target: GoldenTarget): PatchRequest? {
        val relPath = target.relPath?.let { GoldenTargets.normalize(it) ?: return null }
        val copy = target.copy
        val golden = readAction { RoleCatalog.getInstance(project).snapshot().reference(copy.name) } ?: return null
        if (golden.dir == copy.dir) return null
        val entries = withBackgroundProgress(project, message("patch.progress", copy.name, copy.root.displayName)) {
            withContext(Dispatchers.IO) {
                val dirs = listOf(copy.dir, golden.dir).filter { it.isValid && it.isInLocalFileSystem }
                if (dirs.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *dirs.toTypedArray())
            }
            offered(copy, golden, relPath)
        }
        val base = withContext(Dispatchers.IO) { GoldenPatchBase.of(project, golden) }
        val model = PatchModel(
            roleName = copy.name,
            copyName = copy.root.displayName,
            goldenName = golden.root.displayName,
            base = base,
            rows = entries.map { PatchRow(it.relPath, changeOf(it.kind), it.binary, it.sensitive) },
            defaultFileName = PatchModel.fileName(copy.name, copy.root.displayName, golden.root.displayName),
        )
        return PatchRequest(model, entries, relPath)
    }

    /**
     * The differing files: a copy row → all of them (`molecule/` as drift's option says); a file → that file; a folder
     * → those below it. This copy is the plan's source, golden its target; key and vault files are flagged, never read
     * here.
     */
    private suspend fun offered(copy: RoleCopy, golden: RoleCopy, relPath: String?): List<PlanEntry> {
        if (relPath == null) {
            return RoleFilePlan.compute(project, copy.dir, golden.dir, PlanOptions.fromDrift(project, includeSensitive = true)).entries
        }
        val options = PlanOptions(ignoreMolecule = false, includeSensitive = true)
        val folder = readAction { isFolder(copy.dir, relPath) || isFolder(golden.dir, relPath) }
        if (!folder) return RoleFilePlan.computePath(project, copy.dir, golden.dir, relPath, options).entries
        return RoleFilePlan.compute(project, copy.dir, golden.dir, options).entries.filter { it.relPath.startsWith("$relPath/") }
    }

    private fun isFolder(dir: VirtualFile, relPath: String): Boolean = dir.findFileByRelativePath(relPath)?.let { it.isValid && it.isDirectory } == true

    /**
     * The patch of [request] with key and vault files [includeSensitive] or left out. Background dispatcher, one short
     * read action per file; the content read is dropped with the patch.
     */
    internal suspend fun build(request: PatchRequest, includeSensitive: Boolean): GoldenPatch = withContext(Dispatchers.Default) {
        val base = request.model.base
        val diffs = GitPatchWriter.Output()
        val files = ArrayList<String>()
        val binaries = ArrayList<String>()
        val binaryLines = ArrayList<String>()
        val leftOut = ArrayList<String>()
        val unreadable = ArrayList<String>()
        for (entry in request.entries) {
            ProgressManager.checkCanceled()
            val path = base.pathOf(entry.relPath)
            if (entry.sensitive && !includeSensitive) {
                // Never read: named in the note only.
                leftOut += entry.relPath
                continue
            }
            if (entry.binary) {
                binaries += entry.relPath
                binaryLines += GitPatchWriter.binaryLine(path, entry.targetFile != null, entry.sourceFile != null)
                continue
            }
            val sides = readAction { Sides(entry.targetFile?.let(::diskBytes), entry.sourceFile?.let { RoleFiles.bytes(project, it) }) }
            val old = sides.golden
            val new = sides.copy
            if ((entry.targetFile != null && old == null) || (entry.sourceFile != null && new == null)) {
                unreadable += entry.relPath
                continue
            }
            if (old != null && new != null && old.contentEquals(new)) continue
            if (old?.let(GitPatchWriter::isText) == false || new?.let(GitPatchWriter::isText) == false) {
                binaries += entry.relPath
                binaryLines += GitPatchWriter.binaryLine(path, old != null, new != null)
                continue
            }
            GitPatchWriter.write(diffs, GitPatchWriter.FileChange(path, old, new, entry.targetExecutable, entry.sourceExecutable))
            files += entry.relPath
        }
        val out = GitPatchWriter.Output()
        val texts = PatchTexts(request.model)
        out.line(texts.preambleTitle(files.size))
        out.line(texts.preambleApply())
        if (binaryLines.isNotEmpty()) {
            out.line(message("patch.preamble.binary"))
            binaryLines.forEach(out::line)
        }
        for (relPath in leftOut) out.line(message("patch.preamble.leftOut", base.pathOf(relPath)))
        for (relPath in unreadable) out.line(message("patch.preamble.unreadable", base.pathOf(relPath)))
        out.blank()
        GoldenPatch(out.bytes() + diffs.bytes(), files, binaries, leftOut, unreadable)
    }

    /** Golden's side: the file on disk, what `git apply` sees there (an unsaved golden edit is not in it). Read action. */
    private fun diskBytes(file: VirtualFile): ByteArray? = try {
        if (file.isValid) file.contentsToByteArray(false) else null
    } catch (e: IOException) {
        LOG.debug("Cannot read ${file.path} (${e.javaClass.simpleName})")
        null
    }

    private class Sides(val golden: ByteArray?, val copy: ByteArray?)

    /** Copies [patch] to the clipboard and says so. EDT. */
    private fun copy(patch: GoldenPatch, texts: PatchTexts): PatchOutcome {
        if (project.isDisposed) return PatchOutcome(PatchOutcome.Status.NOT_APPLICABLE, patch, null)
        CopyPasteManager.getInstance().setContents(StringSelection(patch.text))
        val text = texts.copied(patch)
        PatchUi.getInstance().notify(project, text)
        return PatchOutcome(PatchOutcome.Status.COPIED, patch, text)
    }

    /** Asks where to save [patch], writes it there and says so. */
    private suspend fun save(patch: GoldenPatch, texts: PatchTexts): PatchOutcome {
        val path = onEdt { if (project.isDisposed) null else PatchUi.getInstance().saveTarget(project, texts.model.defaultFileName) }
            ?: return PatchOutcome(PatchOutcome.Status.CANCELLED, patch, null)
        try {
            withContext(Dispatchers.IO) {
                path.parent?.let { Files.createDirectories(it) }
                Files.write(path, patch.bytes)
            }
        } catch (e: IOException) {
            LOG.info("Cannot save the patch (${e.javaClass.simpleName})")
            return onEdt { told(PatchOutcome.Status.FAILED, texts.saveFailed(path, e), patch) }
        }
        LocalFileSystem.getInstance().refreshNioFiles(listOf(path))
        val text = texts.saved(patch, path)
        onEdt { if (!project.isDisposed) PatchUi.getInstance().notify(project, text) }
        return PatchOutcome(PatchOutcome.Status.SAVED, patch, text)
    }

    /** Tells [text] (a notice) and returns the outcome. EDT. */
    private fun told(status: PatchOutcome.Status, @Nls text: String, patch: GoldenPatch?): PatchOutcome {
        if (!project.isDisposed) PatchUi.getInstance().inform(project, text)
        return PatchOutcome(status, patch, text)
    }

    /** [block] on the EDT, outside any modal dialog. */
    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { block() }

    companion object {
        private val LOG = logger<PatchService>()

        fun getInstance(project: Project): PatchService = project.service()

        internal fun changeOf(kind: PlanKind): PatchChange = when (kind) {
            PlanKind.CHANGED -> PatchChange.CHANGED
            PlanKind.ONLY_IN_SOURCE -> PatchChange.ADDED
            PlanKind.ONLY_IN_TARGET -> PatchChange.DELETED
        }
    }
}

/** What one patch is made from: the dialog's [model], the plan entries behind its rows, the selected path (null: the copy). */
internal class PatchRequest(val model: PatchModel, val entries: List<PlanEntry>, val relPath: String?)

/** The texts of one patch for golden. */
internal class PatchTexts(val model: PatchModel) {
    @Nls
    fun same(relPath: String?): String =
        if (relPath == null) message("patch.same", model.roleName, model.copyName, model.goldenName)
        else message("patch.same.path", relPath, model.roleName, model.copyName, model.goldenName)

    @Nls
    fun onlyBinary(): String = message("patch.onlyBinary", model.roleName, model.copyName, model.goldenName)

    @Nls
    fun nothingInPatch(): String = message("patch.nothing", model.roleName)

    @Nls
    fun copied(patch: GoldenPatch): String = withNotes(message("patch.copied", model.roleName, patch.fileCount), patch)

    @Nls
    fun saved(patch: GoldenPatch, path: Path): String = withNotes(message("patch.saved", model.roleName, patch.fileCount, path.fileName.toString()), patch)

    @Nls
    fun saveFailed(path: Path, e: IOException): String = message("patch.saveFailed", path.toString(), e.javaClass.simpleName)

    /** The first line of the patch's note. */
    @Nls
    fun preambleTitle(files: Int): String = message("patch.preamble.title", model.roleName, model.goldenName, model.copyName, files)

    /** Where the patch applies, and how (under its default file name). */
    @Nls
    fun preambleApply(): String = message("patch.preamble.apply", model.base.location, model.defaultFileName)

    /** [text] and what the patch does not hold (binary files, key and vault files left out, unreadable files). */
    @Nls
    private fun withNotes(@Nls text: String, patch: GoldenPatch): String {
        val notes = listOfNotNull(
            patch.binaries.size.takeIf { it > 0 }?.let { message("patch.note.binary", it) },
            patch.leftOut.size.takeIf { it > 0 }?.let { message("patch.note.leftOut", it) },
            patch.unreadable.size.takeIf { it > 0 }?.let { message("patch.note.unreadable", it) },
        )
        return if (notes.isEmpty()) text else message("patch.withNotes", text, notes.joinToString(message("patch.note.separator")))
    }
}
