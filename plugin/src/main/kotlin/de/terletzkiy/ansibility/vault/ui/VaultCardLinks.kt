package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The card's Reveal link (F7.14): `psi_element://ansibility-vault/reveal/<offset>/<file url>`. The `psi_element://`
 * scheme keeps the documentation popup from ever handing it to a browser. The link carries a position, never data.
 */
internal object VaultCardLinks {
    private const val PREFIX = "psi_element://ansibility-vault/reveal/"

    fun reveal(location: SourceLocation): String =
        PREFIX + location.offset + "/" + URLEncoder.encode(location.file.url, StandardCharsets.UTF_8)

    /** The file URL and offset of a Reveal link, or null for any other URL. */
    fun parse(url: String): Pair<String, Int>? {
        if (!url.startsWith(PREFIX)) return null
        val rest = url.removePrefix(PREFIX)
        val offset = rest.substringBefore('/').toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val file = rest.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
        return URLDecoder.decode(file, StandardCharsets.UTF_8) to offset
    }
}

/**
 * Resolves the card's Reveal links (`platform.backend.documentation.linkHandler`). A click asks
 * [VaultOperations.reveal] for the timed popup (unlocking lazily) next to the caret of the editor showing the value,
 * and resolves to nothing, so the card itself never shows or caches the value and its back/forward history has no
 * entry that could reveal it again. Only values in the project's content are revealed.
 */
class VaultCardLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val (fileUrl, offset) = VaultCardLinks.parse(url) ?: return null
        val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl)?.takeIf { it.isValid && !it.isDirectory } ?: return null
        val project = (target as? VarDocumentationTarget)?.project ?: ProjectLocator.getInstance().guessProjectForFile(file) ?: return null
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) return null
        val location = SourceLocation(file, offset)
        ApplicationManager.getApplication().invokeLater({ reveal(project, location) }, project.disposed)
        return null
    }

    /** The popup appears below the value when the selected editor shows it, else at that editor's caret ([VaultPopups]). */
    private fun reveal(project: Project, location: SourceLocation) =
        VaultOperations.getInstance(project).reveal(location, FileEditorManager.getInstance(project).selectedTextEditor)
}
