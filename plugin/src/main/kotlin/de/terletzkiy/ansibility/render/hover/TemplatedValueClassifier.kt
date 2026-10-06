package de.terletzkiy.ansibility.render.hover

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaBlockSite
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.TemplatedValueSite
import de.terletzkiy.ansibility.vars.JinjaTextSites
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The fallback classifier of R11 (F11.1), registered last: any position in templated text that no other area
 * classified becomes a [TemplatedValueSite], so hovering the literal part of a value still shows what it renders to.
 */
class TemplatedValueClassifier : SiteClassifier {
    override fun classify(file: PsiFile, offset: Int): AnsibleSite? = siteAt(file, offset)

    companion object {
        /** Task keys Ansible templates before the loop runs: `item` is undefined in them. */
        private val BEFORE_LOOP = setOf("name", "loop")

        fun siteAt(file: PsiFile, offset: Int): AnsibleSite? {
            val analysis = JinjaTextSites.analysisAt(file, offset) ?: return null
            val text = analysis.text
            spanAt(text, analysis.textOffset)?.takeIf { text.startsWith("{%", it.startOffset) }?.let { tag ->
                block(text, tag)?.let { (keyword, body, opener) ->
                    val openerStart = opener?.first ?: tag.startOffset
                    val (prelude, postlude) = context(text, openerStart)
                    return JinjaBlockSite(keyword, body, opener?.second, analysis.container, analysis.toHost(openerStart), analysis.toHost(tag), prelude, postlude)
                }
            }
            return when (analysis.container) {
                JinjaContainer.TEMPLATE_FILE -> {
                    val span = spanAt(text, analysis.textOffset) ?: return null
                    val (prelude, postlude) = context(text, span.startOffset)
                    TemplatedValueSite(text.substring(span.startOffset, span.endOffset), analysis.container, false, true, analysis.toHost(span), prelude, postlude)
                }
                else -> {
                    val range = analysis.toHost(TextRange(0, text.length))
                    val expression = analysis.container == JinjaContainer.YAML_EXPRESSION
                    TemplatedValueSite(text.toString(), analysis.container, expression, loopApplies(file, offset), range)
                }
            }
        }

        private val TAG = Regex("""\{%[-+]?\s*([A-Za-z_]+)\s*(.*?)\s*[-+]?%}""", RegexOption.DOT_MATCHES_ALL)
        private val CLOSING = setOf("else", "elif")

        /** keyword, body and, for `else`/`elif`/`end…`, the opener's start in [text] with its keyword and body. */
        private fun block(text: CharSequence, tag: TextRange): Triple<String, String, Pair<Int, Pair<String, String>>?>? {
            val match = TAG.matchEntire(text.subSequence(tag.startOffset, tag.endOffset)) ?: return null
            val keyword = match.groupValues[1]
            val body = match.groupValues[2]
            val wanted = when {
                keyword.startsWith("end") -> keyword.removePrefix("end")
                keyword in CLOSING -> null
                else -> return Triple(keyword, body, null)
            }
            var depth = 0
            for (other in TAG.findAll(text.subSequence(0, tag.startOffset)).toList().asReversed()) {
                val word = other.groupValues[1]
                when {
                    word == "end$wanted" || wanted == null && word.removePrefix("end") in OPENERS && word.startsWith("end") -> depth++
                    word.startsWith("end") -> {}
                    word in CLOSING -> {}
                    wanted == null && word !in OPENERS -> {}
                    wanted != null && word != wanted -> {}
                    depth > 0 -> depth--
                    else -> return Triple(keyword, body, other.range.first to (word to other.groupValues[2]))
                }
            }
            return Triple(keyword, body, null)
        }

        private val FOR_BODY = Regex("""(.+?)\s+in\s+(.+?)(?:\s+if\s+.+?)?(?:\s+recursive)?""", RegexOption.DOT_MATCHES_ALL)

        /**
         * What a position at [end] of a template renders inside: the `{% set x = … %}` tags before it that are still in
         * scope, and the `for` loops still open there, cut to their first item so their targets are bound.
         */
        internal fun context(text: CharSequence, end: Int): Pair<String, String> {
            class Open(val tag: String, val sets: MutableList<String> = ArrayList())
            val outer = Open("")
            val stack = ArrayDeque<Open>()
            for (match in TAG.findAll(text.subSequence(0, end))) {
                val word = match.groupValues[1]
                val body = match.groupValues[2]
                when {
                    word == "for" -> {
                        val loop = FOR_BODY.matchEntire(body.trim())
                        stack.addLast(Open(if (loop != null) "{% for ${loop.groupValues[1]} in (${loop.groupValues[2]})[:1] %}" else match.value))
                    }
                    word == "endfor" -> stack.removeLastOrNull()
                    word == "set" && '=' in body -> (stack.lastOrNull() ?: outer).sets += match.value
                }
            }
            val prelude = StringBuilder(outer.sets.joinToString(""))
            for (open in stack) prelude.append(open.tag).append(open.sets.joinToString(""))
            return prelude.toString() to "{% endfor %}".repeat(stack.size)
        }

        /** Tags `else` and `elif` can belong to. */
        private val OPENERS = setOf("if", "for")

        /** The `{{ … }}` or `{% … %}` span of a template's [text] around [caret], delimiters included. */
        internal fun spanAt(text: CharSequence, caret: Int): TextRange? {
            val source = text.toString()
            val start = maxOf(source.lastIndexOf("{{", caret.coerceAtMost(source.length - 1)), source.lastIndexOf("{%", caret.coerceAtMost(source.length - 1)))
            if (start < 0) return null
            val close = if (source.startsWith("{{", start)) "}}" else "%}"
            val end = source.indexOf(close, start + 2).takeIf { it >= 0 }?.plus(2) ?: return null
            return TextRange(start, end).takeIf { caret <= end }
        }

        /** False when the value belongs to a task's `name:`, `loop:` or `with_*` key. */
        private fun loopApplies(file: PsiFile, offset: Int): Boolean {
            var keyValue = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLKeyValue::class.java) ?: return true
            while (true) {
                val mapping = keyValue.parent as? YAMLMapping ?: return true
                if (mapping.parent is YAMLSequenceItem) return !(keyValue.keyText in BEFORE_LOOP || keyValue.keyText.startsWith("with_"))
                keyValue = mapping.parent as? YAMLKeyValue ?: return true
            }
        }
    }
}
