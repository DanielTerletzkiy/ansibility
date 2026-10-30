package de.terletzkiy.ansibility.vault.reencrypt

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Whole-file replacement for Rotate Vault Password… (plan amendment R29, D210, D211; review, decision 6): the new bytes
 * go to a temporary file in the same directory (created 0600, flushed to disk), which gets the mode the file should
 * have and is then moved over the file's real path with `ATOMIC_MOVE`. The file is therefore never empty or half
 * written, whatever fails (a full disk, a crash), and a symbolic link stays a link. A temporary file left behind by a
 * crash between write and move is named [TEMP_PREFIX]`…`[TEMP_SUFFIX] and removed by [removeLeftovers] (decision 17).
 * Blocking; never on the EDT for large files, and never under a read lock.
 */
internal object AtomicFiles {
    /** The name prefix of the temporary files (hidden on POSIX). */
    const val TEMP_PREFIX: String = ".ansibility-rotate-"
    const val TEMP_SUFFIX: String = ".tmp"

    /** rw------- */
    val PRIVATE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /**
     * Replaces the file at [path] (its real path when it is a link) with [bytes], atomically. [mode] is the POSIX mode
     * the file gets; null keeps the mode it has (a file system without POSIX modes keeps its defaults). Throws an
     * [IOException] (or [UnsupportedOperationException] where no atomic move exists) when nothing was replaced.
     */
    fun replace(path: Path, bytes: ByteArray, mode: Set<PosixFilePermission>? = null) {
        val real = if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
        val directory = real.parent ?: throw IOException("no directory")
        val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
        val wanted = if (!posix) null else mode ?: if (Files.exists(real, LinkOption.NOFOLLOW_LINKS)) Files.getPosixFilePermissions(real) else PRIVATE
        var temporary: Path? = null
        try {
            temporary = if (posix) {
                Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX, PosixFilePermissions.asFileAttribute(PRIVATE))
            } else {
                Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX)
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (wanted != null) Files.setPosixFilePermissions(temporary, wanted)
            Files.move(temporary, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** True for the name of a temporary file of [replace]. */
    fun isLeftover(name: String): Boolean = name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)

    /**
     * Removes the temporary files of [replace] that a crash left in [directory] (not below it): a password file's may
     * hold a new password in plain text, inside the repository. Only files older than [LEFTOVER_AGE_MILLIS] go (review
     * round 2, decision 9): a younger one may belong to a write in progress. Errors are ignored (the next rotation or
     * unlock tries again). Returns how many were removed.
     */
    fun removeLeftovers(directory: Path): Int {
        if (!Files.isDirectory(directory)) return 0
        var removed = 0
        try {
            Files.newDirectoryStream(directory) { isLeftover(it.fileName.toString()) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.use { stream ->
                for (leftover in stream) if (removeIfOld(leftover)) removed++
            }
        } catch (_: IOException) {
            // Unreadable directory: nothing to clean here.
        }
        return removed
    }

    /** Removes the leftover temporary file [path] when it is older than [LEFTOVER_AGE_MILLIS]; true when it was removed. */
    fun removeIfOld(path: Path): Boolean = runCatching {
        val modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        System.currentTimeMillis() - modified >= LEFTOVER_AGE_MILLIS && Files.deleteIfExists(path)
    }.getOrDefault(false)

    /** How old a temporary file must be before it counts as left over by a crash (review round 2, decision 9). */
    const val LEFTOVER_AGE_MILLIS: Long = 60_000L
}
