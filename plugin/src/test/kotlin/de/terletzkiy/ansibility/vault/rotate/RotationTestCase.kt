package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.vault.InMemoryCredentials
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.regenerate.FakeRegeneratePrompts
import de.terletzkiy.ansibility.vault.regenerate.RegeneratePrompts
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted answers to Rotate Vault Password…'s questions; records every request. */
class FakeRotationPrompts(private val password: () -> String) : RotationPrompts {
    val requests = CopyOnWriteArrayList<RotationRequest>()
    val reviews = CopyOnWriteArrayList<RotationReview>()

    /** Edits the request and answers with the new password; null cancels. */
    var onRotation: (RotationRequest) -> NewPassword? = { NewPassword(password().toCharArray(), generated = false) }

    /** Answers the review: true rotates, false cancels. */
    var onReview: (RotationReview) -> Boolean = { true }

    override fun chooseRotation(project: Project, request: RotationRequest): NewPassword? = onRotation(request.also { requests += it })

    override fun review(project: Project, review: RotationReview): Boolean = onReview(review.also { reviews += it })
}

/**
 * Base of the Rotate Vault Password… tests (plan amendment R29, Part 1): [VaultUiTestCase] with scripted rotation and
 * regenerate prompts, an in-memory IDE password store the test can look into, a new password generated at test time
 * (64 alphanumeric, like the dialog's), and a root `falcon` whose `.vault-pass` holds `test-pass-1` (id `default`).
 */
abstract class RotationTestCase : VaultUiTestCase() {
    protected lateinit var rotationPrompts: FakeRotationPrompts
    protected lateinit var regeneratePrompts: FakeRegeneratePrompts
    protected lateinit var credentials: InMemoryCredentials
    protected lateinit var root: String

    /** The new password of this test (synthetic, generated now). */
    protected lateinit var newPassword: String

    protected val service: RotationService get() = RotationService.getInstance(project)
    protected val vaultPath: String get() = "$root/group_vars/all/vault.yml"

    override fun setUp() {
        super.setUp()
        newPassword = NewPassword.generate(SecureRandom()).use { String(it.chars()) }
        rotationPrompts = FakeRotationPrompts { newPassword }
        ApplicationManager.getApplication().replaceService(RotationPrompts::class.java, rotationPrompts, testRootDisposable)
        regeneratePrompts = FakeRegeneratePrompts()
        ApplicationManager.getApplication().replaceService(RegeneratePrompts::class.java, regeneratePrompts, testRootDisposable)
        credentials = InMemoryCredentials()
        secrets.setCredentialsForTests(credentials)
        RotationHints.getInstance(project).drainForTests()
        service.resetForTests()
        root = defaultRoot("falcon")
    }

    override fun tearDown() {
        try {
            // A Copy New Password of the test is cleared now: the clipboard service is application-wide.
            scheduler.advance(de.terletzkiy.ansibility.vault.ui.VaultUiTimings.CLIPBOARD_MILLIS)
            ReencryptionWriter.failWriteForTests = null
            ReencryptionEngine.tamperForTests = null
            service.onStepForTests = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Registers the tree and waits for the indexes (the scan reads `ansible.vault`). */
    protected fun ready() {
        root(root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Waits until the last rotation (or Check) finished and the events it posted ran. */
    protected fun awaitRotation() {
        val job = service.lastRun ?: error("no rotation was started")
        waitFor("rotation timed out") { job.isCompleted }
    }

    /** Runs Rotate Vault Password… on [files] (none: every root) and waits for it. */
    protected fun rotate(vararg files: VirtualFile) {
        service.rotate(files.toList())
        awaitRotation()
    }

    /** The bytes of [relative] on disk, after the writes the IDE still has pending. */
    protected fun bytes(relative: String): ByteArray {
        (LocalFileSystem.getInstance() as? AsyncableFileSystem)?.fsync()
        return Files.readAllBytes(base.resolve(relative))
    }

    protected fun text(relative: String): String = String(bytes(relative), Charsets.UTF_8)

    /** The envelope text of [key]'s `!vault |` block in [text] (the body lines without their indentation). */
    protected fun envelopeOf(text: String, key: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        require(start >= 0) { "no key $key" }
        val body = lines.drop(start + 1).takeWhile { it.isNotBlank() && it.trimStart().let { line -> line.startsWith("\$ANSIBLE_VAULT") || line.all { c -> c in "0123456789abcdef" } } }
        return body.joinToString("\n") { it.trim() } + "\n"
    }

    /** The value of [key] in [relative] decrypted with [password], or null. */
    protected fun valueOf(relative: String, key: String, password: String): String? = decryptWith(envelopeOf(text(relative), key), password)

    /** The IDE password store entry [label] of [root] holds (the id's own entry), or null. */
    protected fun stored(root: AnsibleRoot, label: String = "default"): String? =
        credentials.read(VaultPasswordSafeKeys.serviceName(registry.discovery(root).canonicalRootPath, label), label)?.let { String(it) }

    /** The "before rotation" entry of [label] of [root] (D212), or null. */
    protected fun previous(root: AnsibleRoot, label: String = "default"): String? =
        credentials.read(VaultPasswordSafeKeys.previousServiceName(registry.discovery(root).canonicalRootPath, label), label)?.let { String(it) }
}
