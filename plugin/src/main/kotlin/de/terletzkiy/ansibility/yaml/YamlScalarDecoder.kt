package de.terletzkiy.ansibility.yaml

/**
 * The text a YAML scalar loads as, ported line by line from PyYAML's scanner (`yaml/scanner.py`, 6.0), which is what
 * Ansible's loader runs.
 *
 * The adapter uses this instead of `YAMLScalar.getTextValue()` because the IntelliJ evaluators are wrong in cases
 * Ansible content can contain (measured on 262): a quoted scalar after an anchor (`&a "x"` gives `a "x`), a block
 * scalar with an anchor or an explicit indentation indicator (`&a |`, `|2` lose the final line break), and the
 * `\N`, `\_`, `\L`, `\P` escapes.
 *
 * Malformed input never throws: where PyYAML raises a `ScannerError` (so Ansible could not load the file), the
 * decoder keeps the offending characters and carries on.
 */
internal object YamlScalarDecoder {
    private const val EOF = '\u0000'
    private const val BREAKS = "\r\n\u0085  "
    private const val BLANK_OR_BREAK = " \t$BREAKS"

    private val ESCAPE_REPLACEMENTS = mapOf(
        '0' to "\u0000", 'a' to "\u0007", 'b' to "\u0008", 't' to "\t", '\t' to "\t", 'n' to "\n", 'v' to "\u000B",
        'f' to "\u000C", 'r' to "\r", 'e' to "\u001B", ' ' to " ", '"' to "\"", '/' to "/", '\\' to "\\",
        'N' to "\u0085", '_' to " ", 'L' to " ", 'P' to " ",
    )
    private val ESCAPE_CODES = mapOf('x' to 2, 'u' to 4, 'U' to 8)

    /** A single- or double-quoted scalar; [text] starts at the opening quote (`scan_flow_scalar`). */
    fun decodeFlow(text: CharSequence): String {
        val reader = Reader(text)
        val quote = reader.peek()
        val double = quote == '"'
        val chunks = StringBuilder()
        reader.forward()
        flowNonSpaces(reader, double, chunks)
        while (reader.peek() != quote && reader.peek() != EOF) {
            flowSpaces(reader, chunks)
            flowNonSpaces(reader, double, chunks)
        }
        return chunks.toString()
    }

    /**
     * A literal (`|`) or folded (`>`) block scalar (`scan_block_scalar`).
     *
     * [text] starts at the header indicator and runs to the end of the scalar's last line, including the blank
     * lines after it (they matter for `|+`). [parentIndent] is the column of the block collection that holds the
     * scalar (the column of its key or of its `-`), or -1 for a scalar that is the whole document.
     */
    fun decodeBlock(text: CharSequence, parentIndent: Int): String {
        val reader = Reader(text)
        val folded = reader.peek() == '>'
        reader.forward()

        var chomping: Boolean? = null
        var increment: Int? = null
        var ch = reader.peek()
        if (ch == '+' || ch == '-') {
            chomping = ch == '+'
            reader.forward()
            ch = reader.peek()
            if (ch in '1'..'9') {
                increment = ch - '0'
                reader.forward()
            }
        } else if (ch in '1'..'9') {
            increment = ch - '0'
            reader.forward()
            ch = reader.peek()
            if (ch == '+' || ch == '-') {
                chomping = ch == '+'
                reader.forward()
            }
        }
        skipIgnoredLine(reader)

        val minIndent = maxOf(parentIndent + 1, 1)
        val indent: Int
        var breaks: List<String>
        if (increment == null) {
            val (leadingBreaks, maxIndent) = blockIndentation(reader)
            breaks = leadingBreaks
            indent = maxOf(minIndent, maxIndent)
        } else {
            indent = minIndent + increment - 1
            breaks = blockBreaks(reader, indent)
        }

        val chunks = StringBuilder()
        var lineBreak = ""
        while (reader.column == indent && reader.peek() != EOF) {
            breaks.forEach(chunks::append)
            val leadingNonSpace = reader.peek() != ' ' && reader.peek() != '\t'
            var length = 0
            while (reader.peek(length) != EOF && reader.peek(length) !in BREAKS) length++
            chunks.append(reader.prefix(length))
            reader.forward(length)
            lineBreak = reader.scanLineBreak()
            breaks = blockBreaks(reader, indent)
            if (reader.column == indent && reader.peek() != EOF) {
                // PyYAML's reading of the ambiguous folding rules: only a single "\n" between two lines that both
                // start with a non-space folds into a space.
                if (folded && lineBreak == "\n" && leadingNonSpace && reader.peek() != ' ' && reader.peek() != '\t') {
                    if (breaks.isEmpty()) chunks.append(' ')
                } else {
                    chunks.append(lineBreak)
                }
            } else {
                break
            }
        }
        if (chomping != false) chunks.append(lineBreak)
        if (chomping == true) breaks.forEach(chunks::append)
        return chunks.toString()
    }

    /**
     * A plain scalar's text (`scan_plain` + `scan_plain_spaces`): lines are trimmed, a single line break folds into a
     * space and each further line break is kept. [text] is the scalar's own text without tag and anchor; IntelliJ's
     * lexer has already found where the scalar ends.
     */
    fun decodePlain(text: CharSequence): String {
        val out = StringBuilder()
        var pendingBreaks = 0
        var first = true
        for (line in text.lines()) {
            val content = line.trim(' ', '\t')
            if (content.isEmpty()) {
                if (!first) pendingBreaks++
                continue
            }
            if (!first) {
                if (pendingBreaks == 0) out.append(' ') else repeat(pendingBreaks) { out.append('\n') }
            }
            out.append(content)
            first = false
            pendingBreaks = 0
        }
        return out.toString()
    }

    private fun flowNonSpaces(reader: Reader, double: Boolean, chunks: StringBuilder) {
        while (true) {
            var length = 0
            while (reader.peek(length) !in "'\"\\$EOF$BLANK_OR_BREAK") length++
            if (length > 0) {
                chunks.append(reader.prefix(length))
                reader.forward(length)
            }
            val ch = reader.peek()
            when {
                !double && ch == '\'' && reader.peek(1) == '\'' -> {
                    chunks.append('\'')
                    reader.forward(2)
                }
                (double && ch == '\'') || (!double && (ch == '"' || ch == '\\')) -> {
                    chunks.append(ch)
                    reader.forward()
                }
                double && ch == '\\' -> {
                    reader.forward()
                    decodeEscape(reader, chunks)
                }
                else -> return
            }
        }
    }

    private fun decodeEscape(reader: Reader, chunks: StringBuilder) {
        val ch = reader.peek()
        val replacement = ESCAPE_REPLACEMENTS[ch]
        val codeLength = ESCAPE_CODES[ch]
        when {
            replacement != null -> {
                chunks.append(replacement)
                reader.forward()
            }
            codeLength != null -> {
                val hex = reader.prefix(codeLength + 1).drop(1)
                val valid = hex.length == codeLength && hex.all { it.isHexDigitChar() }
                val code = if (valid) hex.toInt(16) else null
                if (code != null && Character.isValidCodePoint(code)) {
                    chunks.appendCodePoint(code)
                    reader.forward(codeLength + 1)
                } else {
                    chunks.append('\\')
                }
            }
            ch in BREAKS -> {
                reader.scanLineBreak()
                flowBreaks(reader).forEach(chunks::append)
            }
            // PyYAML raises "found unknown escape character"; keep the backslash so the scan always advances.
            else -> chunks.append('\\')
        }
    }

    private fun flowSpaces(reader: Reader, chunks: StringBuilder) {
        var length = 0
        while (reader.peek(length) == ' ' || reader.peek(length) == '\t') length++
        val whitespaces = reader.prefix(length)
        reader.forward(length)
        val ch = reader.peek()
        if (ch != EOF && ch in BREAKS) {
            val lineBreak = reader.scanLineBreak()
            val breaks = flowBreaks(reader)
            if (lineBreak != "\n") {
                chunks.append(lineBreak)
            } else if (breaks.isEmpty()) {
                chunks.append(' ')
            }
            breaks.forEach(chunks::append)
        } else {
            chunks.append(whitespaces)
        }
    }

    private fun flowBreaks(reader: Reader): List<String> {
        val chunks = ArrayList<String>()
        while (true) {
            while (reader.peek() == ' ' || reader.peek() == '\t') reader.forward()
            if (reader.peek() != EOF && reader.peek() in BREAKS) {
                chunks += reader.scanLineBreak()
            } else {
                return chunks
            }
        }
    }

    private fun skipIgnoredLine(reader: Reader) {
        while (reader.peek() == ' ') reader.forward()
        if (reader.peek() == '#') {
            while (reader.peek() != EOF && reader.peek() !in BREAKS) reader.forward()
        }
        // PyYAML raises on anything else after the header; skip it the way a comment is skipped.
        while (reader.peek() != EOF && reader.peek() !in BREAKS) reader.forward()
        reader.scanLineBreak()
    }

    private fun blockIndentation(reader: Reader): Pair<List<String>, Int> {
        val chunks = ArrayList<String>()
        var maxIndent = 0
        while (reader.peek() == ' ' || (reader.peek() != EOF && reader.peek() in BREAKS)) {
            if (reader.peek() != ' ') {
                chunks += reader.scanLineBreak()
            } else {
                reader.forward()
                if (reader.column > maxIndent) maxIndent = reader.column
            }
        }
        return chunks to maxIndent
    }

    private fun blockBreaks(reader: Reader, indent: Int): List<String> {
        val chunks = ArrayList<String>()
        while (reader.column < indent && reader.peek() == ' ') reader.forward()
        while (reader.peek() != EOF && reader.peek() in BREAKS) {
            chunks += reader.scanLineBreak()
            while (reader.column < indent && reader.peek() == ' ') reader.forward()
        }
        return chunks
    }

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** PyYAML's `Reader`: a cursor with the column bookkeeping of `forward()`. Past the end it reads `'\0'`. */
    private class Reader(private val text: CharSequence) {
        private var pointer = 0
        var column = 0
            private set

        fun peek(offset: Int = 0): Char = if (pointer + offset < text.length) text[pointer + offset] else EOF

        fun prefix(length: Int): String = text.subSequence(pointer, minOf(pointer + length, text.length)).toString()

        fun forward(length: Int = 1) {
            repeat(length) {
                if (pointer >= text.length) return
                val ch = text[pointer++]
                if (ch == '\n' || ch == '\u0085' || ch == ' ' || ch == ' ' || (ch == '\r' && peek() != '\n')) {
                    column = 0
                } else if (ch != '﻿') {
                    column++
                }
            }
        }

        fun scanLineBreak(): String {
            val ch = peek()
            return when (ch) {
                '\r', '\n', '\u0085' -> {
                    if (ch == '\r' && peek(1) == '\n') forward(2) else forward()
                    "\n"
                }
                ' ', ' ' -> {
                    forward()
                    ch.toString()
                }
                else -> ""
            }
        }
    }
}
