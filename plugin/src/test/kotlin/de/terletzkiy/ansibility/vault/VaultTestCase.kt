package de.terletzkiy.ansibility.vault

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.keePass.InMemoryCredentialStore
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.identity.LocalVaultSourceAccess
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultSourceAccess
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.secrets.VaultConsentRequest
import de.terletzkiy.ansibility.vault.secrets.VaultCredentialStore
import de.terletzkiy.ansibility.vault.secrets.VaultPasswordAnswer
import de.terletzkiy.ansibility.vault.secrets.VaultPasswordRequest
import de.terletzkiy.ansibility.vault.secrets.VaultPrompter
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Synthetic vault material for plugin tests. Every password is registered in `tools/vault/SYNTHETIC.md`; envelopes
 * are either the committed codec vectors (read by path from `:semantics` test resources, never copied into the
 * plugin's test data) or generated at test time with the codec the vectors prove.
 */
object VaultVectors {
    /** `pw1` (label `default`). */
    const val PW1: String = "test-pass-1"

    /** `dev` (label `dev`). */
    const val DEV: String = "dev-pass-2"

    /** `prod` (label `prod`). */
    const val PROD: String = "prod-pass-3"

    /** The committed raw vectors; Gradle runs the plugin tests in the `plugin` module directory. */
    private val RAW: Path = Paths.get("../semantics/src/test/resources/vault/raw")

    /** The YAML file of vector [id] (`v01` …), as ansible-vault wrote it. */
    fun raw(id: String): String = Files.readString(RAW.resolve("$id.yml"))

    /** A fresh envelope of [plaintext] under [password] and [label] (`null` or `default` write 1.1). */
    fun encrypt(plaintext: String, password: String, label: String? = null): VaultEnvelope =
        SecretBytes.of(password.toByteArray(Charsets.UTF_8)).use { VaultAes256.encrypt(plaintext.toByteArray(Charsets.UTF_8), it, label) }

    /** A `key: !vault |` entry at column 0 with the repository's +2 indent. */
    fun inline(key: String, envelope: VaultEnvelope): String = VaultLayout.inlineBlock(key, 0, 2, envelope)

    /** Parses a well-formed envelope. */
    fun envelope(text: String): VaultEnvelope = (VaultEnvelope.parse(text) as EnvelopeParse.Ok).envelope
}

/** A virtual clock for the idle lock and the plaintext cache. */
class FakeClock(var now: Long = 1_000_000L) : VaultClock {
    override fun millis(): Long = now

    fun advanceMinutes(minutes: Long) {
        now += minutes * 60_000L
    }
}

/**
 * [VaultSourceAccess] over the real file system that records every read and fakes the environment, so a test sees
 * exactly which files vault code opened and the developer's own `ANSIBLE_VAULT_*` variables never leak in.
 */
class RecordingAccess(var environment: Map<String, String>) : VaultSourceAccess {
    private val delegate: VaultSourceAccess = LocalVaultSourceAccess

    /** Every [readSecret] call, in order. */
    val secretReads = CopyOnWriteArrayList<Path>()

    /** Every [readNonSecret] call, in order. */
    val nonSecretReads = CopyOnWriteArrayList<Path>()

    override fun exists(path: Path): Boolean = delegate.exists(path)
    override fun isRegularFile(path: Path): Boolean = delegate.isRegularFile(path)
    override fun isExecutable(path: Path): Boolean = delegate.isExecutable(path)
    override fun size(path: Path): Long? = delegate.size(path)
    override fun canonical(path: Path): Path? = delegate.canonical(path)

    override fun readNonSecret(path: Path, limit: Int): ByteArray? {
        nonSecretReads.add(path)
        return delegate.readNonSecret(path, limit)
    }

    override fun readSecret(path: Path, limit: Int): ByteArray {
        secretReads.add(path)
        return delegate.readSecret(path, limit)
    }

    override fun environment(): Map<String, String> = environment

    /** True when a file named [name] was read as a secret. */
    fun readSecretNamed(name: String): Boolean = secretReads.any { it.fileName.toString() == name }
}

/** A scripted [VaultPrompter]: records every request and answers from [onConsent] and [passwords]. */
class FakePrompter : VaultPrompter {
    val consentRequests = CopyOnWriteArrayList<VaultConsentRequest>()
    val passwordRequests = CopyOnWriteArrayList<VaultPasswordRequest>()

    @Volatile
    var onConsent: (VaultConsentRequest) -> VaultConsentDecision = { VaultConsentDecision.UseAll }

    /** The answers to password prompts, in order; null or an exhausted list cancels. */
    val passwords = java.util.concurrent.ConcurrentLinkedDeque<Pair<String, RememberChoice>>()

    override fun askConsent(project: Project, request: VaultConsentRequest): VaultConsentDecision {
        ThreadingAssertions.assertEventDispatchThread()
        consentRequests.add(request)
        return onConsent(request)
    }

    override fun askPassword(project: Project, request: VaultPasswordRequest): VaultPasswordAnswer? {
        passwordRequests.add(request)
        val (password, remember) = passwords.pollFirst() ?: return null
        return VaultPasswordAnswer(password.toCharArray(), remember)
    }

    val masterRequests = CopyOnWriteArrayList<de.terletzkiy.ansibility.vault.secrets.MasterPasswordRequest>()

    /** The answers to master password prompts, in order; an exhausted list cancels. */
    val masterPasswords = java.util.concurrent.ConcurrentLinkedDeque<String>()

    override fun askMasterPassword(project: Project, request: de.terletzkiy.ansibility.vault.secrets.MasterPasswordRequest): CharArray? {
        ThreadingAssertions.assertEventDispatchThread()
        masterRequests.add(request)
        return masterPasswords.pollFirst()?.toCharArray()
    }
}

/** An in-memory [VaultCredentialStore] keyed like PasswordSafe. */
class InMemoryCredentials : VaultCredentialStore {
    val entries = java.util.concurrent.ConcurrentHashMap<String, CharArray>()
    val memoryOnlyKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun read(serviceName: String, label: String): CharArray? = entries["$serviceName|$label"]?.copyOf()

    override fun write(serviceName: String, label: String, password: CharArray?, memoryOnly: Boolean) {
        val key = "$serviceName|$label"
        if (password == null) {
            entries.remove(key)
            memoryOnlyKeys.remove(key)
        } else {
            entries[key] = password.copyOf()
            if (memoryOnly) memoryOnlyKeys += key else memoryOnlyKeys -= key
        }
    }

    override val isMemoryOnly: Boolean get() = false
}

/**
 * A [PasswordSafe] over two [InMemoryCredentialStore]s (persistent and memory-only), installed for every vault test
 * so nothing can reach the real macOS Keychain: in the test IDE the PasswordSafe service is the split-mode
 * `RemotePasswordSafeImpl`, which on a normal host uses the configured native store.
 */
class InMemoryPasswordSafe : PasswordSafe() {
    val persistent = InMemoryCredentialStore()
    val memory = InMemoryCredentialStore()

    override fun get(attributes: CredentialAttributes): Credentials? = memory.get(attributes) ?: persistent.get(attributes)

    override fun set(attributes: CredentialAttributes, credentials: Credentials?) = set(attributes, credentials, false)

    override fun set(attributes: CredentialAttributes, credentials: Credentials?, memoryOnly: Boolean) {
        if (memoryOnly) {
            memory.set(attributes, credentials)
            persistent.set(attributes, null)
        } else {
            persistent.set(attributes, credentials)
            memory.set(attributes, null)
        }
    }

    override var isRememberPasswordByDefault: Boolean = true

    override val isMemoryOnly: Boolean get() = false

    override fun isPasswordStoredOnlyInMemory(attributes: CredentialAttributes, credentials: Credentials): Boolean =
        memory.get(attributes) != null
}

/**
 * Base for vault service tests: a real temporary directory holding synthetic roots (password files are written at
 * runtime, never committed), added as a content root of the light project; a scripted prompter, a recording file
 * access with a fake environment, and a virtual clock. Everything is reset afterwards: the light project and the
 * application are shared between tests.
 */
abstract class VaultTestCase : BasePlatformTestCase() {
    protected lateinit var prompter: FakePrompter
    protected lateinit var access: RecordingAccess
    protected lateinit var clock: FakeClock
    protected lateinit var base: Path
    protected lateinit var home: Path
    protected lateinit var passwordSafe: InMemoryPasswordSafe
    private var contentRoot: VirtualFile? = null
    private val scopes = ArrayList<CoroutineScope>()

    protected val registry: VaultIdentityRegistry get() = VaultIdentityRegistry.getInstance(project)
    protected val secrets: VaultSecretsService get() = VaultSecretsService.getInstance(project)
    protected val crypto: VaultCrypto get() = VaultCrypto.getInstance(project)

    override fun setUp() {
        super.setUp()
        VaultUserState.getInstance().resetForTests()
        base = FileUtil.createTempDirectory("ansibility-vault", null, true).toPath().toRealPath()
        home = Files.createDirectories(base.resolve("home"))
        VfsRootAccess.allowRootAccess(testRootDisposable, base.toString())
        prompter = FakePrompter()
        ApplicationManager.getApplication().replaceService(VaultPrompter::class.java, prompter, testRootDisposable)
        access = RecordingAccess(mapOf("HOME" to home.toString()))
        registry.setAccessForTests(access)
        clock = FakeClock()
        secrets.setClockForTests(clock)
        secrets.setCredentialsForTests(InMemoryCredentials())
        passwordSafe = InMemoryPasswordSafe()
        ApplicationManager.getApplication().replaceService(PasswordSafe::class.java, passwordSafe, testRootDisposable)
    }

    override fun tearDown() {
        try {
            secrets.lockAll()
            secrets.forgetDeclinedConsents()
            secrets.setClockForTests(VaultClock.SYSTEM)
            secrets.setCredentialsForTests(de.terletzkiy.ansibility.vault.secrets.PasswordSafeCredentialStore)
            secrets.setPasswordManagersForTests(de.terletzkiy.ansibility.vault.identity.PasswordManagers)
            registry.setAccessForTests(LocalVaultSourceAccess)
            VaultProjectSettings.getInstance(project).loadState(VaultProjectSettings.StateBean())
            VaultUserState.getInstance().resetForTests()
            VaultCorpusGuard.protectForTests(null)
            scopes.forEach { it.cancel() }
            contentRoot?.let { PsiTestUtil.removeContentEntry(module, it) }
            AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Writes [text] to [relative] below the synthetic tree. */
    protected fun write(relative: String, text: String): Path = write(relative, text.toByteArray(Charsets.UTF_8))

    protected fun write(relative: String, bytes: ByteArray): Path {
        val path = base.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return path
    }

    /** The root at [relative] (a directory with `ansible.cfg`), after the tree is registered and rescanned. */
    protected fun root(relative: String): AnsibleRoot {
        refresh()
        val dir = base.resolve(relative)
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }
            .singleOrNull { it.dir.toNioPath() == dir } ?: error("no root at $relative: ${AnsibleWorkspace.getInstance(project).roots().map { it.dir.path }}")
    }

    /** Refreshes the VFS below the tree, registers it as a content root once and rescans the roots. */
    protected fun refresh() {
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base) ?: error("no VFS dir for $base")
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        if (contentRoot == null) {
            PsiTestUtil.addContentRoot(module, dir)
            contentRoot = dir
        }
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        registry.invalidate()
    }

    /** The VFS file at [relative] (refreshed, without rescanning the roots). */
    protected fun vf(relative: String): VirtualFile {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(relative)) ?: error("no file $relative")
        file.refresh(false, false)
        return file
    }

    /** The location of [key]'s value in the YAML file at [relative]. */
    protected fun location(relative: String, key: String): SourceLocation {
        val file = vf(relative)
        val text = String(file.contentsToByteArray(), Charsets.UTF_8)
        val offset = Regex("(?m)^\\s*${Regex.escape(key)}:").find(text)?.range?.first ?: error("no key $key in $relative")
        return SourceLocation(file, offset + text.substring(offset).indexOf(key))
    }

    /**
     * Runs [block] on `Dispatchers.Default` and waits on the EDT while dispatching events, so code that switches to
     * the EDT for dialogs (the scripted prompter) completes. Exceptions are rethrown unwrapped.
     */
    protected fun <T> await(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val future = CompletableFuture<T>()
        scope.launch {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("vault operation timed out", { future.isDone }, 60)
        try {
            return future.get(1, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** A synthetic PROJECT root at `repos/<name>/ansible` with an empty `[defaults]` section unless [cfg] says more. */
    protected fun projectRoot(name: String, cfg: String = "[defaults]\n"): String {
        val relative = "repos/$name/ansible"
        write("$relative/ansible.cfg", cfg)
        return relative
    }
}
