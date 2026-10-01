package de.terletzkiy.ansibility.docs

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.api.LocalAnsibleInstall
import de.terletzkiy.ansibility.api.LocalAnsibleRuntime
import de.terletzkiy.ansibility.api.ResolvedModuleDoc
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.dispatch.WebDocTarget
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.ModuleNavigationTarget
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * F5.5 Ctrl+B / middle-click to the web docs: the docs area's `siteNavigation`.
 * - A module key opens the canonical module's page (`…/collections/ansible/builtin/systemd_service_module.html`
 *   for `ansible.builtin.systemd`), versioned by the root's target (D11).
 * - An option key opens the page at the option's anchor (X18: `#parameter-dest`, `#parameter-healthcheck/interval`,
 *   aliases mapped to the documented name); an option the docs do not know opens the anchor of its documented
 *   parent, or the page.
 * - A keyword opens `reference_appendices/playbooks_keywords.html` at its level's section (`#task`, `#play` …);
 *   the target reads "Open the when keyword docs".
 *
 * For modules and options the application setting "Ctrl+B on a module" applies: [ModuleNavigationTarget.WEB_DOCS]
 * (the default) as above; [ModuleNavigationTarget.MODULE_SOURCE] opens the module's source file from the local
 * install when it exists there (the platform guards edits of files outside the project), else the web page;
 * [ModuleNavigationTarget.QUICK_DOC] gives no target, so Ctrl+B falls through to the platform. A module nobody
 * documents has no target either. Needs no indexes, so it is [DumbAware].
 */
class DocsNavigation : SiteNavigation, DumbAware {
    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> {
        if (site !is AnsibleSite.ModuleKey && site !is AnsibleSite.ModuleOptionKey && site !is AnsibleSite.KeywordKey) return emptyList()
        val root = AnsibleDocTarget.rootOf(file) ?: return emptyList()
        val docs = AnsibleDocService.getInstance(file.project)
        val source = file.findElementAt(site.range.startOffset) ?: file
        return when (site) {
            is AnsibleSite.KeywordKey -> {
                val url = docs.docsUrl(root, DocKind.KEYWORD, site.keyword, DocAnchor.KeywordSection(site.level))
                listOf(WebDocTarget.keyword(source, url, site.keyword))
            }
            is AnsibleSite.ModuleKey -> module(docs, root, source, site.fqcn, emptyList())
            is AnsibleSite.ModuleOptionKey -> module(docs, root, source, site.fqcn, site.path)
        }
    }

    private fun module(docs: AnsibleDocService, root: AnsibleRoot, source: PsiElement, fqcn: String, path: List<String>): List<PsiElement> {
        val setting = AnsibilityAppSettings.getInstance().settings.docs.moduleNavigation
        if (setting == ModuleNavigationTarget.QUICK_DOC) return emptyList()
        val resolved = docs.moduleDoc(root, fqcn) ?: return emptyList()
        if (setting == ModuleNavigationTarget.MODULE_SOURCE) {
            moduleSource(source.project, resolved)?.let { return listOf(it) }
        }
        return listOf(WebDocTarget(source, url(docs, root, resolved, path), resolved.requested))
    }

    private fun url(docs: AnsibleDocService, root: AnsibleRoot, resolved: ResolvedModuleDoc, path: List<String>): String {
        if (path.isEmpty()) return resolved.docsUrl
        val documented = resolved.doc?.let { ModuleOptions.documentedPrefix(it.options, path) }.orEmpty()
        if (documented.isEmpty()) return resolved.docsUrl
        return docs.docsUrl(root, DocKind.MODULE, resolved.requested, DocAnchor.Parameter(documented.map { it.name }))
    }

    private fun moduleSource(project: Project, resolved: ResolvedModuleDoc): PsiElement? {
        val filename = resolved.doc?.filename ?: return null
        val install = LocalAnsibleRuntime.getInstanceOrNull()?.localInstall(project)
        val file = ModuleSourceLocator.find(filename, install) ?: return null
        return PsiManager.getInstance(project).findFile(file)
    }
}

/**
 * Finds a module's source file (`ModuleDoc.filename`) on this machine. Documentation stores it relative to the
 * install: `ansible/modules/template.py` below the directory holding the `ansible` package, and
 * `ansible_collections/community/docker/plugins/modules/docker_container.py` below a collection path or that same
 * directory. An absolute name is used as it is.
 */
object ModuleSourceLocator {
    /** The places [filename] may be, in lookup order. */
    fun candidates(filename: String, install: LocalAnsibleInstall?): List<Path> {
        val path = pathOf(filename) ?: return emptyList()
        if (path.isAbsolute) return listOf(path)
        if (install == null) return emptyList()
        val sitePackages = install.moduleLocation?.let(::pathOf)?.parent
        val bases = listOfNotNull(sitePackages) + install.collectionPaths.mapNotNull(::pathOf)
        return bases.map { it.resolve(path).normalize() }.distinct()
    }

    /** The first existing candidate, or null. */
    fun find(filename: String, install: LocalAnsibleInstall?): VirtualFile? {
        val existing = candidates(filename, install).firstOrNull { Files.isRegularFile(it) } ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(existing)
    }

    private fun pathOf(text: String): Path? = try {
        if (text.isBlank()) null else Path.of(text)
    } catch (_: InvalidPathException) {
        null
    }
}
