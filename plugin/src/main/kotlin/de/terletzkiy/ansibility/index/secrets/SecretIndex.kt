package de.terletzkiy.ansibility.index.secrets

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import de.terletzkiy.ansibility.index.IndexIO
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.VersionedListExternalizer
import de.terletzkiy.ansibility.index.vault.VaultIndex
import de.terletzkiy.ansibility.semantics.secrets.KeyFormat
import de.terletzkiy.ansibility.semantics.secrets.KeyProtection
import de.terletzkiy.ansibility.semantics.secrets.PrivateKeySignatures
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import org.jetbrains.yaml.YAMLFileType
import java.io.DataInput
import java.io.DataOutput

/** What one `ansibility.secrets` verdict is about; the index key is its [name]. */
enum class SecretIndexKind {
    /** ANS-V107: an envelope Ansible does not see (`semantics.vault.VaultFileShape`, never [VaultShapeKind.VAULT]). */
    NOT_WHOLE_FILE,

    /** ANS-V108: a private key in the text (`PrivateKeySignatures.scanText`), protected or not. */
    TEXT_KEY,

    /** ANS-V108: a keystore or DER key in a keystore-like file (`PrivateKeySignatures.scanBinary`). */
    BINARY_KEY,

    /**
     * ANS-V108: a `*.key` or `*.password` file below a `files` directory that is neither a vault nor holds a key the
     * signatures read, is not blank and holds more than password hashes (`semantics.secrets.PasswordHashes`). Whether
     * its name is key-like (`semantics.secrets.KeyLikeNames`, below the content root) is confirmed at query time.
     */
    KEY_LIKE_NAME,
}

/**
 * One verdict of `ansibility.secrets` (plan amendment R21, D164): what was found, where, and how it is protected.
 * Never any content: no key body, no envelope, no line text; [toString] prints the fields only.
 */
data class SecretVerdict(
    val kind: SecretIndexKind,
    /** For [SecretIndexKind.NOT_WHOLE_FILE]: the shape. */
    val shape: VaultShapeKind? = null,
    /** For keys: the format. */
    val format: KeyFormat? = null,
    /** For keys: the protection. */
    val protection: KeyProtection? = null,
    /** The 0-based line of the key's BEGIN marker or the envelope's tag or header line; -1 for none (binary, names). */
    val line: Int = -1,
    /** For text keys: written with `\n` escapes inside a string (a JSON value, a double-quoted YAML value). */
    val escaped: Boolean = false,
)

/**
 * `ansibility.secrets` (plan amendment R21, D164): the secret verdicts of a file, keyed by [SecretIndexKind] name, for
 * the Vault tab, its badge and its notifications (`vault.monitor.SecretHealthService`). The `ansible.vault` index is
 * untouched; malformed envelopes (ANS-V101–V103) still come from there.
 *
 * - **Input** ([InputFilter], pure, the path only): files up to 1 MiB that are YAML inputs, keystore-like or key-like
 *   names (`*.p12`, `*.jks`, `*.key`, `*.pem`, `*.ppk`, `id_*` …), templates, or lie below a `files` or `templates`
 *   directory.
 * - **Indexer** ([SecretIndexer]): bounded heads only (16 KiB for envelope shapes, the signatures' 64 KiB text and
 *   1 MiB keystore windows); verdicts only.
 * - **Value:** [SecretVerdict]s: kind, line, protection, shape or format. Never content, never a payload, never a
 *   key: a repository-wide question is answered without opening any file.
 *
 * Root, VCS status, exclusions and severity are resolved at query time (DEV.md rule 5).
 */
class SecretIndex : FileBasedIndexExtension<String, List<SecretVerdict>>() {
    override fun getName(): ID<String, List<SecretVerdict>> = NAME

    override fun getIndexer(): DataIndexer<String, List<SecretVerdict>, FileContent> = DataIndexer(SecretIndexer::index)

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<SecretVerdict>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = InputFilter

    override fun dependsOnFileContent(): Boolean = true

    /** The input rule ([isCandidate]); every file type, because keystores and keys keep whatever type the IDE gives them. */
    object InputFilter : FileBasedIndex.FileTypeSpecificInputFilter {
        override fun registerFileTypesUsedForIndexing(fileTypeSink: Consumer<in FileType>) {
            val types = LinkedHashSet<FileType>()
            types += YAMLFileType.YML
            types += PlainTextFileType.INSTANCE
            types += UnknownFileType.INSTANCE
            types += FileTypeRegistry.getInstance().registeredFileTypes
            types.forEach(fileTypeSink::consume)
        }

        override fun acceptInput(file: VirtualFile): Boolean =
            !file.isDirectory && file.length <= MAX_FILE_SIZE && isCandidate(file.name, ancestorNames(file), PathFacts.of(file))
    }

    companion object {
        @JvmField
        val NAME: ID<String, List<SecretVerdict>> = ID.create("ansibility.secrets")

        /**
         * 2: header lines need `$ANSIBLE_VAULT;`, the byte order mark comes from the file's bytes, encoded keys. 3: key-like
         * files that hold only password hashes get no [SecretIndexKind.KEY_LIKE_NAME].
         */
        const val VERSION: Int = 3
        private const val FORMAT: Int = 1

        /** Larger files are not indexed (D164: at most 1 MiB, as `ansible.vault`). */
        const val MAX_FILE_SIZE: Long = 1024L * 1024

        private const val MAX_DEPTH = 40

        /** Directories whose files Ansible copies or renders as they are. */
        private val RAW_DIRECTORIES = setOf("files", "templates")

        /** Text names that hold keys, certificates (a key may follow the chain), vaults or passwords; and templates. */
        private val TEXT_SUFFIXES = listOf(".pem", ".crt", ".cer", ".ppk", ".password", ".vault", ".asc", ".j2")

        /**
         * Whether a file named [name] in the directories [ancestors] (any order) with [facts] is indexed: a YAML input,
         * a keystore-like or key-like name, a template, or a file below `files` or `templates`. The path alone decides.
         */
        fun isCandidate(name: String, ancestors: Collection<String>, facts: PathFacts): Boolean {
            if (PrivateKeySignatures.isKeystoreName(name)) return true
            val lower = name.lowercase()
            if (TEXT_SUFFIXES.any(lower::endsWith)) return true
            if (lower.startsWith("id_") && !lower.endsWith(".pub")) return true
            if (VaultIndex.isYamlInput(facts)) return true
            return ancestors.any { it in RAW_DIRECTORIES }
        }

        /** Whether the file at the absolute `/`-separated [path] is indexed ([isCandidate]; for VFS events). */
        fun isCandidatePath(path: String): Boolean {
            val segments = path.split('/').filter { it.isNotEmpty() }
            val name = segments.lastOrNull() ?: return false
            return isCandidate(name, segments.dropLast(1).takeLast(MAX_DEPTH), PathFacts.of(path))
        }

        /** The names of [file]'s directories, innermost first, up to [MAX_DEPTH]. */
        internal fun ancestorNames(file: VirtualFile): List<String> {
            val names = ArrayList<String>()
            var dir = file.parent
            while (dir != null && names.size < MAX_DEPTH) {
                names += dir.name
                dir = dir.parent
            }
            return names
        }

        internal val EXTERNALIZER: DataExternalizer<List<SecretVerdict>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeVerdict, ::readVerdict)

        private fun writeVerdict(out: DataOutput, verdict: SecretVerdict) {
            IndexIO.writeString(out, verdict.kind.name)
            IndexIO.writeNullable(out, verdict.shape?.name)
            IndexIO.writeNullable(out, verdict.format?.name)
            IndexIO.writeNullable(out, verdict.protection?.name)
            IndexIO.writeInt(out, verdict.line + 1)
            out.writeBoolean(verdict.escaped)
        }

        private fun readVerdict(input: DataInput): SecretVerdict {
            val kind = IndexIO.readString(input)
            val shape = IndexIO.readNullable(input)
            val format = IndexIO.readNullable(input)
            val protection = IndexIO.readNullable(input)
            return SecretVerdict(
                kind = SecretIndexKind.entries.firstOrNull { it.name == kind } ?: SecretIndexKind.KEY_LIKE_NAME,
                shape = shape?.let { name -> VaultShapeKind.entries.firstOrNull { it.name == name } },
                format = format?.let { name -> KeyFormat.entries.firstOrNull { it.name == name } },
                protection = protection?.let { name -> KeyProtection.entries.firstOrNull { it.name == name } },
                line = IndexIO.readInt(input) - 1,
                escaped = input.readBoolean(),
            )
        }
    }
}
