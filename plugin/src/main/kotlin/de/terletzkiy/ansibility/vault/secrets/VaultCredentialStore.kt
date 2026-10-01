package de.terletzkiy.ansibility.vault.secrets

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Typed passwords per (root, id) in the IDE's password safe (D26). Every call may block (the macOS Keychain can show
 * a permission prompt), so callers run it on `Dispatchers.IO`, never on the EDT or under a read lock.
 */
interface VaultCredentialStore {
    /** The password stored under [serviceName] for [label], or null; the caller zeroes the array. */
    fun read(serviceName: String, label: String): CharArray?

    /** Stores a copy of [password] (null removes the entry); [memoryOnly] keeps it until the IDE exits only. */
    fun write(serviceName: String, label: String, password: CharArray?, memoryOnly: Boolean)

    /** True when the user turned persistence off (Settings › Passwords › "Do not save"). */
    val isMemoryOnly: Boolean
}

/** [VaultCredentialStore] on [PasswordSafe] (the deprecated `PasswordStorage` is not used). */
object PasswordSafeCredentialStore : VaultCredentialStore {
    override fun read(serviceName: String, label: String): CharArray? {
        val credentials = PasswordSafe.instance.get(CredentialAttributes(serviceName, label)) ?: return null
        // A copy: the in-memory store hands out the entry it keeps, so clearing it would forget the password.
        return credentials.password?.toCharArray(false)?.takeIf { it.isNotEmpty() }
    }

    override fun write(serviceName: String, label: String, password: CharArray?, memoryOnly: Boolean) {
        // The copy belongs to PasswordSafe from here on; the caller zeroes its own array. "This session only" is the
        // memoryOnly argument (PasswordSafe's memory store); the attributes carry no memory-only flag, which would
        // keep the password out of that store too.
        PasswordSafe.instance.set(CredentialAttributes(serviceName, label), password?.let { Credentials(label, it.copyOf()) }, memoryOnly)
    }

    override val isMemoryOnly: Boolean get() = PasswordSafe.instance.isMemoryOnly
}
