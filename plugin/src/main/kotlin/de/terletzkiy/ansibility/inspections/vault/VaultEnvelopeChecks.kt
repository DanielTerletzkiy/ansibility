package de.terletzkiy.ansibility.inspections.vault

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.PathHint
import de.terletzkiy.ansibility.index.vault.VaultEnvelopeProblem
import de.terletzkiy.ansibility.index.vault.VaultIndexer
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.FormatHint
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** One password-free vault finding: the code, where it is, why, and the fix that applies (if any). */
class VaultFinding(
    val code: DiagnosticCode,
    /** The `!vault` scalar, the tag of an empty `!vault` value, or the file of a whole-file vault. */
    val element: PsiElement,
    /** The highlighted range inside [element]. */
    val rangeInElement: TextRange,
    val message: String,
    /** The fix to offer, or null (ANS-V101 has none: a malformed envelope needs its author). */
    val fix: VaultFixKind?,
) {
    override fun toString(): String = "VaultFinding(${code.id} @ ${element.textRange.startOffset + rangeInElement.startOffset})"
}

/** The password-free fixes (ModCommand, plan A.13 "Fixes that need no password"). */
enum class VaultFixKind {
    /** ANS-V102 on a folded `>` block: the indicator becomes `|`. */
    FOLDED_TO_LITERAL,

    /** ANS-V102 on a plain or quoted one-line value: rewritten as a literal block at the file's indent style. */
    FLATTENED_TO_LITERAL,

    /** ANS-V103: trailing spaces and TABs are removed from the value's lines. */
    STRIP_TRAILING_WHITESPACE,
}

/**
 * The structural vault checks ANS-V101 (malformed envelope), ANS-V102 (folded or flattened value) and ANS-V103
 * (trailing whitespace on a payload line), on inline `!vault` values of YAML files and on whole-file vaults.
 *
 * Every verdict is the codec's (`semantics.vault.VaultEnvelope.parse`, ansible-core's reader rules), read from the
 * same envelope text the `ansible.vault` index sees ([VaultIndexer]); each envelope gets at most one code. Nothing is
 * decrypted and no secret is read: the checks are safe on the highlighting path.
 */
object VaultEnvelopeChecks {
    private const val NEWLINE = '\n'

    /** Every vault tag (`!vault`, `!vault-encrypted`) contains this; a YAML file without it has nothing to check. */
    private const val VAULT_TAG = "!vault"

    /**
     * The code of an envelope that does not parse ([problem] non-null), from the codec's [hint] and the YAML [style]
     * of the value (null for a whole-file vault or an empty value); null for a well-formed envelope.
     * - ANS-V102: a folded `>` block, or a plain scalar holding an envelope (YAML joins its lines), or a quoted scalar
     *   whose envelope YAML flattened onto one line;
     * - ANS-V103: a payload that fails to decode because a line ends in spaces or TABs (`Odd-length string`);
     * - ANS-V101: everything else that Ansible refuses.
     */
    fun codeOf(problem: VaultEnvelopeProblem?, hint: FormatHint?, style: ScalarStyle?): DiagnosticCode? {
        problem ?: return null
        val trailing = hint == FormatHint.TRAILING_WHITESPACE &&
            (problem == VaultEnvelopeProblem.ODD_LENGTH || problem == VaultEnvelopeProblem.NON_HEX_DIGIT)
        return when (style) {
            ScalarStyle.FOLDED -> DiagnosticCode.V102_FOLDED_VAULT_VALUE
            ScalarStyle.PLAIN ->
                if (problem in NOT_AN_ENVELOPE) DiagnosticCode.V101_MALFORMED_ENVELOPE else DiagnosticCode.V102_FOLDED_VAULT_VALUE
            // YAML trims blanks before the line breaks of a quoted scalar, so its payload lines never end in blanks.
            ScalarStyle.SINGLE_QUOTED, ScalarStyle.DOUBLE_QUOTED ->
                if (hint == FormatHint.FOLDED_BLOCK) DiagnosticCode.V102_FOLDED_VAULT_VALUE else DiagnosticCode.V101_MALFORMED_ENVELOPE
            ScalarStyle.LITERAL, null -> if (trailing) DiagnosticCode.V103_TRAILING_WHITESPACE else DiagnosticCode.V101_MALFORMED_ENVELOPE
        }
    }

    /** Problems where the value is no envelope at all, so its style is not the cause. */
    private val NOT_AN_ENVELOPE = setOf(
        VaultEnvelopeProblem.EMPTY,
        VaultEnvelopeProblem.NO_MAGIC,
        VaultEnvelopeProblem.LEADING_WHITESPACE,
        VaultEnvelopeProblem.BYTE_ORDER_MARK,
        VaultEnvelopeProblem.NON_ASCII,
    )

    /**
     * The findings of [file], cached until it changes. Empty for injected fragments, for secondary PSI of a template,
     * for YAML below a `templates/` directory (rendered before Ansible loads it) and for files without any envelope.
     * Call inside a read action.
     */
    fun of(file: PsiFile): List<VaultFinding> {
        // Cheap exits first: this runs on every file of every language the daemon highlights.
        if (file !is YAMLFile && !file.viewProvider.contents.startsWith(VaultEnvelope.MAGIC)) return emptyList()
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return emptyList()
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return emptyList()
        return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(compute(file), file) }
    }

    private fun compute(file: PsiFile): List<VaultFinding> {
        val text = file.viewProvider.contents
        if (isWholeFileVault(file, text)) return listOfNotNull(wholeFile(file, text))
        if (file !is YAMLFile || VAULT_TAG !in text) return emptyList()
        if (PathFacts.of(file.viewProvider.virtualFile).hint == PathHint.TEMPLATE) return emptyList()
        val findings = ArrayList<VaultFinding>()
        PsiTreeUtil.processElements(file) { element ->
            ProgressManager.checkCanceled()
            when (element) {
                is YAMLScalar -> if (VaultIndexer.isVaultScalar(element)) inline(element, text)?.let(findings::add)
                is YAMLKeyValue -> if (element.value == null) VaultIndexer.vaultTagOf(element)?.let { findings += empty(it) }
            }
            true
        }
        return findings
    }

    /** Ansible's whole-file rule on the document: it starts with `$ANSIBLE_VAULT`, and the file has no byte order mark. */
    private fun isWholeFileVault(file: PsiFile, text: CharSequence): Boolean =
        text.startsWith(VaultEnvelope.MAGIC) && file.viewProvider.virtualFile.bom == null

    private fun wholeFile(file: PsiFile, text: CharSequence): VaultFinding? {
        val parse = VaultEnvelope.parse(text)
        val problem = VaultEnvelopeProblem.of(parse) ?: return null
        val hint = VaultEnvelopeProblem.hintOf(parse)
        val code = codeOf(problem, hint, null) ?: return null
        val all = TextRange(0, text.length)
        return when (code) {
            DiagnosticCode.V103_TRAILING_WHITESPACE -> trailingWhitespaceLine(text, all, skipFirstLine = true)?.let { line ->
                VaultFinding(code, file, line.range, v103Message(line.number + 1), VaultFixKind.STRIP_TRAILING_WHITESPACE)
            }
            else -> null
        } ?: VaultFinding(
            DiagnosticCode.V101_MALFORMED_ENVELOPE, file, firstLine(text, all), message(problem, parse, wholeFile = true), null,
        )
    }

    private fun inline(scalar: YAMLScalar, text: CharSequence): VaultFinding? {
        val parse = VaultEnvelope.parse(scalar.textValue)
        val problem = VaultEnvelopeProblem.of(parse) ?: return null
        val hint = VaultEnvelopeProblem.hintOf(parse)
        val style = YamlPsi.contentStart(scalar)?.let(YamlPsi::styleOf)
        val code = codeOf(problem, hint, style) ?: return null
        val range = scalar.textRange
        val block = style == ScalarStyle.LITERAL || style == ScalarStyle.FOLDED
        return when (code) {
            DiagnosticCode.V102_FOLDED_VAULT_VALUE -> {
                val message = if (style == ScalarStyle.FOLDED) {
                    AnsibilityVaultChecksBundle.message("inspection.v102.folded")
                } else {
                    AnsibilityVaultChecksBundle.message(
                        "inspection.v102.flattened",
                        AnsibilityVaultChecksBundle.message(if (style == ScalarStyle.PLAIN) "style.plain" else "style.quoted"),
                    )
                }
                val fix = when {
                    style == ScalarStyle.FOLDED -> VaultFixKind.FOLDED_TO_LITERAL
                    VaultFixes.flattenedTokens(scalar.textValue) != null && endsItsLine(text, range.endOffset) ->
                        VaultFixKind.FLATTENED_TO_LITERAL
                    else -> null
                }
                VaultFinding(code, scalar, firstLine(text, range).shiftLeft(range.startOffset), message, fix)
            }
            DiagnosticCode.V103_TRAILING_WHITESPACE -> trailingWhitespaceLine(text, range, skipFirstLine = block)?.let { line ->
                // Lines of the value count from its header: line 1 is the first line after a block indicator.
                val lineInValue = line.number - lineOf(text, range.startOffset) + if (block) 0 else 1
                VaultFinding(code, scalar, line.range.shiftLeft(range.startOffset), v103Message(lineInValue), VaultFixKind.STRIP_TRAILING_WHITESPACE)
            }
            else -> null
        } ?: run {
            val header = if (block) contentLine(text, range) ?: firstLine(text, range) else firstLine(text, range)
            VaultFinding(
                DiagnosticCode.V101_MALFORMED_ENVELOPE, scalar, header.shiftLeft(range.startOffset),
                message(problem, parse, wholeFile = false), null,
            )
        }
    }

    private fun empty(tag: PsiElement): VaultFinding = VaultFinding(
        DiagnosticCode.V101_MALFORMED_ENVELOPE, tag, TextRange(0, tag.textLength),
        AnsibilityVaultChecksBundle.message("inspection.v101.message", reason(VaultEnvelopeProblem.EMPTY, null)), null,
    )

    /** The ANS-V101 message: the reason Ansible refuses the value or the file. */
    private fun message(problem: VaultEnvelopeProblem, parse: EnvelopeParse, wholeFile: Boolean): String {
        val reason = reason(problem, parse)
        return AnsibilityVaultChecksBundle.message(if (wholeFile) "inspection.v101.file.message" else "inspection.v101.message", reason)
    }

    /** The user-facing reason of [problem]; mentions the TAB or leading whitespace the codec saw. Never any payload. */
    internal fun reason(problem: VaultEnvelopeProblem, parse: EnvelopeParse?): String {
        val hint = parse?.let(VaultEnvelopeProblem::hintOf)
        return when {
            hint == FormatHint.TAB -> AnsibilityVaultChecksBundle.message("reason.tab")
            hint == FormatHint.LEADING_WHITESPACE -> AnsibilityVaultChecksBundle.message("reason.leading.whitespace.line")
            problem == VaultEnvelopeProblem.UNKNOWN_CIPHER ->
                AnsibilityVaultChecksBundle.message("reason.unknown.cipher", (parse as? EnvelopeParse.UnknownCipher)?.name.orEmpty())
            else -> AnsibilityVaultChecksBundle.message("reason." + problem.name.lowercase().replace('_', '.'))
        }
    }

    /** The ANS-V103 message for the 1-based [lineInValue] (line 1 is the header). */
    private fun v103Message(lineInValue: Int): String = AnsibilityVaultChecksBundle.message("inspection.v103.message", lineInValue)

    // ------------------------------------------------------------------------------------------------ text ranges

    /** A line with trailing whitespace: its 0-based [number] in the file and the range from its content to its end. */
    private class Line(val number: Int, val range: TextRange)

    /** The first line of [range]: from its start to the end of its line (or of the range), never empty when possible. */
    private fun firstLine(text: CharSequence, range: TextRange): TextRange {
        val end = indexOf(text, NEWLINE, range.startOffset, range.endOffset)
        return TextRange(range.startOffset, maxOf(end, minOf(range.startOffset + 1, range.endOffset)))
    }

    /** The first non-blank line of a block scalar after its indicator line, without its indentation; null when none. */
    private fun contentLine(text: CharSequence, range: TextRange): TextRange? {
        var start = indexOf(text, NEWLINE, range.startOffset, range.endOffset) + 1
        while (start in (range.startOffset + 1) until range.endOffset) {
            val end = indexOf(text, NEWLINE, start, range.endOffset)
            val contentStart = skipBlanks(text, start, end)
            if (contentStart < end) return TextRange(contentStart, trimEnd(text, contentStart, end))
            start = end + 1
        }
        return null
    }

    /**
     * The first line of [range] (after the indicator line of a block scalar when [skipFirstLine]) whose line in the
     * document ends in spaces or TABs: trailing blanks of a literal block's last line belong to the value even where
     * the PSI range stops before them. The highlighted range runs from the line's content to its end, inside [range].
     */
    private fun trailingWhitespaceLine(text: CharSequence, range: TextRange, skipFirstLine: Boolean): Line? {
        var start = if (skipFirstLine) indexOf(text, NEWLINE, range.startOffset, text.length) + 1 else range.startOffset
        while (start <= range.endOffset && start < text.length) {
            val end = indexOf(text, NEWLINE, start, text.length)
            if (end > start && isBlank(text[end - 1])) {
                val content = skipBlanks(text, start, end)
                val from = maxOf(if (content < end) content else start, range.startOffset)
                val to = minOf(end, range.endOffset)
                return Line(lineOf(text, start), if (from < to) TextRange(from, to) else range)
            }
            start = end + 1
        }
        return null
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

    private fun skipBlanks(text: CharSequence, from: Int, to: Int): Int {
        var i = from
        while (i < to && isBlank(text[i])) i++
        return i
    }

    /** True when nothing but blanks follows [offset] on its line (a fix may then append lines after it). */
    private fun endsItsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset
        while (i < text.length && text[i] != NEWLINE) {
            if (!isBlank(text[i])) return false
            i++
        }
        return true
    }

    private fun indexOf(text: CharSequence, char: Char, from: Int, to: Int): Int {
        for (i in from until minOf(to, text.length)) if (text[i] == char) return i
        return minOf(to, text.length)
    }

    private fun trimEnd(text: CharSequence, start: Int, end: Int): Int {
        var e = end
        while (e > start && isBlank(text[e - 1])) e--
        return e
    }

    private fun lineOf(text: CharSequence, offset: Int): Int {
        var line = 0
        for (i in 0 until minOf(offset, text.length)) if (text[i] == NEWLINE) line++
        return line
    }
}
