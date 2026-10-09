package de.terletzkiy.ansibility.golden.remote

import de.terletzkiy.ansibility.golden.remote.GoldenMirrorCommands.RefKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties

/**
 * What the golden mirror remembers about itself (plan amendment R25), in `.git/ansibility-mirror.properties` of the
 * mirror: how it was cloned (URL, ref, depth, roles path, whether the blob filter applied), how many refresh fetches
 * it took since (a mirror is re-cloned after [RECLONE_AFTER_FETCHES], as shallow fetches pile up objects and there is
 * no gc), and the mirrored commit, so the state is known at once after a restart without running git. The file's
 * modification time is the mirror's last use (clean-up keeps recently used mirrors of other projects).
 */
data class GoldenMirrorMeta(
    /** The effective URL the mirror was cloned from. */
    val url: String,
    /** The ref setting (blank: the default branch). */
    val ref: String,
    val refKind: RefKind,
    /** The branch or tag followed: the default branch's name for a blank [ref]. */
    val branch: String,
    val depth: Int,
    /** The roles-path setting the sparse checkout was made for (blank: automatic). */
    val rolesSetting: String,
    /** The roles path inside the mirror, as resolved; blank: the top level (no sparse checkout). */
    val rolesPath: String,
    val filtered: Boolean,
    val fetches: Int,
    val commit: String?,
    val author: String?,
    val commitInstant: Instant?,
    val subject: String?,
    val fetchedAt: Instant?,
) {
    fun write(mirror: Path) {
        val props = Properties()
        props["url"] = url
        props["ref"] = ref
        props["refKind"] = refKind.name
        props["branch"] = branch
        props["depth"] = depth.toString()
        props["rolesSetting"] = rolesSetting
        props["rolesPath"] = rolesPath
        props["filtered"] = filtered.toString()
        props["fetches"] = fetches.toString()
        commit?.let { props["commit"] = it }
        author?.let { props["author"] = it }
        commitInstant?.let { props["date"] = it.toString() }
        subject?.let { props["subject"] = it }
        fetchedAt?.let { props["fetchedAt"] = it.toString() }
        val file = fileOf(mirror)
        val temp = file.resolveSibling(FILE_NAME + ".tmp")
        Files.newBufferedWriter(temp).use { props.store(it, "Ansibility golden mirror") }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val FILE_NAME: String = "ansibility-mirror.properties"

        /** Re-clone instead of the next fetch after this many refresh fetches. */
        const val RECLONE_AFTER_FETCHES: Int = 50

        fun fileOf(mirror: Path): Path = mirror.resolve(".git").resolve(FILE_NAME)

        /** The metadata of the mirror in [mirror], or null when there is no (complete) mirror there. */
        fun read(mirror: Path): GoldenMirrorMeta? {
            val file = fileOf(mirror)
            if (!Files.isRegularFile(file)) return null
            val props = Properties()
            try {
                Files.newBufferedReader(file).use(props::load)
            } catch (_: IOException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            fun text(key: String): String? = props.getProperty(key)
            fun instant(key: String): Instant? = text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return GoldenMirrorMeta(
                url = text("url") ?: return null,
                ref = text("ref").orEmpty(),
                refKind = runCatching { RefKind.valueOf(text("refKind").orEmpty()) }.getOrDefault(RefKind.BRANCH),
                branch = text("branch").orEmpty(),
                depth = text("depth")?.toIntOrNull() ?: return null,
                rolesSetting = text("rolesSetting").orEmpty(),
                rolesPath = text("rolesPath").orEmpty(),
                filtered = text("filtered").toBoolean(),
                fetches = text("fetches")?.toIntOrNull() ?: 0,
                commit = text("commit")?.takeIf(GoldenGitUrls::isCommit),
                author = text("author"),
                commitInstant = instant("date"),
                subject = text("subject"),
                fetchedAt = instant("fetchedAt"),
            )
        }
    }
}
