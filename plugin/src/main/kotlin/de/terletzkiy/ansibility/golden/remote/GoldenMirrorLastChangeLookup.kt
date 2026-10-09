package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The golden side's last change (plan amendment R25, D200; X127), for files and directories below the external golden
 * root. Registered in `ansibility-golden.xml` (`order="first"`): it imports nothing of the IDE's VCS, it runs our own
 * git through [GoldenGitTransport] ([GoldenMirrorService.runLocal]): LOCAL, never the network
 * (`GIT_NO_LAZY_FETCH=1`), never a credential callback.
 *
 * - **Git mirror, history depth 1**: the mirror holds only the fetched commit, which is every file's answer, labelled
 *   [LastChange.Kind.FETCHED_COMMIT] (no git runs: the state has it).
 * - **Git mirror, history depth > 1**: `git log -1 -- <path>` in the mirror. A path that the fetched history never
 *   changed reports the oldest fetched commit (a shallow boundary has no parent, so it seems to add every file): that
 *   answer becomes [LastChange.Kind.BEFORE_HISTORY] ("older than the fetched history", dated with that commit).
 * - **Folder** (X125): nothing, unless the folder is in a git checkout; then `git log -1` there the same way.
 *
 * [watch] reports every new commit, history depth or mirror (the cached answers are then revalidated).
 */
class GoldenMirrorLastChangeLookup : LastChangeLookup {
    override fun lastChange(project: Project, file: VirtualFile): LastChange? = lookup(project, file)

    override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? = lookup(project, dir)

    override fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {
        fun key(state: GoldenMirrorState?) = Triple(state?.baseDir, state?.commit, state?.historyDepth ?: 0)
        var last = key(GoldenMirrors.getInstance(project).state())
        project.messageBus.connect(parent).subscribe(
            GoldenMirrorListener.TOPIC,
            GoldenMirrorListener { state ->
                val now = key(state)
                val before = last
                last = now
                if (before != now) changed(null)
            },
        )
    }

    private fun lookup(project: Project, file: VirtualFile): LastChange? {
        if (project.isDisposed || !file.isValid || !file.isInLocalFileSystem) return null
        val state = GoldenMirrors.getInstance(project).state() ?: return null
        val base = state.baseDir ?: return null
        val path = file.toNioPathOrNull() ?: return null
        val relative = relativeTo(base, path) ?: return null
        return when (state.kind) {
            GoldenMirrorState.Kind.GIT -> mirror(project, state, base, relative)
            GoldenMirrorState.Kind.FOLDER -> folder(project, base, relative)
        }
    }

    private fun mirror(project: Project, state: GoldenMirrorState, base: Path, relative: String): LastChange? {
        val commit = state.commit ?: return null
        if (state.historyDepth <= 1) return fetched(state, commit)
        val info = log(project, base, relative) ?: return null
        if (info.hash in shallowCommits(base.resolve(".git"))) {
            return LastChange("", info.instant ?: return null, "", short(info.hash), LastChange.Kind.BEFORE_HISTORY)
        }
        return of(info)
    }

    /** A folder in a git checkout: its full history (or, when shallow, the same rule as the mirror). */
    private fun folder(project: Project, base: Path, relative: String): LastChange? {
        val checkout = checkoutOf(base) ?: return null
        val info = log(project, base, relative) ?: return null
        if (checkout.gitDir != null && info.hash in shallowCommits(checkout.gitDir)) {
            return LastChange("", info.instant ?: return null, "", short(info.hash), LastChange.Kind.BEFORE_HISTORY)
        }
        return of(info)
    }

    private fun log(project: Project, dir: Path, relative: String): GoldenMirrorCommands.CommitInfo? {
        ProgressManager.checkCanceled()
        val result = runLocal(project, dir, GitOp.LOG, GoldenMirrorCommands.lastChangeArgs(relative))
        if (!result.success) {
            LOG.debug("golden last change: git log failed (exit ${result.exitCode})")
            return null
        }
        return GoldenMirrorCommands.parseCommit(result.output)
    }

    companion object {
        private val LOG = logger<GoldenMirrorLastChangeLookup>()
        private const val SHORT_HASH = 8

        /** The fetched commit of a depth-1 mirror as every file's last change (D200). */
        fun fetched(state: GoldenMirrorState, commit: String): LastChange? {
            val date = state.commitInstant ?: return null
            return LastChange(state.commitAuthor.orEmpty(), date, state.commitSubject.orEmpty(), short(commit), LastChange.Kind.FETCHED_COMMIT)
        }

        private fun of(info: GoldenMirrorCommands.CommitInfo): LastChange? {
            val date = info.instant ?: return null
            return LastChange(info.author, date, info.subject.lineSequence().firstOrNull().orEmpty().trim(), short(info.hash))
        }

        private fun short(hash: String): String = hash.take(SHORT_HASH)

        /** [path] relative to [base] with `/` separators ("" for [base] itself), or null outside it. */
        internal fun relativeTo(base: Path, path: Path): String? {
            val normalizedBase = base.toAbsolutePath().normalize()
            val normalized = path.toAbsolutePath().normalize()
            if (!normalized.startsWith(normalizedBase)) return null
            return normalizedBase.relativize(normalized).joinToString("/") { it.toString() }
        }

        /** The commits of `<gitDir>/shallow` (the oldest fetched ones), or none. */
        internal fun shallowCommits(gitDir: Path): Set<String> = try {
            val file = gitDir.resolve("shallow")
            if (file.isRegularFile()) Files.readAllLines(file).map { it.trim() }.filter(GoldenGitUrls::isCommit).toSet() else emptySet()
        } catch (_: IOException) {
            emptySet()
        }

        /** A git checkout a folder is in, with its `.git` directory (null for a `.git` file: a worktree or submodule). */
        internal class Checkout(val gitDir: Path?)

        /** The git checkout [dir] is in, or null (no `.git` above it). */
        internal fun checkoutOf(dir: Path): Checkout? {
            var current: Path? = dir.toAbsolutePath().normalize()
            while (current != null) {
                val git = current.resolve(".git")
                if (git.isDirectory()) return Checkout(git)
                if (Files.exists(git)) return Checkout(null)
                current = current.parent
            }
            return null
        }

        /** Runs a local git command for the lookups: through the mirror service (its transport and safety configuration). */
        internal fun runLocal(project: Project, dir: Path, op: GitOp, args: List<String>): GitResult {
            val service = GoldenMirrorService.getInstance(project)
            if (service != null) return service.runLocal(dir, op, args)
            // A replaced GoldenMirrors (tests): the same command through the registered transport.
            val hooks = PathManager.getSystemDir().resolve("ansibility").resolve("golden").resolve(".no-hooks")
            val request = GitRequest(
                project, dir, op, args, null, Interaction.BACKGROUND, GoldenMirrorCommands.safetyConfig(hooks),
                GoldenMirrorCommands.environment(Interaction.BACKGROUND, network = false) + GoldenMirrorCommands.LOCAL_LOOKUP_ENV,
                timeoutSeconds = 30,
            )
            check(!ApplicationManager.getApplication().isDispatchThread) { "git never runs on the EDT" }
            return GoldenGitTransport.current().run(request)
        }

        private fun VirtualFile.toNioPathOrNull(): Path? = try {
            toNioPath()
        } catch (_: UnsupportedOperationException) {
            null
        }
    }
}
