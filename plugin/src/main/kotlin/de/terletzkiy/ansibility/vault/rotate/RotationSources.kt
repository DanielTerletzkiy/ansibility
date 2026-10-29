package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.SecretLoad
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.settings.RootKeys
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
import de.terletzkiy.ansibility.vault.reencrypt.AtomicFiles
import de.terletzkiy.ansibility.vault.secrets.ConsentFingerprint
import de.terletzkiy.ansibility.vault.secrets.ConsentRecord
import de.terletzkiy.ansibility.vault.secrets.VaultConsents
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The password sources of Rotate Vault Password… (plan amendment R29, D206, D211): which source an id of a root reads,
 * the key that groups roots sharing one, where the new password goes, and the one write the rotation allows itself
 * (a password file, replaced atomically).
 *
 * Nothing is read here without a consent that already exists (D25): `.env.local` is resolved to the file it names the
 * way the unlock does only when its consent holds (else by the file the discovery expects, and the source says it is
 * unresolved), and a password file is looked at (is it a vault itself, does it still hold the old password?) only
 * under its consent. Logs name files and ids only.
 */
internal class RotationSources(private val project: Project) {
    private val registry get() = VaultIdentityRegistry.getInstance(project)

    /**
     * The source of [label] in [discovery]'s root: while a rotation of it is pending (plan amendment R29 review,
     * decision 9), the source that still has to be updated; else the chain entry whose secret is current when the id
     * is unlocked, else the first entry with that label in Ansible's order; a prompt when there is none. Blocking (it
     * may read a consented `.env.local`); call off the EDT, never in a read action.
     */
    fun sourceOf(discovery: VaultDiscovery, label: String): RotationSource {
        val secrets = VaultSecretsService.getInstance(project)
        val pending = VaultUserState.getInstance().pendingRotation(discovery.canonicalRootPath, label)?.slot?.let(discovery::identityInSlot)
        val identity = pending ?: secrets.currentIdentity(discovery, label)
            ?: if (secrets.unlockedLabels(discovery).contains(label)) null else discovery.identities.firstOrNull { it.label == label }
        return sourceOf(discovery, label, identity, read = true)
    }

    /**
     * The chain entry a rotation treats as the source of the secret in [identity]'s slot: while a rotation of [label]
     * is pending, the root's own IDE password store entry (where the rotated password waits) stands in for the source
     * that still has to be updated (decision 9); otherwise [identity] itself.
     */
    fun standIn(discovery: VaultDiscovery, label: String, identity: DiscoveredIdentity?): DiscoveredIdentity? {
        val pending = VaultUserState.getInstance().pendingRotation(discovery.canonicalRootPath, label) ?: return identity
        if (identity == null || identity.slot != VaultSecretsService.interactiveSlot(label)) return identity
        return pending.slot?.let(discovery::identityInSlot) ?: identity
    }

    /** The source [identity] (of [discovery]'s chain, id [label]) is; with [read] a consented `.env.local` is read to find the file it names. */
    fun sourceOf(discovery: VaultDiscovery, label: String, identity: DiscoveredIdentity?, read: Boolean): RotationSource {
        val plan = identity?.plan ?: SecretPlan.Prompt
        return when (plan) {
            is SecretPlan.PasswordFile -> passwordFile(discovery, plan.target, identity, unresolved = null)
            is SecretPlan.EnvLocal -> {
                val resolved = if (read) envLocalTarget(discovery, plan) else EnvLocalTarget(null, consented = false)
                val named = resolved.target ?: plan.expected
                // D25: without a consent for .env.local the file it names is unknown; the dialog shows the expected one,
                // and its group's password file is not written (review, decision 5).
                val unresolved = if (resolved.consented) null else displayOf(plan.envLocal)
                if (named != null) {
                    passwordFile(discovery, named, identity, unresolved)
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

    private fun passwordFile(discovery: VaultDiscovery, target: ConsentTarget, identity: DiscoveredIdentity?, unresolved: String?): RotationSource {
        val outside = target.path?.let { real -> discovery.rootPath?.let { !real.startsWith(repositoryOf(it)) } } ?: false
        return RotationSource(
            "file:${target.locator}", VaultSourceKind.PASSWORD_FILE, displayOf(target), Destination.PASSWORD_FILE, target, identity,
            unresolved = unresolved, outside = outside,
        )
    }

    /** What resolving a `.env.local` gave: the file it names (or null), and whether its consent held so it could be read. */
    private class EnvLocalTarget(val target: ConsentTarget?, val consented: Boolean)

    /**
     * The file a consented `.env.local` names, resolved as the unlock resolves it ([EnvLocalFile], [VaultPaths]); no
     * target without a consent that still holds, or when it names nothing or a script. Reads `.env.local` (its key line
     * only).
     */
    private fun envLocalTarget(discovery: VaultDiscovery, plan: SecretPlan.EnvLocal): EnvLocalTarget {
        val access = registry.access
        val environment = access.environment()
        val check = VaultConsents(access, VaultUserState.getInstance()).check(plan.envLocal, environment) as? VaultConsents.Check.Granted
            ?: return EnvLocalTarget(null, consented = false)
        val value = try {
            EnvLocalFile.passwordFile(check.content, environment)
        } finally {
            check.content.fill(0)
        }
        val rootPath = discovery.rootPath ?: return EnvLocalTarget(null, consented = true)
        val path = value?.let { VaultPaths.resolve(it, rootPath, environment["HOME"]) } ?: return EnvLocalTarget(null, consented = true)
        if (access.isExecutable(path)) return EnvLocalTarget(null, consented = true)
        return EnvLocalTarget(ConsentTargets(rootPath, environment["HOME"], access).file(path), consented = true)
    }

    /**
     * Whether the password file [target] is a vault itself (D211: such a file is never written; you update it). Read
     * only under a consent that still holds; null when that is not the case (unknown yet).
     */
    fun isVaulted(target: ConsentTarget): Boolean? {
        val content = consented(target) ?: return null
        try {
            return isVault(content)
        } finally {
            content.fill(0)
        }
    }

    /** The content of [target] when its consent still holds (the caller zeroes it), else null. */
    fun consented(target: ConsentTarget): ByteArray? {
        val access = registry.access
        return (VaultConsents(access, VaultUserState.getInstance()).check(target, access.environment()) as? VaultConsents.Check.Granted)?.content
    }

    /** What a password file holds compared with a rotation's old password (plan amendment R29 review, decision 1). */
    enum class Holds {
        /** The old password, as Ansible reads the file. */
        OLD,

        /** Another password (a stale one, a teammate's new one pasted in). */
        OTHER,

        /** Not read: no consent, or the file changed since the consent (it is read again only after you agree). */
        NOT_READ,

        /** Missing or unreadable. */
        UNREADABLE,

        /** A vault itself (never written by a rotation). */
        VAULTED,
    }

    /**
     * Whether the password file [target] still holds [old] (Ansible's strip rules, compared in constant time), read under
     * its consent: a rotation overwrites a password file only then (decision 1). Blocking; call off the EDT.
     */
    fun holds(target: ConsentTarget, old: SecretBytes): Holds {
        val access = registry.access
        val check = VaultConsents(access, VaultUserState.getInstance()).check(target, access.environment())
        val content = when (check) {
            is VaultConsents.Check.Granted -> check.content
            is VaultConsents.Check.Needed -> return Holds.NOT_READ
            VaultConsents.Check.Unavailable -> return Holds.UNREADABLE
        }
        try {
            if (isVault(content)) return Holds.VAULTED
            val held = (SecretBytes.fromFile(content) { null } as? SecretLoad.Loaded)?.secret ?: return Holds.OTHER
            return held.use { secret -> secret.read { a -> old.read { b -> if (MessageDigest.isEqual(a, b)) Holds.OLD else Holds.OTHER } } }
        } finally {
            content.fill(0)
        }
    }

    /** How [replacePasswordFile] ended. */
    enum class Replaced { WRITTEN, CHANGED, FAILED }

    /**
     * Replaces the password file [target] (its real path) with [password] and no trailing newline (D211), when it still
     * holds [old] ([holds], decision 1; otherwise [Replaced.CHANGED] and nothing is written): a temporary file in the
     * same directory, created with mode 0600 on a POSIX file system, then moved over the file atomically
     * ([AtomicFiles]), so the file is never half written and never readable by others (repository finding: 0644). Its
     * consent (D25) is renewed for the new content, so the next unlock reads it without asking, and the VFS learns of
     * it. Blocking; call off the EDT. [Replaced.FAILED] (logged by class) when it could not be written.
     */
    fun replacePasswordFile(target: ConsentTarget, password: ByteArray, old: SecretBytes): Replaced {
        val path = target.path ?: return Replaced.FAILED
        VaultCorpusGuard.check(path)
        if (holds(target, old) != Holds.OLD) return Replaced.CHANGED
        try {
            AtomicFiles.replace(path, password, AtomicFiles.PRIVATE)
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, target.display)
            return Replaced.FAILED
        } catch (e: UnsupportedOperationException) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, target.display)
            return Replaced.FAILED
        }
        renewConsent(target, password)
        return Replaced.WRITTEN
    }

    /**
     * Renews the consent of [target] for [content], which the IDE itself just wrote (a password file, or a vaulted
     * password file the rotation re-encrypted), so the next unlock reads it without asking; the VFS learns of it.
     */
    fun renewConsent(target: ConsentTarget, content: ByteArray) {
        VaultUserState.getInstance().grant(ConsentRecord(target.locator, content.size.toLong(), ConsentFingerprint.of(content)))
        LocalFileSystem.getInstance().refreshNioFiles(listOfNotNull(target.path, target.declared).distinct())
    }

    /**
     * How a file source is named in the dialog, the review and the summary: project-relative inside the project, else as
     * its consent names it; a symbolic link also shows where it points (`.vault-pass → ~/x`, review decision 12), as
     * the consent dialog does: a cloned repository can commit a link.
     */
    private fun displayOf(target: ConsentTarget): String {
        val declared = target.declared ?: target.path ?: return target.display
        val name = relative(declared) ?: target.display.substringBefore(LINK)
        val real = target.path ?: return name
        val viaParent = declared.parent?.let(registry.access::canonical)?.resolve(declared.fileName)
        if (viaParent == null || viaParent == real) return name
        return name + LINK + (relative(real) ?: homeRelative(real) ?: real.toString())
    }

    private fun displayOf(path: Path): String = relative(path.toAbsolutePath().normalize()) ?: path.toString()

    private fun relative(path: Path): String? {
        val base = RootKeys.projectDir(project)?.toNioPathOrNull()?.toAbsolutePath()?.normalize() ?: return null
        return if (path.startsWith(base) && path != base) base.relativize(path).toString().replace('\\', '/') else null
    }

    private fun homeRelative(path: Path): String? {
        val home = registry.access.environment()["HOME"]?.let(VaultPaths::parse)?.toAbsolutePath()?.normalize() ?: return null
        val realHome = registry.access.canonical(home) ?: home
        val base = listOf(home, realHome).firstOrNull { path.startsWith(it) && path != it } ?: return null
        return "~/" + base.relativize(path)
    }

    companion object {
        private const val LINK = " → "
        private const val WHITESPACE = " \t\n\r\u000b\u000c"
        private const val HEAD_BYTES = 64

        /** True when [content] (a password file) is vault data, after leading whitespace. */
        fun isVault(content: ByteArray): Boolean {
            var start = 0
            while (start < content.size && content[start].toInt().toChar() in WHITESPACE) start++
            val head = content.copyOfRange(start, minOf(content.size, start + HEAD_BYTES))
            try {
                return VaultEnvelope.isEncrypted(head)
            } finally {
                head.fill(0)
            }
        }

        /**
         * The repository a root at [rootPath] lies in: the nearest directory at or above it with a `.git` entry (a
         * directory, or a file in a linked worktree), else the root itself. A password file outside it gets an explicit
         * note before the rotation writes it (review, decision 12).
         */
        fun repositoryOf(rootPath: Path): Path {
            val root = rootPath.toAbsolutePath().normalize()
            val real = runCatching { root.toRealPath() }.getOrDefault(root)
            return generateSequence(real) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) } ?: real
        }
    }
}
