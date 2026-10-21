package de.terletzkiy.ansibility.lint.vcs.impl

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.lint.LintFinding
import de.terletzkiy.ansibility.lint.LintLevel
import de.terletzkiy.ansibility.lint.LintOutcome
import de.terletzkiy.ansibility.lint.LintRunner
import de.terletzkiy.ansibility.lint.LintSettings
import de.terletzkiy.ansibility.lint.message
import de.terletzkiy.ansibility.navigation.EditorJumps
import de.terletzkiy.ansibility.settings.RootKeys
import org.jetbrains.annotations.Nls

/** Registers [LintCommitCheck] (plan amendment R28, D-L4); only with VCS support (`ansibility-vcs.xml`). */
class LintCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = LintCommitCheck(panel.project)
}

/**
 * Before a commit from the IDE, when switched on (Lint settings, per user; off by default): ansible-lint on the changed
 * Ansible YAML files of each linting root (their working-tree content). Errors (ansible-lint's `error` level, what fails
 * a CI run) stop the commit with Commit Anyway; the details action opens the first one. Runs late (a slow tool) and
 * never blocks a commit when ansible-lint cannot run.
 */
class LintCommitCheck(private val project: Project) : CheckinHandler(), CommitCheck {
    override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.LATE

    override fun isEnabled(): Boolean = LintSettings.getInstance(project).checkCommits

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        if (!commitInfo.isVcsCommit || !isEnabled()) return null
        val files = commitInfo.committedChanges.mapNotNull { change ->
            change.afterRevision?.file?.path?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        }
        val byRoot = readAction { lintable(files) }
        if (byRoot.isEmpty()) return null
        val errors = ArrayList<LintFinding>()
        for ((root, rootFiles) in byRoot) {
            when (val outcome = LintRunner.run(project, root, rootFiles)) {
                is LintOutcome.Findings -> errors += outcome.findings.filter { it.level == LintLevel.ERROR }
                is LintOutcome.Failed -> LOG.info("ansible-lint commit check skipped for ${root.displayName}: ${outcome.reason}")
            }
        }
        if (errors.isEmpty()) return null
        return LintCommitProblem(message("commit.problem", errors.size, errors.map { it.file }.distinct().size), errors)
    }

    private fun lintable(files: List<VirtualFile>): Map<AnsibleRoot, List<VirtualFile>> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val settings = LintSettings.getInstance(project)
        return files.filter { it.extension?.lowercase() in YAML }.mapNotNull { file ->
            val context = workspace.contextOf(file) ?: return@mapNotNull null
            if (context.kind == FileKind.LINT_CONFIG || context.kind == FileKind.OTHER || context.root.detached) return@mapNotNull null
            if (!settings.rootSettings(RootKeys.keyOf(project, context.root.dir)).enabled) return@mapNotNull null
            context.root to file
        }.groupBy({ it.first }, { it.second })
    }

    private companion object {
        val YAML = setOf("yml", "yaml")
        val LOG = logger<LintCommitCheck>()
    }
}

/** ansible-lint errors in the commit; the details action opens the first one. */
internal class LintCommitProblem(@Nls override val text: String, val errors: List<LintFinding>) : CommitProblemWithDetails {
    override val showDetailsAction: String get() = message("commit.details.title")

    override fun showDetails(project: Project) {
        val first = errors.first()
        val file = LocalFileSystem.getInstance().findFileByNioFile(first.file) ?: return
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        val offset = document?.let { if (it.lineCount == 0) 0 else it.getLineStartOffset((first.line - 1).coerceIn(0, it.lineCount - 1)) } ?: 0
        EditorJumps.open(project, file, offset)
    }
}
