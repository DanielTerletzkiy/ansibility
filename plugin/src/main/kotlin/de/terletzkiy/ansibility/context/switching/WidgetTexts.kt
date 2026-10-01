package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.api.WidgetSegment
import org.jetbrains.annotations.Nls

/**
 * The X02 widget's text, click targets and tooltip from the built-in parts and the contributed segments (plan
 * amendment R7/R8, "New internal extension points"): `Ansibility: falcon · prod › prod-prod1 · core 2.18.8 · file: postfix
 * → 4 hosts · 🔓 default`. When the status bar is narrow, the segments after the core version move into the tooltip.
 * Pure, so the rules are tested without a status bar.
 */
object WidgetTexts {
    /** The widget may take at most this share of the status bar before its segments move to the tooltip. */
    const val MAX_STATUS_BAR_SHARE: Double = 0.4

    /** What a part of the widget text opens when clicked. */
    enum class PartKind {
        /** The root name: the whole context popup. */
        ROOT,

        /** The environment (or All envs): the Environment choices. */
        ENVIRONMENT,

        /** The host: the Host choices. */
        HOST,

        /** The core version: the whole context popup. */
        CORE,

        /** A contributed segment: its popup actions. */
        SEGMENT,

        /** A separator: the whole context popup. */
        SEPARATOR,
    }

    /** One part of the widget text; [segment] is the index of a [PartKind.SEGMENT] part among the state's segments. */
    data class Part(@Nls val text: String, val kind: PartKind, val segment: Int = -1) {
        companion object {
            fun separator(text: String): Part = Part(text, PartKind.SEPARATOR)
        }
    }

    /** What the widget shows: the [text] in the status bar (made of [parts]) and the HTML [tooltip]. */
    data class Rendered(val parts: List<Part>, @Nls val tooltip: String) {
        @get:Nls
        val text: String get() = parts.joinToString("") { it.text }
    }

    /**
     * Whether a status bar [statusBarWidth] pixels wide is too narrow for a widget text [fullTextWidth] pixels wide
     * (more than [MAX_STATUS_BAR_SHARE] of it). An unknown width (0, the widget is not laid out yet) is never narrow.
     */
    fun isNarrow(statusBarWidth: Int, fullTextWidth: Int): Boolean =
        statusBarWidth > 0 && fullTextWidth > statusBarWidth * MAX_STATUS_BAR_SHARE

    /** The built-in parts followed by every segment, in EP order. */
    fun fullParts(builtIn: List<Part>, segments: List<WidgetSegment>): List<Part> {
        val parts = builtIn.toMutableList()
        segments.forEachIndexed { index, segment ->
            parts += Part.separator(ContextTexts.SEPARATOR)
            parts += Part(segment.text, PartKind.SEGMENT, index)
        }
        return parts
    }

    /**
     * The widget's parts and tooltip. The tooltip starts with [headerLines] (the selection, a stale part), lists each
     * segment's tooltip line, preceded by the segment text itself when [narrow] moved it out of the status bar, and
     * ends with the click hint.
     */
    fun render(builtIn: List<Part>, segments: List<WidgetSegment>, narrow: Boolean, headerLines: List<String>): Rendered {
        val parts = if (narrow) builtIn else fullParts(builtIn, segments)
        val lines = ArrayList<HtmlChunk>()
        headerLines.mapTo(lines) { HtmlChunk.text(it) }
        for (segment in segments) {
            if (narrow) lines += HtmlChunk.text(segment.text).bold()
            segment.tooltip?.let { lines += HtmlChunk.text(it) }
        }
        lines += HtmlChunk.text(ContextTexts.message("widget.tooltip.click")).wrapWith("i")
        val tooltip = HtmlBuilder().appendWithSeparators(HtmlChunk.br(), lines).wrapWithHtmlBody().toString()
        return Rendered(parts, tooltip)
    }

    /**
     * The part under [x] pixels from the start of the text, measuring text prefixes with [width]; null left of the
     * text or past its end. A click on a separator answers the separator.
     */
    fun partAt(parts: List<Part>, x: Int, width: (String) -> Int): Part? {
        if (x < 0) return null
        val prefix = StringBuilder()
        for (part in parts) {
            prefix.append(part.text)
            if (x < width(prefix.toString())) return part
        }
        return null
    }
}
