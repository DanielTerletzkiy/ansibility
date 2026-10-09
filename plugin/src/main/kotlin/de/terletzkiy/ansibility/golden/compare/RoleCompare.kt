package de.terletzkiy.ansibility.golden.compare

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestProducer
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeTexts
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.golden.sync.PlanEntry
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.model.drift.DriftCategory
import de.terletzkiy.ansibility.model.drift.DriftRules
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The diff chain of two role copies, ready to show (plan amendment R24, D182).
 *
 * [producers] cover the differing files only, sorted by path, [left] on the left and [right] on the right; with more
 * than one, the viewer walks across files (Next/Previous Difference) and lists them in "Go to Changed File".
 */
class RoleCompareChain internal constructor(
    val left: RoleCopy,
    val right: RoleCopy,
    /**
     * The plan the chain was built from: left is its source, right its target. It always includes `molecule/`;
     * [producers] leave it out while drift ignores it (except the path the compare was started on).
     */
    val plan: RoleFilePlan,
    val producers: List<DiffRequestProducer>,
    /** The producer the viewer opens first (the selected file, else 0). */
    val startIndex: Int,
    /** A notice to show with (or instead of) the chain: nothing differs, or the selected file does not. */
    @get:Nls val notice: String?,
) {
    /** The chain, or null when nothing differs. */
    fun chain(): SimpleDiffRequestChain? = if (producers.isEmpty()) null else SimpleDiffRequestChain.fromProducers(producers, startIndex)
}

/**
 * Compare with Golden and Compare with… (plan amendment R24, D182; R9's F9.6): a diff chain over the files in which two
 * copies of a role differ, as an editor tab. A side in the external golden root (plan amendment R25) is read-only, and
 * its title names the fetched commit (D200).
 *
 * - **Files**: [RoleFilePlan] (drift's skip rules, unsaved documents); only the differing ones, sorted by path; the
 *   chain starts at the selected file when one is given. With "Ignore molecule/ in drift" `molecule/` is left out,
 *   unless the compare was started on a path below it, and a copy that differs only there says so (never "the same").
 * - **Sides**: `DiffContentFactory.create(project, file)` for an existing file, so each side is the real file (a
 *   `FileContent`): the right side is editable, the ">>" arrows take a chunk in place, and the platform's Annotate with
 *   Git Blame (VCS module) works on both sides of tracked files without any code here (D183). A missing side is
 *   `createEmpty()`.
 * - **Titles**: "golden · 2026-09-12 · alice" when [LastChanges] answers within [TITLE_BUDGET] while the request is
 *   produced (in the background), else the root's name.
 * - **Sensitive files** (key material by name, whole-file vaults) are a placeholder, "differs (content not shown)",
 *   with a "Show Diff" toolbar action that builds the real request only when clicked. Nothing is ever decrypted.
 * - **UI** goes through [RoleDiffUi] (replaced in tests).
 *
 * Work runs in the service's scope: the plan and the drift tiers in the background with a progress indicator, the UI
 * on the EDT. Never blocks the caller.
 */
@Service(Service.Level.PROJECT)
class RoleCompare(private val project: Project, private val scope: CoroutineScope) {
    /** Compare with Golden for [target]: golden left, the copy right. Does nothing when the copy is golden or has none. */
    fun compareWithGolden(target: GoldenTarget): Job = scope.launch {
        val golden = readAction { RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) }
        if (golden == null || golden.dir == target.copy.dir) return@launch
        show(golden, target.copy, target.relPath)
    }

    /** Compare with…: a popup of the other copies of [target]'s role, then the chain between the pick (left) and the copy (right). */
    fun compareWith(target: GoldenTarget, context: DataContext?): Job = scope.launch {
        val choices = withBackgroundProgress(project, message("compare.progress.copies", target.copy.name)) { choices(target.copy) }
        if (choices.isEmpty()) return@launch
        withContext(Dispatchers.EDT) {
            RoleDiffUi.getInstance().chooseCopy(project, message("compare.choose.title", target.copy.name, target.copy.root.displayName), choices, context) { choice ->
                compare(choice.copy, target.copy, target.relPath)
            }
        }
    }

    /** The chain between [left] and [right], started at [startPath]; shown through [RoleDiffUi]. */
    fun compare(left: RoleCopy, right: RoleCopy, startPath: String? = null): Job = scope.launch { show(left, right, startPath) }

    /**
     * The other copies of [copy]'s role with their drift badge, in-scope copies first (catalog order within each
     * group). Never filtered by the scope (D44). Without a golden root there is no badge, and no drift is computed:
     * a badge is relative to golden (D178).
     */
    suspend fun choices(copy: RoleCopy): List<CompareChoice> {
        val catalog = readAction { RoleCatalog.getInstance(project).snapshot() }
        val others = catalog.copies(copy.name).filter { it.dir != copy.dir }
        if (others.isEmpty()) return emptyList()
        val drift = if (catalog.golden != null) RoleDriftService.getInstance(project).drift(copy.name) else null
        val workspace = WorkspaceScopeService.getInstance(project).current()
        val inScope = readAction { others.map { workspace.contains(it.dir) } }
        val choices = others.mapIndexed { index, other ->
            val tier = drift?.let { d -> d.copyOf(other.dir)?.let { DriftTexts.badge(d, it) } }
            CompareChoice(other, tier, inScope[index])
        }
        return choices.sortedBy { !it.inScope }
    }

    /**
     * The chain between [left] and [right] (see [RoleCompareChain]); computes the plan. With "Ignore molecule/ in drift"
     * ([RoleDriftService.options]) the chain leaves `molecule/` out, except the path it was started on when that is
     * below `molecule/`; the notice then never says "the same" when `molecule/` differs.
     */
    suspend fun chain(left: RoleCopy, right: RoleCopy, startPath: String?): RoleCompareChain {
        // One plan with molecule/ in it: what drift ignores is filtered here, so the notices know whether it differs.
        val plan = RoleFilePlan.compute(project, left, right, PlanOptions(ignoreMolecule = false, includeSensitive = true))
        val ignoreMolecule = RoleDriftService.getInstance(project).options.ignoreMolecule
        val (entries, hidden) = plan.entries.partition { !ignoreMolecule || !isMolecule(it.relPath) || startsAt(it.relPath, startPath) }
        val producers = entries.map { producer(left, right, it) }
        val index = startPath?.let { path ->
            entries.indexOfFirst { it.relPath == path }.takeIf { it >= 0 }
                ?: entries.indexOfFirst { startsAt(it.relPath, path) }.takeIf { it >= 0 }
        }
        val notice = when {
            entries.isEmpty() && hidden.isNotEmpty() ->
                message("compare.notice.moleculeOnly", left.name, right.root.displayName, left.root.displayName)
            entries.isEmpty() -> message("compare.notice.identical", left.name, right.root.displayName, left.root.displayName)
            startPath != null && index == null -> message("compare.notice.fileSame", startPath, left.root.displayName, right.root.displayName)
            else -> null
        }
        return RoleCompareChain(left, right, plan, producers, index ?: 0, notice)
    }

    /** Whether [relPath] is below `molecule/` (what "Ignore molecule/ in drift" leaves out). */
    private fun isMolecule(relPath: String): Boolean = DriftRules.categoryOf(relPath) == DriftCategory.MOLECULE

    /** Whether [relPath] is [startPath] or below it (a folder the compare was started on). */
    private fun startsAt(relPath: String, startPath: String?): Boolean =
        startPath != null && (relPath == startPath || relPath.startsWith("$startPath/"))

    private suspend fun show(left: RoleCopy, right: RoleCopy, startPath: String?) {
        val result = withBackgroundProgress(project, message("compare.progress", left.name, left.root.displayName, right.root.displayName)) {
            chain(left, right, startPath)
        }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val ui = RoleDiffUi.getInstance()
            result.notice?.let { ui.inform(project, it) }
            result.chain()?.let { ui.show(project, it) }
        }
    }

    // ---------------------------------------------------------------- requests

    /** The producer of one differing path: a placeholder for sensitive files, else the real request. */
    private fun producer(left: RoleCopy, right: RoleCopy, entry: PlanEntry): DiffRequestProducer {
        val relPath = entry.relPath
        val sensitive = entry.sensitive || DriftRules.isSensitivePath(relPath)
        return SimpleDiffRequestProducer.create(relPath, ThrowableComputable { if (sensitive) placeholder(left, right, entry) else request(left, right, relPath) })
    }

    /** The real request of [relPath]: both sides as the files they are now (an empty side when missing). Background thread. */
    internal fun request(left: RoleCopy, right: RoleCopy, relPath: String): DiffRequest {
        ProgressManager.checkCanceled()
        val (leftFile, rightFile) = runReadActionBlocking { fileOf(left, relPath) to fileOf(right, relPath) }
        val changes = LastChanges.getInstance(project).lastChanges(listOf(leftFile, rightFile), TITLE_BUDGET)
        ProgressManager.checkCanceled()
        return SimpleDiffRequest(
            requestTitle(left, right, relPath),
            contentOf(left, leftFile),
            contentOf(right, rightFile),
            sideTitle(left, leftFile, changes[0]),
            sideTitle(right, rightFile, changes[1]),
        )
    }

    /** "files/ssl/web.key differs (content not shown)" with the explicit "Show Diff" action (D181). */
    private fun placeholder(left: RoleCopy, right: RoleCopy, entry: PlanEntry): DiffRequest {
        val text = when (entry.kind) {
            PlanKind.CHANGED -> message("compare.sensitive.changed", entry.relPath)
            PlanKind.ONLY_IN_SOURCE -> message("compare.sensitive.onlyIn", entry.relPath, left.root.displayName)
            PlanKind.ONLY_IN_TARGET -> message("compare.sensitive.onlyIn", entry.relPath, right.root.displayName)
        }
        return MessageDiffRequest(requestTitle(left, right, entry.relPath), text).apply {
            putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(ShowSensitiveDiffAction(left, right, entry.relPath)))
        }
    }

    /** Shows the real diff of one sensitive file, in a chain of its own, built when the viewer asks for it. */
    internal fun showSensitive(left: RoleCopy, right: RoleCopy, relPath: String) {
        val producer = SimpleDiffRequestProducer.create(relPath, ThrowableComputable { request(left, right, relPath) })
        RoleDiffUi.getInstance().show(project, SimpleDiffRequestChain.fromProducer(producer))
    }

    /** The "Show Diff" toolbar action of a sensitive placeholder: the only way its content is shown (as it is, never decrypted). */
    inner class ShowSensitiveDiffAction internal constructor(private val left: RoleCopy, private val right: RoleCopy, val relPath: String) :
        DumbAwareAction(message("compare.sensitive.show"), message("compare.sensitive.show.description"), AllIcons.Actions.Diff) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = showSensitive(left, right, relPath)
    }

    private fun fileOf(copy: RoleCopy, relPath: String): VirtualFile? =
        copy.dir.takeIf { it.isValid }?.findFileByRelativePath(relPath)?.takeIf { it.isValid && !it.isDirectory }

    /**
     * A side's content: the file itself (blame and in-place edits work), or empty when missing. A side in the external
     * golden root (plan amendment R25, D198) is read-only ([DiffUserDataKeys.FORCE_READ_ONLY]): the mirror or folder
     * is never edited here.
     */
    internal fun contentOf(copy: RoleCopy, file: VirtualFile?): DiffContent {
        val factory = DiffContentFactory.getInstance()
        val content = if (file == null) factory.createEmpty() else factory.create(project, file)
        if (copy.isExternal || file != null && ExternalGoldenRoot.getInstance(project).isUnder(file)) content.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        return content
    }

    @Nls
    private fun requestTitle(left: RoleCopy, right: RoleCopy, relPath: String): String =
        message("compare.request.title", left.name, relPath, left.root.displayName, right.root.displayName)

    @Nls
    private fun sideTitle(copy: RoleCopy, file: VirtualFile?, change: LastChange?): String = when {
        file == null -> message("compare.side.missing", copy.root.displayName)
        change != null && change.isUpperBound -> LastChangeTexts.side(copy.root.displayName, change)
        change != null -> message("compare.side.lastChange", copy.root.displayName, DATE.format(change.date), change.author)
        else -> copy.root.displayName
    }

    companion object {
        /** How long a request waits for the last change of its two sides before it shows the root names only. */
        val TITLE_BUDGET: Duration = 1500.milliseconds

        private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

        fun getInstance(project: Project): RoleCompare = project.service()
    }
}
