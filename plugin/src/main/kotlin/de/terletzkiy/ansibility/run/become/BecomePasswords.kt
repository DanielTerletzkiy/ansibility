package de.terletzkiy.ansibility.run.become

import com.intellij.credentialStore.generateServiceName
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.identity.VaultPaths
import de.terletzkiy.ansibility.vault.secrets.MasterPasswordRequest
import de.terletzkiy.ansibility.vault.secrets.PasswordSafeCredentialStore
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.VaultCredentialStore
import de.terletzkiy.ansibility.vault.secrets.VaultPrompter
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The outcome of [BecomePasswords.obtain]. */
sealed interface BecomeResult {
    /** [password] belongs to the caller, who zeroes it; [origin] says where it came from (for the console header). */
    class Obtained(val password: CharArray, val origin: String) : BecomeResult {
        override fun toString(): String = "Obtained(***, $origin)"
    }

    data object Cancelled : BecomeResult
}

/** One root as the become password sees it: its directory (relative password files and the store key) and its name. */
class BecomeRoot(val path: Path, val displayName: String) {
    /** The real path, so that every clone and symlink of a root shares its stored password. */
    val canonicalPath: String by lazy { runCatching { path.toRealPath().toString() }.getOrDefault(path.normalize().toString()) }
}

/**
 * The become (sudo) passwords of runs, from the source the root's runner settings name for the environment, like a
 * vault id's secret: the IDE password store, a password manager (1Password, Bitwarden, KeePassXC, Proton Pass), a
 * password file (read, never run), an environment variable, or a prompt. A password typed at a prompt is remembered
 * as the user chooses (the password store, this session only, or not at all), for all environments of the root or
 * for one; the IDE password store entries are "Ansibility Become — <root>#<environment or *>". A password manager's
 * answer is kept like an unlocked vault id, so its CLI (and its approval, and macOS's question whether the IDE may use
 * another app's data) runs once per session. Session passwords are zeroed by the vault's lock ([forgetSession]),
 * after the vault's auto-lock time unused, and when the project closes. Nothing is written to disk.
 */
@Service(Service.Level.PROJECT)
class BecomePasswords(private val project: Project) : Disposable {
    @Volatile
    private var credentials: VaultCredentialStore = PasswordSafeCredentialStore

    @Volatile
    private var managers: PasswordManagerReader = PasswordManagers

    @Volatile
    private var variables: (String) -> String? = { EnvironmentUtil.getValue(it) }

    private val session = ConcurrentHashMap<String, CharArray>()

    /** Passwords read from a password manager, kept like unlocked vault ids so that its CLI and approval run once. */
    private class Read(val password: CharArray, @Volatile var used: Long)

    private val reads = ConcurrentHashMap<String, Read>()

    /**
     * The become password of [root] for [environment] (null: a root without environments). Reads the configured source
     * on `Dispatchers.IO`; asks on the EDT when there is none, it gave nothing, or it is a prompt with nothing remembered.
     */
    suspend fun obtain(root: BecomeRoot, settings: RunnerRootSettings, environment: String?): BecomeResult {
        // Not interrupted: a password manager's read ends its CLI when its caller is cancelled (it checks for that
        // while it waits, R19 D139), whereas an interrupt would leave the CLI and its approval sheet behind.
        val read = withContext(Dispatchers.IO) { readSource(root, settings.becomeSource(environment), environment, settings.becomeScope(environment)) }
        read.obtained?.let { return it }
        val error = read.error
        val request = BecomePasswordRequest(root.displayName, environment, error, credentials.isMemoryOnly)
        val answer = onEdt { BecomePrompter.getInstance().ask(project, request) } ?: return BecomeResult.Cancelled
        val password = answer.use { it.take() }
        val scope = if (answer.onlyEnvironment && environment != null) environment else RunnerRootSettings.ALL_ENVIRONMENTS
        withContext(Dispatchers.IO) { remember(root, scope, password, answer.remember) }
        return BecomeResult.Obtained(password, message("become.origin.typed"))
    }

    /** A configured source's password, or why it gave none ([error] is null for a prompt with nothing remembered). */
    private class SourceRead(val obtained: BecomeResult.Obtained?, val error: String? = null)

    private fun readSource(root: BecomeRoot, source: BecomeSource?, environment: String?, scope: String): SourceRead {
        val kind = source?.kind ?: VaultSourceKind.PROMPT
        val location = source?.location.orEmpty()
        fun found(password: CharArray?, origin: () -> String, error: () -> String): SourceRead =
            if (password != null) SourceRead(BecomeResult.Obtained(password, origin())) else SourceRead(null, error())
        return when (kind) {
            VaultSourceKind.PROMPT -> SourceRead(remembered(root, environment)?.let { BecomeResult.Obtained(it, message("become.origin.remembered")) })
            VaultSourceKind.PASSWORD_SAFE -> found(remembered(root, environment), { message("become.origin.store") }, { message("become.error.store") })
            VaultSourceKind.PASSWORD_FILE -> found(readFile(root, location), { message("become.origin.file", location) }, { message("become.error.file", location) })
            VaultSourceKind.ENVIRONMENT -> found(
                location.takeIf { it.isNotEmpty() }?.let(variables)?.takeIf { it.isNotEmpty() }?.toCharArray(),
                { message("become.origin.environment", location) },
                { message("become.error.environment", location) },
            )
            else -> {
                val manager = PasswordManager.of(kind) ?: return SourceRead(null)
                val key = "${root.canonicalPath}#$scope#${kind.name}#$location"
                earlier(key)?.let { return SourceRead(BecomeResult.Obtained(it, message("become.origin.manager.earlier", manager.displayName))) }
                val password = readManager(root, manager, location)
                password?.let { reads.put(key, Read(it.copyOf(), System.currentTimeMillis()))?.password?.fill('\u0000') }
                found(password, { message("become.origin.manager", manager.displayName) }, { message("become.error.manager", manager.displayName) })
            }
        }
    }

    /** What [obtain] will use for [environment], for the run dialog: "1Password (all environments)", "Asked before the run". */
    fun describe(settings: RunnerRootSettings, environment: String?): String {
        val source = settings.becomeSource(environment) ?: BecomeSource(VaultSourceKind.PROMPT)
        val where = if (settings.becomeScope(environment) == RunnerRootSettings.ALL_ENVIRONMENTS) message("become.scope.all") else environment.orEmpty()
        return when (source.kind) {
            VaultSourceKind.PROMPT -> message("become.describe.prompt")
            VaultSourceKind.PASSWORD_SAFE -> message("become.describe.source", message("become.kind.password_safe"), where)
            VaultSourceKind.PASSWORD_FILE, VaultSourceKind.ENVIRONMENT ->
                message("become.describe.location", message("become.kind.${source.kind.name.lowercase()}"), source.location.orEmpty(), where)
            else -> message("become.describe.source", PasswordManager.of(source.kind)?.displayName ?: source.kind.name, where)
        }
    }

    /** Stores [password] in the IDE password store for [scope] of [root] (null removes it). Blocking; the caller zeroes it. */
    fun store(root: BecomeRoot, scope: String, password: CharArray?) {
        session.remove(key(root, scope))?.fill('\u0000')
        credentials.write(serviceName(root, scope), USER, password?.takeIf { it.isNotEmpty() }, memoryOnly = false)
    }

    /** Whether the IDE password store has an entry for [scope] of [root]. Blocking. */
    fun hasStored(root: BecomeRoot, scope: String): Boolean = credentials.read(serviceName(root, scope), USER)?.also { it.fill('\u0000') } != null

    /** Zeroes and forgets the passwords remembered or read from a password manager this session (the vault's lock calls it). */
    fun forgetSession() {
        session.values.forEach { it.fill('\u0000') }
        session.clear()
        reads.values.forEach { it.password.fill('\u0000') }
        reads.clear()
    }

    /** A copy of a password manager's earlier answer, unless unused for longer than the vault's auto-lock time. */
    private fun earlier(key: String): CharArray? {
        val read = reads[key] ?: return null
        val now = System.currentTimeMillis()
        val minutes = VaultUserState.getInstance().autoLockMinutes
        if (minutes > 0 && now - read.used > minutes * 60_000L) {
            if (reads.remove(key, read)) read.password.fill('\u0000')
            return null
        }
        read.used = now
        return read.password.copyOf()
    }

    override fun dispose() = forgetSession()

    /** A copy of the remembered password: the environment's own before the root's, each this session's, then the store's. */
    private fun remembered(root: BecomeRoot, environment: String?): CharArray? {
        for (scope in listOfNotNull(environment, RunnerRootSettings.ALL_ENVIRONMENTS)) {
            session[key(root, scope)]?.let { return it.copyOf() }
            credentials.read(serviceName(root, scope), USER)?.let { return it }
        }
        return null
    }

    private fun remember(root: BecomeRoot, scope: String, password: CharArray, choice: RememberChoice) {
        when (choice) {
            RememberChoice.KEYCHAIN -> credentials.write(serviceName(root, scope), USER, password, memoryOnly = false)
            RememberChoice.SESSION -> session.put(key(root, scope), password.copyOf())?.fill('\u0000')
            RememberChoice.NONE -> Unit
        }
    }

    /** A password file's content without surrounding whitespace, as Ansible reads it; an executable file is never run. */
    private fun readFile(root: BecomeRoot, location: String): CharArray? {
        val path = location.takeIf { it.isNotEmpty() }?.let { VaultPaths.resolve(it, root.path, System.getProperty("user.home")) } ?: return null
        if (!Files.isRegularFile(path) || Files.isExecutable(path)) return null
        val bytes = try {
            if (Files.size(path) > MAX_FILE) return null
            Files.readAllBytes(path)
        } catch (_: IOException) {
            return null
        }
        return decode(bytes).takeIf { it.isNotEmpty() }
    }

    private fun readManager(root: BecomeRoot, manager: PasswordManager, reference: String): CharArray? {
        if (reference.isEmpty()) return null
        val keePass = PasswordManagers.keePass(reference).takeIf { manager == PasswordManager.KEEPASSXC }
        val resolved = keePass?.let { (database, entry) ->
            VaultPaths.resolve(database, root.path, System.getProperty("user.home"))?.let { "$it#$entry" }
        } ?: reference
        val prompt = MasterPasswordPrompt { m, target, retry ->
            var answer: CharArray? = null
            ApplicationManager.getApplication().invokeAndWait({
                answer = VaultPrompter.getInstance().askMasterPassword(project, MasterPasswordRequest(m.displayName, target, retry))
            }, ModalityState.any())
            answer
        }
        val bytes = managers.read(manager, resolved, prompt) ?: return null
        return decode(bytes).takeIf { it.isNotEmpty() }
    }

    private fun serviceName(root: BecomeRoot, scope: String): String = generateServiceName(SUBSYSTEM, "${root.canonicalPath}#$scope")

    private fun key(root: BecomeRoot, scope: String): String = "${root.canonicalPath}#$scope"

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }

    @TestOnly
    fun setSourcesForTests(credentials: VaultCredentialStore, managers: PasswordManagerReader, variables: (String) -> String?) {
        this.credentials = credentials
        this.managers = managers
        this.variables = variables
    }

    companion object {
        /** The PasswordSafe subsystem: "… Ansibility Become — <root>#<environment>". */
        const val SUBSYSTEM = "Ansibility Become"
        const val USER = "become"
        private const val MAX_FILE = 64 * 1024L

        fun getInstance(project: Project): BecomePasswords = project.service()

        /** UTF-8 [bytes] without surrounding whitespace; [bytes] and the intermediate buffers are zeroed. */
        internal fun decode(bytes: ByteArray): CharArray {
            val buffer: CharBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            bytes.fill(0)
            val all = CharArray(buffer.remaining()).also { buffer.get(it) }
            buffer.array().fill('\u0000')
            val start = all.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val end = all.indexOfLast { !it.isWhitespace() } + 1
            return all.copyOfRange(start, end.coerceAtLeast(start)).also { all.fill('\u0000') }
        }
    }
}
