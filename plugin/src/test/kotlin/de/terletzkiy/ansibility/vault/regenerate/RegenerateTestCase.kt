package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted answers to Regenerate Secrets…'s questions; records every request. Never sees a value. */
class FakeRegeneratePrompts : RegeneratePrompts {
    val valueRequests = CopyOnWriteArrayList<RegenerateRequest>()
    val generatorRequests = CopyOnWriteArrayList<GeneratorRequest>()
    val scriptRequests = CopyOnWriteArrayList<VirtualFile?>()
    val importResults = CopyOnWriteArrayList<Pair<Int, List<String>>>()

    /** What each import said about the script's default arm (null: the script has none). */
    val importDefaultArms = CopyOnWriteArrayList<String?>()

    /** Edits the selection and answers: true regenerates, false cancels. */
    var onValues: (RegenerateRequest) -> Boolean = { true }

    /** The generator chosen in the intentions' chooser; null cancels. */
    var onGenerator: (GeneratorRequest) -> SecretGenerator? = { it.preset }

    /** The script Import from Script… gets. */
    var script: VirtualFile? = null

    override fun chooseValues(project: Project, request: RegenerateRequest): Boolean = onValues(request.also { valueRequests += it })

    override fun chooseGenerator(project: Project, request: GeneratorRequest): SecretGenerator? = onGenerator(request.also { generatorRequests += it })

    override fun chooseScript(project: Project, preselect: VirtualFile?): VirtualFile? {
        scriptRequests += preselect
        return script
    }

    override fun importResult(project: Project, imported: Int, skipped: List<String>, defaultArm: String?) {
        importResults += imported to skipped
        importDefaultArms += defaultArm
    }
}

/**
 * Base of the Regenerate Secrets… tests (plan amendment R29, Part 2): [VaultUiTestCase] with scripted regenerate
 * prompts, a seeded generator (so a test knows which values are new) and a root `falcon` whose ids are dev and prod
 * (`vault_identity_list`) and default (`.vault-pass`).
 */
abstract class RegenerateTestCase : VaultUiTestCase() {
    protected lateinit var regeneratePrompts: FakeRegeneratePrompts
    protected lateinit var root: String
    protected val service: RegenerateService get() = RegenerateService.getInstance(project)

    override fun setUp() {
        super.setUp()
        regeneratePrompts = FakeRegeneratePrompts()
        ApplicationManager.getApplication().replaceService(RegeneratePrompts::class.java, regeneratePrompts, testRootDisposable)
        service.setRandomForTests(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(29L) }, testRootDisposable)
        root = defaultRoot("falcon", cfg = "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n")
        write("$root/dev.pw", "${VaultVectors.DEV}\n")
        write("$root/prod.pw", "${VaultVectors.PROD}\n")
    }

    /** Registers the tree and waits for the indexes (the scan reads `ansible.vault`). */
    protected fun ready() {
        root(root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Waits until the last regeneration finished and the events it posted ran. */
    protected fun awaitRun() {
        val job = service.lastRun ?: error("no regeneration was started")
        waitFor("regeneration timed out") { job.isCompleted }
    }

    /** Runs Regenerate Secrets… on [files] and waits for it. */
    protected fun regenerate(vararg files: VirtualFile) {
        service.regenerate(files.toList())
        awaitRun()
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
    protected fun valueOf(relative: String, key: String, password: String = VaultVectors.PW1): String? = decryptWith(envelopeOf(text(relative), key), password)

    /** One global undo (a confirmation answered OK), then everything saved. */
    protected fun undoOnce() {
        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            val undo = UndoManager.getInstance(project)
            assertTrue("global undo is available", undo.isUndoAvailable(null))
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    /** The row for [keyPath] in the last selection the dialog got. */
    protected fun row(keyPath: String): RegenerateRow = regeneratePrompts.valueRequests.last().rows.single { it.keyPath == keyPath }
}
