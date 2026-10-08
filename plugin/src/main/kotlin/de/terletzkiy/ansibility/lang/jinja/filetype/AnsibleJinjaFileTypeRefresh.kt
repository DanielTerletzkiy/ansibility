package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.FileContentUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.editor.coexist.AnsiblePlaybookYamlOverrider
import de.terletzkiy.ansibility.settings.AnsibilityAppSettingsListener
import de.terletzkiy.ansibility.settings.AppSettings
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells the platform when the answer of [AnsibleJinjaFileTypeOverrider] or [AnsiblePlaybookYamlOverrider], or the
 * outer language of a template (`lang.jinja.template.AnsibleJinjaOuterLanguages`), may have changed (plan A.9), so
 * cached file types, PSI and index input never go stale:
 * - a change of the claim settings or of the outer-language rules re-types every file through
 *   `FileTypeManagerEx.makeFileTypesChange`;
 * - a file change re-parses just the files concerned through [FileContentUtilCore.reparseFiles], the files whose type
 *   or outer language depends on their ancestors ([dependsOnAncestors]): for an `ansible.cfg` that appears or
 *   disappears, those below its directory (skipping directories with an `ansible.cfg` of their own) and the compose
 *   playbooks next to it; for a role directory that gains or loses the markers that make it a role, those of the role;
 *   for a directory that moves or is renamed, those below it; for a directory that appears, disappears or moves, the
 *   compose playbooks next to it (it may hold an `ansible.cfg`); for a file below `roles/<role>/templates/` that gains
 *   or loses Jinja, that file. Only a change that concerns more than [MAX_REPARSE] files re-types every file instead.
 *
 * The VFS listener notes where to look ([Seeds]) and walks there at once, in the VFS write action, while the walk stays
 * small: one that visits more than [MAX_VISITS_IN_WRITE_ACTION] entries is redone in a non-blocking read action on a
 * pooled thread, so the write action (and the EDT waiting for it) never waits for a long walk. The walks read the
 * children the VFS knows, those persisted by an earlier session included, as the platform's own indexing does
 * ([knownChildren]): a file indexed before a restart may not be loaded in this session, yet its index input depends on
 * its type. They skip the dependency, cache and tool directories of [AnsibleLayout.SKIPPED_DIRS] except `build` and
 * `vendor` ([WALKED_DIRS]).
 *
 * Not covered: a directory without `ansible.cfg` that gains or loses its first role (it becomes or stops being a role
 * library) re-parses only that role's files, not the other files of the library; a role becomes one only through a
 * `tasks/`, `defaults/` or `meta/` directory appearing; a per-file "Override File Type" to Ansible Jinja2 is not
 * re-parsed when its outer language changes.
 *
 * The re-parse and the re-type run on the EDT in a write action, outside modal dialogs (after the settings dialog
 * closes).
 */
object AnsibleJinjaFileTypeRefresh {
    private const val REASON_SETTINGS = "Ansibility: Jinja template settings changed"
    private const val REASON_FILES = "Ansibility: an ansible.cfg, role or template directory changed for too many files"

    /** More files than this in one change re-type everything instead. */
    private const val MAX_REPARSE = 2000

    /** Replaces [MAX_REPARSE] (tests). */
    @TestOnly
    @Volatile
    internal var maxReparseForTests: Int? = null

    private val maxReparse: Int get() = maxReparseForTests ?: MAX_REPARSE

    /** More entries than this visited by the walk in the VFS write action move the walk to a background read action. */
    private const val MAX_VISITS_IN_WRITE_ACTION = 10_000

    /** Replaces [MAX_VISITS_IN_WRITE_ACTION] (tests). */
    @TestOnly
    @Volatile
    internal var maxVisitsInWriteActionForTests: Int? = null

    private val maxVisitsInWriteAction: Int get() = maxVisitsInWriteActionForTests ?: MAX_VISITS_IN_WRITE_ACTION

    /** Runs the walks moved out of the VFS write action one at a time. */
    private val backgroundWalks = AppExecutorUtil.createBoundedApplicationPoolExecutor("Ansibility template refresh", 1)

    private val fileTypesChangePending = AtomicBoolean()

    /** Re-types all files (coalesced: one change for a burst of requests). */
    fun scheduleFileTypesChange(reason: String) {
        if (!fileTypesChangePending.compareAndSet(false, true)) return
        val application = ApplicationManager.getApplication()
        application.invokeLater(
            {
                fileTypesChangePending.set(false)
                application.runWriteAction { FileTypeManagerEx.getInstanceEx().makeFileTypesChange(reason) {} }
            },
            ModalityState.nonModal(),
        )
    }

    /** Re-parses [files] (their file type and PSI are recomputed). */
    fun scheduleReparse(files: Collection<VirtualFile>) {
        if (files.isEmpty()) return
        ApplicationManager.getApplication().invokeLater(
            { FileContentUtilCore.reparseFiles(files.filter { it.isValid }) },
            ModalityState.nonModal(),
        )
    }

    /** Settings changes. */
    internal fun settingsChanged(old: AppSettings, new: AppSettings) {
        if (AnsibleJinjaFileTypeRules.fileTypeInputs(old.jinja) != AnsibleJinjaFileTypeRules.fileTypeInputs(new.jinja)) {
            scheduleFileTypesChange(REASON_SETTINGS)
        }
    }

    /** VFS changes, after they happened (in the VFS write action): notes the [Seeds] and walks them. */
    internal fun filesChanged(events: List<VFileEvent>) {
        val seeds = Seeds()
        for (event in events) {
            if (event.requestor == FileContentUtilCore.FORCE_RELOAD_REQUESTOR) continue
            when (event) {
                is VFileContentChangeEvent -> {
                    val file = event.file
                    if (!AnsibleJinjaFileTypeRules.isJ2(file.name) && AnsibleTemplatePaths.isUnderRoleTemplates(file) && JinjaContentProbe.refresh(file)) {
                        seeds.files += file
                    }
                }
                is VFileCreateEvent -> seeds.appearedOrGone(event.childName, event.parent, event.isDirectory)
                is VFileCopyEvent -> seeds.appearedOrGone(event.newChildName, event.newParent, event.file.isDirectory)
                is VFileDeleteEvent -> seeds.appearedOrGone(event.file.name, event.file.parent, event.file.isDirectory)
                is VFileMoveEvent -> {
                    val file = event.file
                    seeds.trees += file
                    seeds.appearedOrGone(file.name, event.oldParent, file.isDirectory)
                    seeds.appearedOrGone(file.name, event.newParent, file.isDirectory)
                }
                is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) {
                    val file = event.file
                    val parent = file.parent ?: continue
                    val names = listOfNotNull(event.oldValue as? String, event.newValue as? String)
                    if (file.isDirectory) {
                        seeds.trees += file
                        names.forEach { seeds.roleMarker(it, parent) }
                    } else if (AnsibleLayout.ANSIBLE_CFG in names) {
                        seeds.cfgDirs += parent
                    }
                }
            }
        }
        if (seeds.isEmpty()) return
        val limit = maxReparse
        // the walk sees the files as the change left them, so its re-parse is scheduled together with the change
        val reparse = seeds.collect(Reparse(limit, maxVisitsInWriteAction, cancellable = false))
        if (!reparse.deferred) return schedule(reparse)
        // a write action restarts the walk; the seeds are never changed after this point
        ReadAction.nonBlocking<Reparse> { seeds.collect(Reparse(limit, Int.MAX_VALUE, cancellable = true)) }
            .finishOnUiThread(ModalityState.nonModal(), ::schedule)
            .submit(backgroundWalks)
    }

    private fun schedule(reparse: Reparse) {
        if (reparse.overflow) scheduleFileTypesChange(REASON_FILES) else scheduleReparse(reparse.files)
    }

    /**
     * Whether the file type or outer language of a file called [name] depends on its ancestors: a `.j2` file (claimed
     * inside Ansible content, outer language by its path relative to the nearest `ansible.cfg`), a file below
     * `roles/<role>/templates/` ([underRoleTemplates]; outer language), a file whose name the user mapped to Ansible
     * Jinja2 (outer language) or a `*-playbook.y*ml` file ([AnsiblePlaybookYamlOverrider]).
     */
    private fun dependsOnAncestors(name: String, underRoleTemplates: Boolean): Boolean =
        AnsibleJinjaFileTypeRules.isJ2(name) ||
            AnsiblePlaybookYamlOverrider.isPlaybookName(name) ||
            underRoleTemplates ||
            FileTypeManager.getInstance().getFileTypeByFileName(name) == AnsibleJinjaFileType

    /**
     * The children of [dir] the VFS knows without reading the disk: those loaded in this session and those persisted
     * by an earlier one (`NewVirtualFile.iterInDbChildren`, which loads them, as the platform's indexing walks do). A
     * directory the VFS never listed has none: none of its files was ever typed or indexed.
     */
    private fun knownChildren(dir: VirtualFile): Iterable<VirtualFile> =
        if (dir is NewVirtualFile) dir.iterInDbChildren() else dir.children.orEmpty().asList()

    private fun hasKnownAnsibleCfg(dir: VirtualFile): Boolean =
        knownChildren(dir).any { it.name == AnsibleLayout.ANSIBLE_CFG && !it.isDirectory }

    /**
     * Where the VFS events of one batch may have changed what files [dependsOnAncestors] get, for [collect]. Never
     * changed once it is walked.
     */
    private class Seeds {
        /** Files to re-parse themselves. */
        val files = LinkedHashSet<VirtualFile>()

        /** Moved or renamed files and directories, roles that gained or lost a marker: their files that depend. */
        val trees = LinkedHashSet<VirtualFile>()

        /** Directories whose `ansible.cfg` appeared or disappeared. */
        val cfgDirs = LinkedHashSet<VirtualFile>()

        /** Parents of directories that appeared, disappeared or moved (they may hold an `ansible.cfg`). */
        val composeDirs = LinkedHashSet<VirtualFile>()

        fun isEmpty(): Boolean = files.isEmpty() && trees.isEmpty() && cfgDirs.isEmpty() && composeDirs.isEmpty()

        /**
         * A file or directory called [name] appeared in or disappeared from [parent]: an `ansible.cfg` file marks
         * [parent] for [cfgDirs]; a directory marks [parent] for [composeDirs]; see also [roleMarker].
         */
        fun appearedOrGone(name: String, parent: VirtualFile?, isDirectory: Boolean) {
            if (parent == null) return
            when {
                name == AnsibleLayout.ANSIBLE_CFG && !isDirectory -> cfgDirs += parent
                isDirectory -> composeDirs += parent
            }
            roleMarker(name, parent)
        }

        /**
         * A role marker (`tasks/`, `defaults/`, `meta/`) called [name] appeared in or disappeared from [parent], a
         * `roles/<role>/` directory: the role's files that [dependsOnAncestors] are re-parsed.
         */
        fun roleMarker(name: String, parent: VirtualFile) {
            if (name in ROLE_MARKERS && parent.parent?.name == AnsibleLayout.ROLES) trees += parent
        }

        /** Walks the seeds into [reparse] (in a read or write action). */
        fun collect(reparse: Reparse): Reparse {
            files.forEach(reparse::add)
            trees.forEach { reparse.addBelow(it, skipNestedRoots = false) }
            for (dir in cfgDirs) {
                // the directory itself is the new or former root; its parent holds the compose files next to it
                reparse.addBelow(dir, skipNestedRoots = true)
                dir.parent?.let(reparse::addComposePlaybooksIn)
            }
            composeDirs.forEach(reparse::addComposePlaybooksIn)
            return reparse
        }
    }

    /**
     * The files to re-parse; [overflow] once there are more than [limit] (the caller then re-types everything),
     * [deferred] once more than [maxVisits] entries were visited (the caller then walks again in the background).
     * Only a [cancellable] walk (in a non-blocking read action) checks for cancellation, never one in a write action.
     */
    private class Reparse(private val limit: Int, maxVisits: Int, private val cancellable: Boolean) {
        val files = LinkedHashSet<VirtualFile>()

        var overflow = false
            private set

        var deferred = false
            private set

        private var visitsLeft = maxVisits

        private val stopped: Boolean get() = overflow || deferred

        fun add(file: VirtualFile) {
            if (files.add(file) && files.size > limit) overflow = true
        }

        /** Counts one visited entry; false once the walk has to stop. */
        private fun visit(): Boolean {
            if (--visitsLeft < 0) deferred = true
            return !stopped
        }

        /**
         * Adds [root] or the files below it that [dependsOnAncestors]; with [skipNestedRoots], directories below [root]
         * that have an `ansible.cfg` of their own are skipped (their files are relative to that one). Whether a file
         * is below a role's `templates/` is passed down the walk instead of climbing the ancestors of every file.
         */
        fun addBelow(root: VirtualFile, skipNestedRoots: Boolean) {
            if (stopped || !root.isValid) return
            if (!root.isDirectory) {
                if (dependsOnAncestors(root.name, AnsibleTemplatePaths.isUnderRoleTemplates(root))) add(root)
                return
            }
            VfsUtilCore.visitChildrenRecursively(
                root,
                // the value for the children of a directory: whether they are below a role's `templates/`
                object : VirtualFileVisitor<Boolean>(VirtualFileVisitor.limit(MAX_DEPTH)) {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (cancellable) ProgressManager.checkCanceled()
                        if (!visit()) return false
                        val inTemplates = currentValue == true
                        if (!file.isDirectory) {
                            if (dependsOnAncestors(file.name, inTemplates)) add(file)
                            return true
                        }
                        if (file == root) {
                            val rootInTemplates = AnsibleTemplatePaths.isRoleTemplatesDir(root) || AnsibleTemplatePaths.isUnderRoleTemplates(root)
                            setValueForChildren(rootInTemplates)
                            return true
                        }
                        if (file.name in AnsibleLayout.SKIPPED_DIRS && file.name !in WALKED_DIRS) return false
                        if (skipNestedRoots && hasKnownAnsibleCfg(file)) return false
                        setValueForChildren(inTemplates || AnsibleTemplatePaths.isRoleTemplatesDir(file))
                        return true
                    }

                    override fun getChildrenIterable(file: VirtualFile): Iterable<VirtualFile> = knownChildren(file)
                },
            )
        }

        /** Adds the compose `*-playbook.y*ml` files directly in [dir], which depend on an `ansible.cfg` next to them. */
        fun addComposePlaybooksIn(dir: VirtualFile) {
            if (stopped || !dir.isValid || !dir.isDirectory) return
            for (child in knownChildren(dir)) {
                if (!visit()) return
                val name = child.name
                if (!child.isDirectory && AnsiblePlaybookYamlOverrider.isComposeName(name) && AnsiblePlaybookYamlOverrider.isPlaybookName(name)) {
                    add(child)
                }
            }
        }
    }

    /** Names whose presence makes `roles/<x>` a role ([de.terletzkiy.ansibility.context.RoleDirectories]). */
    private val ROLE_MARKERS = setOf("tasks", "defaults", "meta")

    /**
     * Names of [AnsibleLayout.SKIPPED_DIRS] the walks still enter: unlike dependency, cache and tool directories they
     * may hold the user's own templates, and roles may carry these names.
     */
    private val WALKED_DIRS = setOf("build", "vendor")

    private const val MAX_DEPTH = 30
}

/** `applicationListeners` on [AnsibilityAppSettingsListener.TOPIC]. */
class AnsibleJinjaSettingsListener : AnsibilityAppSettingsListener {
    override fun appSettingsChanged(old: AppSettings, new: AppSettings) {
        AnsibleJinjaFileTypeRefresh.settingsChanged(old, new)
    }
}

/** `applicationListeners` on `VFS_CHANGES_BG`: runs the checks of [AnsibleJinjaFileTypeRefresh] off the EDT. */
class AnsibleJinjaVfsListener : BulkFileListenerBackgroundable {
    override fun after(events: List<VFileEvent>) {
        AnsibleJinjaFileTypeRefresh.filesChanged(events)
    }
}
