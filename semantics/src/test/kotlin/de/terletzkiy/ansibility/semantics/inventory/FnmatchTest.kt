package de.terletzkiy.ansibility.semantics.inventory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Expected values come from `re.compile(fnmatch.translate(p)).match(s)` in CPython 3.14. */
class FnmatchTest {
    private fun check(pattern: String, vararg expected: Pair<String, Boolean>) {
        val glob = Fnmatch.compile(pattern)
        for ((text, match) in expected) assertEquals(match, glob.matches(text), "'$pattern' vs '$text'")
    }

    @Test
    fun `wildcards`() {
        check("*a*b", "xaYb" to true, "ab" to true, "ba" to false)
        check("?", "a" to true, "ab" to false, "" to false)
        check("web[0-9][0-9]", "web01" to true, "web1" to false)
    }

    @Test
    fun `character classes`() {
        check("[!a]*", "abc" to false, "bcd" to true, "" to false)
        check("[a-c]x", "bx" to true, "dx" to false, "-x" to false)
        check("[]]", "]" to true, "a" to false)
        check("[!]]", "]" to false, "a" to true)
        check("[a-]", "-" to true, "a" to true, "b" to false)
        check("[\\]", "\\" to true)
        check("[a-c-e]", "b" to true, "-" to true, "e" to true, "d" to false)
        check("[^a]", "^" to true, "a" to true, "b" to false)
    }

    @Test
    fun `degenerate classes`() {
        check("[z-a]", "a" to false, "z" to false, "-" to false)
        check("[!z-a]x", "qx" to true, "x" to false)
        check("[", "[" to true)
        check("a[", "a[" to true)
        check("[!]", "!" to false, "x" to false, "[!]" to true)
    }
}
