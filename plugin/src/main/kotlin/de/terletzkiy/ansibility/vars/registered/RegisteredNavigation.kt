package de.terletzkiy.ansibility.vars.registered

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.dispatch.WebDocTarget
import de.terletzkiy.ansibility.resolve.register.AnsibilityRegisteredBundle.message
import de.terletzkiy.ansibility.resolve.register.RegisteredDocs

/**
 * Ctrl+B on a member of a registered result (plan amendment FU, F1.12; `siteNavigation` `ansibilityRegistered`, before
 * the variable targets): `x.stdout` opens the module's documentation page at that return value
 * (`command_module.html#return-stdout`), a common key the common return values reference, `results` the loop guide
 * ([RegisteredDocs.pages]); several pages (a name registered by tasks of different modules) open the chooser. On the
 * variable itself, and on members no documentation knows, it returns nothing, so Ctrl+B goes to the `register:` line
 * ([de.terletzkiy.ansibility.vars.VarNavigation]).
 */
class RegisteredNavigation : SiteNavigation {
    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> {
        val reference = site as? AnsibleSite.VarRef ?: return emptyList()
        val member = RegisteredSites.memberAt(file, reference) ?: return emptyList()
        val source = file.findElementAt(reference.range.startOffset) ?: file
        val docs = AnsibleDocService.getInstance(file.project)
        return RegisteredDocs.pages(docs, member.result.root, member.key, member.member).map { page ->
            val name = if (page.module != null) message("target.module", page.module, member.display) else message("target.page", page.label, member.display)
            WebDocTarget(source, page.url, page.module ?: page.label, name)
        }
    }
}
