package de.terletzkiy.ansibility.vault.identity

import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.vault.VaultCorpusGuard
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A password source is larger than vault code reads ([VaultSourceAccess.readSecret]); carries no data. */
class VaultSourceTooLargeException : IOException("vault source exceeds the size limit")

/**
 * The only way vault code touches password sources on disk and in the environment.
 *
 * Discovery (before consent, D25) uses only the metadata calls ([exists], [isRegularFile], [isExecutable], [size],
 * [canonical]) and [readNonSecret] for files that hold no secret (`.env.local.skel`). [readSecret] is called only
 * for a source the user consented to, at unlock time: `.env.local` and password files are opened nowhere else.
 *
 * Reads go through `java.nio`, never through the VFS: VFS content of a file may be kept in the IDE's persistent
 * content cache on disk, and a password file must not be copied anywhere (secret rule 4).
 */
interface VaultSourceAccess {
    /** True when anything exists at [path] (a file, a directory, a `.git` link file); a metadata check only. */
    fun exists(path: Path): Boolean

    /** True for an existing regular file (symbolic links followed); a metadata check only. */
    fun isRegularFile(path: Path): Boolean

    /** True when [path] is an executable regular file, which Ansible runs as a password script. */
    fun isExecutable(path: Path): Boolean

    /** The size in bytes, or null when [path] does not exist. */
    fun size(path: Path): Long?

    /** The real path ([Path.toRealPath]: symbolic links resolved), or null when [path] does not exist. */
    fun canonical(path: Path): Path?

    /** Reads a file that holds no secret (at most [limit] bytes), or null when it cannot be read or is larger. */
    fun readNonSecret(path: Path, limit: Int): ByteArray?

    /**
     * Reads a consented secret source (at most [limit] bytes). The caller owns and zeroes the array.
     *
     * @throws IOException when it cannot be read; [VaultSourceTooLargeException] when it is larger than [limit]
     * @throws de.terletzkiy.ansibility.vault.VaultCorpusGuardException for a file of the real infra repo in tests
     */
    fun readSecret(path: Path, limit: Int): ByteArray

    /** The IDE's environment (on macOS the login shell's, as Ansible would see it from a terminal). */
    fun environment(): Map<String, String>

    companion object {
        /** The largest password file or `.env.local` read: Ansible has no limit, real ones are a few bytes. */
        const val SECRET_LIMIT: Int = 64 * 1024

        /** The largest `.env.local.skel` read. */
        const val NON_SECRET_LIMIT: Int = 256 * 1024
    }
}

/** [VaultSourceAccess] on the local file system and `EnvironmentUtil`. */
object LocalVaultSourceAccess : VaultSourceAccess {
    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

    override fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

    override fun size(path: Path): Long? = try {
        Files.size(path)
    } catch (_: IOException) {
        null
    }

    override fun canonical(path: Path): Path? = try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

    override fun readNonSecret(path: Path, limit: Int): ByteArray? = try {
        if (!Files.isRegularFile(path)) null else readCapped(path, limit)
    } catch (_: IOException) {
        null
    }

    override fun readSecret(path: Path, limit: Int): ByteArray {
        VaultCorpusGuard.check(path)
        if (!Files.isRegularFile(path)) throw IOException("not a regular file")
        return readCapped(path, limit)
    }

    override fun environment(): Map<String, String> = EnvironmentUtil.getEnvironmentMap()

    private fun readCapped(path: Path, limit: Int): ByteArray {
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) {
                bytes.fill(0)
                throw VaultSourceTooLargeException()
            }
            return bytes
        }
    }
}
