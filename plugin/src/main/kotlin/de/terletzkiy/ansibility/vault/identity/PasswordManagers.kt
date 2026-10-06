package de.terletzkiy.ansibility.vault.identity

import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.api.VaultSourceKind
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The external password managers a vault id can read its secret from, each through its own CLI. */
enum class PasswordManager(val kind: VaultSourceKind, val displayName: String, val command: String, val example: String) {
    ONE_PASSWORD(VaultSourceKind.ONE_PASSWORD, "1Password", "op", "op://Private/Ansible prod/password"),
    BITWARDEN(VaultSourceKind.BITWARDEN, "Bitwarden", "bw", "Ansible prod"),
    KEEPASSXC(VaultSourceKind.KEEPASSXC, "KeePassXC", "keepassxc-cli", "~/secrets.kdbx#Ansible/prod"),
    PROTON_PASS(VaultSourceKind.PROTON_PASS, "Proton Pass", "pass-cli", "pass://Infra/Ansible prod/password"),
    ;

    companion object {
        fun of(kind: VaultSourceKind): PasswordManager? = entries.firstOrNull { it.kind == kind }
    }
}

/**
 * Asks the user for a manager's own unlock secret: Bitwarden's master password, or a KeePassXC database's password
 * ([target] is the database). Null when cancelled or when no dialog may be shown. The reader zeroes the result.
 */
fun interface MasterPasswordPrompt {
    fun ask(manager: PasswordManager, target: String, retry: Boolean): CharArray?

    companion object {
        val NONE = MasterPasswordPrompt { _, _, _ -> null }
    }
}

/** Reads one secret from a password manager; the caller zeroes the returned bytes. */
fun interface PasswordManagerReader {
    /** The secret, or null when the CLI is missing, the read was refused, cancelled or timed out. */
    fun read(manager: PasswordManager, reference: String, prompt: MasterPasswordPrompt): ByteArray?

    /** Drops the unlocks kept for this session (a Bitwarden session key, KeePassXC database passwords). */
    fun forgetUnlocks() {}
}

/**
 * The CLIs of the supported managers. Each read is one process with stdout read into a byte array only:
 * - 1Password: `op read --no-newline op://…`; the desktop app's CLI integration asks for Touch ID itself.
 * - Proton Pass: `pass-cli item view pass://…`, using the CLI's own login.
 * - Bitwarden: `bwbio get password <item>` when the Touch ID wrapper is installed; else `bw get password <item>` with
 *   the `BW_SESSION` of the environment, or a session from `bw unlock` with the master password asked once.
 * - KeePassXC: `keepassxc-cli show -s -a Password <db> <entry>` with the database password on stdin, asked once.
 * Session keys and master passwords stay in memory until [forgetUnlocks] (the vault's lock). Blocking; call off the EDT.
 */
object PasswordManagers : PasswordManagerReader {
    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_ATTEMPTS = 3
    private const val BW_PASSWORD_VARIABLE = "ANSIBILITY_BW_PASSWORD"
    private const val KDBX_SEPARATOR = ".kdbx#"

    @Volatile
    private var bitwardenSession: CharArray? = null
    private val keePassPasswords = ConcurrentHashMap<String, CharArray>()

    /** Whether [text] is a reference [manager] can read (single line, no surrounding whitespace). */
    fun isReference(manager: PasswordManager, text: String): Boolean {
        if (text.isEmpty() || text != text.trim() || text.any { it == '\n' || it == '\r' || it == '\t' }) return false
        return when (manager) {
            PasswordManager.ONE_PASSWORD -> uri(text, "op://", minSlashes = 2)
            PasswordManager.PROTON_PASS -> uri(text, "pass://", minSlashes = 2) && !text.endsWith("/")
            PasswordManager.BITWARDEN -> true
            PasswordManager.KEEPASSXC -> keePass(text) != null
        }
    }

    /** `<database>#<entry>` of a KeePassXC reference (split after `.kdbx` when present), or null. */
    fun keePass(text: String): Pair<String, String>? {
        val at = text.lowercase().indexOf(KDBX_SEPARATOR).takeIf { it >= 0 }?.let { it + KDBX_SEPARATOR.length - 1 } ?: text.indexOf('#')
        if (at <= 0 || at == text.length - 1) return null
        return text.substring(0, at) to text.substring(at + 1)
    }

    /** The CLI of [manager] on the login shell's `PATH` or in the usual install directories, or null. */
    fun executable(manager: PasswordManager): Path? = find(manager.command)

    /** The Bitwarden CLI wrapper that unlocks through the desktop app (Touch ID, Windows Hello), when installed. */
    fun bitwardenBiometrics(): Path? = find("bwbio")

    override fun read(manager: PasswordManager, reference: String, prompt: MasterPasswordPrompt): ByteArray? {
        if (!isReference(manager, reference)) return null
        return when (manager) {
            PasswordManager.ONE_PASSWORD -> executable(manager)?.let { run(listOf(it.toString(), "read", "--no-newline", reference))?.output() }
            PasswordManager.PROTON_PASS -> executable(manager)?.let { run(listOf(it.toString(), "item", "view", reference))?.output() }
            PasswordManager.BITWARDEN -> readBitwarden(reference, prompt)
            PasswordManager.KEEPASSXC -> readKeePass(reference, prompt)
        }
    }

    override fun forgetUnlocks() {
        bitwardenSession?.fill('\u0000')
        bitwardenSession = null
        keePassPasswords.values.forEach { it.fill('\u0000') }
        keePassPasswords.clear()
    }

    // ------------------------------------------------------------------ Bitwarden

    private fun readBitwarden(item: String, prompt: MasterPasswordPrompt): ByteArray? {
        bitwardenBiometrics()?.let { wrapper ->
            return run(listOf(wrapper.toString(), "get", "password", item))?.output()
        }
        val bw = executable(PasswordManager.BITWARDEN)?.toString() ?: return null
        val get = listOf(bw, "get", "password", item, "--nointeraction")
        val inherited = EnvironmentUtil.getValue("BW_SESSION")?.takeIf { it.isNotBlank() }
        val cached = bitwardenSession
        val session = cached?.let(::String) ?: inherited
        if (session != null) {
            val result = run(get, mapOf("BW_SESSION" to session)) ?: return null
            result.output()?.let { return it }
            if (unlocked(bw, session)) return null
            if (cached != null) synchronized(this) { if (bitwardenSession === cached) forgetBitwardenSession() }
        }
        repeat(MAX_ATTEMPTS) { attempt ->
            val master = prompt.ask(PasswordManager.BITWARDEN, item, retry = attempt > 0) ?: return null
            val fresh = try {
                run(listOf(bw, "unlock", "--passwordenv", BW_PASSWORD_VARIABLE, "--raw", "--nointeraction"), mapOf(BW_PASSWORD_VARIABLE to String(master)))
            } finally {
                master.fill('\u0000')
            }?.output() ?: return@repeat
            val key = decode(fresh)
            fresh.fill(0)
            synchronized(this) {
                forgetBitwardenSession()
                bitwardenSession = key
            }
            return run(get, mapOf("BW_SESSION" to String(key)))?.output()
        }
        return null
    }

    private fun unlocked(bw: String, session: String): Boolean =
        run(listOf(bw, "unlock", "--check", "--nointeraction"), mapOf("BW_SESSION" to session))?.let { it.exit == 0 } == true

    private fun forgetBitwardenSession() {
        bitwardenSession?.fill('\u0000')
        bitwardenSession = null
    }

    // ------------------------------------------------------------------ KeePassXC

    private fun readKeePass(reference: String, prompt: MasterPasswordPrompt): ByteArray? {
        val (database, entry) = keePass(reference) ?: return null
        val cli = executable(PasswordManager.KEEPASSXC)?.toString() ?: return null
        if (!Files.isRegularFile(Path.of(database))) return null
        val command = listOf(cli, "show", "-q", "-s", "-a", "Password", database, entry)
        repeat(MAX_ATTEMPTS) { attempt ->
            val cached = keePassPasswords[database]
            val password = cached ?: prompt.ask(PasswordManager.KEEPASSXC, database, retry = attempt > 0) ?: return null
            val stdin = encode(password)
            val result = try {
                run(command, stdin = stdin)
            } finally {
                stdin.fill(0)
            } ?: return null
            result.output()?.let {
                if (cached == null) keePassPasswords.put(database, password)?.takeIf { old -> old !== password }?.fill('\u0000')
                return it
            }
            keePassPasswords.remove(database, password)
            password.fill('\u0000')
            if (!result.credentialsRejected) return null
        }
        return null
    }

    // ------------------------------------------------------------------ processes

    /** A finished process: its exit code, stdout (a secret, zeroed by whoever takes it) and a short stderr. */
    private class Finished(val exit: Int, private val out: ByteArray, private val err: String) {
        /** stdout when the process succeeded with output; else null (and stdout is zeroed). */
        fun output(): ByteArray? {
            if (exit == 0 && out.isNotEmpty()) return out
            out.fill(0)
            return null
        }

        val credentialsRejected: Boolean
            get() = err.contains("credentials", ignoreCase = true) || err.contains("password", ignoreCase = true) ||
                err.contains("Error while reading the database", ignoreCase = true)
    }

    private fun run(command: List<String>, extraEnvironment: Map<String, String> = emptyMap(), stdin: ByteArray? = null): Finished? {
        val process = try {
            ProcessBuilder(command)
                .redirectInput(if (stdin == null) ProcessBuilder.Redirect.from(nullFile()) else ProcessBuilder.Redirect.PIPE)
                .apply {
                    environment().putAll(EnvironmentUtil.getEnvironmentMap())
                    environment().putAll(extraEnvironment)
                }
                .start()
        } catch (_: Exception) {
            return null
        }
        val out = WipeableBuffer()
        val err = ByteArrayOutputStream()
        try {
            val readers = listOf(
                Thread { runCatching { process.inputStream.use { it.transferTo(out) } } },
                Thread { runCatching { process.errorStream.use { s -> s.readNBytes(4096).let(err::writeBytes); s.transferTo(java.io.OutputStream.nullOutputStream()) } } },
            ).onEach { it.isDaemon = true; it.start() }
            if (stdin != null) runCatching { process.outputStream.use { it.write(stdin) } }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            readers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
            return Finished(process.exitValue(), out.toByteArray(), err.toString(StandardCharsets.UTF_8))
        } finally {
            out.wipe()
        }
    }

    private fun find(name: String): Path? {
        val names = if (isWindows()) listOf("$name.exe", "$name.cmd") else listOf(name)
        val home = System.getProperty("user.home").orEmpty()
        val extra = listOf(
            "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin", "$home/.local/bin", "$home/bin", "$home/.npm-global/bin",
            "/Applications/KeePassXC.app/Contents/MacOS", "C:\\Program Files\\1Password CLI", "C:\\Program Files\\KeePassXC",
        )
        val path = EnvironmentUtil.getValue("PATH").orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }
        return (path + extra).asSequence()
            .flatMap { dir -> names.asSequence().map { runCatching { Path.of(dir, it) }.getOrNull() } }
            .filterNotNull()
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private fun uri(text: String, prefix: String, minSlashes: Int): Boolean =
        text.startsWith(prefix) && text.length > prefix.length && text.removePrefix(prefix).count { it == '/' } >= minSlashes

    /** [password] and a newline as UTF-8, in an array the caller zeroes. */
    private fun encode(password: CharArray): ByteArray {
        val chars = CharBuffer.allocate(password.size + 1).put(password).put('\n').flip()
        val buffer = StandardCharsets.UTF_8.encode(chars)
        chars.array().fill('\u0000')
        return ByteArray(buffer.remaining()).also { buffer.get(it); buffer.array().fill(0) }
    }

    /** UTF-8 [bytes] without surrounding whitespace, in an array the caller zeroes. */
    private fun decode(bytes: ByteArray): CharArray {
        val buffer = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
        val all = CharArray(buffer.remaining()).also { buffer.get(it); buffer.array().fill('\u0000') }
        val start = all.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val end = all.indexOfLast { !it.isWhitespace() } + 1
        return all.copyOfRange(start, end.coerceAtLeast(start)).also { all.fill('\u0000') }
    }

    /** A byte buffer whose internal array can be zeroed (`toByteArray` hands out copies). */
    private class WipeableBuffer : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun nullFile(): java.io.File = java.io.File(if (isWindows()) "NUL" else "/dev/null")
}
