package de.terletzkiy.ansibility.semantics.inventory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

class HostRangesTest {
    @Test
    fun `numeric ranges keep the zero padding of the begin value`() {
        assertEquals((1..10).map { "web%02d".format(it) }, HostRanges.expand("web[01:10]"))
        assertEquals(listOf("db8", "db9", "db10"), HostRanges.expand("db[8:10]"))
        assertEquals(listOf("n0", "n1", "n2"), HostRanges.expand("n[:2]"))
    }

    @Test
    fun `alphabetic ranges, strides and several ranges`() {
        assertEquals(listOf("db-a", "db-b", "db-c"), HostRanges.expand("db-[a:c]"))
        assertEquals(listOf("s1", "s4", "s7", "s10"), HostRanges.expand("s[1:10:3]"))
        assertEquals(listOf("xa", "xc", "xe"), HostRanges.expand("x[a:e:2]"))
        assertEquals(listOf("m1-x", "m1-y", "m2-x", "m2-y"), HostRanges.expand("m[1:2]-[x:y]"))
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), HostRanges.expand("10.0.0.[1:2]"))
        assertEquals(listOf("y", "z", "A"), HostRanges.expand("[y:A]"), "ascii_letters runs from a to Z")
    }

    @Test
    fun `invalid ranges raise with ansible-core's messages`() {
        assertEquals("host range must specify end value", assertThrows<HostRangeException> { HostRanges.expand("w[1:]") }.message)
        assertEquals(
            "host range must specify equal-length begin and end formats",
            assertThrows<HostRangeException> { HostRanges.expand("w[01:100]") }.message,
        )
        assertEquals("host range must have begin <= end", assertThrows<HostRangeException> { HostRanges.expand("w[c:a]") }.message)
        assertEquals("host range must be begin:end or begin:end:step", assertThrows<HostRangeException> { HostRanges.expand("w[1]") }.message)
        assertThrows<HostRangeException> { HostRanges.expand("w[1:3:0]") }
        assertThrows<HostRangeException> { HostRanges.expand("w[1:x]") }
        assertThrows<HostRangeException> { HostRanges.expand("w[1:99999999]") }
    }

    @Test
    fun `negative and descending numeric ranges are empty like Python's range`() {
        assertEquals(emptyList<String>(), HostRanges.expand("w[5:1]"))
    }

    @Test
    fun `host patterns split off a valid port and expand ranges`() {
        assertEquals(HostRanges.Expansion(listOf("db"), BigInteger.valueOf(2222)), HostRanges.expandHostPattern("db:2222"))
        assertEquals(HostRanges.Expansion(listOf("web1", "web2"), BigInteger.valueOf(22)), HostRanges.expandHostPattern("web[1:2]:22"))
        assertEquals(HostRanges.Expansion(listOf("::1"), BigInteger.valueOf(22)), HostRanges.expandHostPattern("[::1]:22"))
        assertEquals(HostRanges.Expansion(listOf("preview-dev1.bike.example.test"), null), HostRanges.expandHostPattern("preview-dev1.bike.example.test"))
        // Not a valid host identifier: kept as written, no port.
        assertEquals(HostRanges.Expansion(listOf("weird name:22"), null), HostRanges.expandHostPattern("weird name:22"))
    }

    @Test
    fun `parse_address recognises hostnames and addresses`() {
        assertEquals(HostAddress.Parsed("db", BigInteger.valueOf(2222)), HostAddress.parse("db:2222"))
        assertEquals(HostAddress.Parsed("2001:db8::1", null), HostAddress.parse("2001:db8::1"))
        assertEquals(HostAddress.Parsed("192.0.2.1", BigInteger.valueOf(22)), HostAddress.parse("192.0.2.1:22"))
        assertNull(HostAddress.parse("web[1:3]"), "ranges need allowRanges")
        assertEquals("web[1:3]", HostAddress.parse("web[1:3]", allowRanges = true)?.host)
        assertNull(HostAddress.parse("-leading-dash"))
        assertNull(HostAddress.parse("trailing_"))
        assertNull(HostAddress.parse("database:replisync"))
        assertTrue(HostAddress.isIpAddress("10.0.0.1"))
        assertTrue(HostAddress.isIpAddress("::1"))
        assertFalse(HostAddress.isIpAddress("prod-prod1"))
    }
}
