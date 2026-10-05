package de.terletzkiy.ansibility.vars.usages

import com.intellij.find.FindManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.util.concurrency.AppExecutorUtil
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable

/**
 * The card's Show usages link (F1.10): `psi_element://ansibility-usages/show/<offset>/<file url>/<name>`, the card's
 * position (offset -1 for a card reached through a link) and the variable. The `psi_element://` scheme keeps the
 * documentation popup from ever handing it to a browser.
 */
internal object UsagesCardLinks {
    private const val PREFIX = "psi_element://ansibility-usages/show/"

    /** One parsed link. */
    data class Link(val fileUrl: String, val offset: Int, val name: String)

    fun show(file: VirtualFile, offset: Int, name: String): String =
        PREFIX + offset + "/" + encode(file.url) + "/" + encode(name)

    /** The link [url] names, or null for any other URL. */
    fun parse(url: String): Link? {
        if (!url.startsWith(PREFIX)) return null
        val parts = url.removePrefix(PREFIX).split('/')
        if (parts.size != 3) return null
        val offset = parts[0].toIntOrNull()?.takeIf { it >= -1 } ?: return null
        val file = decode(parts[1]).takeIf { it.isNotEmpty() } ?: return null
        val name = decode(parts[2]).takeIf { it.isNotEmpty() } ?: return null
        return Link(file, offset, name)
    }

    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

    private fun decode(text: String): String = URLDecoder.decode(text, StandardCharsets.UTF_8)
}

/**
 * Resolves the card's Show usages link (`platform.backend.documentation.linkHandler`): a click finds the symbol at the
 * card's position in a background read action and then runs Find Usages on it from the EDT
 * ([FindManager.findUsages], the Find tool window), and resolves to nothing, so the card keeps no history entry.
 */
class UsagesCardLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val link = UsagesCardLinks.parse(url) ?: return null
        val file = VirtualFileManager.getInstance().findFileByUrl(link.fileUrl)?.takeIf { it.isValid && !it.isDirectory } ?: return null
        val project = (target as? VarDocumentationTarget)?.project ?: ProjectLocator.getInstance().guessProjectForFile(file) ?: return null
        ApplicationManager.getApplication().invokeLater({ findUsages(project, file, link) }, project.disposed)
        return null
    }

    private fun findUsages(project: Project, file: VirtualFile, link: UsagesCardLinks.Link) {
        ReadAction.nonBlocking(Callable { VarUsageSearch.symbolForCard(project, file, link.offset, link.name) })
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.nonModal()) { symbol -> if (symbol != null) FindManager.getInstance(project).findUsages(symbol) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
