package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** [UnknownKeywords] suggestions and accepted keys, and the Python name and integer rules of [PyNames]. */
class UnknownKeywordsTest {
    private val taskKeywords = listOf(
        "become", "become_user", "become_method", "delegate_to", "delegate_facts", "register", "retries", "when", "notify",
        "loop", "loop_control", "tags", "vars", "name",
    )

    @Test
    fun `typos get the nearest keyword`() {
        assertEquals("become_user", UnknownKeywords.suggestion("become_usr", taskKeywords))
        assertEquals("delegate_to", UnknownKeywords.suggestion("delegat_to", taskKeywords))
        assertEquals("register", UnknownKeywords.suggestion("regsiter", taskKeywords), "a transposition is one edit")
        assertEquals("gather_facts", UnknownKeywords.suggestion("gather_fact", listOf("gather_facts", "gather_subset")))
        assertNull(UnknownKeywords.suggestion("haproxy_extra", taskKeywords))
        assertNull(UnknownKeywords.suggestion("ab", listOf("ac", "become")), "short keys need an exact match")
        assertNull(UnknownKeywords.suggestion("become", taskKeywords), "the key itself is no suggestion")
    }

    @Test
    fun `undocumented keys ansible-core accepts`() {
        val v218 = CoreVersion(2, 18, 8)
        assertEquals(setOf("async_val", "loop_with"), UnknownKeywords.undocumentedKeys(PlaybookObject.TASK, v218))
        assertEquals(setOf("user"), UnknownKeywords.undocumentedKeys(PlaybookObject.PLAY, v218))
        assertEquals(setOf("vars_val"), UnknownKeywords.undocumentedKeys(PlaybookObject.PLAYBOOK_INCLUDE, v218))
        assertTrue(UnknownKeywords.undocumentedKeys(PlaybookObject.PLAYBOOK_INCLUDE, CoreVersion(2, 21, 4)).isEmpty())
        assertTrue("listen" in UnknownKeywords.dynamicIncludeKeywords(handler = true))
        assertFalse("become" in UnknownKeywords.dynamicIncludeKeywords(handler = false))
        assertEquals("conflicting action statements: ansible.builtin.file, become_usr", UnknownKeywords.conflictingActions("ansible.builtin.file", "become_usr"))
    }

    @Test
    fun `python int of a string`() {
        assertEquals(BigInteger.valueOf(42), PyNames.parseInt(" 42 "))
        assertEquals(BigInteger.valueOf(-7), PyNames.parseInt("-7"))
        assertEquals(BigInteger.valueOf(10), PyNames.parseInt("1_0"))
        assertEquals(BigInteger.valueOf(2), PyNames.parseInt("+2"))
        for (bad in listOf("", "a", "1e3", "2.5", "1__0", "_1", "1_", "0x1F", "-")) assertNull(PyNames.parseInt(bad), bad)
    }

    @Test
    fun `variable names per version`() {
        val v218 = KeywordSemantics.of(CoreSemantics(CoreVersion(2, 18, 8)))
        val v221 = KeywordSemantics.of(CoreSemantics(CoreVersion(2, 21, 4)))
        fun rejects(name: String, rules: KeywordSemantics) = PyNames.rejectsVariableName(PyValue.Str(name), rules)
        assertFalse(rejects("ok_name", v218))
        assertTrue(rejects("bad-name", v218) && rejects("bad-name", v221))
        assertTrue(rejects("1a", v218) && rejects("1a", v221))
        assertTrue(rejects("for", v218))
        assertFalse(rejects("for", v221))
        assertFalse(rejects("true", v218))
        assertTrue(rejects("true", v221))
        assertTrue(rejects("café", v221), "non-ASCII")
        assertTrue(PyNames.rejectsVariableName(PyValue.Int(BigInteger.ONE), v218), "not a string")
    }
}
