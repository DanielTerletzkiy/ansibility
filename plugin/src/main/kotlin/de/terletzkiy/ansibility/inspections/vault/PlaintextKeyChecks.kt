package de.terletzkiy.ansibility.inspections.vault

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.semantics.secrets.KeyFormat
import de.terletzkiy.ansibility.semantics.secrets.KeyHit
import de.terletzkiy.ansibility.semantics.secrets.KeyLikeName
import de.terletzkiy.ansibility.semantics.secrets.KeyLikeNames
import de.terletzkiy.ansibility.semantics.secrets.KeyProtection
import de.terletzkiy.ansibility.semantics.secrets.PasswordHashes
import de.terletzkiy.ansibility.semantics.secrets.PrivateKeySignatures
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import de.terletzkiy.ansibility.vault.actions.PlainValueRef
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultFile
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/** What ANS-V108 found in a file (plan amendment R21, D160/D161), from its content and its path only. */
enum class KeySignal {
    /** A private key `semantics.secrets.PrivateKeySignatures` reads: ERROR in plaintext, WARNING when protected. */
    KEY,

    /**
     * A key-like name (`semantics.secrets.KeyLikeNames`) on a plaintext file without a readable key that holds more than
     * password hashes (`semantics.secrets.PasswordHashes.isHashOnly`): WARNING.
     */
    KEY_LIKE_NAME,

    /**
     * A vault password file of a root (`VaultFileOperations.isPasswordFile`; not a password script, not `.env.local`
     * itself, not a vault-encrypted password file): ERROR when committed or staged, WARNING when untracked, else nothing.
     */
    PASSWORD_SOURCE,
}

/**
 * One ANS-V108 verdict: the signal, where to highlight it and what it is. Never any content: the range is a key's
 * BEGIN marker, or empty at the file's start for a name or a password source (the highlighted text of an inspection
 * result is what Inspect Code exports with the problem, so it must never be a secret). Whether a batch report adds
 * the lines around a problem as context (Qodana's SARIF `contextRegion`) is not verified: such a report may carry
 * them.
 */
class KeyVerdict(
    val signal: KeySignal,
    /** The BEGIN marker of a key; [TextRange.EMPTY_RANGE] for the file-level signals. */
    val range: TextRange,
    /** The 0-based line of the marker, or -1 for the file-level signals. */
    val line: Int,
    val format: KeyFormat? = null,
    val protection: KeyProtection? = null,
    val keyLikeName: KeyLikeName? = null,
    /** The YAML value that holds the key, when "Encrypt value" applies to it (`PlainValueRef.accepts`). */
    val value: YAMLScalar? = null,
) {
    /** A weaker signal (D161): a protected key or keystore, or a key-like name; at most WARNING. */
    val isWeak: Boolean
        get() = signal == KeySignal.KEY_LIKE_NAME ||
            (signal == KeySignal.KEY && format != null && protection != null && PrivateKeySignatures.isWeakSignal(format, protection))

    override fun toString(): String = "KeyVerdict($signal, $format, $protection, $keyLikeName, line $line)"
}

/**
 * ANS-V108 "Plaintext private key" (plan amendment R21, D160–D162): the verdicts of a file, cached until it changes,
 * and their messages. In projects with a (non-detached) Ansible root, every text file is checked, inside or outside
 * roots: keys found by their signatures (`PrivateKeySignatures.scanText`, the first 64 KiB), key-like names on
 * plaintext files that hold no readable key and not only password hashes (`PasswordHashes.isHashOnly`, at most
 * 16 KiB: `files/mysql/users/alice.password` with a MySQL hash is no plaintext secret), and the vault password files of
 * the roots (`VaultFileOperations.isPasswordFile`, which reads no content; other password sources, scripts and
 * `.env.local`, are neither reported nor scanned). Never reported: decrypted tabs, whole-file vaults (also a
 * vault-encrypted password file), files of detached worktrees; exclusions (`vault.keys.PlaintextKeyExclusions`) and the
 * VCS status are applied by the inspection. Verdicts carry kinds, lines and marker ranges only; nothing is decrypted or
 * logged.
 */
object PlaintextKeyChecks {
    private const val MAX_DEPTH = 40

    /** True for the one PSI of [file] the checks run on: the base language of a non-injected file. */
    fun isBaseFile(file: PsiFile): Boolean {
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return false
        val viewProvider = file.viewProvider
        return viewProvider.getPsi(viewProvider.baseLanguage) == file
    }

    /** The verdicts of [file] (empty when none apply), cached until it, its path or the roots' vault setup change. Read action. */
    fun of(file: PsiFile): List<KeyVerdict> {
        if (!isBaseFile(file)) return emptyList()
        val project = file.project
        if (AnsibleWorkspace.getInstance(project).roots().none { !it.detached }) return emptyList()
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                compute(file),
                file,
                VaultIdentityRegistry.getInstance(project).modificationTracker,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }
    }

    private fun compute(file: PsiFile): List<KeyVerdict> {
        val project = file.project
        val virtualFile = file.viewProvider.virtualFile
        if (DecryptedVaultFile.isDecryptedTab(virtualFile)) return emptyList()
        if (AnsibleWorkspace.getInstance(project).rootFor(virtualFile)?.detached == true) return emptyList()
        val text = file.viewProvider.contents
        // A whole-file vault holds no plaintext: also a password file that is itself vaulted (Ansible decrypts it).
        if (text.startsWith(VaultEnvelope.MAGIC) && virtualFile.bom == null) return emptyList()
        if (isPasswordSource(project, virtualFile)) {
            if (!isPasswordFile(project, virtualFile) || isBlankHead(text)) return emptyList()
            return listOf(KeyVerdict(KeySignal.PASSWORD_SOURCE, TextRange.EMPTY_RANGE, -1))
        }
        val hits = PrivateKeySignatures.scanText(text)
        if (hits.isNotEmpty()) return hits.map { key(file, it) }
        val name = keyLikeName(project, virtualFile) ?: return emptyList()
        if (isBlankHead(text) || VaultFileShape.mayHoldEnvelope(text)) return emptyList()
        // Password hashes only (crypt, htpasswd and shadow lines, MySQL, PostgreSQL SCRAM, LDAP …): no plaintext secret.
        if (PasswordHashes.isHashOnly(text)) return emptyList()
        return listOf(KeyVerdict(KeySignal.KEY_LIKE_NAME, TextRange.EMPTY_RANGE, -1, keyLikeName = name))
    }

    /** True when the scanned head of [text] (the signatures' window) is empty or blank. */
    private fun isBlankHead(text: CharSequence): Boolean =
        text.subSequence(0, minOf(text.length, PrivateKeySignatures.TEXT_WINDOW)).isBlank()

    private fun key(file: PsiFile, hit: KeyHit): KeyVerdict {
        ProgressManager.checkCanceled()
        val range = TextRange(hit.offset, hit.offset + hit.length)
        val value = (file as? YAMLFile)?.let { yaml ->
            PsiTreeUtil.getParentOfType(yaml.findElementAt(hit.offset), YAMLScalar::class.java, false)
                ?.takeIf { it.textRange.contains(range) && PlainValueRef.accepts(it) }
        }
        return KeyVerdict(KeySignal.KEY, range, hit.line, hit.format, hit.protection, value = value)
    }

    /** True when [file] is a vault password source of a root of [project]: a file, a script or `.env.local` (no content is read). */
    private fun isPasswordSource(project: Project, file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem) return false
        val operations = VaultFileOperations.getInstance(project)
        return AnsibleWorkspace.getInstance(project).roots().any { !it.detached && operations.isPasswordSource(it, file) }
    }

    /** True when [file] is a vault password file of a root of [project] (ANS-V108's signal; no content is read). */
    fun isPasswordFile(project: Project, file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem) return false
        val operations = VaultFileOperations.getInstance(project)
        return AnsibleWorkspace.getInstance(project).roots().any { !it.detached && operations.isPasswordFile(it, file) }
    }

    /**
     * The key-like name of [file] from its directories up to (and including) its content root; the monitoring confirms
     * the index's path-only candidates with it.
     */
    internal fun keyLikeName(project: Project, file: VirtualFile): KeyLikeName? {
        val stop = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        val dirs = ArrayList<String>()
        var dir = file.parent
        while (dir != null && dirs.size < MAX_DEPTH) {
            dirs += dir.name
            if (dir == stop) break
            dir = dir.parent
        }
        return KeyLikeNames.of(dirs.asReversed(), file.name)
    }

    /**
     * True when "Encrypt file" applies to [file]: inside a (non-detached) root, on the local file system, and Ansible
     * reads the file as it is or it is no YAML file it loads (a key in a vars file gets "Encrypt value" instead).
     */
    fun encryptsFile(project: Project, file: VirtualFile, root: AnsibleRoot?): Boolean {
        if (root == null || root.detached || !file.isInLocalFileSystem || DecryptedVaultFile.isDecryptedTab(file)) return false
        val context = WholeFileShapes.contextOf(project, file)
        return context.readRaw || !context.yamlInput
    }

    /**
     * The message of [verdict] for a file whose VCS status is [status], or null when it gets no finding (an ignored
     * file; a password source that is not under version control). [insideRoot] false for a file outside every
     * (non-detached) Ansible root: Ansibility Vault cannot encrypt it, so the message says to keep it out of version
     * control instead. Kinds and file conventions only, never content.
     */
    fun message(verdict: KeyVerdict, status: TrackedStatus, insideRoot: Boolean = true): String? {
        val suffix = when (status) {
            TrackedStatus.IGNORED -> return null
            TrackedStatus.TRACKED -> ".committed"
            TrackedStatus.ADDED -> ".added"
            TrackedStatus.UNTRACKED -> ".untracked"
            TrackedStatus.NO_VCS -> ""
        }
        return when (verdict.signal) {
            KeySignal.PASSWORD_SOURCE ->
                if (suffix.isEmpty()) null else AnsibilityVaultChecksBundle.message("inspection.v108.password$suffix")
            KeySignal.KEY_LIKE_NAME -> {
                val name = verdict.keyLikeName ?: return null
                AnsibilityVaultChecksBundle.message(placed("inspection.v108.name$suffix", insideRoot), nameText(name))
            }
            KeySignal.KEY -> {
                val format = verdict.format ?: return null
                val kind = if (verdict.protection == KeyProtection.NONE) "key" else "protected"
                AnsibilityVaultChecksBundle.message(placed("inspection.v108.$kind$suffix", insideRoot), formatText(format))
            }
        }
    }

    /** The messages that recommend Ansibility Vault have a variant for files outside every root (`<key>.outside`). */
    private val OUTSIDE_VARIANTS = setOf(
        "inspection.v108.key.committed", "inspection.v108.key",
        "inspection.v108.protected.committed", "inspection.v108.protected.added", "inspection.v108.protected",
        "inspection.v108.name.committed", "inspection.v108.name.added", "inspection.v108.name",
    )

    private fun placed(key: String, insideRoot: Boolean): String = if (!insideRoot && key in OUTSIDE_VARIANTS) "$key.outside" else key

    /** The user-facing name of a key [format] ("RSA, PKCS#1", "OpenSSH", "PKCS#12 keystore"). */
    fun formatText(format: KeyFormat): String = AnsibilityVaultChecksBundle.message("key.format." + format.name.lowercase())

    /** The user-facing description of a key-like [name] ("a *.key file below files/ssl"). */
    fun nameText(name: KeyLikeName): String = AnsibilityVaultChecksBundle.message("key.name." + name.name.lowercase())
}
