package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace

/**
 * The variable source hints (plan amendment R31, D232–D237, as amended by the R31 fixes): a declarative inlay hints
 * provider registered for `yaml` and `AnsibleJinja` under one provider id ([VarSourceHints.PROVIDER_ID]), so Settings ›
 * Editor › Inlay Hints, the hint's own "Disable" menu and "Ansibility: Toggle Variable Source Hints" are one switch for
 * every project (D236). Its options: [VarSourceHints.OPTION_SINGLE] "Also when every environment gets the same value"
 * and [VarSourceHints.OPTION_VARS_FILES] "Also in vars and inventory files", both off.
 *
 * The platform runs it in the background highlighting pass (never in dumb mode: the provider is not dumb aware); the
 * collector reads the file once ([OwnBypassCollector]) and [VarSourceHints] does the work. Each pass records on its
 * editor the selection it showed ([VarSourceHintsRefresh.record]), so context switches recompute only stale visible
 * editors. A click on a hint opens the Ctrl+B list of its reference ([VarSourceHintClickHandler]).
 *
 * Declarative inlay hints are public API in 262 (`codeInsight.declarativeInlayProvider`, checked with javap: no
 * `ApiStatus` annotation on the provider, collector, sink, position, format or payload classes).
 */
class VarSourceHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val project = file.project
        val root = AnsibleWorkspace.getInstance(project).contextOf(file.viewProvider.virtualFile)?.root ?: return null
        VarSourceHintsRefresh.getInstance(project).followContextSwitches()
        return Collector(editor, root)
    }

    private class Collector(private val editor: Editor, private val root: AnsibleRoot) : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            var same = false
            var varsFiles = false
            sink.whenOptionEnabled(VarSourceHints.OPTION_SINGLE) { same = true }
            sink.whenOptionEnabled(VarSourceHints.OPTION_VARS_FILES) { varsFiles = true }
            // The selection is read first: a switch while the pass runs leaves the editor stale, never wrongly current.
            val before = VarSourceHintsRefresh.snapshot(file.project, root, hasHints = false)
            val hints = try {
                VarSourceHints.collect(file.project, file, same, varsFiles)
            } catch (_: IndexNotReadyException) {
                return
            }
            VarSourceHintsRefresh.record(editor, VarSourceHintsRefresh.State(before.root, before.selection, before.followEditor, hints.isNotEmpty()))
            for (hint in hints) {
                val action = InlayActionData(StringInlayActionPayload(hint.payload), VarSourceHints.HANDLER_ID)
                sink.addPresentation(InlineInlayPosition(hint.offset, true), tooltip = hint.tooltip, hintFormat = HintFormat.default) {
                    clickHandlerScope(action) {
                        for (segment in hint.segments) text(segment)
                    }
                }
            }
        }
    }
}
