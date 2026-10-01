package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaLocator

/**
 * The text-level Jinja locator of M2–M4 (plan A.4, registered `order="last"` so the PSI locator of M5 takes over):
 * the variable reference under the caret in `{{ }}`/`{% %}` of YAML scalars, in bare implicit expressions and in
 * template files of any file type (see [JinjaTextSites]).
 *
 * - The reference is the root name plus the accessors up to the one under the caret: on `ssl` in
 *   `item.floating.ssl.port` the result is `item` with `attrPath = [floating, ssl]`; on `item` it is `item` alone.
 * - Filter and test names, keyword-argument names, assignment targets, string literals, comments and `{% raw %}`
 *   bodies are not variables: the result is null (the docs track classifies filters and tests).
 * - [AnsibleSite.VarRef.localNames] are the template locals visible at the caret; a reference to a local keeps
 *   its name, so consumers can tell it apart from a context variable.
 * - [AnsibleSite.VarRef.range] is in host-file coordinates: from the root name through the accessor under the caret.
 *
 * Needs no index, so it keeps working while indexing.
 */
class TextJinjaLocator : JinjaLocator, DumbAware {
    override fun locate(file: PsiFile, offset: Int): AnsibleSite.VarRef? {
        val analysis = JinjaTextSites.analysisAt(file, offset) ?: return null
        val ref = analysis.reference ?: return null
        val covered = JinjaTextSites.coveredSegmentEnds(analysis.text, ref, analysis.textOffset)
        val end = covered.lastOrNull() ?: ref.nameRange.endOffset
        return AnsibleSite.VarRef(
            name = ref.name,
            attrPath = ref.attrPath.take(covered.size),
            container = analysis.container,
            range = analysis.toHost(TextRange(ref.nameRange.startOffset, end)),
            localNames = analysis.localNames,
        )
    }
}
