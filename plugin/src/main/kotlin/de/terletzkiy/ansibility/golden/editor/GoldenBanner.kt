package de.terletzkiy.ansibility.golden.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.compare.RoleCompare
import de.terletzkiy.ansibility.golden.take.TakeService
import de.terletzkiy.ansibility.model.drift.DriftRules
import de.terletzkiy.ansibility.model.drift.RoleDriftListener
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import org.jetbrains.annotations.Nls
import java.util.function.Function
import javax.swing.JComponent

/** What the golden banner of one file says (plan amendment R24, X120). Facts only; no content. */
class GoldenBanner internal constructor(
    /** The copy the file belongs to (never the golden copy). */
    val copy: RoleCopy,
    /** The golden copy of the role. */
    val golden: RoleCopy,
    /** The file's path inside the role ("tasks/main.yml"). */
    val relPath: String,
    /** True: only this copy has the file; false: both have it, with other content. */
    val onlyHere: Boolean,
    /** Key material or a secret by name, or a whole-file vault: "content not shown", no Take. */
    val sensitive: Boolean,
    /** "golden/roles/web/tasks/main.yml": golden's file, relative to the project. */
    val goldenPath: String,
) {
    /** "Differs from golden (golden/roles/web/tasks/main.yml)", "… (content not shown)", or "Only in this copy; …". */
    @get:Nls
    val text: String
        get() = when {
            onlyHere -> message("banner.onlyHere")
            sensitive -> message("banner.differs.sensitive")
            else -> message("banner.differs", goldenPath)
        }

    /** Whether the banner offers "Take Golden's Version" (never for key and vault files). */
    val offersTake: Boolean get() = !sensitive

    /** The take and compare target: this file in this copy. */
    val target: GoldenTarget get() = GoldenTarget(copy, relPath)

    override fun toString(): String = "GoldenBanner(${copy.root.displayName} $relPath, onlyHere=$onlyHere, sensitive=$sensitive)"
}

/**
 * When a file shows the golden banner (plan amendment R24, X120): a file of a role copy that is not the golden copy,
 * while a golden root is set, the role has a golden copy, and the drift known now ([RoleDriftService.cached], never
 * computed here) says this file differs: changed, or only in this copy. A file only golden has has no editor, so no
 * banner. Never in a detached root (the catalog has none of its copies), never once the user hid it for the file as
 * it is ([GoldenBannerState]).
 */
object GoldenBanners {
    /**
     * The banner of [file], or null. Read action (the provider's background one): catalog and cached drift only. When
     * a role file is asked about, that one role name is requested from the drift service
     * ([RoleDriftService.requestNames]: cheap, never blocks, never the all-names pass of the Roles tab), so a banner
     * not known yet appears once the drift is computed ([GoldenBannerRefresher]), and the drift keeps following edits.
     */
    fun of(project: Project, file: VirtualFile): GoldenBanner? {
        if (project.isDisposed || !file.isValid || file.isDirectory) return null
        val catalog = RoleCatalog.getInstance(project).snapshot()
        if (catalog.golden == null) return null
        val copy = catalog.copyContaining(file) ?: return null
        if (AnsibleWorkspace.getInstance(project).rootFor(file)?.detached == true) return null
        val golden = catalog.reference(copy.name) ?: return null
        if (golden.dir == copy.dir) return null
        val relPath = GoldenTargets.relativePath(copy.dir, file) ?: return null
        val service = RoleDriftService.getInstance(project)
        service.requestNames(listOf(copy.name))
        val drift = service.cached(copy.name)?.takeIf { it.reference?.dir == golden.dir } ?: return null
        val paths = drift.copyOf(copy.dir)?.paths ?: return null
        val onlyHere = relPath in paths.onlyHere
        if (!onlyHere && relPath !in paths.changed) return null
        if (GoldenBannerState.getInstance(project).isDismissed(file)) return null
        val sensitive = relPath in paths.sensitive || DriftRules.isSensitivePath(relPath)
        return GoldenBanner(copy, golden, relPath, onlyHere, sensitive, projectPath(project, golden, relPath))
    }

    /** Golden's file "golden/roles/web/tasks/main.yml" relative to the project directory, else from the root's name. */
    private fun projectPath(project: Project, golden: RoleCopy, relPath: String): String {
        val base = RootKeys.projectDir(project)
        val dir = base?.let { RootKeys.relativePath(it, golden.dir) }
        return when {
            dir == null -> listOfNotNull(golden.root.displayName, VfsUtilCore.getRelativePath(golden.dir, golden.root.dir, '/'), relPath).joinToString("/")
            dir.isEmpty() -> relPath
            else -> "$dir/$relPath"
        }
    }
}

/**
 * X120 (plan amendment R24): the banner on an editor of a role file that differs from golden, with **Compare**
 * (`RoleCompare.compareWithGolden` at this file; a key or vault file shows as its placeholder), **Take Golden's
 * Version** (the single-file take, X121; not for key and vault files), **Align with Golden…**
 * (`Ansibility.Golden.AlignWithGolden` for this copy) and **Hide** (per user and file until the file changes,
 * [GoldenBannerState]). A link that runs an action is labelled with that action's menu text (one name per action).
 * The wording is "differs from golden", never "outdated". Not dumb-aware: the catalog reads the role registry.
 */
class GoldenBannerProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val banner = GoldenBanners.of(project, file) ?: return null
        return Function { editor -> panel(project, file, editor, banner) }
    }

    private fun panel(project: Project, file: VirtualFile, editor: FileEditor, banner: GoldenBanner): EditorNotificationPanel =
        EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
            text(banner.text)
            createActionLabel(message("banner.compare")) { RoleCompare.getInstance(project).compareWithGolden(banner.target) }
            if (banner.offersTake) createActionLabel(TAKE_TEXT) { TakeService.getInstance(project).takeGoldens(banner.target) }
            if (ActionManager.getInstance().getAction(GoldenActionIds.ALIGN_WITH_GOLDEN) != null) {
                createActionLabel(ALIGN_TEXT) { runAction(project, GoldenActionIds.ALIGN_WITH_GOLDEN, copyContext(project, banner.copy.dir)) }
            }
            // Hide is not offered among the editor's intentions (Alt+Enter), the other links are.
            createActionLabel(message("banner.hide"), {
                GoldenBannerState.getInstance(project).dismiss(file)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }, false)
        }

    companion object {
        /** Where the banner's actions say they come from. */
        const val PLACE: String = "AnsibilityGoldenBanner"

        /** The Take link: the menu text of Take Golden's Version (`Ansibility.Golden.TakeGoldens`), whose take it runs. */
        @get:Nls
        internal val TAKE_TEXT: String get() = message("action.take.goldens.menu")

        /** The Align link: the menu text of Align with Golden… (`Ansibility.Golden.AlignWithGolden`), which it runs. */
        @get:Nls
        internal val ALIGN_TEXT: String get() = message("action.align.with.menu")

        /** The copy as `GoldenDataKeys.ROLE_COPY`, as the Roles tab gives it. */
        internal fun copyContext(project: Project, dir: VirtualFile): DataContext =
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(GoldenDataKeys.ROLE_COPY, dir).build()

        /** Runs the registered action [id] with [context] when it is enabled there. EDT. */
        private fun runAction(project: Project, id: String, context: DataContext) {
            if (project.isDisposed) return
            val action = ActionManager.getInstance().getAction(id) ?: return
            val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), PLACE, ActionUiKind.NONE, null)
            ActionUtil.updateAction(action, event)
            if (event.presentation.isEnabled) ActionUtil.performAction(action, event)
        }
    }
}

/**
 * Keeps the golden banners current (`projectListeners` in `ansibility-golden.xml`): a role's new drift updates the open
 * files of that role's copies ([RoleDriftListener], background thread), and a change of the drift settings (the golden
 * root, "ignore molecule/") updates every banner. It only schedules the platform's own asynchronous update.
 */
class GoldenBannerRefresher(private val project: Project) : RoleDriftListener, AnsibilitySettingsListener {
    override fun driftChanged(roleName: String) {
        if (project.isDisposed) return
        val dirs = RoleCatalog.getInstance(project).snapshot().copies(roleName).map { it.dir }
        if (dirs.isEmpty()) return
        val notifications = EditorNotifications.getInstance(project)
        for (file in FileEditorManager.getInstance(project).openFiles) {
            if (dirs.any { VfsUtilCore.isAncestor(it, file, true) }) notifications.updateNotifications(file)
        }
    }

    override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {
        if (old.drift != new.drift && !project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
    }
}
