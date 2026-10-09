package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.model.drift.DriftRules
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TreeMap

/** How a path of a [RoleFilePlan] differs between the source and the target role directory. */
enum class PlanKind {
    /** In both directories, with different content. */
    CHANGED,

    /** Only in the source directory. */
    ONLY_IN_SOURCE,

    /** Only in the target directory. */
    ONLY_IN_TARGET,
}

/**
 * What a file looked like at one moment (plan amendment R24, the stale-target guard): whether it exists, its VFS
 * modification stamp and, while it has an unsaved document, that document's stamp. Two stamps are equal exactly when
 * nothing about the file changed in between (an edit, a save, a reload, a deletion or a creation all change it).
 */
data class FileStamp(val exists: Boolean, val modificationStamp: Long, val documentStamp: Long) {
    override fun toString(): String = if (exists) "FileStamp($modificationStamp, document=$documentStamp)" else "FileStamp(absent)"

    companion object {
        /** A file that does not exist. */
        val ABSENT: FileStamp = FileStamp(false, -1, -1)

        /** The stamp of [file] now ([ABSENT] for null or an invalid file). Read action (or EDT). */
        fun of(file: VirtualFile?): FileStamp {
            if (file == null || !file.isValid || file.isDirectory) return ABSENT
            val document = RoleFiles.unsavedDocument(file)
            return FileStamp(true, file.modificationStamp, document?.modificationStamp ?: -1)
        }
    }
}

/** Options of a [RoleFilePlan]. */
data class PlanOptions(
    /**
     * Leave `molecule/` out entirely (R9's D41, "Ignore molecule/ in drift"). Only Compare follows that setting
     * ([fromDrift]); Align, Push and the takes always include `molecule/` (U4: a mirror is byte-identical).
     */
    val ignoreMolecule: Boolean = false,
    /**
     * Include sensitive files ([PlanEntry.sensitive]) in [RoleFilePlan.included]. Off by default: they are still
     * reported, flagged [PlanEntry.excluded]. Their content is never decrypted either way.
     */
    val includeSensitive: Boolean = false,
) {
    companion object {
        /** The drift setting's `molecule/` choice ([RoleDriftService.options]) with [includeSensitive]; Compare's options. */
        fun fromDrift(project: Project, includeSensitive: Boolean = false): PlanOptions =
            PlanOptions(RoleDriftService.getInstance(project).options.ignoreMolecule, includeSensitive)
    }
}

/**
 * One differing path of a [RoleFilePlan]. Facts only: no content and no hash is ever kept.
 */
class PlanEntry internal constructor(
    /** The path relative to both role directories, with `/` separators ("tasks/main.yml"). */
    val relPath: String,
    val kind: PlanKind,
    /**
     * The file type is binary, or a side's first bytes are no valid text (a NUL byte, not decodable in its charset;
     * only the first 8 KB are looked at).
     */
    val binary: Boolean,
    /**
     * Key material or a secret by name ([DriftRules.isSensitivePath]), a whole-file vault on either side
     * ([DriftRules.isWholeFileVault]), or a private key `PrivateKeySignatures` reads in either side's first bytes: its
     * content is never shown and never decrypted.
     */
    val sensitive: Boolean,
    /** Either side is a whole-file vault (ciphertext): Align warns when vault ids differ. */
    val wholeFileVault: Boolean,
    /** Left out of [RoleFilePlan.included]: [sensitive] without [PlanOptions.includeSensitive]. */
    val excluded: Boolean,
    /** The source's owner-executable bit (false when the source has no such file or the file system has no modes). */
    val sourceExecutable: Boolean,
    /** The target's owner-executable bit (false when the target has no such file or the file system has no modes). */
    val targetExecutable: Boolean,
    /** The source's size in bytes (an unsaved document as saving would write it), or -1 when it has no such file. */
    val sourceSize: Long,
    /** The target's size in bytes (an unsaved document as saving would write it), or -1 when it has no such file. */
    val targetSize: Long,
    /** The source file, or null for [PlanKind.ONLY_IN_TARGET]. */
    val sourceFile: VirtualFile?,
    /** The target file, or null for [PlanKind.ONLY_IN_SOURCE]. */
    val targetFile: VirtualFile?,
    /** The source file's [FileStamp] when the plan was made (the stale-source guard of Align and Push). */
    val sourceStamp: FileStamp,
    /** The target file's [FileStamp] when the plan was made; pass it as `FileOp.expected` ([FileStamp.ABSENT] if missing). */
    val targetStamp: FileStamp,
) {
    /** The source's executable bit differs from the target's (both files exist). */
    val executableDiffers: Boolean get() = kind == PlanKind.CHANGED && sourceExecutable != targetExecutable

    /** This entry with [excluded] set anew. */
    internal fun withExcluded(excluded: Boolean): PlanEntry = if (excluded == this.excluded) this else PlanEntry(
        relPath, kind, binary, sensitive, wholeFileVault, excluded, sourceExecutable, targetExecutable, sourceSize, targetSize,
        sourceFile, targetFile, sourceStamp, targetStamp,
    )

    override fun toString(): String =
        "PlanEntry($relPath, $kind${if (binary) ", binary" else ""}${if (sensitive) ", sensitive" else ""}${if (excluded) ", excluded" else ""})"
}

/**
 * The file-level difference of two role directories (plan amendment R24): the shared engine of Compare (D182), Align
 * (D184–D187) and Push (D188–D191).
 *
 * - **Files** are walked with role drift's rules ([DriftRules.isSkippedDirectory], [DriftRules.isSkippedFile], never a
 *   `.git` file, no symbolic link, the project's ignored paths), without `molecule/` when [PlanOptions.ignoreMolecule].
 *   A path one side leaves out (an ignored path or a link there) is no difference, also when the other side has a
 *   file there (D191: Push never deletes what its source's rules hide).
 * - **Equality** is by content: an unsaved document counts as saving would write it (its file's line separator,
 *   charset and byte order mark, as drift does), so a plan always matches what the user sees. Files of different
 *   lengths differ without being read; files of the same length are compared chunk by chunk (P5). Nothing is hashed,
 *   kept or logged, and the first bytes read from sensitive files are zeroed after use. A difference in the
 *   executable bit alone is not a difference (as in drift); [PlanEntry.executableDiffers] tells it for changed files.
 * - **Sensitive** files are reported, flagged, and [PlanEntry.excluded] unless [PlanOptions.includeSensitive];
 *   nothing is ever decrypted.
 * - **Case**: on a target file system that ignores case, paths that differ only in case between the two sides (and
 *   the paths below them) take no part ([caseConflicts]): a mirror would write one onto the other.
 *
 * [entries] are sorted by path. Immutable; the files and stamps it holds may be stale by the time a caller writes,
 * which `RoleWriter` checks, and [changesSince] tells whether anything changed since the plan was made.
 */
class RoleFilePlan internal constructor(
    /** The source role directory (golden for Compare with Golden, the copy merged in for Align, the pushed copy). */
    val source: VirtualFile,
    /** The target role directory (the copy that is compared, aligned or pushed to). */
    val target: VirtualFile,
    val options: PlanOptions,
    /** Every differing path, sorted by path, sensitive ones included (flagged). */
    val entries: List<PlanEntry>,
    /** The number of files of the source that take part (after the skip rules and [options]). */
    val sourceFileCount: Int,
    /** The number of files of the target that take part (after the skip rules and [options]). */
    val targetFileCount: Int,
    /** Paths left out because they differ only in case from a path of the other side (case-insensitive target), sorted. */
    val caseConflicts: List<String> = emptyList(),
    /** The stamp of every file that took part on the source side when the plan was made ([changesSince]). */
    internal val sourceStamps: Map<String, FileStamp> = emptyMap(),
    /** The stamp of every file that took part on the target side when the plan was made ([changesSince]). */
    internal val targetStamps: Map<String, FileStamp> = emptyMap(),
) {
    /** The entries an operation acts on: [entries] without the [PlanEntry.excluded] ones. */
    val included: List<PlanEntry> get() = entries.filterNot { it.excluded }

    /** The sensitive entries left out ([PlanOptions.includeSensitive] off). */
    val excluded: List<PlanEntry> get() = entries.filter { it.excluded }

    /** Whether nothing differs. */
    val isIdentical: Boolean get() = entries.isEmpty()

    /** The entry of [relPath], or null when that path does not differ. */
    fun entry(relPath: String): PlanEntry? = entries.firstOrNull { it.relPath == relPath }

    fun count(kind: PlanKind): Int = entries.count { it.kind == kind }

    /** This plan with [PlanOptions.includeSensitive] set to [include] (the entries' [PlanEntry.excluded] follow). */
    fun withSensitive(include: Boolean): RoleFilePlan = if (include == options.includeSensitive) this else RoleFilePlan(
        source, target, options.copy(includeSensitive = include), entries.map { it.withExcluded(it.sensitive && !include) },
        sourceFileCount, targetFileCount, caseConflicts, sourceStamps, targetStamps,
    )

    /** What changed on each side since a plan was made ([changesSince]); paths sorted. */
    class Changes(val source: List<String>, val target: List<String>) {
        val isEmpty: Boolean get() = source.isEmpty() && target.isEmpty()

        override fun toString(): String = "Changes(source=$source, target=$target)"
    }

    /**
     * The paths of each side that changed since the plan was made (S4: Push writes what its dialog showed, or
     * nothing): a file added, removed, edited, saved or reloaded. Walks both directories again and compares stamps;
     * reads no content. Background dispatcher, read action.
     */
    suspend fun changesSince(project: Project): Changes = withContext(Dispatchers.Default) {
        val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
        readAction {
            val now = stampsOf(RoleFiles.walkWithSkips(source, ignored, options.ignoreMolecule).files)
            val nowTarget = if (target == source) now else stampsOf(RoleFiles.walkWithSkips(target, ignored, options.ignoreMolecule).files)
            Changes(differing(sourceStamps, now).sorted(), differing(targetStamps, nowTarget).sorted())
        }
    }

    override fun toString(): String = "RoleFilePlan(${source.path} → ${target.path}, ${entries.size} differing)"

    companion object {
        /**
         * The plan from [source] to [target] (role directories). Runs on a background dispatcher whatever the
         * caller's context: one read action walks both directories, then one short read action per path reads only
         * what that path needs (P5), each restarted alone when a write action interrupts it; cancellable. Never call
         * it through `runBlocking` on the EDT; blocking background callers use [computeBlocking].
         */
        suspend fun compute(project: Project, source: VirtualFile, target: VirtualFile, options: PlanOptions = PlanOptions()): RoleFilePlan =
            withContext(Dispatchers.Default) {
                val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
                val walks = readAction { walks(source, target, ignored, options) }
                val context = Context(project, source, target, options, walks)
                val evaluations = ArrayList<Evaluation>()
                for (relPath in context.paths) {
                    ProgressManager.checkCanceled()
                    evaluations += readAction { context.evaluate(relPath) }
                }
                context.plan(evaluations)
            }

        /** [compute] for two catalog copies. */
        suspend fun compute(project: Project, source: RoleCopy, target: RoleCopy, options: PlanOptions = PlanOptions()): RoleFilePlan =
            compute(project, source.dir, target.dir, options)

        /**
         * The plan of the one path [relPath] from [source] to [target] (X121's single-file takes): [entries] holds its
         * entry when it differs, else nothing. The same walk and skip rules as [compute] (a path they leave out never
         * differs), but only the files at [relPath] are read. Background dispatcher, read action, cancellable.
         */
        suspend fun computePath(project: Project, source: VirtualFile, target: VirtualFile, relPath: String, options: PlanOptions = PlanOptions()): RoleFilePlan =
            withContext(Dispatchers.Default) {
                val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
                readAction {
                    val context = Context(project, source, target, options, walks(source, target, ignored, options))
                    val evaluation = if (relPath in context.paths) context.evaluate(relPath) else null
                    context.plan(listOfNotNull(evaluation))
                }
            }

        /** [compute] for a blocking background thread (a `DiffRequestProducer`, a `Task.Backgroundable`); never the EDT. */
        fun computeBlocking(project: Project, source: VirtualFile, target: VirtualFile, options: PlanOptions = PlanOptions()): RoleFilePlan =
            runBlockingMaybeCancellable { compute(project, source, target, options) }

        private fun walks(source: VirtualFile, target: VirtualFile, ignored: (VirtualFile) -> Boolean, options: PlanOptions): Pair<RoleFiles.Walk, RoleFiles.Walk> {
            val sourceWalk = RoleFiles.walkWithSkips(source, ignored, options.ignoreMolecule)
            val targetWalk = if (target == source) sourceWalk else RoleFiles.walkWithSkips(target, ignored, options.ignoreMolecule)
            return sourceWalk to targetWalk
        }

        private fun stampsOf(files: Map<String, VirtualFile>): Map<String, FileStamp> = files.mapValues { (_, file) -> FileStamp.of(file) }

        private fun differing(before: Map<String, FileStamp>, now: Map<String, FileStamp>): Set<String> =
            (before.keys + now.keys).filterTo(HashSet()) { before[it] != now[it] }

        /**
         * The paths of [paths] that differ only in case from another path of [paths] (or lie below such a directory):
         * "tasks/main.yml" and "Tasks/main.yml" are one file on a file system that ignores case.
         */
        internal fun caseConflictsOf(paths: Collection<String>): Set<String> {
            val variants = HashMap<String, MutableSet<String>>()
            for (path in paths) for (prefix in RoleFiles.prefixes(path)) variants.getOrPut(prefix.lowercase()) { HashSet() } += prefix
            val clashing = variants.values.filter { it.size > 1 }.flatten().toSet()
            if (clashing.isEmpty()) return emptySet()
            return paths.filterTo(HashSet()) { path -> RoleFiles.prefixes(path).any { it in clashing } }
        }
    }

    /** The outcome of one path: its entry (null when both sides hold the same content) and the stamps of both sides. */
    private class Evaluation(val relPath: String, val entry: PlanEntry?, val sourceStamp: FileStamp?, val targetStamp: FileStamp?)

    /** One plan being computed: the two walks, the paths to evaluate and the rules for each. */
    private class Context(
        val project: Project,
        val source: VirtualFile,
        val target: VirtualFile,
        val options: PlanOptions,
        walks: Pair<RoleFiles.Walk, RoleFiles.Walk>,
    ) {
        val sourceWalk = walks.first
        val targetWalk = walks.second
        val caseConflicts: Set<String> = run {
            val union = sourceWalk.files.keys + targetWalk.files.keys
            if (target.fileSystem.isCaseSensitive) emptySet() else caseConflictsOf(union)
        }

        /** Every path of either side, sorted. */
        val paths: List<String> = (sourceWalk.files.keys + targetWalk.files.keys).toSortedSet().toList()

        /** The entry of [relPath] with both sides' stamps. Read action. */
        fun evaluate(relPath: String): Evaluation {
            val sourceFile = sourceWalk.files[relPath]?.takeIf { it.isValid && !it.isDirectory }
            val targetFile = targetWalk.files[relPath]?.takeIf { it.isValid && !it.isDirectory }
            val sourceStamp = sourceFile?.let(FileStamp::of)
            val targetStamp = targetFile?.let(FileStamp::of)
            if (relPath in caseConflicts || hiddenOnTheOtherSide(relPath, sourceFile, targetFile)) {
                return Evaluation(relPath, null, sourceStamp, targetStamp)
            }
            return Evaluation(relPath, entryOf(relPath, sourceFile, targetFile), sourceStamp, targetStamp)
        }

        /**
         * S5: a file one side has where the other side's rules hide that path (an ignored path there, or a link) is no
         * difference: a mirror must not delete or create what one side does not show.
         */
        private fun hiddenOnTheOtherSide(relPath: String, sourceFile: VirtualFile?, targetFile: VirtualFile?): Boolean = when {
            sourceFile != null && targetFile != null -> false
            sourceFile != null -> targetWalk.isSkipped(relPath) || (target.isDirectory && RoleFiles.isIgnoredPath(project, target, relPath))
            targetFile != null -> sourceWalk.isSkipped(relPath) || (source.isDirectory && RoleFiles.isIgnoredPath(project, source, relPath))
            else -> false
        }

        fun plan(evaluations: List<Evaluation>): RoleFilePlan {
            val sourceStamps = TreeMap<String, FileStamp>()
            val targetStamps = TreeMap<String, FileStamp>()
            for (evaluation in evaluations) {
                evaluation.sourceStamp?.let { sourceStamps[evaluation.relPath] = it }
                evaluation.targetStamp?.let { targetStamps[evaluation.relPath] = it }
            }
            return RoleFilePlan(
                source, target, options, evaluations.mapNotNull { it.entry }, sourceWalk.files.size, targetWalk.files.size,
                caseConflicts.sorted(), sourceStamps, targetStamps,
            )
        }

        /** The entry of one path, or null when both sides hold the same content. Read action. */
        private fun entryOf(relPath: String, sourceFile: VirtualFile?, targetFile: VirtualFile?): PlanEntry? {
            val source = sourceFile?.let { RoleFiles.Content(project, it) }
            val target = targetFile?.let { RoleFiles.Content(project, it) }
            val kind = when {
                // An unreadable side never equals the other one (as in drift); different lengths differ unread (P5).
                source != null && target != null -> if (source.sameBytes(target)) return null else PlanKind.CHANGED
                source != null -> PlanKind.ONLY_IN_SOURCE
                target != null -> PlanKind.ONLY_IN_TARGET
                else -> return null
            }
            val sourceHead = source?.head(RoleFiles.HEAD)
            val targetHead = target?.head(RoleFiles.HEAD)
            var sensitive = DriftRules.isSensitivePath(relPath)
            try {
                val vault = (sourceHead != null && DriftRules.isWholeFileVault(sourceHead)) ||
                    (targetHead != null && DriftRules.isWholeFileVault(targetHead))
                sensitive = sensitive || vault ||
                    (source != null && RoleFiles.holdsKey(sourceHead, source.length)) ||
                    (target != null && RoleFiles.holdsKey(targetHead, target.length))
                val binary = (source != null && RoleFiles.looksBinary(source.file, sourceHead, sourceHead != null && sourceHead.size.toLong() >= source.length)) ||
                    (target != null && RoleFiles.looksBinary(target.file, targetHead, targetHead != null && targetHead.size.toLong() >= target.length))
                return PlanEntry(
                    relPath = relPath,
                    kind = kind,
                    binary = binary,
                    sensitive = sensitive,
                    wholeFileVault = vault,
                    excluded = sensitive && !options.includeSensitive,
                    sourceExecutable = sourceFile?.let(RoleFiles::isExecutable) == true,
                    targetExecutable = targetFile?.let(RoleFiles::isExecutable) == true,
                    sourceSize = source?.length ?: -1,
                    targetSize = target?.length ?: -1,
                    sourceFile = sourceFile,
                    targetFile = targetFile,
                    sourceStamp = FileStamp.of(sourceFile),
                    targetStamp = FileStamp.of(targetFile),
                )
            } finally {
                // The first bytes of key material and vaults do not outlive the comparison (as in drift).
                if (sensitive) {
                    sourceHead?.fill(0)
                    targetHead?.fill(0)
                }
            }
        }
    }
}
