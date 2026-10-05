package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.completion.jinja.AnsibilityJinjaCompletionBundle.message
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.vars.VarCard
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vars.VarSubject
import de.terletzkiy.ansibility.vars.registered.RegisteredMemberDocumentationTarget
import javax.swing.Icon

/**
 * Quick documentation in the completion popup for Jinja variable items (plan F1.2 "the popup shows the F1.2 card"):
 * the `platform.backend.documentation.lookupElementTargetProvider` extension.
 *
 * `LookupElementDocumentationTargetProvider` is `@ApiStatus.Experimental` (and `@OverrideOnly`) in 262; only the
 * interface is implemented, its `@Internal` `EP_NAME` is never touched.
 *
 * Variables (role options, defaults, registers, inventory names, loop items typed by a spec) get the M2 variable card
 * ([VarDocumentationTarget]) of the variable and nested option; members of registered results the return value's card
 * ([RegisteredMemberDocumentationTarget]); facts, special variables, Jinja locals, untyped loop variables, inferred
 * keys, groups and hosts get a small [JinjaSymbolDocumentationTarget].
 */
class JinjaLookupDocumentationProvider : LookupElementDocumentationTargetProvider {
    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? {
        val item = element.`object` as? JinjaLookupItem ?: return null
        return JinjaDocumentation.targetOf(psiFile.project, item)
    }
}

/** The documentation target of a [JinjaLookupItem]. */
internal object JinjaDocumentation {
    fun targetOf(project: Project, item: JinjaLookupItem): DocumentationTarget? {
        if (project.isDisposed || !item.rootDir.isValid) return null
        val root = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == item.rootDir } ?: return null
        return when (val doc = item.candidate.doc) {
            is CandidateDoc.Variable -> {
                if (DumbService.isDumb(project)) return null
                VarDocumentationTarget(project, VarSubject.referenceTo(root, doc.name, doc.path, item.file, item.offset))
            }
            is CandidateDoc.Registered -> RegisteredMemberDocumentationTarget(project, doc.result, doc.path, doc.display)
            else -> JinjaSymbolDocumentationTarget(symbolOf(root, item.candidate, doc))
        }
    }

    /** The plain data of a non-variable card; all texts are escaped when rendered. */
    private fun symbolOf(root: AnsibleRoot, candidate: JinjaCandidate, doc: CandidateDoc): JinjaSymbol = when (doc) {
        is CandidateDoc.Fact -> JinjaSymbol(
            title = doc.injectedAs?.let { name -> (listOf(name) + doc.path.drop(1)).joinToString(".") }
                ?: (listOf(JinjaMembers.ANSIBLE_FACTS) + doc.path).joinToString("."),
            typeText = doc.fact.typeText,
            kind = doc.injectedAs?.let { message("doc.kind.fact.injected", it) } ?: message("doc.kind.fact"),
            description = doc.fact.description,
            rows = listOfNotNull(doc.fact.setBy?.let { message("doc.section.set.by") to it }),
            icon = AllIcons.Nodes.Property,
        )
        is CandidateDoc.Magic -> JinjaSymbol(
            title = (listOf(doc.variable.name) + doc.path).joinToString("."),
            typeText = doc.key?.typeText ?: doc.variable.typeText,
            kind = message(
                when {
                    doc.variable.templateOnly -> "doc.kind.magic.template"
                    doc.variable.connection -> "doc.kind.magic.connection"
                    else -> "doc.kind.magic"
                },
            ),
            description = doc.key?.description ?: doc.variable.description,
            rows = listOfNotNull(doc.variable.deprecated?.takeIf { doc.key == null }?.let { message("doc.section.deprecated") to it }),
            icon = AllIcons.Nodes.Constant,
        )
        is CandidateDoc.Local -> JinjaSymbol(
            title = doc.name,
            typeText = candidate.typeText,
            kind = message("doc.kind.local", doc.kindLabel, VarLocations.label(root, doc.definition)),
            description = null,
            rows = emptyList(),
            icon = AllIcons.Nodes.Parameter,
        )
        is CandidateDoc.Loop -> JinjaSymbol(
            title = doc.name,
            typeText = doc.option?.let(VarCard::typeText),
            kind = when {
                doc.contexts > 1 -> message("doc.kind.loop.contexts", doc.contexts)
                doc.task != null -> message("doc.kind.loop", VarLocations.label(root, doc.task))
                else -> message("part.loop.item")
            },
            description = null,
            rows = doc.option?.options.orEmpty().values.map { it.name to VarCard.typeText(it) },
            icon = AllIcons.Nodes.Parameter,
        )
        is CandidateDoc.Member -> JinjaSymbol(
            title = "${doc.owner}.${doc.name}",
            typeText = doc.typeText,
            kind = message("doc.kind.member", doc.owner),
            description = doc.description,
            rows = emptyList(),
            icon = AllIcons.Nodes.Property,
        )
        is CandidateDoc.Group -> JinjaSymbol(
            title = "groups['${doc.name}']",
            typeText = "list[str]",
            kind = message("doc.kind.group", root.displayName),
            description = null,
            rows = listOf(message("doc.section.environments") to doc.environments.joinToString(", "), message("doc.section.hosts") to hostsText(doc.hosts)),
            icon = AllIcons.Nodes.Folder,
        )
        is CandidateDoc.Host -> JinjaSymbol(
            title = "hostvars['${doc.name}']",
            typeText = "dict",
            kind = message("doc.kind.host", root.displayName),
            description = FactsCatalog.magicVars["hostvars"]?.description,
            rows = listOf(message("doc.section.environments") to doc.environments.joinToString(", ")),
            icon = AllIcons.Nodes.Plugin,
        )
        is CandidateDoc.Variable -> error("variables have a variable card")
        is CandidateDoc.Registered -> error("registered members have a member card")
    }

    private fun hostsText(hosts: List<String>): String =
        if (hosts.size <= MAX_HOSTS) hosts.joinToString(", ") else hosts.take(MAX_HOSTS).joinToString(", ") + " … (${hosts.size})"

    private const val MAX_HOSTS = 20
}

/** What a [JinjaSymbolDocumentationTarget] shows; immutable plain text. */
internal data class JinjaSymbol(
    val title: String,
    val typeText: String?,
    /** The grey line: what the name is. */
    val kind: String,
    val description: String?,
    /** Section rows (header → value). */
    val rows: List<Pair<String, String>>,
    val icon: Icon,
)

/** A small documentation card for names without a variable card (facts, special variables, locals …). */
internal class JinjaSymbolDocumentationTarget(private val symbol: JinjaSymbol) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(symbol.title).icon(symbol.icon).presentation()

    override fun computeDocumentationHint(): String = buildString {
        append("<b>").append(esc(symbol.title)).append("</b>")
        symbol.typeText?.let { append(": ").append(esc(it)) }
        append(" · ").append(esc(symbol.kind))
    }

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(render())

    internal fun render(): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append("<b>").append(esc(symbol.title)).append("</b>")
        symbol.typeText?.let { append(" : ").append(esc(it)) }
        append("    ").append(DocumentationMarkup.GRAYED_START).append(esc(symbol.kind)).append(DocumentationMarkup.GRAYED_END)
        append(DocumentationMarkup.DEFINITION_END)
        symbol.description?.takeIf { it.isNotBlank() }?.let {
            append(DocumentationMarkup.CONTENT_START).append("<p>").append(esc(it)).append("</p>").append(DocumentationMarkup.CONTENT_END)
        }
        val rows = listOfNotNull(symbol.typeText?.let { message("doc.section.type") to it }) + symbol.rows
        if (rows.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            for ((header, value) in rows) {
                append("<tr>").append(DocumentationMarkup.SECTION_HEADER_START).append(esc(header)).append("</p>")
                append(DocumentationMarkup.SECTION_SEPARATOR).append(esc(value)).append(DocumentationMarkup.SECTION_END).append("</tr>")
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    override fun toString(): String = "JinjaSymbolDocumentationTarget(${symbol.title})"

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)
}
