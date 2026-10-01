package de.terletzkiy.ansibility.semantics

/** An ansible-core version, compared numerically ("2.18.8" < "2.21.4"). */
data class CoreVersion(val major: Int, val minor: Int, val patch: Int = 0) : Comparable<CoreVersion> {
    override fun compareTo(other: CoreVersion): Int =
        compareValuesBy(this, other, CoreVersion::major, CoreVersion::minor, CoreVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** The version pinned by every root's Docker images in the target repo. */
        val PINNED = CoreVersion(2, 18, 8)

        fun parse(text: String): CoreVersion? {
            val parts = text.trim().split('.').map { it.takeWhile(Char::isDigit) }
            if (parts.size < 2 || parts.any { it.isEmpty() }) return null
            return CoreVersion(parts[0].toInt(), parts[1].toInt(), parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }
}
