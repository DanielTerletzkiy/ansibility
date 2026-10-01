package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.CoreVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreSemanticsTest {
    private fun of(text: String) = CoreSemantics(CoreVersion.parse(text)!!)

    @Test
    fun `pinned target is 2_18_8 with classic semantics`() {
        val pinned = CoreSemantics.PINNED
        assertEquals(CoreVersion(2, 18, 8), pinned.version)
        assertTrue(pinned.strRejectsNone)
        assertTrue(pinned.stringifyNonBareTemplates)
        assertFalse(pinned.conditionalsMustBeBool)
        assertTrue(pinned.elementsRequireListType)
        assertFalse(pinned.jsonargEncodesDates)
        assertFalse(pinned.booleanToleratesUnhashable)
        assertFalse(pinned.nativeTypeNamesInMessages)
    }

    @Test
    fun `boundaries follow the measured sources`() {
        val v2190 = of("2.19.0")
        assertTrue(v2190.strRejectsNone, "2.19.0 still raises for check_type_str(None)")
        assertFalse(v2190.stringifyNonBareTemplates)
        assertTrue(v2190.conditionalsMustBeBool)
        assertFalse(v2190.elementsRequireListType)
        assertTrue(v2190.jsonargEncodesDates)
        assertFalse(of("2.19.1").strRejectsNone)
        val v2214 = of("2.21.4")
        assertFalse(v2214.strRejectsNone)
        assertFalse(v2214.stringifyNonBareTemplates)
        assertTrue(v2214.conditionalsMustBeBool)
        assertTrue(v2214.booleanToleratesUnhashable)
        assertTrue(v2214.nativeTypeNamesInMessages)
        assertTrue(of("2.17.0").strRejectsNone)
    }
}
