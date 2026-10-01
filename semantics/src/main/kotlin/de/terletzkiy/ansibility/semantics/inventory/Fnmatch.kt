package de.terletzkiy.ansibility.semantics.inventory

/**
 * A compiled shell-style pattern with the exact semantics of Python's `re.compile(fnmatch.translate(p)).match(s)`
 * (case-sensitive, whole string, `*`, `?`, `[seq]`, `[!seq]`, ranges; an unclosed `[` is literal; reversed ranges
 * are dropped). Implemented as a matcher instead of a translated regex because Java and Python character-class
 * syntax differ.
 */
internal class Fnmatch private constructor(private val tokens: List<Token>) {
    private sealed interface Token {
        fun matches(c: Char): Boolean
    }

    private data class Literal(val char: Char) : Token {
        override fun matches(c: Char) = c == char
    }

    private data object AnyChar : Token {
        override fun matches(c: Char) = true
    }

    private data object Star : Token {
        override fun matches(c: Char) = true
    }

    private data object Never : Token {
        override fun matches(c: Char) = false
    }

    private class CharSet(val negated: Boolean, val singles: Set<Char>, val ranges: List<CharRange>) : Token {
        override fun matches(c: Char): Boolean = (c in singles || ranges.any { c in it }) != negated
    }

    /** Full-string match (the translated regex ends in `\Z`). */
    fun matches(text: String): Boolean {
        var p = 0
        var s = 0
        var starP = -1
        var starS = 0
        while (s < text.length) {
            if (p < tokens.size && tokens[p] === Star) {
                starP = p
                starS = s
                p++
            } else if (p < tokens.size && tokens[p].matches(text[s])) {
                p++
                s++
            } else if (starP >= 0) {
                p = starP + 1
                starS++
                s = starS
            } else {
                return false
            }
        }
        while (p < tokens.size && tokens[p] === Star) p++
        return p == tokens.size
    }

    /** A character of the regex class body `fnmatch.translate` builds; [escaped] means it was written as `\x`. */
    private data class Atom(val char: Char, val escaped: Boolean)

    companion object {
        /** Port of `fnmatch._translate`; throws IllegalArgumentException where `re.compile` would fail. */
        fun compile(pattern: String): Fnmatch {
            val tokens = ArrayList<Token>()
            var i = 0
            val n = pattern.length
            while (i < n) {
                val c = pattern[i]
                i++
                when (c) {
                    '*' -> {
                        tokens += Star
                        while (i < n && pattern[i] == '*') i++
                    }
                    '?' -> tokens += AnyChar
                    '[' -> {
                        var j = i
                        if (j < n && pattern[j] == '!') j++
                        if (j < n && pattern[j] == ']') j++
                        while (j < n && pattern[j] != ']') j++
                        if (j >= n) {
                            tokens += Literal('[')
                        } else {
                            tokens += charClass(pattern, i, j)
                            i = j + 1
                        }
                    }
                    else -> tokens += Literal(c)
                }
            }
            return Fnmatch(tokens)
        }

        private fun charClass(pattern: String, start: Int, end: Int): Token {
            val raw = pattern.substring(start, end)
            val atoms = ArrayList<Atom>()
            if ('-' !in raw) {
                for (ch in raw) atoms += Atom(ch, escaped = ch == '\\')
            } else {
                val chunks = ArrayList<String>()
                var i = start
                var k = if (pattern[i] == '!') i + 2 else i + 1
                while (true) {
                    k = if (k < end) pattern.indexOf('-', k).takeIf { it in 0 until end } ?: -1 else -1
                    if (k < 0) break
                    chunks += pattern.substring(i, k)
                    i = k + 1
                    k += 3
                }
                val last = pattern.substring(i, end)
                if (last.isNotEmpty()) chunks += last else chunks[chunks.size - 1] = chunks.last() + "-"
                // Remove empty ranges -- invalid in RE.
                for (idx in chunks.size - 1 downTo 1) {
                    val prev = chunks[idx - 1]
                    val cur = chunks[idx]
                    if (prev.isNotEmpty() && cur.isNotEmpty() && prev.last() > cur[0]) {
                        chunks[idx - 1] = prev.dropLast(1) + cur.substring(1)
                        chunks.removeAt(idx)
                    }
                }
                chunks.forEachIndexed { idx, chunk ->
                    if (idx > 0) atoms += Atom('-', escaped = false) // the hyphens that form ranges
                    for (ch in chunk) atoms += Atom(ch, escaped = ch == '\\' || ch == '-')
                }
            }
            if (atoms.isEmpty()) return Never // empty range: never match
            if (atoms.size == 1 && atoms[0] == Atom('!', false)) return AnyChar // negated empty range
            val negated = atoms[0] == Atom('!', false)
            val body = if (negated) atoms.drop(1) else atoms
            return parseClassBody(body, negated)
        }

        /** The class-body grammar of Python's `sre_parse` (literal, or `a-b` range) over the atoms. */
        private fun parseClassBody(atoms: List<Atom>, negated: Boolean): Token {
            val singles = HashSet<Char>()
            val ranges = ArrayList<CharRange>()
            var i = 0
            while (i < atoms.size) {
                val low = atoms[i]
                i++
                if (i < atoms.size && atoms[i] == Atom('-', false)) {
                    i++
                    if (i >= atoms.size) {
                        singles += low.char
                        singles += '-'
                        break
                    }
                    val high = atoms[i]
                    i++
                    require(low.char <= high.char) { "bad character range ${low.char}-${high.char}" }
                    ranges += low.char..high.char
                } else {
                    singles += low.char
                }
            }
            return CharSet(negated, singles, ranges)
        }
    }
}
