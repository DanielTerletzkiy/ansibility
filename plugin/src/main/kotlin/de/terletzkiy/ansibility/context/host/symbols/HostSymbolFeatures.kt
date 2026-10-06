package de.terletzkiy.ansibility.context.host.symbols

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.HostConstruct
import de.terletzkiy.ansibility.api.HostPatternSite
import de.terletzkiy.ansibility.api.InventoryNameSite
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.host.AnsibilityHostBundle.message
import org.jetbrains.yaml.psi.YAMLScalar

/** Hover and Ctrl+Q on a host symbol (F8.8): per environment, what the pattern, group or host resolves to. */
class HostSymbolDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        if (site !is HostPatternSite && site !is InventoryNameSite) return null
        val virtualFile = file.originalFile.viewProvider.virtualFile
        return HostSymbolDocumentationTarget(file.project, virtualFile, site)
    }
}

internal class HostSymbolDocumentationTarget(
    private val project: Project,
    private val file: VirtualFile,
    private val site: AnsibleSite,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val self = this
        return Pointer { self.takeIf { !project.isDisposed && file.isValid } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(label()).icon(AllIcons.Nodes.Deploy).presentation()

    override fun computeDocumentationHint(): String? = hover()?.let { esc(it.title) + "<br>" + it.lines.take(3).joinToString("<br>") { l -> esc(l) } }

    override fun computeDocumentation(): DocumentationResult? {
        val hover = hover() ?: return null
        val html = buildString {
            append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(hover.title)).append("</b>").append(DocumentationMarkup.DEFINITION_END)
            append(DocumentationMarkup.CONTENT_START)
            hover.lines.forEach { append("<p>").append(esc(it)).append("</p>") }
            append(DocumentationMarkup.CONTENT_END)
        }
        return DocumentationResult.documentation(html)
    }

    override val navigatable: Navigatable?
        get() = HostSymbolNavigation.locations(project, file, site).firstOrNull()?.let { OpenFileDescriptor(project, it.file, it.offset) }

    private fun hover(): HostSymbolTexts.Hover? {
        val scope = HostSymbols.scope(project, file) ?: return null
        return HostSymbolTexts.hover(site, scope)
    }

    private fun label(): String = when (site) {
        is HostPatternSite -> site.pattern
        is InventoryNameSite -> site.name
        else -> ""
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    override fun toString(): String = "HostSymbolDocumentationTarget($site)"
}

/**
 * Ctrl+B on a host symbol (F8.8): the group or host keys in the inventory sources, the selected environment's first;
 * several environments open the platform chooser. `hostvars['h']` goes to the host's key.
 */
class HostSymbolNavigation : SiteNavigation {
    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val manager = PsiManager.getInstance(file.project)
        return locations(file.project, virtualFile, site).mapNotNull { location ->
            val psi = manager.findFile(location.file) ?: return@mapNotNull null
            psi.findElementAt(location.offset)?.let { PsiTreeUtil.getParentOfType(it, YAMLScalar::class.java, false) ?: it }
        }.distinct()
    }

    internal companion object {
        fun locations(project: Project, file: VirtualFile, site: AnsibleSite): List<SourceLocation> {
            val scope = HostSymbols.scope(project, file) ?: return emptyList()
            return when (site) {
                is InventoryNameSite -> scope.environments.flatMap { env ->
                    if (site.isGroup) HostSymbols.groupLocations(env, site.name).take(1) else HostSymbols.hostLocations(env, site.name).take(1)
                }
                is HostPatternSite -> {
                    val names = if (site.construct == HostConstruct.DELEGATE_TO) listOf(site.pattern) else HostSymbols.names(site.pattern)
                    val name = names.singleOrNull() ?: return emptyList()
                    scope.environments.flatMap { env ->
                        HostSymbols.groupLocations(env, name).take(1).ifEmpty { HostSymbols.hostLocations(env, name).take(1) }
                    }
                }
                else -> emptyList()
            }
        }
    }
}

/**
 * Group and host names in a play's `hosts:`, a literal `delegate_to:` and inside the quotes of `'…' in group_names`,
 * with member counts and addresses; names of the selected environment rank first.
 */
class HostSymbolCompletion : CompletionSource {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        val construct = when (site) {
            is HostPatternSite -> site.construct
            is InventoryNameSite -> site.construct.takeIf { it == HostConstruct.GROUP_NAMES } ?: return
            else -> return
        }
        val file = parameters.originalFile.viewProvider.virtualFile
        val scope = HostSymbols.scope(parameters.originalFile.project, file) ?: return
        val prefix = prefixOf(site, parameters)
        val set = result.withPrefixMatcher(prefix)
        val seen = HashSet<String>()
        scope.environments.forEachIndexed { index, env ->
            val priority = if (index == 0) 2.0 else 1.0
            if (construct != HostConstruct.DELEGATE_TO) {
                for (name in env.graph.groups.keys) {
                    if (name in HostSymbols.IMPLICIT_GROUPS && construct == HostConstruct.GROUP_NAMES) continue
                    if (!seen.add(name)) continue
                    val members = env.graph.hostsOf(name).size
                    set.addElement(PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Folder)
                            .withTypeText(message("symbols.completion.group", members, env.name)),
                        priority,
                    ))
                }
            }
            if (construct != HostConstruct.GROUP_NAMES) {
                for (host in env.graph.hosts.keys) {
                    if (!seen.add(host)) continue
                    set.addElement(PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(host).withIcon(AllIcons.Nodes.Deploy)
                            .withTypeText(env.address(host) ?: env.name),
                        priority - 0.5,
                    ))
                }
            }
        }
    }

    /** The text of the name under completion: after the last pattern separator in `hosts:`, the quoted text otherwise. */
    private fun prefixOf(site: AnsibleSite, parameters: CompletionParameters): String {
        val document = parameters.editor.document
        val start = site.range.startOffset.coerceAtMost(parameters.offset)
        val typed = document.getText(TextRange(start, parameters.offset))
        return if (site is HostPatternSite && site.construct == HostConstruct.PLAY_HOSTS) {
            typed.substring(typed.indexOfLast { it == ':' || it == ',' || it == '&' || it == '!' } + 1).trimStart()
        } else {
            typed
        }
    }
}
