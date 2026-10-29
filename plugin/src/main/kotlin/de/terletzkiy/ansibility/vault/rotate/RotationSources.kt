package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultCorpusGuard
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.identity.ConsentTarget
import de.terletzkiy.ansibility.vault.identity.ConsentTargets
import de.terletzkiy.ansibility.vault.identity.DiscoveredIdentity
import de.terletzkiy.ansibility.vault.identity.EnvLocalFile
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultPaths
import de.terletzkiy.ansibility.vault.identity.sourceKind
import de.terletzkiy.ansibility.vault.secrets.ConsentFingerprint
import de.terletzkiy.ansibility.vault.secrets.ConsentRecord
import de.terletzkiy.ansibility.vault.secrets.VaultConsents
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The password sources of Rotate Vault Password… (plan amendment R29, D206, D211): which source an id of a root reads,
 * the key that groups roots sharing one, where the new password goes, and the one write the rotation allows itself
 * (a password file, replaced atomically).
 *
 * Nothing is read here without a consent that already exists (D25): `.env.local` is resolved to the file it names the
 * way the unlock does only when its consent holds (else by the file the discovery expects), and a password file is
 * looked at (is it a vault itself?) only under its consent. Logs name files and ids only.
 */
internal class RotationSources(private val project: Project) {
    private val registry get() = VaultIdentityRegistry.getInstance(project)

    /**
     * The source of [label] in [discovery]'s root: the chain entry whose secret is current when the id is unlocked,
     * else the first entry with that label in Ansible's order; a prompt when there is none. Blocking (it may read a
     * consented `.env.local`); call off the EDT, never in a read action.
     */
    fun sourceOf(discovery: VaultDiscovery, label: String): RotationSource {
        val secrets = VaultSecretsService.getInstance(project)
        val identity = secrets.currentIdentity(discovery, label)
            ?: if (secrets.unlockedLabels(discovery).contains(label)) null else discovery.identities.firstOrNull { it.label == label }
        return sourceOf(discovery, label, identity, read = true)
    }

    /** The source [identity] (of [discovery]'s chain, id [label]) is; with [read] a consented `.env.local` is read to find the file it names. */
    fun sourceOf(discovery: VaultDiscovery, label: String, identity: DiscoveredIdentity?, read: Boolean): RotationSource {
        val plan = identity?.plan ?: SecretPlan.Prompt
        return when (plan) {
            is SecretPlan.PasswordFile -> passwordFile(plan.target, identity)
            is SecretPlan.EnvLocal -> {
                val named = (if (read) envLocalTarget(discovery, plan) else null) ?: plan.expected
                if (named != null) {
                    passwordFile(named, identity)
                } else {
                    RotationSource(
                        "env-local:${plan.envLocal.locator}", VaultSourceKind.PASSWORD_FILE, displayOf(plan.envLocal), Destination.YOURSELF, null, identity,
                    )
                }
            }
            is SecretPlan.PasswordSafeEntry -> RotationSource(
                "safe:${plan.serviceName}", VaultSourceKind.PASSWORD_SAFE, message("rotate.source.password.safe"), Destination.PASSWORD_SAFE, null, identity,
            )
            is SecretPlan.External -> RotationSource(
                "manager:${plan.manager.name}:${plan.reference}", plan.manager.kind, identity?.source?.location ?: plan.reference, Destination.YOURSELF, null, identity,
            )
            is SecretPlan.Environment -> RotationSource(
                "env:${plan.target.locator}", VaultSourceKind.ENVIRONMENT, message("rotate.source.environment", plan.target.display), Destination.YOURSELF, null, identity,
            )
            is SecretPlan.Script -> {
                val real = registry.access.canonical(plan.path) ?: plan.path
                RotationSource("script:$real", plan.sourceKind, displayOf(plan.path), Destination.YOURSELF, null, identity)
            }
            SecretPlan.Prompt -> RotationSource(
                "prompt:${discovery.rootKey}#$label", VaultSourceKind.PROMPT, message("rotate.source.prompt"), Destination.PROMPT, null, identity,
            )
        }
    }

    private fun passwordFile(target: ConsentTarget, identity: DiscoveredIdentity?): RotationSource =
        RotationSource("file:${target.locator}", VaultSourceKind.PASSWORD_FILE, displayOf(target), Destination.PASSWORD_FILE, target, identity)

    /**
     * The file a consented `.env.local` names, resolved as the unlock resolves it ([EnvLocalFile], [VaultPaths]); null
     * without a consent that still holds, or when it names nothing or a script. Reads `.env.local` (its key line only).
     */
    private fun envLocalTarget(discovery: VaultDiscovery, plan: SecretPlan.EnvLocal): ConsentTarget? {
        val access = registry.access
        val environment = access.environment()
        val check = VaultConsents(access, VaultUserState.getInstance()).check(plan.envLocal, environment) as? VaultConsents.Check.Granted ?: return null
        val value = try {
            EnvLocalFile.passwordFile(check.content, environment)
        } finally {
            check.content.fill(0)
        }
        val rootPath = discovery.rootPath ?: return null
        val path = value?.let { VaultPaths.resolve(it, rootPath, environment["HOME"]) } ?: return null
        if (access.isExecutable(path)) return null
        return ConsentTargets(rootPath, environment["HOME"], access).file(path)
    }

    /**
     * Whether the password file [target] is a vault itself (D211: such a file is never written; you update it). Read
     * only under a consent that still holds; null when that is not the case (unknown yet).
     */
    fun isVaulted(target: ConsentTarget): Boolean? {
        val access = registry.access
        val check = VaultConsents(access, VaultUserState.getInstance()).check(target, access.environment()) as? VaultConsents.Check.Granted ?: return null
        val content = check.content
        try {
            var start = 0
            while (start < content.size && content[start].toInt().toChar() in WHITESPACE) start++
            val head = content.copyOfRange(start, minOf(content.size, start + HEAD_BYTES))
            try {
                return VaultEnvelope.isEncrypted(head)
            } finally {
                head.fill(0)
            }
        } finally {
            content.fill(0)
        }
    }

    /**
     * Replaces the password file [target] (its real path) with [password] and no trailing newline (D211): a temporary
     * file in the same directory, created with mode 0600 on a POSIX file system, then moved over the file atomically,
     * so the file is never half written and never readable by others (repository finding: 0644). Its consent (D25) is
     * renewed for the new content, so the next unlock reads it without asking, and the VFS learns of it. Blocking; call
     * off the EDT. False (logged by class) when it could not be written.
     */
    fun replacePasswordFile(target: ConsentTarget, password: ByteArray): Boolean {
        val path = target.path ?: return false
        VaultCorpusGuard.check(path)
        val directory = path.parent ?: return false
        var temporary: Path? = null
        try {
            temporary = if (POSIX) {
                Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(PRIVATE)))
            } else {
                Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX)
            }
            Files.write(temporary, password)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, target.display)
            return false
        } catch (e: UnsupportedOperationException) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, target.display)
            return false
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
        VaultUserState.getInstance().grant(ConsentRecord(target.locator, password.size.toLong(), ConsentFingerprint.of(password)))
        LocalFileSystem.getInstance().refreshNioFiles(listOfNotNull(path, target.declared).distinct())
        return true
    }

    /** How a file source is named in the dialog and the summary: project-relative inside the project, else as its consent names it. */
    private fun displayOf(target: ConsentTarget): String {
        val declared = target.declared ?: target.path ?: return target.display
        return relative(declared) ?: target.display
    }

    private fun displayOf(path: Path): String = relative(path.toAbsolutePath().normalize()) ?: path.toString()

    private fun relative(path: Path): String? {
        val base = RootKeys.projectDir(project)?.toNioPathOrNull()?.toAbsolutePath()?.normalize() ?: return null
        return if (path.startsWith(base) && path != base) base.relativize(path).toString().replace('\\', '/') else null
    }

    companion object {
        private const val TEMP_PREFIX = ".ansibility-rotate-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val PRIVATE = "rw-------"
        private const val WHITESPACE = " \t\n\r\u000b\u000c"
        private const val HEAD_BYTES = 64

        /** Whether the default file system has POSIX modes (R18's `VaultFileOperations.FileModes` asks the same). */
        private val POSIX: Boolean get() = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    }
}
