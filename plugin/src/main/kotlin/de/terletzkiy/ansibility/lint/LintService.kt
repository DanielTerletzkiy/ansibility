package de.terletzkiy.ansibility.lint

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.settings.RootKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Lints files with ansible-lint on open and save (plan amendment R28, D-L3) and keeps the findings for the editor.
 *
 * - [request] lints a file's saved content in the background (debounced; one process per root at a time; a newer
 *   request for the same file cancels the older one). The editor annotator calls it for a file without a result for
 *   its saved content (opening it); a VFS content change of an open file (a save, a git checkout) calls it too.
 * - Findings are anchored with range markers on the file's document, so they follow edits until the next save.
 * - A root that cannot lint (no executable, no service) is reported once per reason with a link to the settings.
 */
@Service(Service.Level.PROJECT)
class LintService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    /** The findings of one file, for its content at [stamp] (`VirtualFile.modificationStamp`). */
    class FileResult(val stamp: Long, val findings: List<Anchored>)

    /** A finding and where it sits now in the document. */
    class Anchored(val finding: LintFinding, private val marker: RangeMarker?, private val fallback: TextRange) {
        val range: TextRange get() = marker?.takeIf { it.isValid }?.textRange ?: fallback
    }

    private val results = ConcurrentHashMap<VirtualFile, FileResult>()
    private val jobs = ConcurrentHashMap<VirtualFile, Job>()
    private val rootLocks = ConcurrentHashMap<String, Mutex>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val editors = FileEditorManager.getInstance(project)
                for (event in events) {
                    if (event !is VFileContentChangeEvent) continue
                    val file = event.file
                    if (results.containsKey(file) || editors.isFileOpen(file)) request(file)
                }
            }
        })
    }

    /** The current findings of [file], or null when it has none for its saved content (yet). */
    fun result(file: VirtualFile): FileResult? = results[file]?.takeIf { it.stamp == file.modificationStamp }

    /** Whether [file] has no result for its saved content and no run is pending for it. */
    fun needsRun(file: VirtualFile): Boolean = result(file) == null && jobs[file]?.isActive != true

    /** Lints [file]'s saved content in the background, when its root lints and it is an Ansible YAML file. */
    fun request(file: VirtualFile) {
        if (!file.isValid || file.isDirectory || file.extension?.lowercase() !in YAML) return
        val previous = jobs[file]
        val job = scope.launch {
            previous?.cancel()
            delay(DEBOUNCE_MILLIS)
            val root = readAction { rootToLint(file) } ?: return@launch
            val stamp = file.modificationStamp
            val key = readAction { RootKeys.keyOf(project, root.dir) }
            val outcome = rootLocks.computeIfAbsent(key) { Mutex() }.withLock {
                if (file.modificationStamp != stamp || !file.isValid) return@launch
                LintRunner.run(project, root, listOf(file))
            }
            when (outcome) {
                is LintOutcome.Failed -> {
                    results.remove(file)
                    report(root, outcome.reason)
                }
                is LintOutcome.Findings -> {
                    reported.removeIf { it.startsWith("$key\u0000") }
                    store(file, stamp, outcome.findings.filter { sameFile(it.file, file) })
                    LintPluginSuggestion.suggestOnce(project)
                }
            }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "ansible-lint findings") }
            }
        }
        jobs[file] = job
        job.invokeOnCompletion { jobs.remove(file, job) }
    }

    /** The root whose settings lint [file]; null when its root does not lint or the file is no Ansible YAML file. */
    private fun rootToLint(file: VirtualFile): AnsibleRoot? {
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(file) ?: return null
        if (context.kind in NOT_LINTED) return null
        val root = context.root
        if (root.detached) return null
        if (!LintSettings.getInstance(project).rootSettings(RootKeys.keyOf(project, root.dir)).enabled) return null
        return root
    }

    private suspend fun store(file: VirtualFile, stamp: Long, findings: List<LintFinding>) {
        val anchored = readAction {
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
            findings.map { finding -> Anchored(finding, document?.let { marker(it, finding) }, document?.let { lineRange(it, finding.line) } ?: TextRange.EMPTY_RANGE) }
        }
        results[file] = FileResult(stamp, anchored)
    }

    private fun marker(document: Document, finding: LintFinding): RangeMarker? {
        val range = lineRange(document, finding.line)
        return if (range.isEmpty && document.textLength > 0) null else document.createRangeMarker(range)
    }

    private fun report(root: AnsibleRoot, reason: String) {
        val key = RootKeys.keyOf(project, root.dir)
        if (!reported.add("$key\u0000$reason") || project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message("notification.failed.title", root.displayName), reason, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring(message("notification.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, LintConfigurable::class.java)
            })
            .notify(project)
    }

    private fun sameFile(path: java.nio.file.Path, file: VirtualFile): Boolean {
        val own = file.toNioPath()
        return path.normalize() == own.normalize() || runCatching { java.nio.file.Files.isSameFile(path, own) }.getOrDefault(false)
    }

    /** The service's coroutine scope (background work of the quick fixes). */
    internal fun scope(): CoroutineScope = scope

    /** Forgets every result (settings changed): open files lint again. */
    fun reset() {
        results.clear()
        reported.clear()
        for (file in FileEditorManager.getInstance(project).openFiles) request(file)
    }

    /** Whether a lint run is pending (tests wait for it while dispatching EDT events). */
    @TestOnly
    internal fun busy(): Boolean = jobs.values.any { it.isActive }

    override fun dispose() = Unit

    companion object {
        /** The range of 1-based [line] without its line break (the document's last line when it is shorter). */
        fun lineRange(document: Document, line: Int): TextRange {
            if (document.lineCount == 0) return TextRange.EMPTY_RANGE
            val index = (line - 1).coerceIn(0, document.lineCount - 1)
            return TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index))
        }

        private val YAML = setOf("yml", "yaml")
        private val NOT_LINTED = setOf(FileKind.LINT_CONFIG, FileKind.OTHER)
        const val NOTIFICATION_GROUP = "Ansibility"
        private const val DEBOUNCE_MILLIS = 300L

        fun getInstance(project: Project): LintService = project.service()
    }
}
