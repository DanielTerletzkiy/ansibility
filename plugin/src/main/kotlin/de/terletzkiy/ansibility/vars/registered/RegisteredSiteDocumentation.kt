package de.terletzkiy.ansibility.vars.registered

import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteDocumentation

/**
 * Hover and Ctrl+Q on a member of a registered result (plan amendment FU, F1.12; `siteDocumentation`
 * `ansibilityRegistered`, before the variable card): `x.stdout` shows the card of that return value
 * ([RegisteredMemberDocumentationTarget]). Every other site, the registered variable itself and members no
 * documentation knows return null, so the variable card ([de.terletzkiy.ansibility.vars.VarSiteDocumentation])
 * answers. Needs indexes, so it is not `DumbAware`.
 */
class RegisteredSiteDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        val reference = site as? AnsibleSite.VarRef ?: return null
        val member = RegisteredSites.memberAt(file, reference) ?: return null
        return RegisteredMemberDocumentationTarget(file.project, member.result, member.path, member.display)
    }
}
