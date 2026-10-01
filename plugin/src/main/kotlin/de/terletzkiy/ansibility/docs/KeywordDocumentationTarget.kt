package de.terletzkiy.ansibility.docs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.presentation.TargetPresentation
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.model.task.TaskSyntax
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.KeywordDoc
import de.terletzkiy.ansibility.semantics.schema.TemplateMode

/**
 * F5.4 keyword hover: playbook keyword [keyword] used at [level] (`when` on a task, `hosts` on a play,
 * `loop_var` in `loop_control`) in [root], from the root's target ansible-core line: type, the levels it applies
 * to (Play, Role, Block, Task, Handler …), default and description (RST rendered). `when`, `changed_when`,
 * `failed_when` and `until` add "implicit Jinja expression — no `{{ }}`"; a `with_<lookup>` loop names its
 * lookup. The web page is `reference_appendices/playbooks_keywords.html` at the level's section (X18).
 * Created through [create], which gives no target for a keyword the target line does not have.
 */
class KeywordDocumentationTarget private constructor(
    project: Project,
    root: AnsibleRoot,
    val keyword: String,
    val level: KeywordLevel,
) : AnsibleDocTarget(project, root) {

    private fun doc(): KeywordDoc? = lookup(docs, root, keyword, level)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(keyword).icon(AllIcons.Nodes.Property).containerText(levelName(level)).presentation()

    override fun computeDocumentationHint(): String? {
        val doc = doc() ?: return null
        val summary = DocsHtml.firstSentence(doc.description, rst = true)?.let { " — " + DocsHtml.esc(it) }.orEmpty()
        return DocsHtml.bold(keyword) + typeSuffix(doc) + " · " + DocsHtml.esc(AnsibilityDocsBundle.message("keyword.kind")) + summary
    }

    override fun computeDocumentation(): DocumentationResult? {
        val doc = doc() ?: return null
        val url = docs.docsUrl(root, DocKind.KEYWORD, keyword, DocAnchor.KeywordSection(level))
        return DocumentationResult.documentation(render(doc)).externalUrl(url)
    }

    private fun render(doc: KeywordDoc): String {
        val links = links(null)
        val definition = DocsHtml.bold(keyword) + typeSuffix(doc) + "  " +
            DocsHtml.grayed(DocsHtml.esc(AnsibilityDocsBundle.message("keyword.definition", levelName(level))))
        val content = StringBuilder()
        if (isImplicit(doc)) content.append(DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("keyword.implicit"))))
        if (doc.name == DocSnapshot.WITH_LOOKUP && keyword.startsWith(TaskSyntax.WITH_PREFIX)) {
            val lookup = keyword.removePrefix(TaskSyntax.WITH_PREFIX)
            content.append("<p>").append(DocsHtml.esc(AnsibilityDocsBundle.message("keyword.with.lookup"))).append(' ')
                .append(DocsHtml.code(lookup)).append("</p>")
        }
        for (paragraph in doc.description) {
            if (paragraph.isNotBlank()) content.append("<p>").append(MarkupHtml.rst(paragraph, links)).append("</p>")
        }
        val rows = ArrayList<Pair<String, String>>()
        if (doc.appliesTo.isNotEmpty()) {
            rows += AnsibilityDocsBundle.message("section.applies.to") to doc.appliesTo.joinToString(" · ") { DocsHtml.esc(it) }
        }
        doc.default?.takeIf { it.isNotEmpty() && it != NULL }?.let { rows += AnsibilityDocsBundle.message("section.default") to DocsHtml.code(it) }
        if (doc.template == TemplateMode.STATIC) {
            rows += AnsibilityDocsBundle.message("section.templating") to DocsHtml.esc(AnsibilityDocsBundle.message("keyword.static"))
        }
        return DocsHtml.definition(definition) + DocsHtml.content(content.toString()) + DocsHtml.sections(rows)
    }

    private fun typeSuffix(doc: KeywordDoc): String {
        val type = doc.type?.name ?: doc.isa ?: return ""
        return " : " + DocsHtml.esc(type)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is KeywordDocumentationTarget && other.keyword == keyword && other.level == level &&
            other.root.dir == root.dir && other.project == project

    override fun hashCode(): Int = (31 * keyword.hashCode() + level.hashCode()) * 31 + root.dir.hashCode()

    override fun toString(): String = "KeywordDocumentationTarget($keyword at $level in ${root.displayName})"

    companion object {
        private const val NULL = "null"

        /** The keys whose values ansible-core evaluates as bare Jinja expressions. */
        private val IMPLICIT_KEYWORDS = setOf("when", "changed_when", "failed_when", "until")

        /** The target for [keyword] at [level], or null when the root's target line has no such keyword there. */
        fun create(project: Project, root: AnsibleRoot, keyword: String, level: KeywordLevel): KeywordDocumentationTarget? {
            lookup(AnsibleDocService.getInstance(project), root, keyword, level) ?: return null
            return KeywordDocumentationTarget(project, root, keyword, level)
        }

        private fun lookup(docs: AnsibleDocService, root: AnsibleRoot, keyword: String, level: KeywordLevel): KeywordDoc? =
            docs.keywordDoc(root, keyword, level)

        /** Whether the keyword's value is an implicit Jinja expression (written without `{{ }}`). */
        fun isImplicit(doc: KeywordDoc): Boolean = doc.template == TemplateMode.IMPLICIT || doc.name in IMPLICIT_KEYWORDS

        /** The display name of a structural level (`Task`, `Role entry`, `loop_control` …). */
        fun levelName(level: KeywordLevel): String = AnsibilityDocsBundle.message(
            when (level) {
                KeywordLevel.PLAY -> "level.play"
                KeywordLevel.BLOCK -> "level.block"
                KeywordLevel.TASK -> "level.task"
                KeywordLevel.HANDLER -> "level.handler"
                KeywordLevel.ROLE_ENTRY -> "level.role.entry"
                KeywordLevel.LOOP_CONTROL -> "level.loop.control"
                KeywordLevel.PLAYBOOK_INCLUDE -> "level.playbook.include"
            },
        )
    }
}
