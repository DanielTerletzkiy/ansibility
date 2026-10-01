package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import org.jetbrains.yaml.psi.YAMLScalar
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One text replacement in a document. */
internal class VaultReplacement(val range: TextRange, val text: String) {
    override fun toString(): String = "VaultReplacement($range)"
}

/**
 * The text the vault actions write: envelope bodies in the file's layout (80 columns, lowercase hex, the block's own
 * indentation), whole `!vault |` values, and decrypted values as YAML strings. Pure functions over text and PSI.
 */
internal object VaultValueText {
    /** The envelope lines at [indent] spaces, LF-joined, without a final line break: a literal block's body. */
    fun body(envelope: VaultEnvelope, indent: Int): String {
        val prefix = " ".repeat(indent)
        return envelope.formatLines().joinToString("\n") { prefix + it }
    }

    /** A whole `!vault |` value, [comment] kept on the tag line, the body at [indent] spaces; no final line break. */
    fun block(envelope: VaultEnvelope, indent: Int, comment: String?): String =
        VaultLayout.TAG + comment?.let { " $it" }.orEmpty() + "\n" + body(envelope, indent)

    /**
     * The replacement that writes [envelope] into the value [scalar] of [text]:
     * - a literal block keeps its `key: !vault |` line (tag, indicator, comment) and its indentation; only the body
     *   lines change (F7.2, as `ansible-vault edit` keeps the layout);
     * - any other value (a quoted `!vault "…"`, or a plain value being encrypted) becomes a `!vault |` block at the
     *   file's indentation style relative to its key (+2 in the target repo), with a same-line comment moved onto
     *   the tag line so it never ends up inside the hex.
     */
    fun envelopeReplacement(scalar: YAMLScalar, text: CharSequence, envelope: VaultEnvelope): VaultReplacement {
        if (VaultValuePsi.isVault(scalar)) {
            VaultValuePsi.literalBody(scalar, text)?.let { body -> return VaultReplacement(body.range, body(envelope, body.indent)) }
        }
        val start = VaultValuePsi.valueStart(scalar)
        var end = VaultValuePsi.valueEnd(scalar, text)
        val comment = VaultValuePsi.lineComment(scalar, text)
        comment?.first?.let { end = it.endOffset }
        val indent = VaultLayout.bodyIndentFor(text, VaultValuePsi.ownerColumn(scalar, text))
        return VaultReplacement(TextRange(start, end), block(envelope, indent, comment?.second))
    }

    /**
     * The replacement that writes the decrypted [value] in place of the vault value [scalar] (F7.4): one
     * double-quoted string, so YAML loads it back as exactly this string. A value with Jinja syntax gets `!unsafe`,
     * because Ansible never templates a vault value and must not start templating its plain copy.
     */
    fun plainReplacement(scalar: YAMLScalar, text: CharSequence, value: String): VaultReplacement {
        val start = VaultValuePsi.valueStart(scalar)
        val end = VaultValuePsi.valueEnd(scalar, text)
        val quoted = doubleQuoted(value)
        return VaultReplacement(TextRange(start, end), if (isTemplated(value)) "$UNSAFE $quoted" else quoted)
    }

    /** True when Ansible would template [value] as a plain string (`{{`, `{%` or `{#`). */
    fun isTemplated(value: String): Boolean = JINJA.any { it in value }

    /** [value] as a YAML double-quoted scalar (PyYAML's escapes), one line, loading back as exactly [value]. */
    fun doubleQuoted(value: String): String = buildString(value.length + 2) {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\u0000' -> append("\\0")
                '\u0007' -> append("\\a")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000B' -> append("\\v")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                '\u001B' -> append("\\e")
                '\u0085' -> append("\\N")
                '\u00A0' -> append("\\_")
                '\u2028' -> append("\\L")
                '\u2029' -> append("\\P")
                '\uFEFF' -> append("\\uFEFF")
                else -> if (char < ' ' || char in '\u007F'..'\u009F') append("\\x%02X".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    /** [bytes] as UTF-8 text, or null when they are not valid UTF-8 (a binary value, which is never shown as text). */
    fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private const val UNSAFE = "!unsafe"
    private val JINJA = listOf("{{", "{%", "{#")
}
