package de.terletzkiy.ansibility.docs

import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.schema.OptionSpec

/**
 * Base of the docs area's documentation targets ([ModuleDocumentationTarget], [OptionDocumentationTarget],
 * [KeywordDocumentationTarget]): documentation of one name in one Ansible [root], computed from
 * [AnsibleDocService] when the popup asks for it (in a background read action).
 *
 * Targets hold no PSI, only the root and names, so the pointer hands out the same target while the project and
 * the root directory are alive; [AnsibleDocLinkHandler] builds the targets of links in the root of the page that
 * holds them.
 */
sealed class AnsibleDocTarget(val project: Project, val root: AnsibleRoot) : DocumentationTarget {
    protected val docs: AnsibleDocService get() = AnsibleDocService.getInstance(project)

    protected val target: TargetVersion get() = TargetVersionDetector.getInstance(project).targetVersion(root)

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val self = this
        return Pointer { self.takeIf { !project.isDisposed && root.dir.isValid } }
    }

    internal fun links(module: String?): DocLinks = DocLinks(root, docs, module)

    companion object {
        /** The root of the (host) file a site was classified in; null outside every Ansible root. */
        fun rootOf(file: PsiFile): AnsibleRoot? =
            AnsibleWorkspace.getInstance(file.project).rootFor(file.originalFile.viewProvider.virtualFile)
    }
}

/** Resolution of option paths in module documentation: aliases are accepted at every level. */
internal object ModuleOptions {
    /** The option named [name] (or with [name] as an alias) in [options]. */
    fun find(options: Map<String, OptionSpec>, name: String): OptionSpec? =
        options[name] ?: options.values.firstOrNull { name in it.aliases }

    /** The documented options along [path], outermost first, or null when some segment is not documented. */
    fun chain(options: Map<String, OptionSpec>, path: List<String>): List<OptionSpec>? =
        documentedPrefix(options, path).takeIf { it.size == path.size && path.isNotEmpty() }

    /** The documented options along [path] as far as the documentation goes (`env.FOO` → `[env]`). */
    fun documentedPrefix(options: Map<String, OptionSpec>, path: List<String>): List<OptionSpec> {
        val chain = ArrayList<OptionSpec>()
        var level: Map<String, OptionSpec>? = options
        for (segment in path) {
            val spec = level?.let { find(it, segment) } ?: break
            chain += spec
            level = spec.options
        }
        return chain
    }
}
