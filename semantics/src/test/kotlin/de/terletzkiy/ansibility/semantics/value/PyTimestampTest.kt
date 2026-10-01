package de.terletzkiy.ansibility.semantics.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PyTimestampTest {
    @Test
    fun `dates and datetimes like PyYAML constructs them`() {
        assertEquals(PyTimestamp(2024, 1, 1), PyTimestamp.parse("2024-01-01"))
        assertEquals(PyTimestamp(2001, 12, 15, 2, 59, 43, 100000, 0), PyTimestamp.parse("2001-12-15T02:59:43.1Z"))
        assertEquals(PyTimestamp(2001, 12, 14, 21, 59, 43, 123456), PyTimestamp.parse("2001-12-14 21:59:43.1234567"))
        assertEquals(PyTimestamp(2001, 12, 14, 21, 59, 43, 0, 3600), PyTimestamp.parse("2001-12-14T21:59:43.+01"))
        assertEquals(PyTimestamp(2001, 12, 14, 21, 59, 0, 0, 19800), PyTimestamp.parse("2001-12-14T21:59:00+05:30"))
        assertEquals(PyTimestamp(2001, 12, 15, 2, 59, 43, 100000), PyTimestamp.parse("2001-12-15 2:59:43.10"))
    }

    @Test
    fun `invalid fields make the file unloadable`() {
        assertNull(PyTimestamp.parse("2024-13-01"))
        assertNull(PyTimestamp.parse("2023-02-29"))
        assertNull(PyTimestamp.parse("0000-01-01"))
        assertNull(PyTimestamp.parse("2024-01-01 24:00:00"))
        assertNull(PyTimestamp.parse("2024-01-01 00:00:00 +24"))
        assertEquals(PyTimestamp(2024, 1, 1), PyTimestamp.parse("2024-1-1"), "the constructor is laxer than the resolver")
        assertEquals(PyTimestamp(2024, 2, 29), PyTimestamp.parse("2024-02-29"))
    }

    @Test
    fun `str isoformat and repr`() {
        val aware = PyTimestamp.parse("2001-12-14t21:59:43.10-05:00")!!
        assertEquals("2001-12-14 21:59:43.100000-05:00", aware.pyStr())
        assertEquals("2001-12-14T21:59:43.100000-05:00", aware.isoFormat())
        val utc = PyTimestamp.parse("2024-01-01T00:00:00+00:00")!!
        assertEquals("2024-01-01 00:00:00+00:00", utc.pyStr())
        assertEquals("datetime.datetime(2024, 1, 1, 0, 0, tzinfo=datetime.timezone.utc)", utc.pyRepr())
        assertEquals("datetime.datetime(2001, 12, 14, 21, 59, tzinfo=datetime.timezone(datetime.timedelta(seconds=19800)))",
            PyTimestamp.parse("2001-12-14T21:59:00+05:30")!!.pyRepr())
        assertEquals("datetime.date(2024, 1, 1)", PyTimestamp.parse("2024-01-01")!!.pyRepr())
        assertEquals("0001-01-01", PyTimestamp.parse("0001-01-01")!!.pyStr())
    }
}
