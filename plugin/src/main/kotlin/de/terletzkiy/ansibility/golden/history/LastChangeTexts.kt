package de.terletzkiy.ansibility.golden.history

import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.model.drift.DriftTexts
import org.jetbrains.annotations.Nls

/**
 * How a [LastChange] reads (plan amendment R24, D180/D183, with R25's golden mirror, D200/X127): exact commits as
 * before; the fetched commit of a depth-1 mirror labelled "fetched commit"; a path the fetched history never changed
 * as "older than the fetched history". Facts only, never "outdated".
 */
object LastChangeTexts {
    /** The details' line (D180): `golden: 2026-09-12 · alice · fix verify`, `golden: fetched commit · 2026-09-12 · …`. */
    @Nls
    fun line(rootName: String, change: LastChange): String = when (change.kind) {
        LastChange.Kind.COMMIT -> DriftTexts.lastChange(rootName, change.date, change.author, change.subject)
        LastChange.Kind.FETCHED_COMMIT -> DriftTexts.lastChange(rootName, change.date, change.author, change.subject, message("lastChange.fetched"))
        LastChange.Kind.BEFORE_HISTORY -> message("lastChange.beforeHistory.line", rootName, DriftTexts.date(change.date))
    }

    /**
     * A diff side's or merge panel's title (D183): `golden · 2026-09-12 · alice`, `golden · fetched commit · 2026-09-12
     * · alice`, `golden · older than the fetched history (2026-08-01 or earlier)`.
     */
    @Nls
    fun side(@Nls prefix: String, change: LastChange): String = when (change.kind) {
        LastChange.Kind.COMMIT -> message("compare.side.lastChange", prefix, DriftTexts.date(change.date), change.author)
        LastChange.Kind.FETCHED_COMMIT -> message("lastChange.side.fetched", prefix, DriftTexts.date(change.date), change.author)
        LastChange.Kind.BEFORE_HISTORY -> message("lastChange.side.beforeHistory", prefix, DriftTexts.date(change.date))
    }

    /**
     * Which side changed later (X122), or null when that is not known: with two exact commits as before; when one
     * side is only an upper bound ([LastChange.isUpperBound]: the golden mirror's fetched commit, or older than its
     * fetched history) only when the other side changed after that bound, which says so; never with two bounds.
     */
    @Nls
    fun direction(first: String, firstChange: LastChange, second: String, secondChange: LastChange, file: Boolean): String? {
        val firstBound = firstChange.isUpperBound
        val secondBound = secondChange.isUpperBound
        return when {
            !firstBound && !secondBound -> DriftTexts.direction(first, firstChange.date, second, secondChange.date, file)
            firstBound && secondBound -> null
            else -> {
                val (exactName, exact) = if (firstBound) second to secondChange else first to firstChange
                val (boundName, bound) = if (firstBound) first to firstChange else second to secondChange
                if (!exact.date.isAfter(bound.date)) return null
                message(if (file) "lastChange.direction.file" else "lastChange.direction.role", exactName, DriftTexts.date(exact.date), boundName, DriftTexts.date(bound.date))
            }
        }
    }
}
