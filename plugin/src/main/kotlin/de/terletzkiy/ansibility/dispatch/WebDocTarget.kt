package de.terletzkiy.ansibility.dispatch

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/**
 * A Go to Declaration target that opens a documentation page in the browser (F5.5): `siteNavigation` extensions
 * return it for a module key (`…/collections/ansible/builtin/template_module.html`), an option key
 * (`#parameter-dest`) or a keyword. The plugin never fetches the page itself.
 *
 * Spike S3, verified on 262 by `WebDocTargetNavigationTest` through the real Go to Declaration action: for a
 * single target the platform asks the element for its navigation request; `PsiElementBase` hands classes that
 * override [navigate] to `NavigationRequests.rawNavigationRequest`, which needs [canNavigate], and executing that
 * request calls [navigate], i.e. [BrowserUtil.browse]. Ctrl-hover underlines the key as navigatable without
 * opening anything, and among several targets the chooser shows the item presentation "Open <fqcn> docs". So the
 * `WebReference` fallback and the one-item chooser are not needed.
 *
 * [source] is the element the navigation started from; it supplies the project, the containing file and the
 * validity. [canNavigateToSource] is false, so the platform never treats the target as a source location.
 * [displayName] is what the chooser and the Ctrl-hover hint show: "Open ansible.builtin.template docs" for a module
 * (the default), "Open the when keyword docs" for a keyword ([keyword]).
 */
class WebDocTarget(
    private val source: PsiElement,
    val url: String,
    val fqcn: String,
    val displayName: String = AnsibilityDispatchBundle.message("web.doc.target.name", fqcn),
) : FakePsiElement(), Navigatable {

    override fun getParent(): PsiElement = source

    override fun getName(): String = displayName

    override fun getPresentableText(): String = name

    override fun getLocationString(): String = url

    override fun getIcon(open: Boolean): Icon = AllIcons.General.Web

    override fun isValid(): Boolean = source.isValid

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = false

    override fun navigate(requestFocus: Boolean) {
        BrowserUtil.browse(url, source.project)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WebDocTarget && other.url == url && other.fqcn == fqcn && other.source == source &&
            other.displayName == displayName

    override fun hashCode(): Int = 31 * url.hashCode() + fqcn.hashCode()

    override fun toString(): String = "WebDocTarget($fqcn -> $url)"

    companion object {
        /** The target of playbook keyword [keyword]'s section of the keywords page, named "Open the <keyword> keyword docs". */
        fun keyword(source: PsiElement, url: String, keyword: String): WebDocTarget =
            WebDocTarget(source, url, keyword, AnsibilityDispatchBundle.message("web.doc.target.keyword.name", keyword))
    }
}
