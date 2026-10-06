package de.terletzkiy.ansibility.render.hover

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.TemplatedValueSite
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle.message
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import de.terletzkiy.ansibility.render.service.ValueReport

/** Hover and Ctrl+Q on a [TemplatedValueSite] (F11.1): what the value renders to, per host and loop item. */
class RenderedValueDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        if (site !is TemplatedValueSite) return null
        return RenderedValueTarget(file.project, file.originalFile.viewProvider.virtualFile, site)
    }
}

internal class RenderedValueTarget(
    private val project: Project,
    private val file: VirtualFile,
    private val site: TemplatedValueSite,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val self = this
        return Pointer { self.takeIf { !project.isDisposed && file.isValid } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(message("hover.title")).icon(AllIcons.Actions.Preview).presentation()

    override fun computeDocumentationHint(): String? = report()?.let { RenderedValueHtml.body(it).toString() }

    override fun computeDocumentation(): DocumentationResult? {
        val report = report() ?: return null
        val html = DocumentationMarkup.DEFINITION_START + "<b>" + message("hover.title") + "</b> " +
            HtmlChunk.text(RenderedValueHtml.summary(report)).toString() + DocumentationMarkup.DEFINITION_END +
            DocumentationMarkup.CONTENT_START + RenderedValueHtml.body(report) + DocumentationMarkup.CONTENT_END
        return DocumentationResult.documentation(html)
    }

    private fun report(): ValueReport? = RenderedValueHtml.render(project, file, site)

    override fun toString(): String = "RenderedValueTarget(${site.range})"
}

/**
 * The "Rendered" section of a variable card (F11.1) when the caret is on a name inside a larger templated value
 * (`/etc/alloy/{{ item | basename }}` on `item`): the whole value rendered. A bare `{{ name }}` of the card's own
 * variable adds nothing to the Effective section, so it gets none.
 */
class RenderedValueCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.BOTTOM

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        if (subject !is CardSubject.Variable || subject.definition != null || context.offset < 0) return null
        val psi = PsiManager.getInstance(context.project).findFile(context.file) ?: return null
        val site = TemplatedValueClassifier.siteAt(psi, context.offset) as? TemplatedValueSite ?: return null
        if (bare(site.text, subject.name)) return null
        val report = RenderedValueHtml.render(context.project, context.file, site) ?: return null
        return HtmlChunk.fragment(
            HtmlChunk.p().child(HtmlChunk.tag("b").addText(message("hover.section"))).addText(" " + RenderedValueHtml.summary(report)),
            RenderedValueHtml.body(report),
        )
    }

    private fun bare(text: String, name: String): Boolean = Regex("""\s*\{\{\s*${Regex.escape(name)}\s*}}\s*""").matches(text) || text.trim() == name
}

internal object RenderedValueHtml {
    private const val MAX_OUTCOMES = 10
    private const val MAX_WHO = 6
    private const val MAX_TEXT = 2000
    private const val MAX_LINES = 20

    fun render(project: Project, file: VirtualFile, site: TemplatedValueSite): ValueReport? {
        if (DumbService.isDumb(project)) return null
        return try {
            TemplatePreviewService.getInstance(project).renderValue(file, site.range.startOffset, site.text, site.expression, site.loopApplies, site.prelude, site.postlude)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: RuntimeException) {
            null
        }?.takeIf { it.outcomes.isNotEmpty() }
    }

    /** `one output · on 3 hosts · 4 items` or `2 outputs · on 10 hosts`. */
    fun summary(report: ValueReport): String {
        val parts = mutableListOf(
            if (report.outcomes.size == 1) message("hover.summary.one") else message("hover.summary.many", report.outcomes.size),
        )
        if (report.hosts > 0) parts += message("hover.hosts", report.hosts)
        report.items?.let { parts += message("hover.items", it) }
        if (report.tasks > 0) parts += message("hover.tasks", report.tasks)
        return parts.joinToString(" · ")
    }

    fun body(report: ValueReport): HtmlChunk {
        val blocks = report.outcomes.take(MAX_OUTCOMES).flatMap { outcome ->
            val who = outcome.who.distinct().let { all ->
                all.take(MAX_WHO).joinToString(", ") + if (all.size > MAX_WHO) " " + message("hover.more", all.size - MAX_WHO) else ""
            }
            val notes = listOfNotNull(
                outcome.error?.let { message("hover.error", it) },
                outcome.placeholders.takeIf { it > 0 }?.let { message("hover.placeholders", it) },
            )
            val header = (listOf(who) + notes).filter { it.isNotEmpty() }.joinToString(" · ")
            listOfNotNull(
                header.takeIf { it.isNotEmpty() }?.let { HtmlChunk.div().child(grayed(it)) },
                HtmlChunk.tag("pre").addText(shown(outcome.text.ifEmpty { message("hover.empty") })),
            )
        }
        val more = report.outcomes.size - MAX_OUTCOMES
        val footer = listOfNotNull(
            report.noHost,
            report.itemsUnknown?.let { message("hover.items.unknown", it) },
            more.takeIf { it > 0 }?.let { message("hover.more", it) },
            message("hover.footer", report.core.toString()),
        ).joinToString(" · ")
        return HtmlChunk.fragment(*blocks.toTypedArray(), HtmlChunk.p().child(grayed(footer)))
    }

    /** The output as shown: lists and dicts one element per line, cut after [MAX_LINES] lines or [MAX_TEXT] characters. */
    fun shown(text: String): String {
        val lines = ValuePrinter.pretty(text).lines()
        val kept = lines.take(MAX_LINES).joinToString("\n").let { if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "\u2026" else it }
        return if (lines.size > MAX_LINES) kept + "\n" + message("hover.lines.more", lines.size - MAX_LINES) else kept
    }

    private fun grayed(text: String): HtmlChunk = HtmlChunk.span().attr("class", "grayed").addText(text)
}

/**
 * Pretty-prints a rendered value written as a Python literal (`['a', 'b']`, `{'k': [1, 2]}`): each element of a
 * list, dict or tuple on its own line, nested ones indented. Short values (at most [INLINE] characters) and text that
 * is not one balanced literal stay as they are.
 */
internal object ValuePrinter {
    private const val INLINE = 60
    private const val INDENT = "  "

    fun pretty(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= INLINE || trimmed.first() !in OPENERS || CLOSERS[trimmed.first()] != trimmed.last()) return text
        val out = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var i = 0
        fun newline() {
            out.append('\n')
            repeat(depth) { out.append(INDENT) }
        }
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && i + 1 < trimmed.length) {
                    out.append(trimmed[i + 1])
                    i++
                } else if (c == quote) {
                    quote = null
                }
            } else when (c) {
                '\'', '"' -> {
                    quote = c
                    out.append(c)
                }
                in OPENERS -> {
                    out.append(c)
                    val next = trimmed.drop(i + 1).firstOrNull { !it.isWhitespace() }
                    val closer = CLOSERS.getValue(c)
                    if (next == closer) {
                        out.append(closer)
                        i = trimmed.indexOf(closer, i + 1)
                    } else {
                        depth++
                        newline()
                    }
                }
                in CLOSERS.values -> {
                    depth--
                    if (depth < 0) return text
                    newline()
                    out.append(c)
                }
                ',' -> {
                    out.append(c)
                    newline()
                    while (i + 1 < trimmed.length && trimmed[i + 1] == ' ') i++
                }
                else -> out.append(c)
            }
            i++
        }
        return if (depth == 0 && quote == null) out.toString() else text
    }

    private val OPENERS = setOf('[', '{', '(')
    private val CLOSERS = mapOf('[' to ']', '{' to '}', '(' to ')')
}
