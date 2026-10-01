package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.WorkspaceState
import org.jetbrains.annotations.Nls
import java.util.function.Function
import javax.swing.JComponent

/**
 * The context banners (plan amendment R7/R8, F8.1, absorbing X49): their texts, kept pure so they are tested without
 * an editor, and the two [EditorNotificationProvider]s below.
 */
object ContextBanners {
    /**
     * "This file applies to prod-prod1 only; your Ansible context is test › test-test1" for a file scope disjoint from
     * the selection. With Follow editor off the file is not loaded for the selection, and the text says so.
     */
    @Nls
    fun overrideText(scope: HostScope, followEditor: Boolean, playLabel: String? = null): String {
        val hosts = ContextTexts.hostNames(scope.fileHosts.ifEmpty { scope.hosts })
        val context = contextLabel(scope, playLabel)
        return ContextTexts.message(if (followEditor) "banner.override" else "banner.override.not.loaded", hosts, context)
    }

    /** `test › test-test1`, `All environments · play playbook-setup-system.yml › KeepAliveD`. */
    @Nls
    fun contextLabel(scope: HostScope, playLabel: String? = null): String {
        val label = ContextTexts.environmentAndHost(scope.selection)
        val play = playLabel ?: scope.selection.play ?: return label
        return ContextTexts.message("banner.context.play", label, play)
    }

    /**
     * Ex-X49 for a playbook whose own plays run from another directory than the one holding the root's
     * playbook-level variables (a danger-zone playbook, a playbook in a subdirectory): "Plays defined in this file do
     * not load the ansible group_vars; the imported ../../playbook-setup-replisync.yml plays do". Null when the plays do
     * load them, when there are none, or for other files. Needs a read lock.
     */
    @Nls
    fun playbookDirText(project: Project, root: AnsibleRoot, playbook: VirtualFile): String? {
        val owner = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(root) ?: root
        val varsDirs = listOf(AnsibleLayout.GROUP_VARS, AnsibleLayout.HOST_VARS).mapNotNull { name ->
            owner.dir.findChild(name)?.takeIf { it.isDirectory }
        }
        if (varsDirs.isEmpty() || playbook.parent == owner.dir) return null
        val graph = PlayGraph.getInstance(project)
        if (graph.playsOf(playbook).isEmpty()) return null
        val missing = varsDirs.joinToString(", ") { "${owner.dir.name}/${it.name}/**" }
        val loading = graph.imports(playbook).filter { edge ->
            val target = edge.target ?: return@filter false
            target.parent == owner.dir && graph.playsOf(target).isNotEmpty()
        }
        if (loading.isEmpty()) return ContextTexts.message("banner.playbook.dir", missing)
        return ContextTexts.message("banner.playbook.dir.imports", missing, ContextTexts.names(loading.map { it.path }))
    }
}

/**
 * The banner on a file whose scope is disjoint from the selection (plan amendment R7/R8, "Per-file inference"): the
 * file scope wins (with Follow editor off the file is not loaded for the selection), and the banner offers to make
 * the file's host or environment the context ("Make prod-prod1 the Ansible context"; All environments when its hosts
 * span several) or to switch. Not dumb-aware: the host scope needs smart mode.
 */
class ContextOverrideBannerProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val impl = AnsibleContextServiceImpl.getInstance(project) ?: return null
        if (AnsibleWorkspace.getInstance(project).rootFor(file) == null) return null
        val scope = impl.hostScope(file, -1, followEditor = true)
        if (!scope.overriddenSelection) return null
        val playLabel = scope.selection.play?.let { ContextChoices(project).playLabel(scope.root, it) }
        val text = ContextBanners.overrideText(scope, ContextSwitcher.followsEditor(project), playLabel)
        val target = FileScopeSegment.narrowestTarget(scope) ?: ContextTarget(scope.root, null)
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text(text)
                createActionLabel(ContextTexts.message("banner.make.context", target.label)) { ContextSwitcher.use(project, target) }
                createActionLabel(ContextTexts.message("banner.switch"), SWITCH_CONTEXT_ACTION_ID)
            }
        }
    }
}

/** The ex-X49 playbook-dir banner on playbooks ([ContextBanners.playbookDirText]). */
class PlaybookDirBannerProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        if (context.kind != FileKind.PLAYBOOK) return null
        val text = ContextBanners.playbookDirText(project, context.root, file) ?: return null
        return Function { editor -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).text(text) }
    }
}

/**
 * Refreshes the context banners when a selection or Follow editor changes, or the Ansible structure does
 * (`projectListeners` in `ansibility-host.xml`). It only schedules the platform's own asynchronous update.
 */
class ContextBannerRefresher(private val project: Project) : AnsibilitySettingsListener, AnsibleStructureListener {
    override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
        if (old.roots != new.roots || old.followEditor != new.followEditor) refresh()
    }

    override fun structureChanged() = refresh()

    private fun refresh() {
        if (!project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
    }
}
