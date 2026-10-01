package de.terletzkiy.ansibility.index.vault

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
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.index.IndexIO
import de.terletzkiy.ansibility.index.IndexInput
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.VersionedListExternalizer
import de.terletzkiy.ansibility.semantics.vault.FormatHint
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import org.jetbrains.yaml.YAMLFileType
import java.io.DataInput
import java.io.DataOutput

/**
 * `ansible.vault` (plan amendment R7/R8, A.7 "Index `ansible.vault`"): raw header label → the vault envelopes of a file,
 * for the vault manager's counts and its Test button, the tool window's Vault node, ANS-V106–V108 and the commit check.
 *
 * - **Key:** the raw label, the fourth header field, or the empty string when there is none (every `1.1` envelope).
 *   Mapping an unlabelled envelope to `default` (or the root's renamed `vault_identity`) happens at query time in
 *   [VaultIndexQueries], because indexers never read configuration (DEV.md rule 5).
 * - **Value:** a [VaultIndexEntry] per inline `!vault` scalar (any YAML file) and per whole-file vault (a file whose
 *   first 14 bytes are `$ANSIBLE_VAULT`): header facts as written, key path and offset, payload line count, whether
 *   it parses (the codec's reader rules) and why not, plus the file's size and timestamp.
 * - **Input:** YAML files and vars files without a YAML name (as the other Ansible indexes), plus files below a
 *   `files` directory or named `*.key` / `*.vault`, up to 1 MiB. Files that are neither YAML nor a vault are judged on
 *   their first 14 bytes only.
 *
 * It never stores plaintext, never stores the payload hex and never decrypts: there is no secret on this path at all.
 */
class VaultIndex : FileBasedIndexExtension<String, List<VaultIndexEntry>>() {
    override fun getName(): ID<String, List<VaultIndexEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<VaultIndexEntry>, FileContent> = DataIndexer(VaultIndexer::index)

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<VaultIndexEntry>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = InputFilter

    override fun dependsOnFileContent(): Boolean = true

    /** The input rule: YAML (by name or as a vars file), or a file that may be a whole-file vault. */
    object InputFilter : FileBasedIndex.FileTypeSpecificInputFilter {
        override fun registerFileTypesUsedForIndexing(fileTypeSink: Consumer<in FileType>) {
            val types = LinkedHashSet<FileType>()
            types += YAMLFileType.YML
            types += PlainTextFileType.INSTANCE
            types += UnknownFileType.INSTANCE
            // Whole-file vaults keep whatever type the IDE gives them, binary types included (`*.key`, `*.p12`).
            types += FileTypeRegistry.getInstance().registeredFileTypes
            types.forEach(fileTypeSink::consume)
        }

        override fun acceptInput(file: VirtualFile): Boolean =
            !file.isDirectory && file.length <= MAX_FILE_SIZE && (isYamlInput(PathFacts.of(file)) || isVaultCandidate(file))
    }

    companion object {
        @JvmField
        val NAME: ID<String, List<VaultIndexEntry>> = ID.create("ansible.vault")

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        /** Larger files are not indexed (A.7: at most 1 MiB). */
        const val MAX_FILE_SIZE: Long = 1024L * 1024

        /** Directories whose files Ansible copies or reads verbatim (and decrypts when they are vaults). */
        private const val FILES_DIR = "files"
        private val VAULT_FILE_SUFFIXES = listOf(".key", ".vault")
        private const val MAX_DEPTH = 40

        /** YAML that Ansible loads: a `.yml`/`.yaml` name or a vars file without one. */
        internal fun isYamlInput(facts: PathFacts): Boolean = facts.isYamlName || IndexInput.isVarsWithoutYamlName(facts)

        /** A non-YAML file that may be a whole-file vault: below a `files` directory, or named `*.key` / `*.vault`. */
        internal fun isVaultCandidate(file: VirtualFile): Boolean {
            if (VAULT_FILE_SUFFIXES.any { file.name.endsWith(it, ignoreCase = true) }) return true
            var dir = file.parent
            var depth = 0
            while (dir != null && depth++ < MAX_DEPTH) {
                if (dir.name == FILES_DIR) return true
                dir = dir.parent
            }
            return false
        }

        internal val EXTERNALIZER: DataExternalizer<List<VaultIndexEntry>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeEntry, ::readEntry)

        private fun writeEntry(out: DataOutput, e: VaultIndexEntry) {
            out.writeByte(if (e.kind == VaultEnvelopeKind.FILE) 1 else 0)
            IndexIO.writeInt(out, e.offset)
            IndexIO.writeStrings(out, e.keyPath)
            IndexIO.writeNullable(out, e.keyName)
            IndexIO.writeString(out, e.version)
            IndexIO.writeString(out, e.cipher)
            IndexIO.writeNullable(out, e.label)
            IndexIO.writeInt(out, e.ciphertextLength?.let { it + 1 } ?: 0)
            IndexIO.writeInt(out, e.hexLines)
            IndexIO.writeInt(out, e.problem?.code ?: 0)
            IndexIO.writeNullable(out, e.hint?.name)
            IndexIO.writeNullable(out, e.style?.name)
            DataInputOutputUtil.writeLONG(out, e.fileSize)
            DataInputOutputUtil.writeLONG(out, e.timestamp)
        }

        private fun readEntry(input: DataInput): VaultIndexEntry = VaultIndexEntry(
            kind = if (input.readUnsignedByte() == 1) VaultEnvelopeKind.FILE else VaultEnvelopeKind.INLINE,
            offset = IndexIO.readInt(input),
            keyPath = IndexIO.readStrings(input),
            keyName = IndexIO.readNullable(input),
            version = IndexIO.readString(input),
            cipher = IndexIO.readString(input),
            label = IndexIO.readNullable(input),
            ciphertextLength = IndexIO.readInt(input).takeIf { it > 0 }?.minus(1),
            hexLines = IndexIO.readInt(input),
            problem = VaultEnvelopeProblem.ofCode(IndexIO.readInt(input)),
            hint = IndexIO.readNullable(input)?.let { name -> FormatHint.entries.firstOrNull { it.name == name } },
            style = IndexIO.readNullable(input)?.let { name -> ScalarStyle.entries.firstOrNull { it.name == name } },
            fileSize = DataInputOutputUtil.readLONG(input),
            timestamp = DataInputOutputUtil.readLONG(input),
        )
    }
}
