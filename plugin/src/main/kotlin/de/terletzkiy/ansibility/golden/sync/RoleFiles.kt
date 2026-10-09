package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.model.drift.DriftCategory
import de.terletzkiy.ansibility.model.drift.DriftRules
import de.terletzkiy.ansibility.semantics.secrets.PrivateKeySignatures
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The file rules the golden engine shares with role drift (plan amendment R24, D191; R9's F9.5): which files of a role
 * copy take part, what bytes a file holds (an unsaved document counts as saving would write it), and whether it is
 * text. No hashes, nothing logged but paths and error classes.
 */
internal object RoleFiles {
    private val LOG = logger<RoleFiles>()

    /** The UTF-8 byte order mark (the platform's `CharsetToolkit` is internal API). */
    val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * The bytes read to tell a file's nature: a whole-file vault, a private key (`PrivateKeySignatures` scans this
     * window of text) and, from its first [SNIFF] bytes, binary content.
     */
    const val HEAD: Int = PrivateKeySignatures.TEXT_WINDOW

    /** The bytes a binary sniff looks at (as git does: a NUL byte or invalid text in the first 8 KB). */
    const val SNIFF: Int = 8 * 1024

    private const val CHUNK = 64 * 1024

    /** Read counters (plan amendment R24, P5): tests compare deltas. Never a byte or a hash, counts only. */
    object Counters {
        /** Whole files read into memory ([bytes]). */
        val fullReads = AtomicLong()

        /** Bytes read to compare two files of the same length ([Content.sameBytes]). */
        val comparedBytes = AtomicLong()

        /** Bytes read as heads ([Content.head]). */
        val headBytes = AtomicLong()
    }

    /**
     * Whether a directory named [name] is never walked or written: [DriftRules.isSkippedDirectory] (`.git`,
     * `__pycache__`, …).
     */
    fun isSkippedDirectoryName(name: String): Boolean = DriftRules.isSkippedDirectory(name) || name == GIT

    /** Whether a file named [name] is never compared or written: [DriftRules.isSkippedFile] and a `.git` file. */
    fun isSkippedFileName(name: String): Boolean = DriftRules.isSkippedFile(name) || name == GIT

    /** Whether [file] is a symbolic link (to a file or a directory, dangling or not). */
    fun isLink(file: VirtualFile): Boolean {
        if (file.`is`(VFileProperty.SYMLINK)) return true
        val path = file.toNioPathOrNull() ?: return false
        return Files.isSymbolicLink(path)
    }

    /**
     * The files of a role directory that take part ([files], by path relative to it, sorted) and the paths the walk
     * left out for this side only ([skipped]: an ignored path or a symbolic link, file or directory; a skipped
     * directory's files are not listed). Names skipped everywhere (`.git`, `__pycache__`, …) are not in [skipped]:
     * they are left out on both sides alike.
     */
    class Walk(val files: Map<String, VirtualFile>, val skipped: Set<String>) {
        /** Whether [relPath] or a directory on its way was left out of this side ([skipped]). */
        fun isSkipped(relPath: String): Boolean = skipped.isNotEmpty() && prefixes(relPath).any { it in skipped }
    }

    /** No files at all (a missing directory, or the empty target of a role created by Push). */
    val EMPTY_WALK: Walk = Walk(emptyMap(), emptySet())

    /**
     * Every file of the role directory [dir] that takes part, by path relative to [dir], sorted: drift's skip rules,
     * no symbolic link (neither a linked directory nor a linked file, D191), no ignored path, and without `molecule/`
     * when [ignoreMolecule]. Call in a read action.
     */
    fun walk(dir: VirtualFile, ignored: (VirtualFile) -> Boolean, ignoreMolecule: Boolean): Map<String, VirtualFile> =
        walkWithSkips(dir, ignored, ignoreMolecule).files

    /** [walk] with the paths left out for this side only ([Walk.skipped]). Read action. */
    fun walkWithSkips(dir: VirtualFile, ignored: (VirtualFile) -> Boolean, ignoreMolecule: Boolean): Walk {
        val result = TreeMap<String, VirtualFile>()
        val skipped = HashSet<String>()
        if (!dir.isValid || !dir.isDirectory) return EMPTY_WALK
        fun visit(current: VirtualFile, prefix: String) {
            for (child in current.children.orEmpty()) {
                ProgressManager.checkCanceled()
                val name = child.name
                val relPath = "$prefix$name"
                if (child.isDirectory) {
                    if (isSkippedDirectoryName(name)) continue
                    if (child.`is`(VFileProperty.SYMLINK) || ignored(child)) {
                        skipped += relPath
                        continue
                    }
                    visit(child, "$relPath/")
                } else if (!isSkippedFileName(name)) {
                    if (child.`is`(VFileProperty.SYMLINK) || ignored(child)) {
                        skipped += relPath
                        continue
                    }
                    if (ignoreMolecule && DriftRules.categoryOf(relPath) == DriftCategory.MOLECULE) continue
                    result[relPath] = child
                }
            }
        }
        visit(dir, "")
        return Walk(result, skipped)
    }

    /**
     * Whether [relPath] of the role directory [dir] is one the project ignores, also when it does not exist there:
     * the path itself or a directory on its way matches an ignored path (`AnsibilityProjectSettings` paths, relative
     * to the project directory). False outside the project directory.
     */
    fun isIgnoredPath(project: Project, dir: VirtualFile, relPath: String): Boolean {
        val paths = AnsibilityProjectSettings.getInstance(project).settings.paths
        if (paths.extraIgnoredPaths.isEmpty()) return false
        val base = RootKeys.projectDir(project) ?: return false
        val root = RootKeys.relativePath(base, dir) ?: return false
        return prefixes(relPath).any { prefix -> paths.isIgnored(if (root.isEmpty()) prefix else "$root/$prefix") }
    }

    /** "a", "a/b", "a/b/c" for "a/b/c". */
    fun prefixes(relPath: String): List<String> {
        val result = ArrayList<String>()
        var at = relPath.indexOf('/')
        while (at >= 0) {
            result += relPath.substring(0, at)
            at = relPath.indexOf('/', at + 1)
        }
        result += relPath
        return result
    }

    /**
     * Whether [relPath] below a role directory may be written: no skipped directory or file name on the way
     * ([isSkippedDirectoryName], [isSkippedFileName]); the caller checks ignored paths and symlinks on the real files.
     */
    fun isWritablePath(relPath: String): Boolean {
        val segments = relPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (segments.dropLast(1).any(::isSkippedDirectoryName)) return false
        return !isSkippedFileName(segments.last())
    }

    /** The unsaved document of [file], or null when the file has none (its bytes are on disk). Read action. */
    fun unsavedDocument(file: VirtualFile): Document? {
        val documents = FileDocumentManager.getInstance()
        return if (documents.isFileModified(file)) documents.getCachedDocument(file) else null
    }

    /**
     * The bytes [file] holds as the user sees it: an unsaved document as saving would write it (the file's line
     * separator, charset and byte order mark, like role drift), else the file's bytes. Null when it cannot be read.
     * Read action.
     */
    fun bytes(project: Project, file: VirtualFile): ByteArray? {
        if (!file.isValid) return null
        Counters.fullReads.incrementAndGet()
        unsavedDocument(file)?.let { return encode(project, file, it) }
        return try {
            file.contentsToByteArray(false)
        } catch (e: IOException) {
            LOG.debug("Cannot read ${file.path} (${e.javaClass.simpleName})")
            null
        }
    }

    /** The bytes saving [document] would write: the file's line separator, charset and byte order mark. */
    fun encode(project: Project, file: VirtualFile, document: Document): ByteArray {
        val separator = FileDocumentManager.getInstance().getLineSeparator(file, project)
        val text = StringUtil.convertLineSeparators(document.immutableCharSequence.toString(), separator)
        val body = text.toByteArray(file.charset)
        val bom = file.bom
        return if (bom != null && bom.isNotEmpty()) bom + body else body
    }

    /**
     * One side of a comparison as the user sees it (plan amendment R24, P5): an unsaved document as saving would write
     * it (held in memory), else the file, read only as far as a question needs: [length] reads nothing, [sameBytes]
     * reads two files of the same length in chunks and stops at the first difference, [head] reads the first bytes.
     * Read action.
     */
    class Content(project: Project, val file: VirtualFile) {
        private val encoded: ByteArray? = unsavedDocument(file)?.let { encode(project, file, it) }

        /** The size in bytes (an unsaved document as saving would write it). */
        val length: Long = encoded?.size?.toLong() ?: file.length

        /** The first [limit] bytes (all of them when the file is shorter), or null when the file cannot be read. */
        fun head(limit: Int): ByteArray? {
            encoded?.let { return it.copyOf(minOf(it.size, limit)) }
            return try {
                file.inputStream.use { it.readNBytes(limit) }.also { Counters.headBytes.addAndGet(it.size.toLong()) }
            } catch (e: IOException) {
                LOG.debug("Cannot read ${file.path} (${e.javaClass.simpleName})")
                null
            }
        }

        /** Whether [other] holds exactly the same bytes; false when the lengths differ (nothing read) or a side cannot be read. */
        fun sameBytes(other: Content): Boolean {
            if (length != other.length) return false
            return try {
                open().use { a -> other.open().use { b -> sameStreams(a, b) } }
            } catch (e: IOException) {
                LOG.debug("Cannot compare ${file.path} (${e.javaClass.simpleName})")
                false
            }
        }

        private fun open(): InputStream = encoded?.inputStream() ?: file.inputStream

        private fun sameStreams(a: InputStream, b: InputStream): Boolean {
            val left = ByteArray(CHUNK)
            val right = ByteArray(CHUNK)
            while (true) {
                ProgressManager.checkCanceled()
                val n = a.readNBytes(left, 0, CHUNK)
                val m = b.readNBytes(right, 0, CHUNK)
                Counters.comparedBytes.addAndGet((n + m).toLong())
                if (n != m) return false
                if (n == 0) return true
                if (!left.copyOf(n).contentEquals(right.copyOf(m))) return false
                if (n < CHUNK) return true
            }
        }
    }

    /**
     * Whether [bytes] of [file] are binary: the file type is binary, or the bytes are no valid text (a NUL byte, or not
     * decodable in the file's charset).
     */
    fun isBinary(file: VirtualFile, bytes: ByteArray?): Boolean {
        if (file.fileType.isBinary) return true
        if (bytes == null) return false
        return decodeText(bytes, file.charset) == null
    }

    /**
     * Whether a file whose first bytes are [head] is binary (P5: only the first [SNIFF] bytes are looked at): its file
     * type is binary, or those bytes hold a NUL byte or are no valid text in the file's charset ([complete] false: a
     * character cut at the end of the window is no error). Null [head] (unreadable): false.
     */
    fun looksBinary(file: VirtualFile, head: ByteArray?, complete: Boolean): Boolean {
        if (file.fileType.isBinary) return true
        if (head == null) return false
        val window = if (head.size > SNIFF) head.copyOf(SNIFF) else head
        if (window.any { it == 0.toByte() }) return true
        val charset = file.charset
        val body = if (charset == Charsets.UTF_8 && startsWith(window, UTF8_BOM)) window.copyOfRange(UTF8_BOM.size, window.size) else window
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(body)
        val output = CharBuffer.allocate(body.size * 2 + 16)
        val result = decoder.decode(input, output, complete && head.size <= SNIFF)
        return result.isError
    }

    /**
     * Whether the first bytes [head] of a file of [length] bytes hold a private key `PrivateKeySignatures` reads (R21's
     * rule: complete PEM blocks, OpenSSH, PuTTY, PGP and SSH2 keys, also encrypted ones). Never logged, never kept.
     */
    fun holdsKey(head: ByteArray?, length: Long): Boolean {
        if (head == null || head.isEmpty()) return false
        val text = String(head, Charsets.ISO_8859_1)
        return PrivateKeySignatures.scanText(text, complete = head.size.toLong() >= length).isNotEmpty()
    }

    /**
     * [bytes] as text in [charset] (a leading UTF-8 byte order mark is skipped for UTF-8), or null when they are no
     * valid text: a NUL byte or malformed input.
     */
    fun decodeText(bytes: ByteArray, charset: Charset): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val body = if (charset == Charsets.UTF_8 && startsWith(bytes, UTF8_BOM)) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    /**
     * Whether [file] has its owner's executable bit (POSIX file systems only; false elsewhere, on errors and for a
     * symbolic link, whose target's mode is not this file's).
     */
    fun isExecutable(file: VirtualFile): Boolean {
        val path = file.toNioPathOrNull() ?: return false
        return try {
            PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private const val GIT = ".git"
}
