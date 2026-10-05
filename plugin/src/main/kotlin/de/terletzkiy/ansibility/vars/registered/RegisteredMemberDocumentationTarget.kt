package de.terletzkiy.ansibility.vars.registered

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.docs.DocLinks
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.resolve.register.AnsibilityRegisteredBundle.message
import de.terletzkiy.ansibility.resolve.register.RegisteredDocs
import de.terletzkiy.ansibility.resolve.register.RegisteredResult
import de.terletzkiy.ansibility.resolve.register.RegisteringTask
import de.terletzkiy.ansibility.semantics.registered.MemberDoc
import de.terletzkiy.ansibility.semantics.registered.MemberKind
import de.terletzkiy.ansibility.semantics.registered.ResultMember
import de.terletzkiy.ansibility.vars.VarCard
import de.terletzkiy.ansibility.vars.VarLinks
import de.terletzkiy.ansibility.vars.VarLocations

/**
 * The card of one member of a registered result (plan amendment FU, F1.12: hover on `x.stdout`, Ctrl+Q in member
 * completion): `x.stdout : str  return value of ansible.builtin.command`, the return value's description (one block
 * per distinct documentation when several registering tasks document it differently), then Type, Returned, Sample,
 * Contains (the documented keys of a dict, or of each element of a list of dicts), Set by (the registering tasks,
 * linked to their `register:` definitions) and Documentation (the web pages, the module page at `#return-<path>`).
 *
 * Holds only the immutable [RegisteredResult] (no PSI), so a hard pointer stays valid while the root exists. "Jump to
 * source" opens the `register:` line of the first task that sets the member. All texts are escaped; descriptions are
 * Ansible doc markup rendered by [MarkupHtml] with the docs area's links.
 */
internal class RegisteredMemberDocumentationTarget(
    internal val project: Project,
    internal val result: RegisteredResult,
    internal val path: List<String>,
    private val display: String,
) : DocumentationTarget {
    private val member: ResultMember? get() = result.member(path)

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val self = this
        return Pointer { self.takeIf { !project.isDisposed && result.root.dir.isValid } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(display).icon(AllIcons.Nodes.Property).containerText(result.root.displayName).presentation()

    override fun computeDocumentationHint(): String? {
        val member = member ?: return null
        val doc = member.primary ?: return null
        return buildString {
            append("<b>").append(esc(display)).append("</b>: ").append(esc(VarCard.typeText(member.option)))
            append(" · ").append(esc(RegisteredDocs.kind(doc)))
        }
    }

    override fun computeDocumentation(): DocumentationResult? {
        val member = member ?: return null
        return DocumentationResult.documentation(render(member))
    }

    override val navigatable: Navigatable?
        get() {
            val task = member?.let(result::tasksOf)?.firstOrNull() ?: result.tasks.firstOrNull() ?: return null
            return task.register.takeIf { it.file.isValid }?.let { OpenFileDescriptor(project, it.file, it.offset) }
        }

    internal fun render(member: ResultMember): String = buildString {
        val docs = AnsibleDocService.getInstance(project)
        val primary = member.primary
        append(DocumentationMarkup.DEFINITION_START)
        append("<b>").append(esc(display)).append("</b> : ").append(esc(VarCard.typeText(member.option)))
        primary?.let {
            val kind = RegisteredDocs.kind(it)
            val grey = if (result.tasks.size > 1) kind + SEPARATOR + message("card.registered.by.tasks", result.tasks.size) else kind
            append("    ").append(DocumentationMarkup.GRAYED_START).append(esc(grey)).append(DocumentationMarkup.GRAYED_END)
        }
        append(DocumentationMarkup.DEFINITION_END)
        description(docs, member)
        append(DocumentationMarkup.SECTIONS_START)
        row(message("card.section.type"), esc(VarCard.typeText(member.option)))
        primary?.let(RegisteredDocs::returned)?.let { row(message("card.section.returned"), esc(it)) }
        primary?.sample?.takeIf { it.isNotBlank() }?.let { row(message("card.section.sample"), "<code>${esc(StringUtil.first(it, MAX_SAMPLE, true))}</code>") }
        contains(member)?.let { row(message("card.section.contains"), it) }
        setBy(member)?.let { row(message("card.section.set.by"), it) }
        val pages = RegisteredDocs.pages(docs, result.root, memberKey(), member)
        if (pages.isNotEmpty()) {
            row(message("card.section.docs"), pages.joinToString("<br>") { "<a href=\"${esc(it.url)}\">${esc(it.label)}</a>" })
        }
        append(DocumentationMarkup.SECTIONS_END)
    }

    /** The descriptions: one block, or one per distinct documentation headed "From <module>:". */
    private fun StringBuilder.description(docs: AnsibleDocService, member: ResultMember) {
        val blocks = member.docs.map { it to RegisteredDocs.description(it) }.filter { it.second.isNotEmpty() }.distinctBy { it.second }
        if (blocks.isEmpty()) return
        append(DocumentationMarkup.CONTENT_START)
        for ((doc, paragraphs) in blocks) {
            if (blocks.size > 1) append("<p><i>").append(esc(message("card.variant.from", sourceOf(doc)))).append("</i></p>")
            append(MarkupHtml.paragraphs(paragraphs, DocLinks(result.root, docs, doc.module)))
        }
        append(DocumentationMarkup.CONTENT_END)
    }

    /** The keys of a dict member, or of each element of a list of dicts, as code; null for free-form values. */
    private fun contains(member: ResultMember): String? {
        val keys = (member.members ?: member.element()?.members)?.keys?.toList()?.takeIf { it.isNotEmpty() } ?: return null
        val shown = keys.take(MAX_KEYS).joinToString(", ") { "<code>${esc(it)}</code>" }
        val more = keys.size - MAX_KEYS
        return if (more > 0) "$shown ${esc(message("card.member.list.more", more))}" else shown
    }

    /** The registering tasks that set the member, each linked to its `register:` definition. */
    private fun setBy(member: ResultMember): String? {
        val tasks = result.tasksOf(member).ifEmpty { return null }
        return tasks.joinToString("<br>") { task ->
            "<a href=\"${esc(VarLinks.definition(task.register))}\">${esc(taskLabel(task))}</a>"
        }
    }

    private fun taskLabel(task: RegisteringTask): String = taskLabel(result, task)

    private fun sourceOf(doc: MemberDoc): String = when (doc.kind) {
        MemberKind.MODULE, MemberKind.SUPPLEMENT -> doc.module ?: RegisteredDocs.kind(doc)
        else -> RegisteredDocs.kind(doc)
    }

    private fun memberKey(): String = path.lastOrNull { it.toIntOrNull() == null } ?: result.name

    private fun StringBuilder.row(header: String, html: String) {
        append("<tr>").append(DocumentationMarkup.SECTION_HEADER_START).append(esc(header)).append("</p>")
        append(DocumentationMarkup.SECTION_SEPARATOR).append(html).append(DocumentationMarkup.SECTION_END).append("</tr>")
    }

    override fun toString(): String = "RegisteredMemberDocumentationTarget($display)"

    companion object {
        private const val MAX_SAMPLE = 300
        private const val MAX_KEYS = 40
        private const val SEPARATOR = " · "

        private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

        /** `task “Wait for keepalived …” (roles/keepalived/tasks/main.yml:52)`, or `a task (…)` without a name. */
        fun taskLabel(result: RegisteredResult, task: RegisteringTask): String {
            val where = VarLocations.label(result.root, task.register)
            val name = task.taskName ?: return message("card.task.unnamed", where)
            return message("card.task.named", StringUtil.first(name, MAX_TASK_NAME, true), where)
        }

        private const val MAX_TASK_NAME = 60
    }
}
