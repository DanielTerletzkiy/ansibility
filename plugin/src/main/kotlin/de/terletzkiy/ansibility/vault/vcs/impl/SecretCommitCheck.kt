package de.terletzkiy.ansibility.vault.vcs.impl

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.commit.CommitFix
import de.terletzkiy.ansibility.vault.commit.CommitSecret
import de.terletzkiy.ansibility.vault.commit.CommitSecretTexts
import de.terletzkiy.ansibility.vault.commit.CommittedContent
import de.terletzkiy.ansibility.vault.commit.CommittedSecrets
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.commit.CommitTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

/**
 * The commit check of plan amendment R21 (D167), registered only by the optional fragment `ansibility-vcs.xml`
 * (`com.intellij.modules.vcs`), like every class of this package.
 */
class SecretCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = SecretCommitCheck(panel.project)
}

/**
 * Before a commit from the IDE (plan amendment R21, D167): the committed content of every changed file is checked by
 * `vault.commit.CommittedSecrets` (plaintext private keys, keystores and key-like files, a root's vault password file,
 * vault files Ansible does not decrypt, and X99, a file that was a whole-file vault and is plaintext now). Findings stop
 * the commit with a [CommitProblem] naming kinds and files only; the platform offers Commit Anyway, and the problem's
 * details action is "Encrypt Files…" (or "Convert to Whole-File Vault"), which fixes the working tree so you commit
 * again. Commits from the terminal are not checked.
 *
 * The committed content is what the commit takes: `ByteBackedContentRevision.getContentAsBytes` of a staged revision
 * (staging-area mode), the working-tree file for `CurrentContentRevision` (read through the VFS, bounded), else the
 * revision's text (or the local file when it has none). At most [CommittedSecrets.MAX_BYTES] are looked at, on
 * `Dispatchers.IO`, cancellable between files; only verdicts are kept. X99 reads the before-revisions of the files whose
 * committed content asks for it ([CommittedSecrets.classify]), [BEFORE_READS] at a time (one VCS read each), and keeps
 * only their first [CommittedSecrets.BEFORE_BYTES]. When a finding comes from a staged revision, the problem text says
 * to stage the fixed files again. Runs early, reads no index (dumb aware) and is switched on the Vault settings page
 * (`VaultUserState.checkCommits`, per user). A revision that cannot be read is logged by file and error class and
 * skipped: a VCS failure never blocks a commit.
 */
class SecretCommitCheck(private val project: Project) : CheckinHandler(), CommitCheck {
    override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.EARLY

    override fun isDumbAware(): Boolean = true

    override fun isEnabled(): Boolean = VaultUserState.getInstance().checkCommits

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        if (!commitInfo.isVcsCommit || !isEnabled()) return null
        val changes = commitInfo.committedChanges
        if (changes.isEmpty()) return null
        val scan = withContext(Dispatchers.IO) { scanChanges(changes) }
        return if (scan.secrets.isEmpty()) null else problemOf(scan.secrets, scan.staged)
    }

    /** The findings of [changes]. Background thread. */
    internal suspend fun scan(changes: List<Change>): List<CommitSecret> = scanChanges(changes).secrets

    /** The findings of a commit, and whether one of them comes from a staged revision (staging-area mode). */
    internal class Scan(val secrets: List<CommitSecret>, val staged: Boolean)

    /** The findings of [changes]; the X99 before-revisions are read [BEFORE_READS] at a time. Background thread. */
    internal suspend fun scanChanges(changes: List<Change>): Scan {
        if (project.isDisposed || !readAction { CommittedSecrets.applies(project) }) return Scan(emptyList(), staged = false)
        val found = ArrayList<CommitSecret>()
        var staged = false
        val asksBefore = ArrayList<Triple<CommitTarget, ContentRevision, Boolean>>()
        for (change in changes) {
            currentCoroutineContext().ensureActive()
            val after = change.afterRevision ?: continue
            val path = after.file
            if (path.isDirectory || path.isNonLocal) continue
            val target = readAction { CommittedSecrets.target(project, path.path, path.virtualFile) } ?: continue
            val content = if (target.readsContent) contentOf(after, target.file) ?: continue else null
            val verdict = CommittedSecrets.classify(project, target, content)
            found += verdict.secrets
            // A working-tree revision is the file itself; anything else is what the staging area holds.
            val fromStage = after !is CurrentContentRevision
            if (verdict.secrets.isNotEmpty() && fromStage) staged = true
            if (verdict.decidesByBefore) change.beforeRevision?.let { asksBefore += Triple(target, it, fromStage) }
        }
        val decrypted = coroutineScope {
            val permits = Semaphore(BEFORE_READS)
            asksBefore.map { (target, before, fromStage) ->
                async {
                    permits.withPermit { headOf(before)?.let { CommittedSecrets.decrypted(target, it) }?.let { it to fromStage } }
                }
            }.awaitAll().filterNotNull()
        }
        found += decrypted.map { it.first }
        if (decrypted.any { it.second }) staged = true
        return Scan(found, staged)
    }

    /** The first [CommittedSecrets.BEFORE_BYTES] of a before-revision (X99 needs the magic only), or null. */
    private suspend fun headOf(revision: ContentRevision): CommittedContent? {
        currentCoroutineContext().ensureActive()
        val content = contentOf(revision, null) ?: return null
        val bytes = content.bytes
        return CommittedContent(if (bytes.size > CommittedSecrets.BEFORE_BYTES) bytes.copyOf(CommittedSecrets.BEFORE_BYTES) else bytes, complete = false)
    }

    /**
     * The committed content of [revision] (bounded), or null when it cannot be read: the working tree's file for a
     * current revision, a staged or historic revision's bytes, else the revision's text in the file's charset or, when
     * it has none, the working tree's file [local].
     */
    private fun contentOf(revision: ContentRevision, local: VirtualFile?): CommittedContent? = try {
        when (revision) {
            is CurrentContentRevision -> revision.virtualFile?.let(::localContent)
            is ByteBackedContentRevision -> revision.contentAsBytes?.let(::bounded)
            else -> revision.content?.let { bounded(it.toByteArray(revision.file.charset)) } ?: local?.let(::localContent)
        }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VaultLog.failure(VaultLog.Operation.COMMIT_CHECK, e, revision.file.path)
        null
    }

    /** The first bytes of the working tree's [file] (through the VFS, which has unsaved-to-disk writes first). */
    private fun localContent(file: VirtualFile): CommittedContent? {
        if (!file.isValid || file.isDirectory) return null
        return bounded(VfsUtilCore.loadNBytes(file, CommittedSecrets.MAX_BYTES + 1))
    }

    private fun bounded(bytes: ByteArray): CommittedContent =
        if (bytes.size <= CommittedSecrets.MAX_BYTES) CommittedContent(bytes, complete = true)
        else CommittedContent(bytes.copyOf(CommittedSecrets.MAX_BYTES), complete = false)

    companion object {
        /** The before-revisions X99 reads at the same time (each one VCS call, such as one `git` process). */
        const val BEFORE_READS: Int = 8

        /**
         * The problem of [secrets]: with the details action of [CommitFix.of], or a plain one (only Commit Anyway).
         * [staged] true when a finding comes from the staging area: the fix changes the working tree, so the text says to
         * stage the fixed files again.
         */
        internal fun problemOf(secrets: List<CommitSecret>, staged: Boolean = false): CommitProblem {
            val fix = CommitFix.of(secrets)
            val text = CommitSecretTexts.text(secrets, restage = staged && fix != null)
            fix ?: return SecretCommitProblem(text, secrets)
            return FixableSecretCommitProblem(text, secrets, fix)
        }
    }
}

/** A stopped commit without a fix (a password file, a malformed vault): Commit Anyway or Cancel. */
internal class SecretCommitProblem(@Nls override val text: String, val secrets: List<CommitSecret>) : CommitProblem {
    override fun toString(): String = "SecretCommitProblem($secrets)"
}

/** A stopped commit whose details action fixes the working tree ([CommitFix]); the user commits again afterwards. */
internal class FixableSecretCommitProblem(@Nls override val text: String, val secrets: List<CommitSecret>, val fix: CommitFix) : CommitProblemWithDetails {
    override val showDetailsAction: String get() = fix.actionText

    override fun showDetails(project: Project) = fix.apply(project)

    override fun toString(): String = "FixableSecretCommitProblem($secrets, $fix)"
}
