package de.terletzkiy.ansibility.model.drift

import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.context.AnsibleLayout
import java.security.MessageDigest

/**
 * The content identity of one file of a role copy: its path inside the role, its length and the first 128 bits of
 * the SHA-256 of its bytes (plenty to tell a few thousand files apart, and half the memory of the full digest).
 *
 * The hash never leaves this package, is never logged and never persisted: a hash of a short secret file can be
 * brute-forced. [toString] therefore prints `***` instead.
 */
internal class FileEntry private constructor(
    val relPath: String,
    val length: Long,
    private val high: Long,
    private val low: Long,
    private val flags: Int,
) {
    /** Names a secret (see [DriftRules.isSensitivePath]) or holds a whole-file vault. */
    val isSensitive: Boolean get() = flags and SENSITIVE != 0

    /** The content was not read: the identity is the length only (sensitive files in [SensitiveContent.SIZE_ONLY]). */
    val isSizeOnly: Boolean get() = flags and SIZE_ONLY != 0

    /** The content could not be read; such a file never equals another one. */
    val isUnreadable: Boolean get() = flags and UNREADABLE != 0

    /** Whether [other] has the same content (the paths are not compared). */
    fun sameContent(other: FileEntry): Boolean {
        if (isUnreadable || other.isUnreadable) return false
        if (length != other.length || isSizeOnly != other.isSizeOnly) return false
        return isSizeOnly || (high == other.high && low == other.low)
    }

    /** This entry at another path (a renamed or moved file whose content did not change). */
    fun withPath(path: String): FileEntry = if (path == relPath) this else FileEntry(path, length, high, low, flags)

    override fun toString(): String = "FileEntry($relPath, $length bytes, ***)"

    companion object {
        private const val SENSITIVE = 1
        private const val SIZE_ONLY = 2
        private const val UNREADABLE = 4

        /** Hashes [bytes]; [sensitiveName] comes from [DriftRules.isSensitivePath], a vault header is detected here. */
        fun of(relPath: String, bytes: ByteArray, sensitiveName: Boolean): FileEntry {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sensitive = sensitiveName || DriftRules.isWholeFileVault(bytes)
            return FileEntry(relPath, bytes.size.toLong(), long(digest, 0), long(digest, 8), if (sensitive) SENSITIVE else 0)
        }

        /** A sensitive file identified by its length only; its content was never read. */
        fun sizeOnly(relPath: String, length: Long): FileEntry = FileEntry(relPath, length, 0, 0, SENSITIVE or SIZE_ONLY)

        /** A file that could not be read. */
        fun unreadable(relPath: String, length: Long, sensitiveName: Boolean): FileEntry =
            FileEntry(relPath, length, 0, 0, UNREADABLE or (if (sensitiveName) SENSITIVE else 0))

        private fun long(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in offset until offset + 8) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            return value
        }
    }
}

/** How the content of sensitive files is treated while fingerprinting. */
internal enum class SensitiveContent {
    /** Hash it like any other file (the default): differences are detected, the content is never kept or shown. */
    HASH,

    /** Never read it: the identity is the length only. Corpus tests on the real infra repo use this. */
    SIZE_ONLY,
}

/** One copy's evaluation against the reference, before it is attached to a [de.terletzkiy.ansibility.model.role.RoleCopy]. */
internal data class CopyEvaluation(val tier: DriftTier, val paths: DriftPaths, val variant: Int, val fileCount: Int)

/**
 * The pure rules of role drift (plan amendment R9, F9.5): the skip list, the sensitive-name rule, path categories,
 * tiers, the comparison of two fingerprints and the variant grouping. No VFS, no PSI.
 */
object DriftRules {
    private const val MOLECULE = "molecule"
    private val SPEC_FILES: Set<String> = setOf("meta/argument_specs.yml", "meta/argument_specs.yaml")
    private val SPEC_DIRS: List<String> = listOf("defaults/", "vars/")
    private val SKIPPED_FILES: Set<String> = setOf(".DS_Store")
    private const val SKIPPED_EXTENSION = ".pyc"

    /** Extensions of key material and secrets (the fixture sanitiser's "never copied" list). */
    private val SENSITIVE_EXTENSIONS: Set<String> =
        setOf("key", "pem", "crt", "password", "p12", "pfx", "jks", "keystore")
    private val SENSITIVE_NAMES: Set<String> = setOf(".vault-pass", ".initial-root-pass")
    private val SENSITIVE_DIRS: Set<String> = setOf("ssl", "ssh")
    private val VAULT_MAGIC: ByteArray = VaultHeaderInfo.MAGIC.toByteArray(Charsets.US_ASCII)

    /** Directories never walked: [AnsibleLayout.SKIPPED_DIRS] (`__pycache__`, `.git`, `.ansible`, …). */
    fun isSkippedDirectory(name: String): Boolean = name in AnsibleLayout.SKIPPED_DIRS

    /** Files never fingerprinted: `.DS_Store` and compiled Python (`*.pyc`). */
    fun isSkippedFile(name: String): Boolean = name in SKIPPED_FILES || name.endsWith(SKIPPED_EXTENSION)

    /**
     * Whether the path (relative to the role directory) names key material or a secret: `*.key`, `*.pem`, `*.crt`,
     * `*.password`, keystores, `.env*`, `.vault-pass`, names starting with `vault`, and anything below a `files/ssl`
     * or `files/ssh` directory (also inside molecule scenarios). Such files are fingerprinted, never shown.
     */
    fun isSensitivePath(relPath: String): Boolean {
        val segments = relPath.split('/')
        val name = segments.last().lowercase()
        if (name in SENSITIVE_NAMES || name.startsWith(".env") || name.startsWith("vault")) return true
        if (name.substringAfterLast('.', "") in SENSITIVE_EXTENSIONS) return true
        return (0 until segments.size - 2).any { segments[it] == "files" && segments[it + 1] in SENSITIVE_DIRS }
    }

    /** Whether [bytes] are a whole-file vault (they start with `$ANSIBLE_VAULT`, as ansible-vault's `is_encrypted`). */
    fun isWholeFileVault(bytes: ByteArray): Boolean {
        if (bytes.size < VAULT_MAGIC.size) return false
        return VAULT_MAGIC.indices.all { bytes[it] == VAULT_MAGIC[it] }
    }

    /** The category of a path relative to the role directory. */
    fun categoryOf(relPath: String): DriftCategory = when {
        relPath == MOLECULE || relPath.startsWith("$MOLECULE/") -> DriftCategory.MOLECULE
        relPath in SPEC_FILES || SPEC_DIRS.any(relPath::startsWith) -> DriftCategory.SPEC_DEFAULTS
        else -> DriftCategory.BEHAVIOUR
    }

    /**
     * The tier of a copy whose differing paths are [differing]: [DriftTier.IDENTICAL] for none, else the tier of the
     * highest category (so spec plus molecule changes stay [DriftTier.SPEC_DEFAULTS]).
     */
    fun tierOf(differing: Collection<String>): DriftTier =
        differing.maxOfOrNull { categoryOf(it) }?.tier ?: DriftTier.IDENTICAL

    /** The paths in which [second] differs from [first]; both lists are sorted by path. */
    internal fun compare(first: List<FileEntry>, second: List<FileEntry>): DriftPaths {
        val a = first.associateBy { it.relPath }
        val b = second.associateBy { it.relPath }
        val changed = ArrayList<String>()
        val sensitive = HashSet<String>()
        for ((path, left) in a) {
            val right = b[path] ?: continue
            if (!left.sameContent(right)) {
                changed += path
                if (left.isSensitive || right.isSensitive) sensitive += path
            }
        }
        val onlyFirst = a.keys.filter { it !in b }
        val onlySecond = b.keys.filter { it !in a }
        onlyFirst.filterTo(sensitive) { a.getValue(it).isSensitive }
        onlySecond.filterTo(sensitive) { b.getValue(it).isSensitive }
        return DriftPaths(changed.sorted(), onlyFirst.sorted(), onlySecond.sorted(), sensitive)
    }

    /** Whether the two fingerprints are byte-identical: the same paths, each with the same content. */
    internal fun identical(first: List<FileEntry>, second: List<FileEntry>): Boolean {
        if (first.size != second.size) return false
        val b = second.associateBy { it.relPath }
        return first.all { left -> b[left.relPath]?.let(left::sameContent) == true }
    }

    /** The variant index of each fingerprint: equal fingerprints share one, numbered by first appearance. */
    internal fun variants(fingerprints: List<List<FileEntry>>): List<Int> {
        val representatives = ArrayList<List<FileEntry>>()
        return fingerprints.map { fingerprint ->
            val existing = representatives.indexOfFirst { identical(it, fingerprint) }
            if (existing >= 0) existing else representatives.size.also { representatives += fingerprint }
        }
    }

    /** [fingerprint] as the comparison sees it under [options]. */
    internal fun effective(fingerprint: List<FileEntry>, options: DriftOptions): List<FileEntry> =
        if (options.ignoreMolecule) fingerprint.filter { categoryOf(it.relPath) != DriftCategory.MOLECULE } else fingerprint

    /**
     * Evaluates the copies of one role name: [fingerprints] in catalog order, [referenceIndex] the position of the
     * reference copy or null. The reference's variant is variant 0 because it comes first.
     */
    internal fun evaluate(fingerprints: List<List<FileEntry>>, referenceIndex: Int?, options: DriftOptions): List<CopyEvaluation> {
        val effective = fingerprints.map { effective(it, options) }
        val variants = variants(effective)
        val reference = referenceIndex?.let(effective::get)
        return effective.mapIndexed { index, entries ->
            val paths = if (reference == null || index == referenceIndex) DriftPaths.NONE else compare(reference, entries)
            val tier = when {
                reference == null -> DriftTier.NO_REFERENCE
                index == referenceIndex -> DriftTier.REFERENCE
                else -> tierOf(paths.all)
            }
            CopyEvaluation(tier, paths, variants[index], entries.size)
        }
    }
}
