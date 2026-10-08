package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultStatusListener
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.become.BecomeResult
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import de.terletzkiy.ansibility.vault.secrets.PasswordSafeCredentialStore
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDialog
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Plan amendment R19, D139: interactive unlocks honour "Not now" for the password managers of the root being unlocked,
 * a password manager read ends when its caller is cancelled (the CLI and whatever it started with it), and prompts,
 * consents and manager reads show in idea.log at INFO with names and labels only. The CLI tests run synthetic
 * `/bin/sh` scripts that sleep or print a synthetic password; no installed manager is ever run.
 */
class VaultManagerReadTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)

    private val reference = "op://Infra/ansible dev/password"

    override fun tearDown() {
        try {
            PasswordManagers.setCommandsForTests(null)
            PasswordManagers.forgetUnlocks()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** falcon: `.vault-pass` (default, needs a consent) and the explicit id dev in 1Password; reads are recorded. */
    private fun falconWithOnePassword(reads: MutableList<String>): AnsibleRoot {
        val falcon = projectRoot("falcon")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        configure(root, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, reference))
        secrets.setPasswordManagersForTests { manager, ref, _ ->
            reads += "$manager $ref"
            VaultVectors.DEV.toByteArray()
        }
        return root
    }

    // ------------------------------------------------------------------------------------------------ "Not now"

    fun testNotNowAlsoKeepsThePasswordManagersOfTheRootUnread() {
        val reads = CopyOnWriteArrayList<String>()
        val root = falconWithOnePassword(reads)
        prompter.onConsent = { VaultConsentDecision.NotNow }

        assertEquals("only the prompt is left, and it is cancelled", VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { ops.unlock(root) })
        assertEmpty("1Password is not run for a declined root", reads)
        assertEquals(listOf("default"), prompter.passwordRequests.map { it.label })
        assertEmpty(access.secretReads)
        // The dialog says so.
        val listed = prompter.consentRequests.single().roots.single()
        assertEquals(listOf("1Password"), listed.managers)
        assertEquals(
            "If you do not allow ${listed.displayName} now, its 1Password ids are not read either in this session; Unlock vault ids… asks again.",
            VaultConsentDialog.managersText(listed),
        )

        assertEquals(VaultUnlockResult.Failed(VaultFailure.LOCKED), await { secrets.unlock(root, interactive = false) })
        prompter.passwords += VaultVectors.PW1 to RememberChoice.NONE
        assertEquals(VaultUnlockResult.Unlocked(listOf("default")), await { ops.unlock(root) })
        assertEmpty("declined for the whole session", reads)
        assertEquals("asked once", 1, prompter.consentRequests.size)

        // Only the session's "Not now" kept it unread: once forgotten for the root (an explicit Unlock…), the same
        // unlock asks for the consent again and reads 1Password.
        secrets.lockAll()
        secrets.forgetDeclined(root)
        prompter.onConsent = { VaultConsentDecision.UseAll }
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "default")), await { ops.unlock(root) })
        assertEquals(listOf("ONE_PASSWORD $reference"), reads)
        assertEquals(2, prompter.consentRequests.size)
    }

    fun testNotNowLeavesThePasswordManagersOfTheOtherListedRootsAlone() {
        val reads = CopyOnWriteArrayList<String>()
        val roots = listOf("falcon", "heron").map { name ->
            val relative = projectRoot(name)
            write("$relative/.vault-pass", "${VaultVectors.PW1}\n")
            write("$relative/vault.yml", VaultVectors.raw("v02"))
            relative
        }.map { root(it) }
        val (falcon, heron) = roots
        configure(falcon, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, "op://Infra/falcon dev/password"))
        configure(heron, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, "op://Infra/heron dev/password"))
        secrets.setPasswordManagersForTests { _, ref, _ ->
            reads += ref
            VaultVectors.DEV.toByteArray()
        }

        prompter.onConsent = { VaultConsentDecision.NotNow }
        assertEquals(VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { ops.unlock(falcon) })
        val request = prompter.consentRequests.single()
        assertEquals("one dialog for both roots", 2, request.roots.size)
        assertEquals("only the root being unlocked loses its password managers", listOf(listOf("1Password"), emptyList()), request.roots.map { it.managers })
        assertNull(VaultConsentDialog.managersText(request.roots[1]))
        assertEmpty(reads)

        // heron was only listed beside falcon: its files stay unread and nothing is asked, but its 1Password id works.
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(heron) })
        assertEquals(listOf("op://Infra/heron dev/password"), reads)
        assertEquals(1, prompter.consentRequests.size)
        assertFalse(access.readSecretNamed(".vault-pass"))

        // The same with Choose…: a root left out keeps its password managers.
        secrets.lockAll()
        secrets.forgetDeclinedConsents()
        VaultUserState.getInstance().resetForTests()
        reads.clear()
        prompter.onConsent = { VaultConsentDecision.Choose(setOf(registry.rootKey(falcon))) }
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "default")), await { ops.unlock(falcon) })
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(heron) })
        assertEquals(listOf("op://Infra/falcon dev/password", "op://Infra/heron dev/password"), reads)
    }

    fun testAConsentForTheRootStillReadsItsPasswordManager() {
        val reads = CopyOnWriteArrayList<String>()
        val root = falconWithOnePassword(reads)
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "default")), await { ops.unlock(root) })
        assertEquals(listOf("ONE_PASSWORD $reference"), reads)
        assertEmpty(prompter.passwordRequests)
    }

    // ------------------------------------------------------------------------------------------------ cancellation

    fun testCancellingAnUnlockEndsAPasswordManagerReadThatIsStillWaiting() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        configure(root, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, reference))
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        secrets.setPasswordManagersForTests { _, _, _ ->
            started.countDown()
            try {
                Thread.sleep(60_000)
                null
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val unlock = scope.async { secrets.unlock(root) }
            PlatformTestUtil.waitWithEventsDispatching("the read did not start", { started.count == 0L }, 10)
            unlock.cancel()
            PlatformTestUtil.waitWithEventsDispatching("the unlock still waits for the password manager", { unlock.isCompleted }, 10)
            assertTrue(unlock.isCancelled)
            assertTrue("the waiting read was interrupted", interrupted.get())
        } finally {
            scope.cancel()
        }

        // Nothing is left holding the unlock: the next one reads again.
        secrets.setPasswordManagersForTests { _, _, _ -> VaultVectors.DEV.toByteArray() }
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root) })
    }

    fun testACancelledUnlockKeepsAndAnnouncesWhatItHadUnlockedBefore() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        // dev from the IDE password store first (no question), then prod from 1Password, which waits.
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(
                identities = listOf(
                    ExplicitIdentity("dev", VaultSourceKind.PASSWORD_SAFE, "falcon dev"),
                    ExplicitIdentity("prod", VaultSourceKind.ONE_PASSWORD, reference),
                ),
            )
        }
        secrets.storePassword(root, "dev", VaultVectors.DEV.toCharArray())
        val started = CountDownLatch(1)
        secrets.setPasswordManagersForTests { _, _, _ ->
            started.countDown()
            Thread.sleep(60_000)
            null
        }
        val events = AtomicInteger()
        project.messageBus.connect(testRootDisposable).subscribe(VaultStatusListener.TOPIC, VaultStatusListener { events.incrementAndGet() })
        clock.advanceMinutes(29)
        val stamp = secrets.lockTracker.modificationCount

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val unlock = scope.async { secrets.unlock(root) }
            PlatformTestUtil.waitWithEventsDispatching("the read did not start", { started.count == 0L }, 10)
            unlock.cancel()
            PlatformTestUtil.waitWithEventsDispatching("the unlock still waits for the password manager", { unlock.isCompleted }, 10)
            assertTrue(unlock.isCancelled)
        } finally {
            scope.cancel()
        }

        val discovery = registry.discovery(root)
        assertEquals("what was loaded before the cancel stays unlocked", listOf("dev"), secrets.unlockedLabels(discovery))
        assertTrue("the lock state changed", secrets.lockTracker.modificationCount > stamp)
        assertTrue("the gutter and the widgets are told", events.get() > 0)
        // The idle lock counts from the cancelled unlock, and locks it after the auto-lock time.
        clock.advanceMinutes(2)
        secrets.checkIdle()
        assertEquals(listOf("dev"), secrets.unlockedLabels(discovery))
        clock.advanceMinutes(30)
        secrets.checkIdle()
        assertEmpty(secrets.unlockedLabels(discovery))
    }

    fun testAnAnswerLeftBehindByACancelledUnlockIsZeroed() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        configure(root, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, reference))
        val unlock = AtomicReference<Deferred<VaultUnlockResult>>()
        val answer = VaultVectors.DEV.toByteArray()
        // The unlock is cancelled while the manager answers: the answer arrives, but nobody takes it any more.
        secrets.setPasswordManagersForTests { _, _, _ ->
            unlock.get().cancel()
            answer
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deferred = scope.async(start = CoroutineStart.LAZY) { secrets.unlock(root) }
            unlock.set(deferred)
            deferred.start()
            PlatformTestUtil.waitWithEventsDispatching("the unlock did not end", { deferred.isCompleted }, 10)
            assertTrue(deferred.isCancelled)
        } finally {
            scope.cancel()
        }
        assertTrue("the answer is zeroed", answer.all { it == 0.toByte() })
        assertEmpty(secrets.unlockedLabels(registry.discovery(root)))
    }

    fun testABecomeReadEndsWhenItsCallerIsCancelled() {
        if (SystemInfo.isWindows) return
        // BecomePasswords reads on Dispatchers.IO without runInterruptible: the slices' checkCanceled sees the
        // cancelled coroutine (262 installs its job as the thread's context), so Stop ends the CLI there too.
        val pidFile = base.resolve("cli.pid")
        PasswordManagers.setCommandsForTests(mapOf("op" to script("op", "echo \$\$ > '$pidFile'\nexec sleep 30\n")))
        val become = BecomePasswords.getInstance(project)
        become.setSourcesForTests(InMemoryCredentials(), PasswordManagers) { null }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val settings = RunnerRootSettings(become = mapOf(RunnerRootSettings.ALL_ENVIRONMENTS to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Infra/sudo/password")))
            val obtain = scope.async { become.obtain(BecomeRoot(Files.createDirectories(base.resolve("repos/tern/ansible")), "tern"), settings, "dev") }
            val pid = awaitPid(pidFile)
            obtain.cancel()
            PlatformTestUtil.waitWithEventsDispatching("the become read still waits for the CLI", { obtain.isCompleted }, 10)
            assertTrue(obtain.isCancelled)
            assertEnds(pid)
        } finally {
            scope.cancel()
            become.forgetSession()
            become.setSourcesForTests(PasswordSafeCredentialStore, PasswordManagers) { EnvironmentUtil.getValue(it) }
        }
    }

    fun testACancelledCoroutineEndsTheCli() {
        if (SystemInfo.isWindows) return
        val pidFile = base.resolve("cli.pid")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val read = scope.launch { runInterruptible { PasswordManagers.outputForTests(sleeper(pidFile), 60_000) } }
            val pid = awaitPid(pidFile)
            read.cancel()
            PlatformTestUtil.waitWithEventsDispatching("the read still waits for the CLI", { read.isCompleted }, 10)
            assertTrue(read.isCancelled)
            assertEnds(pid)
        } finally {
            scope.cancel()
        }
    }

    fun testACancelledProgressIndicatorEndsTheCli() {
        if (SystemInfo.isWindows) return
        val pidFile = base.resolve("cli.pid")
        val indicator = EmptyProgressIndicator()
        val outcome = readUnder(indicator) { PasswordManagers.outputForTests(sleeper(pidFile), 60_000) }
        val pid = awaitPid(pidFile)
        indicator.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the read ignores the cancelled indicator", { outcome.isDone }, 10)
        assertInstanceOf(outcome.get(), ProcessCanceledException::class.java)
        assertEnds(pid)
    }

    fun testACancelledOrTimedOutReadAlsoEndsWhatTheCliStarted() {
        if (SystemInfo.isWindows) return
        // A wrapper (bwbio, a shell shim) that runs the real CLI as its child and waits for it.
        val pidFile = base.resolve("child.pid")
        val wrapper = listOf("/bin/sh", "-c", "sleep 30 & echo \$! > '$pidFile'; wait")

        val indicator = EmptyProgressIndicator()
        val outcome = readUnder(indicator) { PasswordManagers.outputForTests(wrapper, 60_000) }
        val child = awaitPid(pidFile)
        indicator.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the read ignores the cancelled indicator", { outcome.isDone }, 10)
        assertEnds(child)

        Files.delete(pidFile)
        val timedOut = readUnder(EmptyProgressIndicator()) { PasswordManagers.outputForTests(wrapper, 2_000) }
        val second = awaitPid(pidFile)
        PlatformTestUtil.waitWithEventsDispatching("the read ignores its timeout", { timedOut.isDone }, 20)
        assertNull(timedOut.get())
        assertEnds(second)
    }

    fun testATimedOutReadEndsTheCliAndGivesNothing() {
        if (SystemInfo.isWindows) return
        val pidFile = base.resolve("cli.pid")
        assertNull(PasswordManagers.outputForTests(sleeper(pidFile), 2_000))
        // The shell writes its id first; on a very busy machine it may have been ended before it got to that.
        val pid = pidFile.takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }?.takeIf { it.isNotEmpty() }?.toLong()
        if (pid != null) assertEnds(pid)
        val printed = PasswordManagers.outputForTests(listOf("/bin/sh", "-c", "printf synthetic-heron"), 10_000)
        assertEquals("a CLI that answers is read as before", "synthetic-heron", printed?.let { String(it) })
    }

    fun testATypedKeePassPasswordIsZeroedWhenTheReadDoesNotFinish() {
        if (SystemInfo.isWindows) return
        val database = write("vaults/team.kdbx", "synthetic")
        val pidFile = base.resolve("cli.pid")
        val cli = script("keepassxc-cli", "echo \$\$ > '$pidFile'\nexec sleep 30\n")
        val typed = CopyOnWriteArrayList<CharArray>()
        val prompt = MasterPasswordPrompt { _, _, _ -> "ANSIBILITY-SENTINEL-82".toCharArray().also { typed += it } }
        val entry = "$database#Ansible/dev"

        PasswordManagers.setCommandsForTests(mapOf("keepassxc-cli" to cli), timeoutMillis = 1_000)
        assertNull("the CLI timed out", PasswordManagers.read(PasswordManager.KEEPASSXC, entry, prompt))
        assertTrue("zeroed after a timeout", typed.single().all { it == '\u0000' })

        Files.deleteIfExists(pidFile)
        PasswordManagers.setCommandsForTests(mapOf("keepassxc-cli" to cli))
        val indicator = EmptyProgressIndicator()
        val outcome = readUnder(indicator) { PasswordManagers.read(PasswordManager.KEEPASSXC, entry, prompt) }
        val pid = awaitPid(pidFile)
        indicator.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the read ignores the cancelled indicator", { outcome.isDone }, 10)
        assertInstanceOf(outcome.get(), ProcessCanceledException::class.java)
        assertEnds(pid)
        assertEquals("asked again: nothing was kept", 2, typed.size)
        assertTrue("zeroed after a cancel", typed[1].all { it == '\u0000' })
    }

    // ------------------------------------------------------------------------------------------------ logging

    fun testPromptsConsentsAndManagerReadsAreLoggedAtInfoWithNamesOnly() {
        if (SystemInfo.isWindows) return
        // Synthetic CLIs instead of any manager installed here: both print the synthetic dev password.
        PasswordManagers.setCommandsForTests(
            mapOf(
                "op" to script("op", "printf '%s' '${VaultVectors.DEV}'\n"),
                "keepassxc-cli" to script("keepassxc-cli", "cat > /dev/null\nprintf '%s' '${VaultVectors.DEV}'\n"),
            ),
        )
        val falcon = projectRoot("falcon")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val heron = projectRoot("heron")
        write("$heron/.vault-pass", "${VaultVectors.PW1}\n")
        write("$heron/vault.yml", VaultVectors.raw("v02"))
        val database = write("vaults/team.kdbx", "synthetic")
        val thrush = projectRoot("thrush")
        write("$thrush/vault.yml", VaultVectors.raw("v01"))
        val falconRoot = root(falcon)
        val heronRoot = root(heron)
        configure(falconRoot, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, reference))
        configure(heronRoot, ExplicitIdentity("dev", VaultSourceKind.KEEPASSXC, "$database#Ansible/dev"))
        prompter.masterPasswords += "ANSIBILITY-SENTINEL-81"
        val becomeReference = "op://Infra/sudo/password"

        val lines = capture {
            // One dialog lists falcon and heron.
            assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "default")), await { ops.unlock(falconRoot) })
            assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "default")), await { ops.unlock(heronRoot) })
            assertEquals(VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { ops.unlock(root(thrush)) })
            // A changed password file asks again (same size, other content).
            secrets.lockAll()
            write("$falcon/.vault-pass", "test-pass-9\n")
            assertTrue(await { ops.unlock(falconRoot) } is VaultUnlockResult.Unlocked)
            // A become password from 1Password goes through the same reader.
            val become = BecomePasswords.getInstance(project)
            become.setSourcesForTests(InMemoryCredentials(), PasswordManagers) { null }
            try {
                val settings = RunnerRootSettings(become = mapOf(RunnerRootSettings.ALL_ENVIRONMENTS to BecomeSource(VaultSourceKind.ONE_PASSWORD, becomeReference)))
                val obtained = await { become.obtain(BecomeRoot(base.resolve(falcon), "falcon"), settings, "dev") }
                (obtained as BecomeResult.Obtained).password.fill('\u0000')
            } finally {
                become.forgetSession()
                become.setSourcesForTests(PasswordSafeCredentialStore, PasswordManagers) { EnvironmentUtil.getValue(it) }
            }
        }

        val info = lines.filter { it.first == "INFO" }.map { it.second }
        fun assertLogged(vararg parts: String) = assertTrue("no INFO line with ${parts.toList()}: $info", info.any { line -> parts.all { it in line } })
        assertLogged("vault consent: CONSENT_ASKED", "repos/falcon/ansible")
        assertLogged("vault consent: CONSENT_ASKED", "repos/heron/ansible")
        assertLogged("vault load_source: MANAGER_READ_STARTED", "file=1Password", "key=dev")
        assertLogged("vault load_source: MANAGER_READ_ENDED", "file=1Password", "key=dev", "ms=", "outcome=OK")
        assertLogged("vault prompt: MASTER_PASSWORD_PROMPTED", "file=KeePassXC", "key=dev")
        assertLogged("vault load_source: MANAGER_READ_ENDED", "file=KeePassXC", "key=dev", "outcome=OK")
        assertLogged("vault prompt: PASSWORD_PROMPTED", "repos/thrush/ansible", "key=default")
        assertLogged("vault consent: CONSENT_ASKED", "file=.vault-pass", "key=default")
        // The become read has no vault id.
        assertTrue(info.toString(), info.any { it.endsWith("vault load_source: MANAGER_READ_STARTED file=1Password") })
        assertTrue(info.toString(), info.any { "MANAGER_READ_ENDED file=1Password ms=" in it && it.endsWith("outcome=OK") })
        // falcon's 1Password twice (before and after the lock), heron's KeePassXC, the become password.
        assertEquals("one start and one end per read", 4, info.count { "MANAGER_READ_STARTED" in it })
        assertEquals(4, info.count { "MANAGER_READ_ENDED" in it })
        val forbidden = listOf(VaultVectors.PW1, VaultVectors.DEV, "test-pass-9", "ANSIBILITY-SENTINEL-81", reference, becomeReference, "#Ansible/dev")
        for ((_, line) in lines) for (secret in forbidden) assertFalse("log line leaks '$secret': $line", secret in line)

        // "Not now": the skipped read is logged too.
        secrets.lockAll()
        secrets.forgetDeclinedConsents()
        VaultUserState.getInstance().resetForTests()
        prompter.onConsent = { VaultConsentDecision.NotNow }
        val skipped = capture { await { ops.unlock(falconRoot) } }
        assertTrue(skipped.toString(), skipped.any { (level, line) -> level == "INFO" && "MANAGER_READ_SKIPPED" in line && "file=1Password" in line && "key=dev" in line })
        assertFalse(skipped.toString(), skipped.any { (_, line) -> "MANAGER_READ_STARTED" in line })
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun configure(root: AnsibleRoot, identity: ExplicitIdentity) {
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) { VaultRootSettings(identities = listOf(identity)) }
    }

    /** A synthetic CLI that writes its process id to [pidFile] and sleeps for 30 s (exec: the id is the sleeping process). */
    private fun sleeper(pidFile: Path): List<String> = listOf("/bin/sh", "-c", "echo \$\$ > '$pidFile'; exec sleep 30")

    /** An executable `/bin/sh` script [name] with [body], standing in for a password manager's CLI. */
    private fun script(name: String, body: String): Path {
        val path = write("bin/$name", "#!/bin/sh\n$body")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    /** Runs [read] on a pooled thread under [indicator]; the future completes with what it threw, or null. */
    private fun readUnder(indicator: EmptyProgressIndicator, read: () -> Any?): CompletableFuture<Throwable?> {
        val outcome = CompletableFuture<Throwable?>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ProgressManager.getInstance().runProcess({ read() }, indicator)
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        return outcome
    }

    private fun awaitPid(pidFile: Path): Long {
        PlatformTestUtil.waitWithEventsDispatching("the CLI did not start", { Files.exists(pidFile) && Files.readString(pidFile).trim().isNotEmpty() }, 10)
        return Files.readString(pidFile).trim().toLong()
    }

    private fun assertEnds(pid: Long) =
        PlatformTestUtil.waitWithEventsDispatching("the CLI $pid still runs", { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) }, 5)

    /** Runs [block] with every logger also writing `level to line` into the returned list. */
    private fun capture(block: () -> Unit): List<Pair<String, String>> {
        val lines = CopyOnWriteArrayList<Pair<String, String>>()
        val previous = Logger.getFactory()
        val open = AtomicBoolean(true)
        Logger.setFactory(Logger.Factory { category -> LevelLogger(previous.getLoggerInstance(category), lines, open) })
        try {
            block()
        } finally {
            open.set(false)
            Logger.setFactory(previous)
        }
        return lines
    }

    private class LevelLogger(
        private val delegate: Logger,
        private val lines: MutableList<Pair<String, String>>,
        private val open: AtomicBoolean,
    ) : Logger() {
        private fun record(level: String, message: String?) {
            if (open.get() && message != null) lines += level to message
        }

        override fun isDebugEnabled(): Boolean = delegate.isDebugEnabled

        override fun debug(message: String?, t: Throwable?) {
            record("DEBUG", message)
            delegate.debug(message, t)
        }

        override fun info(message: String?, t: Throwable?) {
            record("INFO", message)
            delegate.info(message, t)
        }

        override fun warn(message: String?, t: Throwable?) {
            record("WARN", message)
            delegate.warn(message, t)
        }

        override fun error(message: String?, t: Throwable?, vararg details: String) {
            record("ERROR", message)
            delegate.error(message, t, *details)
        }
    }
}
