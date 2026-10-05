package de.terletzkiy.ansibility.vault.tab

import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.navigation.RefsNavigation
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import com.intellij.openapi.util.text.HtmlChunk

/**
 * The hover of a `template`/`copy` `src` that resolves to a whole-file vault (F7.7): `🔒 whole-file vault · id default ·
 * Ansible decrypts it when the task runs` (and, for `copy`, that `decrypt: false` copies the envelope). Any other
 * `src` gets null, so the normal documentation runs unchanged. Header facts only; nothing is decrypted.
 */
class VaultTemplateRefDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        if (site !is AnsibleSite.TemplateRef) return null
        val target = RefsNavigation().targets(site, file).firstOrNull() ?: return null
        val vault = (target.navigationElement as? PsiFileSystemItem ?: target.containingFile)?.virtualFile ?: return null
        val line = line(file.project, vault, site.isCopySource) ?: return null
        return Target(file.project, vault, site.isCopySource, line)
    }

    private class Target(val project: Project, val vault: VirtualFile, val copy: Boolean, val line: String) : DocumentationTarget {
        override fun createPointer(): Pointer<out DocumentationTarget> {
            val project = project
            val vault = vault
            val copy = copy
            return Pointer { line(project, vault, copy)?.let { Target(project, vault, copy, it) } }
        }

        override fun computePresentation(): TargetPresentation =
            TargetPresentation.builder(vault.name).icon(AllIcons.Nodes.Padlock).presentation()

        override fun computeDocumentation(): DocumentationResult =
            DocumentationResult.documentation(HtmlChunk.div().child(HtmlChunk.text(line)).toString())
    }

    internal companion object {
        /** The card line for [vault], or null when it is no whole-file vault. Read action. */
        fun line(project: Project, vault: VirtualFile, copy: Boolean): String? {
            if (project.isDisposed || !vault.isValid || !VaultEnvelopes.isWholeFileVault(vault)) return null
            val header = VaultEnvelopes.ofFile(vault)?.header ?: return null
            val root = AnsibleWorkspace.getInstance(project).rootFor(vault)
            val defaultIdentity = root?.let { VaultStatusService.getInstance(project).config(it).defaultIdentity }
            val id = defaultIdentity?.let(header::labelOrDefault) ?: header.labelOrDefault()
            return TabTexts.message(if (copy) "doc.src.vault.copy" else "doc.src.vault.template", id)
        }
    }
}
