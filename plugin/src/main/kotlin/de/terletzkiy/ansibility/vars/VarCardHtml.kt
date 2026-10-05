package de.terletzkiy.ansibility.vars

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.QuickDocHighlightingHelper
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.context.host.card.SetInEffects
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.semantics.schema.Choices
import org.jetbrains.yaml.YAMLLanguage

/**
 * The chunks other areas contribute to a variable card through [CardSection] (plan amendment R7/R8, "New internal
 * extension points"), per placement: [top] directly after the definition line, [section] as rows of the sections
 * table after the built-in rows, [bottom] after the table.
 */
internal class CardContributions(val top: List<HtmlChunk>, val section: List<HtmlChunk>, val bottom: List<HtmlChunk>) {
    companion object {
        val NONE = CardContributions(emptyList(), emptyList(), emptyList())

        /** Every registered section's chunks for [subject] seen from [context], in EP order per placement. */
        fun collect(subject: CardSubject, context: CardContext): CardContributions = CardContributions(
            CardSection.collect(subject, context, CardPlacement.TOP),
            CardSection.collect(subject, context, CardPlacement.SECTION),
            CardSection.collect(subject, context, CardPlacement.BOTTOM),
        )
    }
}

/**
 * Renders a [VarCard] as JSDoc-style quick documentation (plan F1.2): a definition line
 * `name : type  role · root · optional`, the description, then a sections table (Type, Required, Default (spec),
 * Runtime default, Choices, Aliases, Options, Declared by, Set in (ranked by effect for the card's hosts, [SetInEffects]),
 * This definition). Other areas' [CardSection] chunks ([CardContributions]) go after the definition line, after the
 * built-in rows and after the table. Also renders the one-line Ctrl-hover hint, which never includes contributions. All
 * texts come from [AnsibilityVarsBundle] or from the files and are escaped; contributed chunks and the "Set in" effect
 * marks are HTML or texts built by their owners.
 */
internal object VarCardHtml {
    private const val MAX_VALUE_LINES = 12
    private const val MAX_VALUE_CHARS = 600
    private const val MAX_HINT_VALUE = 40
    private const val WARNING = "⚠"
    private val SEPARATOR = VarLabels.SEPARATOR
    private val ABBREVIATIONS = setOf("e.g.", "i.e.", "etc.", "vs.", "z.b.", "bzw.", "ca.", "d.h.", "u.a.", "incl.", "approx.")

    /**
     * The card's HTML. [subject] and [context] are the card as its [CardSection]s see it, so the ranked "Set in" rows
     * look from the same position as the host-aware sections ([SetInEffects.of]).
     */
    fun render(
        project: Project,
        card: VarCard,
        subject: CardSubject.Variable,
        context: CardContext,
        contributions: CardContributions = CardContributions.NONE,
    ): String = buildString {
        definition(card)
        contributions.top.forEach { append(it) }
        content(card)
        append(DocumentationMarkup.SECTIONS_START)
        sections(project, card, subject, context)
        contributions.section.forEach { append(it) }
        append(DocumentationMarkup.SECTIONS_END)
        contributions.bottom.forEach { append(it) }
    }

    /** `postfix_relayhost: str = "" · postfix (optional)` (HTML). */
    fun hint(card: VarCard): String = buildString {
        append("<b>").append(esc(card.title)).append("</b>")
        card.typeText?.let { append(": ").append(esc(it)) }
        hintValue(card)?.let { append(" = ").append(esc(it)) }
        val role = card.role
        val option = card.option
        when {
            role != null && option != null -> append(SEPARATOR).append(
                esc(AnsibilityVarsBundle.message(if (option.required) "hint.role.required" else "hint.role.optional", role)),
            )
            role != null -> append(SEPARATOR).append(esc(role))
            card.note != null -> append(SEPARATOR).append(esc(headerInfo(card).joinToString(SEPARATOR)))
            else -> {
                val count = card.setIn.sumOf { it.entries.size } + (card.thisDefinition?.let { 1 } ?: 0)
                append(SEPARATOR).append(esc(AnsibilityVarsBundle.message("hint.definitions", count, card.root.displayName)))
            }
        }
        if (card.badges.isNotEmpty()) append(' ').append(WARNING)
    }

    private fun hintValue(card: VarCard): String? {
        val runtime = card.runtimeDefault
        if (runtime != null) {
            if (runtime.secret != null) return "🔒"
            val text = runtime.text ?: return null
            val line = text.lineSequence().first()
            return if (line.length <= MAX_HINT_VALUE && line.length == text.length) line else line.take(MAX_HINT_VALUE) + "…"
        }
        return card.option?.default?.let(ValueDisplay::dump)
    }

    // ------------------------------------------------------------------------------------------------ header

    private fun StringBuilder.definition(card: VarCard) {
        append(DocumentationMarkup.DEFINITION_START)
        append("<b>").append(esc(card.title)).append("</b>")
        card.typeText?.let { append(" : ").append(esc(it)) }
        if (card.badges.isNotEmpty()) {
            append(' ').append("<span title='").append(esc(AnsibilityVarsBundle.message("card.badge.title"))).append("'>").append(WARNING).append("</span>")
        }
        val info = headerInfo(card)
        if (info.isNotEmpty()) {
            append("    ").append(DocumentationMarkup.GRAYED_START).append(esc(info.joinToString(SEPARATOR))).append(DocumentationMarkup.GRAYED_END)
        }
        append(DocumentationMarkup.DEFINITION_END)
    }

    private fun headerInfo(card: VarCard): List<String> {
        val parts = ArrayList<String>()
        card.role?.let { parts += AnsibilityVarsBundle.message("card.header.role", it) }
        parts += card.root.displayName
        val option = card.option
        when {
            option != null -> parts += AnsibilityVarsBundle.message(if (option.required) "card.header.required" else "card.header.optional")
            card.note is Note.Local -> parts += AnsibilityVarsBundle.message("card.header.local")
            card.note is Note.Loop -> parts += AnsibilityVarsBundle.message("card.header.loop")
            card.note is Note.Undefined -> {
                parts.remove(card.root.displayName)
                parts += AnsibilityVarsBundle.message("card.header.undefined", card.root.displayName)
            }
            card.role == null -> parts += AnsibilityVarsBundle.message("card.header.inventory")
        }
        return parts
    }

    private fun StringBuilder.content(card: VarCard) {
        val note = card.note
        val loop = card.loop
        if (card.description.isEmpty() && note == null && loop == null) return
        append(DocumentationMarkup.CONTENT_START)
        if (loop != null) {
            val tasks = LoopItems.tasksLabel(card.root, loop.tasks)
            append("<p>").append(DocumentationMarkup.GRAYED_START)
                .append(esc(AnsibilityVarsBundle.message("card.loop.via", loop.loopVar, loop.iterated, tasks)))
                .append(DocumentationMarkup.GRAYED_END).append("</p>")
        }
        if (card.descriptionFromComment) {
            for (paragraph in card.description.flatMap { it.split(Regex("\n\\s*\n")) }.filter { it.isNotBlank() }) {
                append("<p>").append(esc(paragraph.trim()).replace("\n", "<br/>")).append("</p>")
            }
            append("<p>").append(DocumentationMarkup.GRAYED_START)
                .append(esc(AnsibilityVarsBundle.message("card.description.from.comment")))
                .append(DocumentationMarkup.GRAYED_END).append("</p>")
        } else if (card.description.isNotEmpty()) {
            append(MarkupHtml.paragraphs(card.description, CardLinks))
        }
        when (note) {
            is Note.Local -> append("<p>").append(esc(AnsibilityVarsBundle.message("card.local.defined", note.kind, note.label))).append("</p>")
            is Note.Loop -> append("<p>").append(esc(AnsibilityVarsBundle.message("card.loop.task", note.label))).append("</p>")
            is Note.Undefined -> append("<p>").append(esc(AnsibilityVarsBundle.message("card.undefined.note", note.rootName))).append("</p>")
            null -> Unit
        }
        append(DocumentationMarkup.CONTENT_END)
    }

    // ------------------------------------------------------------------------------------------------ sections

    private fun StringBuilder.sections(project: Project, card: VarCard, subject: CardSubject.Variable, context: CardContext) {
        val option = card.option
        if (option != null) {
            row(message("card.section.type"), code(VarCard.typeText(option)))
            row(message("card.section.required"), esc(message(if (option.required) "card.required.yes" else "card.required.no")))
            option.default?.let {
                row(message("card.section.default.spec"), code(ValueDisplay.dump(it)) + " " + grayed(message("card.default.spec.note")))
            }
        }
        runtimeDefault(project, card)?.let { row(message("card.section.default.runtime"), it) }
        option?.choices?.let { row(message("card.section.choices"), choices(it)) }
        option?.aliases?.takeIf { it.isNotEmpty() }?.let { aliases -> row(message("card.section.aliases"), aliases.joinToString(", ") { code(it) }) }
        if (card.hasSubOptions) row(message("card.section.options"), options(card))
        if (card.declaredBy.isNotEmpty()) row(message("card.section.declared.by"), declaredBy(card))
        if (card.note == null) {
            val header = card.setInOf?.let { message("card.section.set.in.nested", it) } ?: message("card.section.set.in")
            row(header, setIn(project, card, subject, context))
        }
        card.thisDefinition?.let { row(message("card.section.this.definition"), thisDefinition(it)) }
    }

    private fun runtimeDefault(project: Project, card: VarCard): String? {
        val runtime = card.runtimeDefault
        if (runtime == null) {
            return when (card.missingDefault) {
                MissingDefault.DOCUMENTED -> esc(message("card.default.runtime.none.documented"))
                MissingDefault.REQUIRED -> esc(message("card.default.runtime.none.required"))
                MissingDefault.NONE -> esc(message("card.default.runtime.none"))
                null -> null
            }
        }
        return buildString {
            val secret = runtime.secret
            if (secret != null) {
                append(esc(secret))
            } else {
                append(value(project, runtime.text.orEmpty()))
                if (runtime.isJinja) append(' ').append(grayed(message("card.default.runtime.jinja")))
                append(SEPARATOR).append(link(VarLinks.definition(runtime.definition.location), runtime.label))
                runtime.chain?.let { chain ->
                    append("<br/>").append(
                        esc(message("card.default.runtime.chain", chain.typeName, chain.valueText, chain.via, chain.label)),
                    )
                }
            }
            for (badge in card.badges) append("<br/>").append(WARNING).append(' ').append(esc(badge))
            runtime.comment?.let { comment ->
                append("<br/>").append(DocumentationMarkup.GRAYED_START)
                    .append(esc(comment.lines().joinToString(" ") { "# $it" }))
                    .append(DocumentationMarkup.GRAYED_END)
            }
        }
    }

    /** A value as written: YAML-highlighted inline code, or a code block for multi-line values (truncated). */
    private fun value(project: Project, text: String): String {
        val lines = text.lines()
        val shown = lines.take(MAX_VALUE_LINES).joinToString("\n").let { if (it.length > MAX_VALUE_CHARS) it.take(MAX_VALUE_CHARS) + "…" else it }
        val truncated = shown.length < text.length
        return if ('\n' in shown) {
            QuickDocHighlightingHelper.getStyledCodeBlock(project, YAMLLanguage.INSTANCE, if (truncated) "$shown\n…" else shown)
        } else {
            QuickDocHighlightingHelper.getStyledInlineCode(project, YAMLLanguage.INSTANCE, shown)
        }
    }

    private fun choices(choices: Choices): String = when (choices) {
        is Choices.Values -> choices.values.joinToString(" | ") { code(ValueDisplay.dump(it)) }
        is Choices.Described -> choices.described.joinToString("<br/>") { (value, description) ->
            code(ValueDisplay.dump(value)) + if (description.isEmpty()) "" else " — " + MarkupHtml.inline(description.joinToString(" "), CardLinks)
        }
    }

    private fun options(card: VarCard): String = buildString {
        append("<table>")
        for (row in card.subOptions) {
            val option = row.option
            append("<tr><td valign='top'>")
            append("&nbsp;&nbsp;".repeat(row.depth))
            append(link(VarLinks.option(card.optionNames + row.names), code(option.name), escaped = true))
            append("</td><td valign='top'>").append(code(VarCard.typeText(option)))
            append("</td><td valign='top'>").append(esc(message(if (option.required) "card.declared.by.required" else "card.declared.by.optional")))
            append("</td><td valign='top'>")
            option.description.firstOrNull { it.isNotBlank() }?.let { append(MarkupHtml.inline(firstSentence(it), CardLinks)) }
            append("</td></tr>")
        }
        append("</table>")
        if (card.subOptionsTruncated > 0) append(grayed(message("card.options.more", card.subOptionsTruncated)))
    }

    private fun declaredBy(card: VarCard): String = buildString {
        val required = card.declaredBy.count { it.required }
        append("<p>").append(esc(message("card.declared.by.summary", card.declaredBy.size, required))).append("</p>")
        for (declaration in card.declaredBy) {
            append(link(VarLinks.definition(declaration.location), message("card.header.role", declaration.role)))
            append(SEPARATOR)
            val flag = message(if (declaration.required) "card.declared.by.required" else "card.declared.by.optional")
            if (declaration.differs) {
                append("<b>").append(esc(message("card.declared.by.required.differs", flag, card.role.orEmpty()))).append("</b>")
            } else {
                append(esc(flag))
            }
            append(' ').append(grayed(declaration.label)).append("<br/>")
        }
    }

    /**
     * "Set in", ranked by effect for the card's hosts (plan amendment R7/R8, F8.2; [SetInEffects]): winners first,
     * marked "wins on n of m hosts" (All mode) or ✓ / struck through / "not for prod-prod1" (host mode); with an
     * environment selected, the other environments' definitions collapse into one line. Without a host scope (no
     * inventory reaches the file) the groups stay as [VarCards] built them. The effects are seen from [context] as the
     * Effective section sees it (a reference card reached through a link covers its whole file).
     */
    private fun setIn(project: Project, card: VarCard, subject: CardSubject.Variable, context: CardContext): String {
        if (card.setIn.isEmpty()) return esc(message("card.set.in.none", card.root.displayName))
        val effects = SetInEffects.of(project, subject.definition, context, card.subject.name)
        val groups = if (effects == null) {
            card.setIn
        } else {
            card.setIn
                .map { group -> SetInGroup(group.title, group.entries.sortedBy { effects.rank(it.location) }) }
                .sortedBy { group -> group.entries.minOfOrNull { effects.rank(it.location) } ?: Int.MAX_VALUE }
        }
        val collapsed = if (effects == null) emptyList() else groups.filter { group -> group.entries.all { effects.isOtherEnvironment(it.location) } }
        return buildString {
            for (group in groups) {
                if (group in collapsed) continue
                append("<p><b>").append(esc(group.title)).append("</b><br/>")
                for (entry in group.entries) {
                    val effect = effects?.effect(entry.location)
                    if (effect?.struck == true) append("<s>")
                    append(link(VarLinks.definition(entry.location), entry.label))
                    append(SEPARATOR).append(esc(entry.layer))
                    when {
                        entry.secret != null -> append(SEPARATOR).append(esc(entry.secret))
                        entry.preview != null -> append(SEPARATOR).append(code(entry.preview))
                    }
                    if (effect?.struck == true) append("</s>")
                    effect?.text?.let { append(SEPARATOR).append(grayed(it)) }
                    append("<br/>")
                }
                append("</p>")
            }
            if (effects != null && collapsed.isNotEmpty()) {
                append("<p><b>").append(esc(effects.otherEnvironmentsTitle)).append("</b><br/>")
                for (group in collapsed) {
                    append(grayed(group.title)).append(": ")
                    append(group.entries.joinToString(", ") { link(VarLinks.definition(it.location), it.label) })
                    append("<br/>")
                }
                append("</p>")
            }
        }
    }

    private fun thisDefinition(definition: ThisDefinition): String = buildString {
        append(esc(definition.parts.joinToString(SEPARATOR)))
        val overrides = definition.overrides
        val location = definition.overridesLocation
        if (overrides != null) {
            append(SEPARATOR)
            if (location != null) append(link(VarLinks.definition(location), overrides)) else append(esc(overrides))
        }
        definition.secret?.let { append("<br/>").append(esc(it)) }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The first sentence of a description paragraph (abbreviations such as `e.g.` do not end it). */
    fun firstSentence(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        var wordStart = 0
        for (i in normalized.indices) {
            val c = normalized[i]
            if (c == ' ') wordStart = i + 1
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == normalized.length || normalized[i + 1] == ' ')) {
                val word = normalized.substring(wordStart, i + 1).trimStart('(', '[', '"', '\'').lowercase()
                if (word !in ABBREVIATIONS) return normalized.substring(0, i + 1)
            }
        }
        return normalized
    }

    private object CardLinks : MarkupHtml.Links {
        override fun option(path: List<String>, plugin: String?): String? = if (plugin == null && path.isNotEmpty()) VarLinks.variable(path) else null
    }

    private fun StringBuilder.row(header: String, content: String) {
        append(DocumentationMarkup.SECTION_HEADER_START).append(esc(header)).append("</p>")
        append(DocumentationMarkup.SECTION_SEPARATOR).append(content).append(DocumentationMarkup.SECTION_END).append("</tr>")
    }

    private fun link(url: String, text: String, escaped: Boolean = false): String =
        "<a href=\"${esc(url)}\">${if (escaped) text else esc(text)}</a>"

    private fun code(text: String): String = "<code>${esc(text)}</code>"

    private fun grayed(text: String): String = DocumentationMarkup.GRAYED_START + esc(text) + DocumentationMarkup.GRAYED_END

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    private fun message(key: String, vararg params: Any): String = AnsibilityVarsBundle.message(key, *params)
}
