package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import org.jetbrains.annotations.Nls
import java.util.function.Function
import javax.swing.JComponent

/**
 * D198: nothing writes into the external golden root (plan amendment R25), the git mirror (the next fetch replaces it)
 * or the folder outside the project (X125). Every file below its base directory is denied (project extension point
 * `com.intellij.writingAccessProvider`): the editor opens it read-only and the platform refuses edits, saves and
 * refactorings there with [getReadOnlyMessage]. `golden.sync.RoleWriter` refuses such a target too (defence in depth).
 */
class GoldenMirrorWritingAccessProvider(private val project: Project) : WritingAccessProvider() {
    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> {
        if (project.isDisposed) return emptyList()
        val root = ExternalGoldenRoot.getInstance(project)
        return files.filter(root::isUnder)
    }

    override fun isPotentiallyWritable(file: VirtualFile): Boolean =
        project.isDisposed || !ExternalGoldenRoot.getInstance(project).isUnder(file)

    override fun getReadOnlyMessage(): String = message("external.readOnly")
}

/**
 * The editor banner on a file of the external golden root (D198): "Golden mirror of <url> (main @ 3f2a1c9) —
 * read-only; change it in the golden repository", or "Golden folder ~/src/golden — read-only here". State only (no
 * VFS walk, no index): dumb-aware.
 */
class GoldenMirrorBannerProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val text = GoldenMirrorBanner.text(project, file) ?: return null
        return Function { editor -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply { text(text) } }
    }
}

/** The text of [GoldenMirrorBannerProvider]. */
object GoldenMirrorBanner {
    /** The banner of [file], or null when it is not below the external golden root. Any thread. */
    @Nls
    fun text(project: Project, file: VirtualFile): String? {
        if (project.isDisposed || file.isDirectory || !ExternalGoldenRoot.getInstance(project).isUnder(file)) return null
        val state = GoldenMirrors.getInstance(project).state() ?: return null
        return when (state.kind) {
            GoldenMirrorState.Kind.GIT -> {
                val url = GoldenGitUrls.display(AnsibilityProjectSettings.getInstance(project).settings.drift.remote.normalized().url)
                val commit = state.shortCommit ?: return message("external.banner.git.unfetched", url)
                message("external.banner.git", url, state.ref ?: "HEAD", commit)
            }
            GoldenMirrorState.Kind.FOLDER -> message("external.banner.folder", FileUtil.getLocationRelativeToUserHome(state.baseDir?.toString() ?: state.source))
        }
    }
}
