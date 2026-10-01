package de.terletzkiy.ansibility.semantics.vault

/**
 * The text layout of inline `!vault` values: rendering an envelope as a literal block, and finding the indentation
 * style a file already uses, so that a new or re-encrypted value matches its neighbours (the target repo puts the
 * block at key indent + 2; `encrypt_string` prints it at a fixed 10 spaces).
 *
 * Works on text only (no YAML parser): it recognises lines of the form `key: !vault |` (also `- key: …`, `- !vault |`,
 * `!vault-encrypted`, any block indicator and a trailing comment).
 */
object VaultLayout {
    /** The block indent relative to the key that the target repo uses (796 of its 804 values). */
    const val DEFAULT_RELATIVE_INDENT: Int = 2

    /** The absolute block indent `ansible-vault encrypt_string` prints. */
    const val ENCRYPT_STRING_INDENT: Int = 10

    /** The tag and indicator written before the block. */
    const val TAG: String = "!vault |"

    /**
     * The value part of an inline vault: `!vault |`, then the envelope lines indented by [bodyIndent] spaces, joined
     * by [newline], **without** a final line break, so it replaces the range of an existing scalar and keeps the
     * document's own line end after it.
     */
    fun inlineValue(envelope: VaultEnvelope, bodyIndent: Int, newline: String = "\n"): String {
        require(bodyIndent >= 1) { "a block scalar needs an indent" }
        val indent = " ".repeat(bodyIndent)
        return envelope.formatLines().joinToString(newline, prefix = TAG + newline) { indent + it }
    }

    /**
     * A whole `key: !vault |` entry with its final line break, as `encrypt_string --name key` prints it when
     * [keyIndent] is 0 and [bodyIndent] is [ENCRYPT_STRING_INDENT] (byte for byte).
     */
    fun inlineBlock(key: String, keyIndent: Int, bodyIndent: Int, envelope: VaultEnvelope, newline: String = "\n"): String {
        require(bodyIndent > keyIndent) { "the block must be indented more than its key" }
        return " ".repeat(keyIndent) + key + ": " + inlineValue(envelope, bodyIndent, newline) + newline
    }

    /** Every inline vault block of [text], in order. */
    fun blocks(text: CharSequence): List<InlineBlock> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val result = ArrayList<InlineBlock>()
        for ((index, line) in lines.withIndex()) {
            val match = KEY_LINE.matchEntire(line) ?: continue
            val dashes = match.groups["dashes"]?.value.orEmpty()
            val key = match.groups["key"]?.value
            if (key == null && dashes.isEmpty()) continue
            val keyColumn = match.groups["indent"]!!.value.length + if (key != null) dashes.length else dashes.trimEnd().length - 1
            val first = (index + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: continue
            val bodyIndent = lines[first].length - lines[first].trimStart(' ').length
            if (bodyIndent <= keyColumn) continue
            result += InlineBlock(
                keyLine = index,
                key = key,
                keyColumn = keyColumn,
                firstLine = first,
                bodyIndent = bodyIndent,
                indicator = match.groups["indicator"]!!.value,
                hasHeader = lines[first].startsWith(VaultEnvelope.MAGIC, bodyIndent),
            )
        }
        return result
    }

    /**
     * The block indent relative to the key that [text] uses most (ties: the one seen first), counting only blocks
     * whose first line is an envelope header; null when the file has none.
     */
    fun detectRelativeIndent(text: CharSequence): Int? {
        val indents = blocks(text).filter { it.hasHeader }.map { it.relativeIndent }
        if (indents.isEmpty()) return null
        val counts = indents.groupingBy { it }.eachCount()
        val max = counts.values.max()
        return indents.first { counts[it] == max }
    }

    /** The absolute block indent for a key at [keyColumn] in [text]: the file's style, else [DEFAULT_RELATIVE_INDENT]. */
    fun bodyIndentFor(text: CharSequence, keyColumn: Int): Int =
        keyColumn + (detectRelativeIndent(text) ?: DEFAULT_RELATIVE_INDENT)

    private val KEY_LINE = Regex(
        """(?<indent> *)(?<dashes>(?:- +)*)(?:(?<key>"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[^\s#'"\-][^#]*?|-[^\s#][^#]*?) *: +)?""" +
            """!vault(?:-encrypted)? +(?<indicator>[|>][0-9+-]*) *(?:#.*)?""",
    )
}

/** One inline vault block found by [VaultLayout.blocks]. Lines are 0-based. */
data class InlineBlock(
    /** The line of `key: !vault |`. */
    val keyLine: Int,
    /** The key as written, or null for a sequence item (`- !vault |`). */
    val key: String?,
    /** The column of the key (or of the item's dash). */
    val keyColumn: Int,
    /** The first non-blank line of the block. */
    val firstLine: Int,
    /** The indentation of [firstLine]. */
    val bodyIndent: Int,
    /** `|`, `|-`, `|+`, `>`, … */
    val indicator: String,
    /** True when [firstLine] starts with `$ANSIBLE_VAULT` after the indentation. */
    val hasHeader: Boolean,
) {
    /** [bodyIndent] relative to [keyColumn]: 2 in the target repo's style, 10 for pasted `encrypt_string` output. */
    val relativeIndent: Int get() = bodyIndent - keyColumn
}
