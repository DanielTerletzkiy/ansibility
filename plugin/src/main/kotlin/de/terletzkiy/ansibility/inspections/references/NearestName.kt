package de.terletzkiy.ansibility.inspections.references

/**
 * Picks the existing name a misspelt reference most likely meant, for "Change to '…'" (ANS-R001): the candidate
 * sharing the longest prefix with the value, then the smallest edit distance ([distance]: Levenshtein with adjacent
 * transpositions, so `wbe` → `web` is one edit), then the shorter name. A
 * candidate qualifies when it shares a prefix of at least [MIN_PREFIX] characters, or when its distance is at most a
 * third of the value's length (at least 1). `configure` → `configuration` (prefix `configur`).
 */
object NearestName {
    private const val MIN_PREFIX = 3

    /** The best candidate for [value], or null when none is close enough (the value itself never counts). */
    fun of(value: String, candidates: Collection<String>): String? {
        val maxDistance = maxOf(1, value.length / 3)
        return candidates.asSequence()
            .filter { it.isNotEmpty() && it != value }
            .map { Scored(it, commonPrefix(value, it), distance(value, it)) }
            .filter { it.prefix >= minOf(MIN_PREFIX, value.length) && it.prefix > 0 || it.distance <= maxDistance }
            .sortedWith(compareByDescending<Scored> { it.prefix }.thenBy { it.distance }.thenBy { it.name.length }.thenBy { it.name })
            .firstOrNull()?.name
    }

    private data class Scored(val name: String, val prefix: Int, val distance: Int)

    /** The length of the common prefix of [a] and [b], ignoring case. */
    fun commonPrefix(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i].equals(b[i], ignoreCase = true)) i++
        return i
    }

    /**
     * The edit distance of [a] and [b]: insertions, deletions, substitutions and transpositions of adjacent characters
     * each cost 1 (optimal string alignment).
     */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, d[i - 2][j - 2] + 1)
                d[i][j] = best
            }
        }
        return d[a.length][b.length]
    }
}
