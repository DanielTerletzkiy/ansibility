package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.coerce.PyLiteral.Lit
import de.terletzkiy.ansibility.semantics.value.PyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** `ast.literal_eval` as the INI inventory plugin uses it ([PyLiteral.literalEval]). */
class PyLiteralEvalTest {
    private fun eval(text: String) = PyLiteral.literalEval(text)

    private fun pyClass(text: String): String = assertThrows(PyException::class.java) { eval(text) }.pyClass

    private fun int(v: Long) = Lit.LInt(BigInteger.valueOf(v))

    @Test
    fun `scalars as Python evaluates them`() {
        assertEquals(int(5), eval("5"))
        assertEquals(Lit.LStr("5"), eval("\"5\""))
        assertEquals(Lit.LBool(true), eval("True"))
        assertEquals(Lit.LNone, eval("None"))
        assertEquals(Lit.LFloat(1000.0), eval("1e3"))
        assertEquals(int(16), eval("0x10"))
        assertEquals(int(-3), eval("-3"))
        assertEquals(int(1000), eval("1_000"))
        assertEquals(Lit.LBytes("abc"), eval("b'abc'"))
        assertEquals(Lit.LEllipsis, eval("..."))
        assertEquals(Lit.LComplex(1.0, 2.0), eval("1+2j"))
        assertEquals(int(5), eval("  \t5 # a comment"))
    }

    @Test
    fun `containers, and a tuple without parentheses`() {
        assertEquals(Lit.LTuple(listOf(int(1), int(2))), eval("1,2"))
        assertEquals(Lit.LTuple(listOf(int(1))), eval("1,"))
        assertEquals(Lit.LTuple(listOf(int(1), int(2))), eval("(1, 2)"))
        assertEquals(Lit.LList(listOf(int(1), int(2))), eval("[1,2,]"))
        assertEquals(Lit.LSet(listOf(int(1), int(2))), eval("{1, 2}"))
        assertEquals(Lit.LSet(emptyList()), eval("set()"))
        assertEquals(Lit.LDict(listOf(Lit.LStr("a") to int(1))), eval("{'a': 1}"))
    }

    @Test
    fun `what literal_eval refuses, with Python's exception class`() {
        for (text in listOf("", "hello world", "abc ; c", "a=b", "a:b", "010", "1,,", "\\x", "{1: 2")) {
            assertEquals("SyntaxError", pyClass(text), text)
        }
        for (text in listOf("yes", "abc # c", "[True,false]", "{a:1}", "{{ x }}", "--5", "x.y", "len([])", "True+1j", "f'x'")) {
            assertEquals("ValueError", pyClass(text), text)
        }
    }

    @Test
    fun `unhashable set elements and dict keys are TypeErrors, raised in Python's order`() {
        assertEquals("TypeError", pyClass("{[1]}"))
        assertEquals("TypeError", pyClass("{[1]: 2}"))
        assertEquals("TypeError", pyClass("{([1],)}"))
        // set(map(_convert, elts)) hashes the dict before it converts the name after it.
        assertEquals("TypeError", pyClass("{{}, x}"))
        // A malformed key is converted before the set element after it is hashed.
        assertEquals("ValueError", pyClass("{x, {}}"))
        assertEquals("unhashable type: 'list'", assertThrows(PyException::class.java) { eval("{([1],)}") }.message)
    }

    @Test
    fun `nesting and sign runs end where CPython's tokenizer and parser give up`() {
        assertEquals("SyntaxError", pyClass("[".repeat(201) + "]".repeat(201)))
        assertEquals(Lit.LList::class, eval("[".repeat(200) + "]".repeat(200))::class)
        assertEquals("ValueError", pyClass("-".repeat(5974) + "5"))
        assertEquals("MemoryError", pyClass("-".repeat(100_000) + "5"))
    }

    @Test
    fun `evalDict is literalEval restricted to dicts`() {
        assertEquals("TypeError", assertThrows(PyException::class.java) { PyLiteral.evalDict("{'a': 1}, 2") }.pyClass)
        assertEquals("TypeError", assertThrows(PyException::class.java) { PyLiteral.evalDict("[1]") }.pyClass)
    }
}
