package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.util.Consumer
import de.terletzkiy.ansibility.dispatch.SiteDispatch

/**
 * `highlightUsagesHandlerFactory` (`id="ansibilityVars"`, `order="first"`, F1.10): Highlight Usages in File
 * (Ctrl+Shift+F7, which passes the injected editor inside an injection) and the caret's identifier highlighting
 * (host editor, on every caret move). The handler always works on the top-level editor and the host file, so uses in
 * other injections and the definitions of the same file are host ranges.
 *
 * Not used while indexing (the occurrences come from the indexes).
 */
class VarHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase<*>? {
        if (DumbService.isDumb(file.project)) return null
        val offset = editor.caretModel.offset
        val symbol = VarUsageSearch.symbolAt(file, offset) ?: return null
        val host = SiteDispatch.hostPosition(file, offset).file
        return VarHighlightUsagesHandler(InjectedLanguageEditorUtil.getTopLevelEditor(editor), host, symbol)
    }
}

/**
 * Marks the reads (Jinja uses) and writes (definitions, the loop or local binding) of one variable in the host file,
 * from that file's own entries only ([VarUsageSearch.rangesIn], [OpenFileIndexData]: never the indexes), since the
 * caret highlighting runs it on a background thread under a read lock on every caret move and keystroke.
 */
class VarHighlightUsagesHandler internal constructor(
    editor: Editor,
    file: PsiFile,
    private val symbol: VarSymbolElement,
) : HighlightUsagesHandlerBase<VarSymbolElement>(editor, file) {

    override fun getTargets(): List<VarSymbolElement> = listOf(symbol)

    // com.intellij.util.Consumer is @Obsolete, but it is the parameter type of this platform method.
    override fun selectTargets(targets: List<VarSymbolElement>, selectionConsumer: Consumer<in List<VarSymbolElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<VarSymbolElement>) {
        val virtualFile = myFile.originalFile.viewProvider.virtualFile
        val (reads, writes) = VarUsageSearch.rangesIn(myFile.project, symbol, virtualFile)
        myReadUsages += reads
        myWriteUsages += writes
        buildStatusText(AnsibilityUsagesBundle.message("highlight.variable"), reads.size + writes.size)
    }

    /** The ranges come from [computeUsages]; there are no PSI references to search. */
    override fun highlightReferences(): Boolean = false
}
