package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.JinjaLocator
import de.terletzkiy.ansibility.api.SiteClassifier

/**
 * The variables area's `siteClassifier` (plan A.4, `order="first"`):
 * - [AnsibleSite.VarRef] for a variable reference in Jinja, from the first `jinjaLocator` extension that answers
 *   (in their `order`: the PSI locator of M5 before [TextJinjaLocator]); filter and test names are left to the
 *   docs track's classifier, which runs later;
 * - [AnsibleSite.VarKey] for a key in a vars-like place, decided structurally by [VarKeySites].
 *
 * Returns null for every other position, in particular for module names, module options and keywords of tasks and
 * plays. Needs no index (while indexing, only `DumbAware` locators are asked).
 */
class VarsSiteClassifier : SiteClassifier, DumbAware {
    override fun classify(file: PsiFile, offset: Int): AnsibleSite? {
        val project = file.project
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return null
        val dumb = DumbService.isDumb(project)
        val reference = JinjaLocator.EP_NAME.computeSafeIfAny { locator ->
            ProgressManager.checkCanceled()
            if (dumb && !DumbService.isDumbAware(locator)) null else locator.locate(file, offset)
        }
        return reference ?: VarKeySites.at(file, offset, context)?.site
    }
}
