package de.terletzkiy.ansibility.semantics.value

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.YearMonth
import java.util.Locale

/**
 * The value PyYAML's `construct_yaml_timestamp` builds: a `datetime.date` (no time part) or a
 * `datetime.datetime`, naive or with a fixed UTC offset. The fraction is truncated or padded to microseconds.
 */
data class PyTimestamp(
    val year: Int,
    val month: Int,
    val day: Int,
    /** Null for a `date`. */
    val hour: Int? = null,
    val minute: Int = 0,
    val second: Int = 0,
    val microsecond: Int = 0,
    /** UTC offset in seconds for an aware datetime, null for a naive one (or a date). */
    val offsetSeconds: Int? = null,
) {
    val isDateTime: Boolean get() = hour != null

    /** Python `str()`: `2024-01-01`, `2001-12-14 21:59:43.100000-05:00`. */
    fun pyStr(): String = format(' ')

    /** Python `isoformat()`: like [pyStr] with a `T` separator. */
    fun isoFormat(): String = format('T')

    /** Python `repr()`: `datetime.date(2024, 1, 1)`, `datetime.datetime(2001, 12, 14, 21, 59, 43, 100000, tzinfo=…)`. */
    fun pyRepr(): String {
        if (hour == null) return "datetime.date($year, $month, $day)"
        val fields = mutableListOf(year, month, day, hour, minute, second, microsecond)
        if (fields.last() == 0) fields.removeAt(fields.lastIndex)
        if (fields.last() == 0) fields.removeAt(fields.lastIndex)
        val tz = offsetSeconds?.let { ", tzinfo=${timezoneRepr(it)}" } ?: ""
        return "datetime.datetime(${fields.joinToString(", ")}$tz)"
    }

    /** Python `==` between two dates/datetimes (aware datetimes compare as instants; mixed kinds are unequal). */
    fun pyEquals(other: PyTimestamp): Boolean {
        if (isDateTime != other.isDateTime) return false
        if (!isDateTime) return year == other.year && month == other.month && day == other.day
        if ((offsetSeconds == null) != (other.offsetSeconds == null)) return false
        return epochMicros() == other.epochMicros()
    }

    /** A hash consistent with [pyEquals]. */
    fun pyHash(): Int = if (!isDateTime) (year * 400 + month) * 32 + day else epochMicros().hashCode()

    private fun epochMicros(): Long {
        val local = LocalDateTime.of(year, month, day, hour ?: 0, minute, second)
        val seconds = local.toEpochSecond(ZoneOffset.UTC) - (offsetSeconds ?: 0)
        return seconds * 1_000_000 + microsecond
    }

    private fun format(separator: Char): String {
        val date = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
        if (hour == null) return date
        val time = buildString {
            append(String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second))
            if (microsecond != 0) append(String.format(Locale.ROOT, ".%06d", microsecond))
            offsetSeconds?.let { append(offsetText(it)) }
        }
        return "$date$separator$time"
    }

    companion object {
        private val PATTERN = Regex(
            "^(?<year>[0-9][0-9][0-9][0-9])-(?<month>[0-9][0-9]?)-(?<day>[0-9][0-9]?)" +
                "(?:(?:[Tt]|[ \\t]+)(?<hour>[0-9][0-9]?):(?<minute>[0-9][0-9]):(?<second>[0-9][0-9])" +
                "(?:\\.(?<fraction>[0-9]*))?" +
                "(?:[ \\t]*(?<tz>Z|(?<tzsign>[-+])(?<tzhour>[0-9][0-9]?)(?::(?<tzminute>[0-9][0-9]))?))?)?$",
        )

        /**
         * Port of PyYAML's `construct_yaml_timestamp`. Returns null when the text does not match the timestamp
         * pattern or Python's `datetime` refuses the fields (month 13, 30 February, hour 24, an offset of a day or
         * more); PyYAML then fails to load the file.
         */
        fun parse(text: String): PyTimestamp? {
            val match = PATTERN.matchEntire(text) ?: return null
            val groups = match.groups
            fun group(name: String): String? = groups[name]?.value?.takeIf { it.isNotEmpty() }
            val year = group("year")!!.toInt()
            val month = group("month")!!.toInt()
            val day = group("day")!!.toInt()
            if (year < 1 || month !in 1..12) return null
            if (day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) return null
            val hourText = group("hour") ?: return PyTimestamp(year, month, day)
            val hour = hourText.toInt()
            val minute = group("minute")!!.toInt()
            val second = group("second")!!.toInt()
            if (hour > 23 || minute > 59 || second > 59) return null
            val fraction = group("fraction")?.take(6)?.padEnd(6, '0')?.toInt() ?: 0
            val offset = when {
                group("tzsign") != null -> {
                    val magnitude = group("tzhour")!!.toInt() * 3600 + (group("tzminute")?.toInt() ?: 0) * 60
                    if (group("tzsign") == "-") -magnitude else magnitude
                }
                group("tz") != null -> 0
                else -> null
            }
            if (offset != null && (offset <= -86_400 || offset >= 86_400)) return null
            return PyTimestamp(year, month, day, hour, minute, second, fraction, offset)
        }

        /** `utcoffset` as `str()`/`isoformat()` print it: `+05:30`, `-05:00`, `+00:00`. */
        private fun offsetText(offset: Int): String {
            val sign = if (offset < 0) '-' else '+'
            val magnitude = kotlin.math.abs(offset)
            return String.format(Locale.ROOT, "%c%02d:%02d", sign, magnitude / 3600, magnitude % 3600 / 60)
        }

        /** `repr(datetime.timezone(timedelta(seconds=offset)))`; a zero offset is the `utc` singleton. */
        private fun timezoneRepr(offset: Int): String {
            if (offset == 0) return "datetime.timezone.utc"
            val days = Math.floorDiv(offset, 86_400)
            val seconds = offset - days * 86_400
            val parts = buildList {
                if (days != 0) add("days=$days")
                if (seconds != 0) add("seconds=$seconds")
            }
            return "datetime.timezone(datetime.timedelta(${parts.joinToString(", ")}))"
        }
    }
}
