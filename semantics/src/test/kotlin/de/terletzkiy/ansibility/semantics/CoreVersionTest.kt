package de.terletzkiy.ansibility.semantics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreVersionTest {
    @Test
    fun `parses and orders versions`() {
        assertEquals(CoreVersion(2, 18, 8), CoreVersion.parse("2.18.8"))
        assertEquals(CoreVersion(2, 21, 4), CoreVersion.parse("2.21.4rc1"))
        assertTrue(CoreVersion.parse("2.18.8")!! < CoreVersion.parse("2.21.4")!!)
        assertNull(CoreVersion.parse("latest"))
    }
}
