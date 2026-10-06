package de.terletzkiy.ansibility.render.hover

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaBlockSite
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.TemplatedValueSite
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle.message
import org.jetbrains.annotations.Nls

/**
 * Quick Doc on a `{% %}` tag (plan amendment R11, F11.3): what the statement does and, for `if`/`elif`, `for` and
 * `set`, what its expressions evaluate to on the hosts of the current scope. `else`/`elif`/`end…` tags explain the
 * statement they belong to.
 */
class JinjaBlockDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        if (site !is JinjaBlockSite) return null
        return JinjaBlockTarget(file.project, file.originalFile.viewProvider.virtualFile, site)
    }
}

internal class JinjaBlockTarget(
    private val project: Project,
    private val file: VirtualFile,
    private val site: JinjaBlockSite,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val self = this
        return Pointer { self.takeIf { !project.isDisposed && file.isValid } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(tag(site.keyword, "")).icon(AllIcons.Nodes.Template).presentation()

    override fun computeDocumentationHint(): String = content().toString()

    override fun computeDocumentation(): DocumentationResult {
        val html = DocumentationMarkup.DEFINITION_START + HtmlChunk.tag("b").addText(tag(site.keyword, site.body)) + DocumentationMarkup.DEFINITION_END +
            DocumentationMarkup.CONTENT_START + content() + DocumentationMarkup.CONTENT_END
        return DocumentationResult.documentation(html)
    }

    private fun content(): HtmlChunk {
        val parts = ArrayList<HtmlChunk>()
        val (keyword, body) = site.opener ?: (site.keyword to site.body)
        site.opener?.let { (word, text) ->
            val key = if (site.keyword.startsWith("end")) "block.end.of" else "block.branch.of"
            parts += HtmlChunk.p().addText(message(key, tag(word, text)))
            if (site.keyword == "else" && word == "for") parts += HtmlChunk.p().addText(message("block.for.else"))
        }
        if (site.keyword == "elif") parts += condition(site.body, site.range.startOffset)
        else when (keyword) {
            "if", "elif" -> if (site.keyword != "else") parts += condition(body, site.openerOffset)
            "for" -> parts += forLoop(body)
            "set" -> parts += set(body)
            "include" -> parts += named("block.include", body.substringBefore(" ignore missing").substringBefore(" with").substringBefore(" without"))
            "import" -> parts += named("block.import", body.substringBefore(" as "))
            "from" -> parts += named("block.from", body.substringBefore(" import "))
            "macro" -> parts += HtmlChunk.p().addText(message("block.macro", body))
            "call" -> parts += HtmlChunk.p().addText(message("block.call"))
            "filter" -> parts += HtmlChunk.p().addText(message("block.filter", body))
            "with" -> parts += HtmlChunk.p().addText(message("block.with"))
            "block" -> parts += HtmlChunk.p().addText(message("block.block", body))
            "extends" -> parts += named("block.extends", body)
            "raw" -> parts += HtmlChunk.p().addText(message("block.raw"))
            in UNKNOWN_TAGS -> parts += HtmlChunk.p().addText(message("block.unknown", keyword))
            else -> if (site.opener == null) parts += HtmlChunk.p().addText(message("block.other", keyword))
        }
        return HtmlChunk.fragment(*parts.toTypedArray())
    }

    private fun condition(expression: String, offset: Int): List<HtmlChunk> =
        listOf(HtmlChunk.p().addText(message("block.condition"))) + rendered(expression, offset, message("block.condition.none"))

    private fun forLoop(body: String): List<HtmlChunk> {
        val match = FOR.matchEntire(body.trim()) ?: return listOf(HtmlChunk.p().addText(message("block.for.loop")))
        val (targets, sequence, filter, recursive) = match.destructured
        val parts = ArrayList<HtmlChunk>()
        parts += HtmlChunk.p().addText(message("block.for.over", targets.trim(), sequence.trim()))
        parts += rendered("$sequence | list | length", site.openerOffset, null, message("block.for.count"))
        parts += rendered("($sequence | list)[:$PREVIEW_ITEMS]", site.openerOffset, null, message("block.for.first", PREVIEW_ITEMS))
        if (filter.isNotBlank()) parts += HtmlChunk.p().addText(message("block.for.filter", filter.trim()))
        if (recursive.isNotBlank()) parts += HtmlChunk.p().addText(message("block.for.recursive"))
        parts += HtmlChunk.p().child(grayed(message("block.for.loop")))
        return parts
    }

    private fun set(body: String): List<HtmlChunk> {
        val name = body.substringBefore('=').trim()
        if ('=' !in body) return listOf(HtmlChunk.p().addText(message("block.set.capture", name)))
        val value = body.substringAfter('=').trim()
        return rendered(value, site.openerOffset, null, message("block.set.value", name)) + HtmlChunk.p().child(grayed(message("block.set.scope")))
    }

    private fun named(key: String, expression: String): List<HtmlChunk> =
        listOf(HtmlChunk.p().addText(message(key, expression.trim()))) + rendered(expression, site.openerOffset, null, message("block.name"))

    /** [expression] rendered at [offset], under an optional [title]; [fallback] when nothing renders. */
    private fun rendered(expression: String, offset: Int, @Nls fallback: String?, @Nls title: String? = null): List<HtmlChunk> {
        val probe = TemplatedValueSite(expression, site.container, expression = true, loopApplies = true, range = TextRange(offset, offset), site.prelude, site.postlude)
        val report = RenderedValueHtml.render(project, file, probe)
            ?: return listOfNotNull(fallback?.let { HtmlChunk.p().child(grayed(it)) })
        val header = HtmlChunk.p().apply { title?.let { child(HtmlChunk.tag("b").addText(it)).addText(" ") } }.addText(RenderedValueHtml.summary(report))
        return listOf(header, RenderedValueHtml.body(report))
    }

    private fun grayed(text: String): HtmlChunk = HtmlChunk.span().attr("class", "grayed").addText(text)

    override fun toString(): String = "JinjaBlockTarget(${site.keyword})"

    private companion object {
        val FOR = Regex("""(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+?))?(\s+recursive)?""")
        val UNKNOWN_TAGS = setOf("do", "break", "continue", "trans", "pluralize", "endtrans")
        const val PREVIEW_ITEMS = 3

        fun tag(keyword: String, body: String): String = "{% " + listOf(keyword, body).filter { it.isNotBlank() }.joinToString(" ") + " %}"
    }
}
