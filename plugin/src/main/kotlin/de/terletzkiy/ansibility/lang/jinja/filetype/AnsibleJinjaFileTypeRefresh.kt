package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.FileContentUtilCore
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.settings.AnsibilityAppSettingsListener
import de.terletzkiy.ansibility.settings.AppSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells the platform when the answer of [AnsibleJinjaFileTypeOverrider] may have changed (plan A.9), so cached file
 * types, PSI and index input never go stale:
 * - a change of the claim settings or of the outer-language rules, or an `ansible.cfg` that appears or disappears,
 *   re-types every file through `FileTypeManagerEx.makeFileTypesChange`;
 * - a narrow change (a role directory gains or loses the markers that make it a role, a directory or template moves,
 *   a file below `roles/<role>/templates/` gains or loses Jinja) re-parses just the files concerned through
 *   [FileContentUtilCore.reparseFiles].
 *
 * Both run on the EDT in a write action, outside modal dialogs (after the settings dialog closes).
 */
object AnsibleJinjaFileTypeRefresh {
    private const val REASON_SETTINGS = "Ansibility: Jinja template settings changed"
    private const val REASON_FILES = "Ansibility: ansible.cfg added or removed, or many templates moved"

    /** More files than this in one narrow change re-type everything instead. */
    private const val MAX_REPARSE = 2000

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

    /** VFS changes, after they happened. */
    internal fun filesChanged(events: List<VFileEvent>) {
        var retypeAll = false
        val reparse = LinkedHashSet<VirtualFile>()
        for (event in events) {
            if (event.requestor == FileContentUtilCore.FORCE_RELOAD_REQUESTOR) continue
            when (event) {
                is VFileContentChangeEvent -> {
                    val file = event.file
                    if (!AnsibleJinjaFileTypeRules.isJ2(file.name) && AnsibleTemplatePaths.isUnderRoleTemplates(file) && JinjaContentProbe.refresh(file)) {
                        reparse += file
                    }
                }
                is VFileCreateEvent -> retypeAll = retypeAll || affects(event.childName, event.parent, event.isDirectory, reparse)
                is VFileCopyEvent -> retypeAll = retypeAll || affects(event.newChildName, event.newParent, event.file.isDirectory, reparse)
                is VFileDeleteEvent -> retypeAll = retypeAll || affects(event.file.name, event.file.parent, event.file.isDirectory, reparse)
                is VFileMoveEvent -> {
                    val file = event.file
                    retypeAll = retypeAll || file.name == AnsibleLayout.ANSIBLE_CFG || !collectTemplates(file, reparse)
                    affects(file.name, event.oldParent, file.isDirectory, reparse)
                    affects(file.name, event.newParent, file.isDirectory, reparse)
                }
                is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) {
                    val file = event.file
                    val names = listOf(event.oldValue as? String, event.newValue as? String)
                    if (AnsibleLayout.ANSIBLE_CFG in names) retypeAll = true
                    if (file.isDirectory) {
                        retypeAll = retypeAll || !collectTemplates(file, reparse)
                        file.parent?.let { parent -> names.filterNotNull().forEach { affects(it, parent, true, reparse) } }
                    }
                }
            }
        }
        when {
            retypeAll || reparse.size > MAX_REPARSE -> scheduleFileTypesChange(REASON_FILES)
            else -> scheduleReparse(reparse)
        }
    }

    /**
     * Handles a file called [name] appearing in or disappearing from [parent]: an `ansible.cfg` needs a full re-type
     * (returns true); a role marker (`tasks/`, `defaults/`, `meta/`) inside `roles/<role>/` re-parses that role's
     * templates.
     */
    private fun affects(name: String, parent: VirtualFile?, isDirectory: Boolean, reparse: MutableSet<VirtualFile>): Boolean {
        if (name == AnsibleLayout.ANSIBLE_CFG && !isDirectory) return true
        if (parent == null || name !in ROLE_MARKERS) return false
        if (parent.parent?.name != AnsibleLayout.ROLES) return false
        return !collectTemplates(parent, reparse)
    }

    /**
     * Adds the `.j2` files below [root] and the files below its `templates/` directories to [into]; false when there
     * are more than [MAX_REPARSE] (the caller then re-types everything).
     */
    private fun collectTemplates(root: VirtualFile, into: MutableSet<VirtualFile>): Boolean {
        if (!root.isValid) return true
        if (!root.isDirectory) {
            if (AnsibleJinjaFileTypeRules.isJ2(root.name) || AnsibleTemplatePaths.isUnderRoleTemplates(root)) into += root
            return true
        }
        var overflow = false
        VfsUtilCore.visitChildrenRecursively(
            root,
            object : VirtualFileVisitor<Unit>(VirtualFileVisitor.limit(MAX_DEPTH)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (overflow) return false
                    if (file.isDirectory) return file.name !in AnsibleLayout.SKIPPED_DIRS
                    if (AnsibleJinjaFileTypeRules.isJ2(file.name) || AnsibleTemplatePaths.isUnderRoleTemplates(file)) {
                        into += file
                        if (into.size > MAX_REPARSE) overflow = true
                    }
                    return true
                }
            },
        )
        return !overflow
    }

    /** Names whose presence makes `roles/<x>` a role ([de.terletzkiy.ansibility.context.RoleDirectories]). */
    private val ROLE_MARKERS = setOf("tasks", "defaults", "meta")

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
