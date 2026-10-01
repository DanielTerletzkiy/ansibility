package de.terletzkiy.ansibility.inspections.types

import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PSI-free parts of [SpecEdits]: plain-scalar safety, quoting and the type a value suggests for a new option. */
class SpecEditsTest {
    private fun plain(text: String) = YScalar(text, ScalarStyle.PLAIN)

    private fun quoted(text: String) = YScalar(text, ScalarStyle.DOUBLE_QUOTED)

    @Test
    fun safePlainScalarsLoadAsTheSameString() {
        for (text in listOf("roundrobin", "static-rr", "access.token.claim", "files/totp/deploy.prod.secret")) assertTrue(text, SpecEdits.isSafePlain(text))
        for (text in listOf("", " a", "yes", "1", "3.10", "null", "2024-01-01", "a: b", "a #b", "-x", "*a", "!t", "a:")) assertFalse(text, SpecEdits.isSafePlain(text))
    }

    @Test
    fun quotingEscapes() {
        assertEquals("\"3.2\"", SpecEdits.quoted("3.2"))
        assertEquals("\"a\\\"b\\\\c\\n\"", SpecEdits.quoted("a\"b\\c\n"))
        assertEquals("name", SpecEdits.keyText("name"))
        assertEquals("\"yes\"", SpecEdits.keyText("yes"))
    }

    @Test
    fun typesOfValues() {
        assertEquals("str", SpecEdits.typeOf(plain("abc")))
        assertEquals("str", SpecEdits.typeOf(quoted("1")))
        assertEquals("str", SpecEdits.typeOf(plain("2024-01-01")))
        assertEquals("int", SpecEdits.typeOf(plain("444")))
        assertEquals("float", SpecEdits.typeOf(plain("3.2")))
        assertEquals("bool", SpecEdits.typeOf(plain("true")))
        assertEquals("str", SpecEdits.typeOf(YVault()))
        assertEquals("list", SpecEdits.typeOf(YSeq(emptyList())))
        assertEquals("dict", SpecEdits.typeOf(YMap(emptyList())))
        assertNull("templates are unknown", SpecEdits.typeOf(plain("{{ x }}")))
        assertNull(SpecEdits.typeOf(YEmpty()))
        assertNull(SpecEdits.typeOf(plain("~")))
    }

    @Test
    fun joiningTypes() {
        assertEquals("str", SpecEdits.join(listOf("str", null, "str")))
        assertEquals("float", SpecEdits.join(listOf("int", "float")))
        assertEquals("raw", SpecEdits.join(listOf("int", "str")))
        assertEquals("raw", SpecEdits.join(listOf(null)))
        assertEquals("raw", SpecEdits.join(emptyList()))
    }

    @Test
    fun fieldsForANewOption() {
        assertEquals(listOf("type" to "list", "elements" to "str"), SpecEdits.fieldsFor(YSeq(listOf(plain("offline_access")))))
        assertEquals(listOf("type" to "list", "elements" to "dict"), SpecEdits.fieldsFor(YSeq(listOf(YMap(listOf(YEntry(plain("a"), plain("1"))))))))
        assertEquals(listOf("type" to "list"), SpecEdits.fieldsFor(YSeq(listOf(plain("a"), plain("1")))))
        assertEquals(listOf("type" to "list"), SpecEdits.fieldsFor(YSeq(emptyList())))
        assertEquals(listOf("type" to "bool"), SpecEdits.fieldsFor(plain("true")))
        assertEquals(listOf("type" to "raw"), SpecEdits.fieldsFor(null))
    }
}
