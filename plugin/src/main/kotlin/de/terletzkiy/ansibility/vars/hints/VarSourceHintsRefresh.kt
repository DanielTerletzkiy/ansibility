package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.context.host.HostModeRestarts
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the variable source hints current (plan amendment R31, D234, D236):
 * - **Context switches:** the hints show the winner for the selected context, so the first hint pass of a project
 *   registers them with [HostModeRestarts] (the selection-change hook), counted only while the provider is on; a
 *   selection change then recomputes the hints of the root's open editors.
 * - **The toggle:** [setEnabled] flips the platform's own switch for the provider
 *   ([DeclarativeInlayHintsSettings.setProviderEnabled], application level: every project) and recomputes the hints of
 *   every open editor, as the platform's "Disable hints" action does
 *   ([DeclarativeInlayHintsPassFactory.scheduleRecompute], public and unannotated in 262: a daemon restart alone keeps
 *   the hints while the PSI is unchanged).
 */
@Service(Service.Level.PROJECT)
class VarSourceHintsRefresh(private val project: Project) : Disposable {
    private val following = AtomicBoolean()

    /** Registers the hints with the selection-change hook once per project. */
    fun followContextSwitches() {
        if (!following.compareAndSet(false, true)) return
        HostModeRestarts.getInstance(project).register(this, inlays = true) { isEnabled() }
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): VarSourceHintsRefresh = project.service()

        /**
         * True when the provider is on (its own switch, the default being on). The global "show inlay hints" switch is not
         * consulted: its `InlayHintsSettings` is `@ApiStatus.Experimental` in 262, and with hints off globally a restart
         * only costs a re-highlight.
         */
        fun isEnabled(): Boolean = isProviderEnabled()

        /** The platform's switch for the provider; on by default (D236). */
        fun isProviderEnabled(): Boolean = DeclarativeInlayHintsSettings.getInstance().isProviderEnabled(VarSourceHints.PROVIDER_ID) ?: true

        /** Turns the hints on or off for every project and recomputes the open editors' hints. Call on the EDT. */
        fun setEnabled(enabled: Boolean) {
            DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, enabled)
            recomputeOpenEditors()
        }

        /** Recomputes the declarative inlay hints of every open editor of every project. Call on the EDT. */
        fun recomputeOpenEditors() {
            for (editor in EditorFactory.getInstance().allEditors) {
                val project = editor.project ?: continue
                if (project.isDisposed) continue
                DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
            }
        }
    }
}
