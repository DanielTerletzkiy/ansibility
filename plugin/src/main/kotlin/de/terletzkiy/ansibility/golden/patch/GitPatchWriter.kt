package de.terletzkiy.ansibility.golden.patch

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.DumbProgressIndicator

/**
 * A git-style unified diff (plan amendment R25, X126), written on top of the platform's line comparison
 * ([ComparisonManager], public API). The VCS patch writer of `vcs-impl` is not used: it needs the VCS change model,
 * writes the IDE's own headers, and parts of it are internal.
 *
 * The writer is byte-transparent: file content is read as ISO-8859-1, one char per byte, so a line is compared and
 * written with exactly its bytes (a `\r` before the `\n` stays, any charset passes through), and [Output.bytes] gives
 * the patch's bytes back. A line is what ends with `\n`; a last line without one is followed by
 * `\ No newline at end of file`, as git writes it. Three lines of context; hunks closer than twice that are joined.
 * Paths with a quote, a backslash or a control character are C-quoted like git does; other names are written as their
 * UTF-8 bytes.
 */
internal object GitPatchWriter {
    /** Lines of context around each change (git's default). */
    const val CONTEXT: Int = 3

    /** Git's marker after a line that has no line break at the end of the file. */
    const val NO_NEWLINE: String = "\\ No newline at end of file"

    /** The mode git records for a regular file, and for one with the executable bit. */
    private const val MODE_FILE = "100644"
    private const val MODE_EXECUTABLE = "100755"

    /**
     * Marks, in the compared text only, a last line that has no line break, so it never matches the same line with
     * one (a NUL byte never occurs in a text the writer accepts: [isText]).
     */
    private const val NO_EOL_MARK = '\u0000'

    /**
     * One file of a patch: [path] relative to the repository the patch is applied in ("roles/web/tasks/main.yml"),
     * [old] the content there (null: the file is new), [new] the content it gets (null: the file is deleted).
     */
    class FileChange(
        val path: String,
        val old: ByteArray?,
        val new: ByteArray?,
        val oldExecutable: Boolean = false,
        val newExecutable: Boolean = false,
    ) {
        init {
            require(old != null || new != null) { "a change needs a side" }
        }
    }

    /** Whether [bytes] can go into a text diff: no NUL byte anywhere (git's own test looks at the first 8000 bytes). */
    fun isText(bytes: ByteArray): Boolean = bytes.none { it == 0.toByte() }

    /** The patch being written: one char per byte (ISO-8859-1); [bytes] is the patch. */
    class Output {
        private val text = StringBuilder()

        /** Appends [line] (any text, written as UTF-8) and a line break. */
        fun line(line: String) {
            text.append(utf8(line)).append('\n')
        }

        /** Appends an empty line. */
        fun blank() {
            text.append('\n')
        }

        internal fun raw(chars: String) {
            text.append(chars)
        }

        val isEmpty: Boolean get() = text.isEmpty()

        /** The patch's bytes. */
        fun bytes(): ByteArray = text.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * "Binary files a/x and b/x differ", the line git writes for a binary change of [path] (no content); `/dev/null`
     * for the side that has no such file.
     */
    fun binaryLine(path: String, oldExists: Boolean, newExists: Boolean): String =
        "Binary files ${if (oldExists) quote("a/$path") else DEV_NULL} and ${if (newExists) quote("b/$path") else DEV_NULL} differ"

    /**
     * Writes the diff of [change] to [out]: the `diff --git` header, the mode lines (a new or deleted file, a changed
     * executable bit), then `---`/`+++` and the hunks when the content differs. Both sides must be text ([isText]).
     */
    fun write(out: Output, change: FileChange) {
        val a = "a/${change.path}"
        val b = "b/${change.path}"
        out.line("diff --git ${quote(a)} ${quote(b)}")
        when {
            change.old == null -> out.line("new file mode ${mode(change.newExecutable)}")
            change.new == null -> out.line("deleted file mode ${mode(change.oldExecutable)}")
            change.oldExecutable != change.newExecutable -> {
                out.line("old mode ${mode(change.oldExecutable)}")
                out.line("new mode ${mode(change.newExecutable)}")
            }
        }
        val old = change.old?.let { split(latin1(it)) } ?: Lines.EMPTY
        val new = change.new?.let { split(latin1(it)) } ?: Lines.EMPTY
        val changes = changes(old, new)
        // A new or deleted empty file, or a mode change alone: the header says it all.
        if (changes.isEmpty()) return
        out.line("--- ${if (change.old == null) DEV_NULL else quote(a)}")
        out.line("+++ ${if (change.new == null) DEV_NULL else quote(b)}")
        for (hunk in hunks(changes, old.size, new.size)) writeHunk(out, hunk, old, new)
    }

    /** The lines of a text: without their `\n`, and whether the last one had one. */
    internal class Lines(val lines: List<String>, val lastHasNewline: Boolean) {
        val size: Int get() = lines.size

        /** Whether line [index] is the last one and has no line break. */
        fun missingNewline(index: Int): Boolean = !lastHasNewline && index == lines.lastIndex

        companion object {
            val EMPTY: Lines = Lines(emptyList(), true)
        }
    }

    internal fun split(text: String): Lines {
        if (text.isEmpty()) return Lines.EMPTY
        val parts = text.split('\n')
        return if (text.endsWith('\n')) Lines(parts.dropLast(1), true) else Lines(parts, false)
    }

    /** A changed range: lines [start1, end1) of the old side become lines [start2, end2) of the new side. */
    internal data class Change(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

    /** The changed ranges of [old] → [new], in order. */
    internal fun changes(old: Lines, new: Lines): List<Change> {
        if (old.size == 0 || new.size == 0) {
            return if (old.size == 0 && new.size == 0) emptyList() else listOf(Change(0, old.size, 0, new.size))
        }
        return try {
            ComparisonManager.getInstance()
                .compareLines(comparable(old), comparable(new), ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
                .map { Change(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
        } catch (_: DiffTooBigException) {
            // Too many changes for the comparison: one hunk that replaces everything is still a correct patch.
            listOf(Change(0, old.size, 0, new.size))
        }
    }

    /** The text the comparison sees: the lines joined by `\n`, a last line without a line break marked. */
    private fun comparable(lines: Lines): String = buildString {
        lines.lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(line)
            if (lines.missingNewline(index)) append(NO_EOL_MARK)
        }
    }

    /** One hunk: lines [start1, end1) of the old side and [start2, end2) of the new side, with [changes] inside. */
    internal data class Hunk(val start1: Int, val end1: Int, val start2: Int, val end2: Int, val changes: List<Change>)

    /** [changes] grouped into hunks with [CONTEXT] lines around them; changes closer than twice that share one. */
    internal fun hunks(changes: List<Change>, size1: Int, size2: Int): List<Hunk> {
        val groups = ArrayList<MutableList<Change>>()
        for (change in changes) {
            val last = groups.lastOrNull()?.last()
            if (last != null && change.start1 - last.end1 <= 2 * CONTEXT) groups.last() += change else groups += mutableListOf(change)
        }
        return groups.map { group ->
            val first = group.first()
            val last = group.last()
            val start1 = maxOf(0, first.start1 - CONTEXT)
            val start2 = first.start2 - (first.start1 - start1)
            val end1 = minOf(size1, last.end1 + CONTEXT)
            val end2 = minOf(size2, last.end2 + (end1 - last.end1))
            Hunk(start1, end1, start2, end2, group)
        }
    }

    private fun writeHunk(out: Output, hunk: Hunk, old: Lines, new: Lines) {
        out.line("@@ -${range(hunk.start1, hunk.end1 - hunk.start1)} +${range(hunk.start2, hunk.end2 - hunk.start2)} @@")
        var at = hunk.start1
        for (change in hunk.changes) {
            while (at < change.start1) writeLine(out, ' ', old, at++)
            for (index in change.start1 until change.end1) writeLine(out, '-', old, index)
            for (index in change.start2 until change.end2) writeLine(out, '+', new, index)
            at = change.end1
        }
        while (at < hunk.end1) writeLine(out, ' ', old, at++)
    }

    private fun writeLine(out: Output, prefix: Char, lines: Lines, index: Int) {
        out.raw(prefix.toString())
        out.raw(lines.lines[index])
        out.raw("\n")
        if (lines.missingNewline(index)) out.line(NO_NEWLINE)
    }

    /** A hunk range: "start,count" with 1-based start; "start" alone for one line; for none, the line before it. */
    internal fun range(start: Int, count: Int): String = when (count) {
        0 -> "$start,0"
        1 -> "${start + 1}"
        else -> "${start + 1},$count"
    }

    private fun mode(executable: Boolean): String = if (executable) MODE_EXECUTABLE else MODE_FILE

    /**
     * [name] as git writes it in a header: as it is, or C-quoted (`"a/x\ty"`) when it holds a double quote, a backslash
     * or a control character, which git apply reads back.
     */
    internal fun quote(name: String): String {
        if (name.none { it == '"' || it == '\\' || it < ' ' || it == '\u007f' }) return name
        val quoted = StringBuilder("\"")
        for (char in name) {
            when (char) {
                '"' -> quoted.append("\\\"")
                '\\' -> quoted.append("\\\\")
                '\t' -> quoted.append("\\t")
                '\n' -> quoted.append("\\n")
                '\r' -> quoted.append("\\r")
                '\u0007' -> quoted.append("\\a")
                '\b' -> quoted.append("\\b")
                '\u000b' -> quoted.append("\\v")
                '\u000c' -> quoted.append("\\f")
                else -> if (char < ' ' || char == '\u007f') quoted.append("\\%03o".format(char.code)) else quoted.append(char)
            }
        }
        return quoted.append('"').toString()
    }

    private const val DEV_NULL = "/dev/null"

    private fun latin1(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    /** [text]'s UTF-8 bytes, one char per byte. */
    private fun utf8(text: String): String = String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
}
