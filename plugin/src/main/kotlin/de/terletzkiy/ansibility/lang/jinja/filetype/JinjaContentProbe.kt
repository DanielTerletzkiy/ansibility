package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Whether a file without `.j2` contains Jinja (plan A.9): the raw bytes of its first 64 KB contain `{{`, `{%` or `{#`.
 * No charset detection is needed, since the markers are ASCII in every encoding the repos use.
 *
 * The result is cached on the [VirtualFile] and keyed by its modification stamp and length, so the file type overrider
 * reads a file at most once per change.
 */
object JinjaContentProbe {
    /** How much of a file is read. */
    const val LIMIT: Int = 64 * 1024

    private class Probe(val stamp: Long, val length: Long, val result: Boolean)

    private val PROBE = Key.create<Probe>("ansibility.jinja.contentProbe")

    /** Whether [file] contains Jinja markers, from the cache when the file did not change since the last probe. */
    fun containsJinja(file: VirtualFile): Boolean {
        val stamp = file.modificationStamp
        val length = file.length
        file.getUserData(PROBE)?.let { if (it.stamp == stamp && it.length == length) return it.result }
        val result = probe(file)
        file.putUserData(PROBE, Probe(stamp, length, result))
        return result
    }

    /**
     * Re-probes [file] after a content change; true when the answer differs from the cached one. A file never probed
     * before reports false: no file type was derived from it, so nothing has to be refreshed.
     */
    fun refresh(file: VirtualFile): Boolean {
        val previous = file.getUserData(PROBE) ?: return false
        val result = probe(file)
        file.putUserData(PROBE, Probe(file.modificationStamp, file.length, result))
        return result != previous.result
    }

    /** Reads up to [LIMIT] bytes of [file] and looks for the markers; false for unreadable files. */
    private fun probe(file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory) return false
        return try {
            file.inputStream.use { input ->
                val buffer = ByteArray(LIMIT)
                var read = 0
                while (read < LIMIT) {
                    val n = input.read(buffer, read, LIMIT - read)
                    if (n < 0) break
                    read += n
                }
                hasMarkers(buffer, read)
            }
        } catch (_: IOException) {
            false
        }
    }

    /** Whether the first [length] bytes of [bytes] contain `{{`, `{%` or `{#`. */
    fun hasMarkers(bytes: ByteArray, length: Int = bytes.size): Boolean {
        for (i in 0 until length - 1) {
            if (bytes[i] != OPEN_BRACE) continue
            val next = bytes[i + 1]
            if (next == OPEN_BRACE || next == PERCENT || next == HASH) return true
        }
        return false
    }

    private const val OPEN_BRACE = '{'.code.toByte()
    private const val PERCENT = '%'.code.toByte()
    private const val HASH = '#'.code.toByte()
}
