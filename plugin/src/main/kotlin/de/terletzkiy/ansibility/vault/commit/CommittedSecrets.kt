package de.terletzkiy.ansibility.vault.commit

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.index.secrets.SecretIndex
import de.terletzkiy.ansibility.index.vault.VaultEnvelopeProblem
import de.terletzkiy.ansibility.inspections.vault.PlaintextKeyChecks
import de.terletzkiy.ansibility.inspections.vault.VaultEnvelopeChecks
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.secrets.KeyHit
import de.terletzkiy.ansibility.semantics.secrets.PrivateKeySignatures
import de.terletzkiy.ansibility.semantics.vault.ShapeContext
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.monitor.SecretFix

/**
 * What stops a commit (plan amendment R21, D167), in the order its problem text names them. The weaker signals of D161
 * (passphrase-protected keys, keystores, key-like names) are warnings in the editor and the Vault tab and never stop a
 * commit: D167 lists plaintext private keys, vault password files, broken vaults and vaults that are plaintext now.
 */
enum class CommitSecretKind(internal val key: String) {
    /** ANS-V108: a private key in plaintext (also an unprotected PKCS#12 key bag or a DER key). */
    PLAINTEXT_KEY("plaintext.key"),

    /** ANS-V108: a vault password file of a root (not a script, not `.env.local`, not vaulted). Its content is never read. */
    PASSWORD_FILE("password.file"),

    /** X99: the file was a whole-file vault before this commit and is plaintext now. */
    DECRYPTED_VAULT("decrypted.vault"),

    /** ANS-V107 (an envelope Ansible does not see) or a malformed whole-file vault (ANS-V101, ANS-V103). */
    BROKEN_VAULT("broken.vault"),
}

/**
 * One finding of the commit check: the [kind], the committed file ([path], its [name], its working-tree [file] when it
 * has one) and the fix "Encrypt Files…" or "Convert to Whole-File Vault" offers for it. Never any content.
 */
class CommitSecret(
    val kind: CommitSecretKind,
    /** The absolute, `/`-separated path of the committed file. */
    val path: String,
    val name: String,
    val file: VirtualFile?,
    val fix: SecretFix?,
) {
    override fun toString(): String = "CommitSecret($kind, $name, fix=$fix)"
}

/**
 * The committed content of one file as the commit check reads it: its first [bytes] (at most
 * [CommittedSecrets.MAX_BYTES]) and whether they are all of it. Lives only for one file's check; [toString] prints the
 * size only.
 */
class CommittedContent(val bytes: ByteArray, val complete: Boolean) {
    override fun toString(): String = "CommittedContent(${bytes.size} bytes, complete=$complete)"
}

/**
 * What the commit check knows about a committed path before it reads anything (path facts only): its root, whether
 * the plaintext key allowlist covers it, whether it is a vault password source of a root (and a password file to
 * report), how Ansible reads it and which fixes apply. Made by [CommittedSecrets.target] in a read action.
 */
class CommitTarget internal constructor(
    val path: String,
    val name: String,
    val file: VirtualFile?,
    internal val root: AnsibleRoot?,
    internal val allowlisted: Boolean,
    /** A vault password source of a root (a file, a script, `.env.local`): its committed content is never read. */
    internal val passwordSource: Boolean,
    /** A password source that is a plaintext vault password file (`VaultFileOperations.isPasswordFile`, not vaulted). */
    internal val passwordFile: Boolean,
    internal val context: ShapeContext,
    /** "Encrypt Files…" applies to a key in it (`PlaintextKeyChecks.encryptsFile`). */
    internal val encrypts: Boolean,
    /** Encrypt File applies at all (a local file inside a root): for a vault that is plaintext now. */
    internal val reencrypts: Boolean,
) {
    /** False for a vault password source: the commit check never reads its content. */
    val readsContent: Boolean get() = !passwordSource

    override fun toString(): String = "CommitTarget($name, root=${root?.displayName}, allowlisted=$allowlisted, passwordSource=$passwordSource)"
}

/**
 * The findings of one committed file ([secrets]) and whether its before-revision decides more ([decidesByBefore]: the
 * content, read completely, is plaintext without any other finding and without inline `!vault` values, so it is X99
 * when the file was a whole-file vault before).
 */
class CommitVerdict(val secrets: List<CommitSecret>, val decidesByBefore: Boolean)

/**
 * The checks of the commit check (plan amendment R21, D167) on committed content, without any VCS class: the same
 * detectors, exclusions and severities as ANS-V107 and ANS-V108, the Vault tab and the `ansibility.secrets` index.
 *
 * - ANS-V107: `VaultFileShape.classify` on the first 16 KiB of the committed bytes (a byte order mark is read from
 *   them); a whole-file vault (`$ANSIBLE_VAULT` first) is checked with `VaultEnvelope.parse` when it was read
 *   completely (ANS-V101/V103).
 * - ANS-V108: `PrivateKeySignatures.scanText` on the decoded head and `scanBinary` for keystore-like names (keys in
 *   plaintext only: the weaker signals of D161 never stop a commit); the vault password files of the roots
 *   (`VaultFileOperations.isPasswordFile`; no password source is ever read, a vaulted password file in the working tree
 *   is no finding). The plaintext key allowlist silences them.
 * - X99: a file that was a whole-file vault before the commit and is plaintext now ([decrypted]); not a YAML file that
 *   now holds inline `!vault` values (moving secrets into values is no leak), and only for content read completely.
 *
 * Applied as the inspections do: only in projects with a (non-detached) Ansible root, never for files of detached
 * worktrees or Ansibility's ignored paths, every severity from [SeverityPolicy] for a committed file (the uncommitted
 * cap does not apply: the file is being committed). A finding at ERROR or WARNING stops the commit; X99 has no
 * diagnostic code and always does. Only verdicts are kept: kinds and names, never a byte of content.
 */
object CommittedSecrets {
    /** The bytes read of a committed file (as `ansibility.secrets` indexes files up to this size). */
    const val MAX_BYTES: Int = SecretIndex.MAX_FILE_SIZE.toInt()

    /** The bytes the envelope shape is read from (as `WholeFileShapes.ofHead`). */
    private const val SHAPE_BYTES = WholeFileShapes.HEAD_BYTES

    /** The bytes decoded for the text signatures: their 64 KiB window in any UTF-8 text. */
    private const val TEXT_BYTES = PrivateKeySignatures.TEXT_WINDOW * 4

    private const val MAX_DEPTH = 40

    /** Every vault tag (`!vault`, `!vault-encrypted`) contains this. */
    private const val VAULT_TAG = "!vault"

    /** True when the commit check applies to [project]: it has an Ansible root that is not a detached worktree. Read action. */
    fun applies(project: Project): Boolean = AnsibleWorkspace.getInstance(project).roots().any { !it.detached }

    /**
     * The path facts of the committed file at the absolute `/`-separated [path], whose working-tree file is [file]
     * (null when the working tree has none: a file staged and then deleted); null when the commit check skips it (a
     * detached worktree, an ignored path). Reads no content. Read action.
     */
    fun target(project: Project, path: String, file: VirtualFile?): CommitTarget? {
        val workspace = AnsibleWorkspace.getInstance(project)
        val local = file?.takeIf { it.isValid && !it.isDirectory }
        if (local != null) {
            val root = workspace.rootFor(local)
            if (root?.detached == true || AnsibilityProjectSettings.getInstance(project).isIgnored(local)) return null
            val operations = VaultFileOperations.getInstance(project)
            val roots = workspace.roots().filter { !it.detached }
            val passwordSource = local.isInLocalFileSystem && roots.any { operations.isPasswordSource(it, local) }
            // A password file that is itself vaulted (Ansible decrypts it) is no leak: its first 14 bytes tell.
            val passwordFile = passwordSource && roots.any { operations.isPasswordFile(it, local) } && !VaultEnvelopes.isWholeFileVault(local)
            return CommitTarget(
                path, local.name, local, root,
                allowlisted = PlaintextKeyExclusions.isAllowlisted(project, local),
                passwordSource = passwordSource,
                passwordFile = passwordFile,
                context = WholeFileShapes.contextOf(project, local, byteOrderMark = false),
                encrypts = PlaintextKeyChecks.encryptsFile(project, local, root),
                reencrypts = root != null && local.isInLocalFileSystem,
            )
        }
        val root = nearestDirectory(path)?.let(workspace::rootFor)
        if (root?.detached == true || PlaintextKeyExclusions.isIgnoredPath(project, path)) return null
        return CommitTarget(
            path, path.substringAfterLast('/'), null, root,
            allowlisted = PlaintextKeyExclusions.isAllowlistedPath(project, path),
            passwordSource = false,
            passwordFile = false,
            context = WholeFileShapes.pathContextOf(path),
            encrypts = false,
            reencrypts = false,
        )
    }

    /** The closest existing directory above [path] (for the root of a file the working tree no longer has). */
    private fun nearestDirectory(path: String): VirtualFile? {
        var parent = path.substringBeforeLast('/', "")
        var depth = 0
        while (parent.isNotEmpty() && depth++ < MAX_DEPTH) {
            LocalFileSystem.getInstance().findFileByPath(parent)?.takeIf { it.isDirectory }?.let { return it }
            parent = parent.substringBeforeLast('/', "")
        }
        return null
    }

    /**
     * The findings of [target] whose committed [content] is given (null for a vault password source, which is never
     * read). Any thread; pure apart from the severity settings.
     */
    fun classify(project: Project, target: CommitTarget, content: CommittedContent?): CommitVerdict {
        val policy = SeverityPolicy.getInstance(project)
        if (target.passwordSource) {
            // Its size, never its content: an empty password file unlocks nothing; scripts and `.env.local` are no finding.
            val empty = target.file?.let { it.length == 0L } == true
            if (!target.passwordFile || empty || target.allowlisted ||
                policy.level(DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, target.root).reported() == null
            ) {
                return CommitVerdict(emptyList(), decidesByBefore = false)
            }
            return CommitVerdict(listOf(secret(target, CommitSecretKind.PASSWORD_FILE, null)), decidesByBefore = false)
        }
        val bytes = content?.bytes
        if (bytes == null || bytes.isEmpty()) return CommitVerdict(emptyList(), decidesByBefore = false)
        if (VaultEnvelope.isEncryptedFile(bytes)) {
            val broken = if (content.complete) malformedVault(policy, target, bytes) else null
            return CommitVerdict(listOfNotNull(broken), decidesByBefore = false)
        }
        ProgressManager.checkCanceled()
        val found = ArrayList<CommitSecret>()
        val head = if (bytes.size > SHAPE_BYTES) bytes.copyOf(SHAPE_BYTES) else bytes
        val shape = VaultFileShape.classify(head, target.context, complete = content.complete && head.size == bytes.size)
        if (shape != null && shape.kind != VaultShapeKind.VAULT && policy.level(DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT, target.root).reported() != null) {
            found += secret(target, CommitSecretKind.BROKEN_VAULT, if (converts(target, shape)) SecretFix.CONVERT else null)
        }
        val text = decode(bytes, content.complete)
        if (!target.allowlisted) keyFinding(policy, target, bytes, text, content.complete)?.let(found::add)
        // Inline `!vault` values (a whole-file vault split into encrypt_string values) are no plaintext secret.
        val inlineVaults = target.context.yamlInput && VAULT_TAG in text.text && VaultEnvelope.MAGIC in text.text
        val plaintext = shape == null && !inlineVaults && !text.text.isBlank()
        // A decrypted vault is about as large as its plaintext: only content read completely is compared with its past.
        val decides = found.isEmpty() && plaintext && content.complete && SecretIndex.isCandidatePath(target.path)
        return CommitVerdict(found, decidesByBefore = decides)
    }

    /** The bytes of a before-revision that X99 looks at: the magic of a whole-file vault. */
    const val BEFORE_BYTES: Int = 14

    /**
     * X99: the finding for [target] whose content before the commit was [before], when that was a whole-file vault
     * (only its first [BEFORE_BYTES] count), or null. Call only for a [CommitVerdict.decidesByBefore] verdict.
     */
    fun decrypted(target: CommitTarget, before: CommittedContent): CommitSecret? {
        if (!VaultEnvelope.isEncryptedFile(before.bytes)) return null
        return secret(target, CommitSecretKind.DECRYPTED_VAULT, if (target.reencrypts) SecretFix.ENCRYPT else null)
    }

    /** ANS-V101/V103 of a whole-file vault read completely, when its level stops a commit. */
    private fun malformedVault(policy: SeverityPolicy, target: CommitTarget, bytes: ByteArray): CommitSecret? {
        val parse = VaultEnvelope.parse(bytes)
        val problem = VaultEnvelopeProblem.of(parse) ?: return null
        val code = VaultEnvelopeChecks.codeOf(problem, VaultEnvelopeProblem.hintOf(parse), null) ?: return null
        policy.level(code, target.root).reported() ?: return null
        return secret(target, CommitSecretKind.BROKEN_VAULT, null)
    }

    /** True when "Convert to Whole-File Vault" applies to a wrapped [shape] of a working-tree file. */
    private fun converts(target: CommitTarget, shape: VaultFileShape): Boolean =
        target.file != null && shape.kind.isWrapped && shape.kind != VaultShapeKind.BYTE_ORDER_MARK && (shape.inner == null || shape.unwrapsToVault)

    /** The decoded head of a committed file: its text (no byte order mark) and whether it is all of it. */
    private class Decoded(val text: String, val complete: Boolean)

    private fun decode(bytes: ByteArray, complete: Boolean): Decoded {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val start = if (bom) 3 else 0
        val length = minOf(bytes.size - start, TEXT_BYTES)
        return Decoded(String(bytes, start, length, Charsets.UTF_8), complete && start + length == bytes.size)
    }

    /**
     * ANS-V108 of committed content: a key in plaintext (text, or binary for keystore-like names) whose level stops a
     * commit, or null. The weaker signals of D161 (`PrivateKeySignatures.isWeakSignal`: protected keys, keystores) and
     * key-like names stay warnings of the editor and the Vault tab: D167 stops a commit for plaintext keys only.
     */
    private fun keyFinding(policy: SeverityPolicy, target: CommitTarget, bytes: ByteArray, text: Decoded, complete: Boolean): CommitSecret? {
        val hits: List<KeyHit> = PrivateKeySignatures.scanText(text.text, text.complete)
            .ifEmpty { listOfNotNull(PrivateKeySignatures.scanBinary(bytes, target.name, complete)) }
        val plaintext = hits.filter { !PrivateKeySignatures.isWeakSignal(it.format, it.protection) }
        if (plaintext.isEmpty()) return null
        policy.level(DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, target.root, FindingContext()).reported() ?: return null
        return secret(target, CommitSecretKind.PLAINTEXT_KEY, if (target.encrypts) SecretFix.ENCRYPT else null)
    }

    private fun secret(target: CommitTarget, kind: CommitSecretKind, fix: SecretFix?) = CommitSecret(kind, target.path, target.name, target.file, fix)

    /** This level when it stops a commit (ERROR or WARNING), else null. */
    private fun Level.reported(): Level? = takeIf { it == Level.ERROR || it == Level.WARNING }
}
