package de.terletzkiy.ansibility.lang.jinja.locator

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaLocator
import de.terletzkiy.ansibility.lang.jinja.scopes.JinjaScopes

/**
 * The PSI-based Jinja locator of M5 (plan A.4, WU C5), registered `order="first"` before the text-level
 * `vars.TextJinjaLocator`, which stays the fallback: the variable reference under the caret read from the Ansible Jinja
 * PSI of a template file or of the fragment injected into a YAML scalar ([JinjaPsiSite]).
 *
 * The answer is the one the text locator gives for the same position, now computed from the parse tree:
 * - the reference is the root name plus the constant accessors up to the one under the caret ([JinjaReferenceChain]):
 *   on `ssl` in `item.floating.ssl.port` it is `item` with `attrPath = [floating, ssl]`;
 * - filter and test names, keyword-argument names, assignment targets, string literals, comments and `{% raw %}`
 *   bodies are no references (null);
 * - [AnsibleSite.VarRef.localNames] are the bare template locals visible at the caret by [JinjaScopes] (`for` targets
 *   shadow `item`, macro parameters shadow inventory variables, a `set` inside a loop stays in it);
 * - [AnsibleSite.VarRef.range] is in host-file coordinates, from the root name through the accessor under the caret.
 *
 * Null wherever there is no Jinja PSI (a `.j2` file kept as YAML, a scalar the injector skips) or no reference: the
 * text locator then answers. Needs no index.
 */
class PsiJinjaLocator : JinjaLocator, DumbAware {
    override fun locate(file: PsiFile, offset: Int): AnsibleSite.VarRef? {
        val site = JinjaPsiSite.at(file, offset) ?: return null
        val chain = JinjaReferenceChains.at(site.file, site.offset) ?: return null
        val covered = chain.coveredSegmentEnds(site.offset)
        val end = covered.lastOrNull() ?: chain.nameRange.endOffset
        return AnsibleSite.VarRef(
            name = chain.name,
            attrPath = chain.path.take(covered.size),
            container = site.container,
            range = site.toHost(TextRange(chain.nameRange.startOffset, end)),
            localNames = JinjaScopes.of(site.file).visibleNamesAt(site.offset),
        )
    }
}
