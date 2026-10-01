package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** The project-level coroutine scope of the tool window (cancelled when the project closes). */
@Service(Service.Level.PROJECT)
class AnsibleToolWindowScope(val scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): AnsibleToolWindowScope = project.service()
    }
}

/**
 * Keeps the tool window current (plan F6.4): whenever the Ansible structure changes
 * ([AnsibleStructureListener.TOPIC]), a VFS event or a document edit touches an inventory, a var file, `ansible.cfg`
 * or a playbook of a shown root ([RefreshFilter]), the project roots or the Ansibility project settings change, it
 * rebuilds the [WorkspaceSnapshot] in a background read action (with all documents committed) and hands it to
 * [apply] on the EDT.
 *
 * Requests are conflated and debounced by [DEBOUNCE_MS]; a newer request cancels a build still running, so an
 * edit shows up well within a second. Disposing the refresher stops everything.
 */
class AnsibleTreeRefresher(
    private val project: Project,
    private val apply: (WorkspaceSnapshot) -> Unit,
) : Disposable {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val applied = AtomicLong()
    private var job: Job? = null

    @Volatile
    private var disposed = false

    /** Judges VFS events and document edits below the roots of the last snapshot; nothing counts before the first. */
    @Volatile
    private var filter = RefreshFilter(emptyList())

    /** How many snapshots were applied so far (tests wait on it). */
    val appliedCount: Long get() = applied.get()

    /** Subscribes to the change sources and schedules the first build. */
    fun start() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { request() })
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    val current = filter
                    if (events.any(current::isRelevant)) request()
                }
            },
        )
        // Typing in hosts.yml or a var file changes the PSI long before the file is saved.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (filter.isRelevantPath(file.path)) request()
                }
            },
            this,
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = request()
            },
        )
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) = request()
            },
        )
        job = AnsibleToolWindowScope.getInstance(project).scope.launch(Dispatchers.Default) {
            requests.consumeAsFlow().collectLatest {
                delay(DEBOUNCE_MS)
                // Committed documents only: an edited or reloaded hosts.yml is read as its PSI will be.
                val snapshot = constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) { WorkspaceSnapshotBuilder.build(project) }
                filter = RefreshFilter(snapshot.roots.map { it.dir.path })
                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed) {
                        apply(snapshot)
                        applied.incrementAndGet()
                    }
                }
            }
        }
        request()
    }

    /** Asks for a rebuild; cheap and callable from any thread. */
    fun request() {
        if (!disposed) requests.trySend(Unit)
    }

    override fun dispose() {
        disposed = true
        job?.cancel()
        requests.close()
    }

    companion object {
        /** Collects bursts of events (a save of several files, a branch switch) into one rebuild. */
        const val DEBOUNCE_MS: Long = 250

        /** Registers a new refresher under [parent] and starts it. */
        fun start(project: Project, parent: Disposable, apply: (WorkspaceSnapshot) -> Unit): AnsibleTreeRefresher =
            AnsibleTreeRefresher(project, apply).also {
                Disposer.register(parent, it)
                it.start()
            }
    }
}

/**
 * Which VFS events can change what the tool window shows: creating, deleting, moving, renaming or editing
 * `ansible.cfg`, a `hosts.y*ml`, a playbook (`playbook-*.y*ml`, YAML files in a `playbooks/` directory) or anything
 * below `environments/`, `group_vars/` or `host_vars/`, inside one of the roots the tree shows ([rootPaths]).
 *
 * Judged on the path below the root only (cheap, no VFS access), so a project that happens to live below a
 * directory called `build` is not ignored; events inside [AnsibleLayout.SKIPPED_DIRS] of a root never count. New
 * roots arrive through [AnsibleStructureListener] instead.
 */
class RefreshFilter(rootPaths: Collection<String>) {
    private val roots: List<String> = rootPaths.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    fun isRelevant(event: VFileEvent): Boolean = when (event) {
        is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent -> isRelevantPath(event.path)
        is VFileMoveEvent -> isRelevantPath(event.oldPath) || isRelevantPath(event.newPath)
        is VFileCopyEvent -> isRelevantPath("${event.newParent.path}/${event.newChildName}")
        is VFilePropertyChangeEvent -> event.isRename && (isRelevantPath(event.oldPath) || isRelevantPath(event.newPath))
        else -> false
    }

    /** Whether a change of the file or directory at the absolute [path] can change the tree. */
    fun isRelevantPath(path: String): Boolean {
        val segments = below(path) ?: return false
        val name = segments.lastOrNull() ?: return true
        val parents = segments.dropLast(1)
        if (parents.any { it in AnsibleLayout.SKIPPED_DIRS }) return false
        return name == AnsibleLayout.ANSIBLE_CFG ||
            AnsibleLayout.isHostsFileName(name) ||
            AnsibleLayout.isPlaybookName(name) ||
            name in TREE_PARENTS ||
            parents.any { it in TREE_PARENTS } ||
            (parents.lastOrNull() == AnsibleLayout.PLAYBOOKS && AnsibleLayout.isYamlName(name))
    }

    /** The segments of [path] below the innermost root containing it, or null outside every root. */
    private fun below(path: String): List<String>? {
        val normalized = path.trimEnd('/')
        for (root in roots) {
            if (normalized == root) return emptyList()
            if (normalized.startsWith("$root/")) return normalized.substring(root.length + 1).split('/').filter { it.isNotEmpty() }
        }
        return null
    }

    private companion object {
        val TREE_PARENTS = setOf(AnsibleLayout.ENVIRONMENTS, AnsibleLayout.GROUP_VARS, AnsibleLayout.HOST_VARS)
    }
}
