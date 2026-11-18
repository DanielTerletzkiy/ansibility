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
import de.terletzkiy.ansibility.api.AnsibleWorkspace

/**
 * The variable source hints (plan amendment R31, D232–D237): a declarative inlay hints provider registered for `yaml`
 * and `AnsibleJinja` under one provider id ([VarSourceHints.PROVIDER_ID]), so Settings › Editor › Inlay Hints, the hint's
 * own "Disable" menu and "Ansibility: Toggle Variable Source Hints" are one switch for every project (D236). The option
 * [VarSourceHints.OPTION_SINGLE] is "Also for variables with only a default".
 *
 * The platform runs it in the background highlighting pass (never in dumb mode: the provider is not dumb aware); the
 * collector reads the file once ([OwnBypassCollector]) and [VarSourceHints] does the work. A click on a hint opens the
 * Ctrl+B list of its reference ([VarSourceHintClickHandler]). Context switches recompute the hints through
 * `HostModeRestarts` ([VarSourceHintsRefresh]).
 *
 * Declarative inlay hints are public API in 262 (`codeInsight.declarativeInlayProvider`, checked with javap: no
 * `ApiStatus` annotation on the provider, collector, sink, position, format or payload classes).
 */
class VarSourceHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val project = file.project
        if (AnsibleWorkspace.getInstance(project).contextOf(file.viewProvider.virtualFile) == null) return null
        VarSourceHintsRefresh.getInstance(project).followContextSwitches()
        return Collector()
    }

    private class Collector : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            var single = false
            sink.whenOptionEnabled(VarSourceHints.OPTION_SINGLE) { single = true }
            val hints = try {
                VarSourceHints.collect(file.project, file, single)
            } catch (_: IndexNotReadyException) {
                return
            }
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
