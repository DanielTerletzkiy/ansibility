package de.terletzkiy.ansibility.model.drift

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * [RoleDriftService] on the synthetic tree `testData/toolwindow/drift` (plan amendment R9, testing item 4): one copy
 * per tier, variants, the pair without golden, the skip list, invalidation by VFS edits, unsaved documents (typing
 * and undo), structure changes, options, and the secret rules (testing item 5).
 *
 * Every test uses its own service instance ([DriftFixture.freshService]), so counters are exact.
 */
class RoleDriftServiceTest : BasePlatformTestCase() {
    private lateinit var service: RoleDriftService

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        DriftFixture.useGolden(project, testRootDisposable)
        service = DriftFixture.freshService(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun vf(path: String): VirtualFile = DriftFixture.file(myFixture, path)

    private fun dir(team: String, role: String = "web"): VirtualFile = vf(DriftFixture.roleDir(team, role))

    private fun web(): RoleDrift = DriftFixture.drift(service, "web")

    private fun webCopy(team: String): CopyDrift = DriftFixture.copyDrift(myFixture, service, "web", team)

    private fun teams(drift: RoleDrift): List<String> = drift.copies.map { it.copy.root.displayName }

    // ------------------------------------------------------------------ catalog and tiers

    fun testProjectServiceIsAvailable() {
        assertNotNull(RoleDriftService.getInstance(project))
    }

    fun testCatalogOfTheSyntheticTree() {
        val catalog = RoleCatalog.getInstance(project).snapshot()
        assertEquals(listOf("app-same", "base", "solo", "web"), catalog.names)
        assertEquals(listOf("golden") + DriftFixture.WEB_REPOS, catalog.copies("web").map { it.root.displayName })
        assertEquals(dir("golden"), catalog.reference("web")?.dir)
        assertEquals(listOf(true) + List(7) { false }, catalog.copies("web").map { it.isReference })
        assertEquals("8 web + 3 base + 2 solo + 1 app-same", 14, catalog.copyCount)
        assertEquals(3, catalog.sharedNameCount)
        assertNull(catalog.reference("solo"))
    }

    fun testEveryWebCopyGetsItsTier() {
        val drift = web()
        assertEquals(listOf("golden") + DriftFixture.WEB_REPOS, teams(drift))
        assertEquals(
            listOf(
                DriftTier.REFERENCE, DriftTier.BEHAVIOUR, DriftTier.MOLECULE_ONLY, DriftTier.MOLECULE_ONLY, DriftTier.IDENTICAL,
                DriftTier.SPEC_DEFAULTS, DriftTier.SPEC_DEFAULTS, DriftTier.BEHAVIOUR,
            ),
            drift.copies.map { it.tier },
        )
        assertEquals(dir("golden"), drift.reference?.dir)
        assertEquals(6, drift.differingCount)
        assertFalse(drift.identicalEverywhere)

        assertEquals(listOf("molecule/default/verify.yml"), webCopy("mol").paths.changed)
        assertEquals(listOf("meta/argument_specs.yml"), webCopy("spec").paths.changed)
        assertEquals("spec plus molecule stays spec/defaults", listOf("defaults/main.yml", "molecule/default/verify.yml"), webCopy("specmol").paths.changed)
        assertEquals(listOf("tasks/main.yml"), webCopy("tasks").paths.changed)
        assertTrue(webCopy("same").paths.isEmpty)
        assertEquals(16, webCopy("golden").fileCount)
    }

    fun testTheMissingFileCopyListsTwelvePaths() {
        val missing = webCopy("missing")
        assertEquals(listOf("defaults/main.yml", "handlers/main.yml", "tasks/main.yml"), missing.paths.changed)
        assertEquals(
            listOf(
                "meta/argument_specs.yml", "molecule/default/converge.yml", "molecule/default/molecule.yml",
                "molecule/default/prepare.yml", "molecule/default/verify.yml", "tasks/configure.yml", "tasks/install.yml",
                "tasks/service.yml", "templates/site.conf.j2",
            ),
            missing.paths.onlyInReference,
        )
        assertEquals(emptyList<String>(), missing.paths.onlyHere)
        assertEquals("3 changed + 9 only in golden, like heron percona", 12, missing.paths.size)
        assertEquals(7, missing.fileCount)
        assertEquals(DriftTier.BEHAVIOUR, missing.tier)
    }

    fun testVariantsGroupByteIdenticalCopies() {
        val drift = web()
        assertEquals(
            listOf(listOf("golden", "same"), listOf("missing"), listOf("mol", "mol2"), listOf("spec"), listOf("specmol"), listOf("tasks")),
            drift.variants.map { variant -> variant.copies.map { it.root.displayName } },
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), drift.variants.map { it.index })
        assertEquals(listOf(0, 1, 2, 2, 0, 3, 4, 5), drift.copies.map { it.variant })
    }

    fun testOtherNames() {
        val base = DriftFixture.drift(service, "base")
        assertEquals(listOf("golden", "mol", "same"), teams(base))
        assertTrue(base.identicalEverywhere)
        assertEquals(listOf(DriftTier.REFERENCE, DriftTier.IDENTICAL, DriftTier.IDENTICAL), base.copies.map { it.tier })

        val solo = DriftFixture.drift(service, "solo")
        assertNull("no golden copy", solo.reference)
        assertEquals(listOf("same", "tasks"), teams(solo))
        assertEquals(listOf(DriftTier.NO_REFERENCE, DriftTier.NO_REFERENCE), solo.copies.map { it.tier })
        assertEquals(2, solo.variants.size)
        assertTrue(solo.copies.all { it.paths.isEmpty })

        val app = DriftFixture.drift(service, "app-same")
        assertEquals(listOf("same"), teams(app))
        assertEquals(DriftTier.NO_REFERENCE, app.copies.single().tier)
        assertFalse("a single copy is not 'identical everywhere'", app.identicalEverywhere)

        assertNull(DriftFixture.await { service.drift("no-such-role") })
    }

    fun testBadgesSayDiffersNeverOutdated() {
        val drift = web()
        val badges = drift.copies.map { DriftTexts.badge(drift, it) }
        assertEquals(
            listOf("golden root", "Δ tasks/templates", "≈ molecule only", "≈ molecule only", "= golden", "Δ spec/defaults", "Δ spec/defaults", "Δ tasks/templates"),
            badges,
        )
        val solo = DriftFixture.drift(service, "solo")
        assertEquals("no golden copy", DriftTexts.badge(solo, solo.copies.first()))
        val app = DriftFixture.drift(service, "app-same")
        assertEquals("same only", DriftTexts.badge(app, app.copies.single()))
        assertEquals("…", DriftTexts.pending())
        for (tier in DriftTier.entries) {
            val tooltip = DriftTexts.tooltip(tier, "golden")
            assertTrue(tooltip, tooltip.contains("golden"))
            assertFalse(tooltip, tooltip.contains("outdated", ignoreCase = true))
        }
    }

    fun testCompareTwoCopies() {
        val catalog = RoleCatalog.getInstance(project)
        val mol = catalog.copyOf(dir("mol"))!!
        val specmol = catalog.copyOf(dir("specmol"))!!
        val paths = DriftFixture.await { service.compare(mol, specmol) }
        assertEquals(listOf("defaults/main.yml"), paths.changed)
        assertTrue(paths.onlyInReference.isEmpty() && paths.onlyHere.isEmpty())
        val reverse = DriftFixture.await { service.compare(catalog.copyOf(dir("missing"))!!, catalog.copyOf(dir("golden"))!!) }
        assertEquals(9, reverse.onlyHere.size)
        assertEquals(0, reverse.onlyInReference.size)
        assertEquals(DriftTier.MOLECULE_ONLY, DriftFixture.await { service.driftOf(mol) }?.tier)
    }

    fun testIgnoreMoleculeOption() {
        service.options = DriftOptions(ignoreMolecule = true)
        val drift = web()
        assertEquals(DriftOptions(ignoreMolecule = true), drift.options)
        assertEquals(DriftTier.IDENTICAL, webCopy("mol").tier)
        assertEquals(DriftTier.IDENTICAL, webCopy("mol2").tier)
        assertEquals(listOf("defaults/main.yml"), webCopy("specmol").paths.changed)
        assertEquals("missing no longer lists the 4 molecule files", 8, webCopy("missing").paths.size)
        assertEquals("16 files minus 4 molecule files", 12, webCopy("golden").fileCount)
        assertEquals(listOf("golden", "mol", "mol2", "same"), drift.variants.first().copies.map { it.root.displayName })
        service.options = DriftOptions()
        assertEquals(DriftTier.MOLECULE_ONLY, webCopy("mol").tier)
    }

    fun testSkipListAndIgnoredPaths() {
        val same = DriftFixture.roleDir("same")
        DriftFixture.write(myFixture, "$same/__pycache__/helper.cpython-312.pyc", byteArrayOf(0x42, 0x0d, 0x0d, 0x0a))
        DriftFixture.write(myFixture, "$same/library/helper.pyc", byteArrayOf(1, 2, 3))
        DriftFixture.write(myFixture, "$same/files/.DS_Store", byteArrayOf(0, 0, 0, 1))
        DriftFixture.write(myFixture, "$same/.ansible/cache.json", "{}")
        assertEquals(DriftTier.IDENTICAL, webCopy("same").tier)
        assertEquals(16, webCopy("same").fileCount)

        DriftFixture.write(myFixture, "$same/files/notes/local.txt", "only here\n")
        assertEquals(listOf("files/notes/local.txt"), webCopy("same").paths.onlyHere)
        val settings = AnsibilityProjectSettings.getInstance(project)
        val before = settings.settings
        try {
            settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = it.paths.extraIgnoredPaths + "**/files/notes/**")) }
            assertEquals("ignored paths take no part", DriftTier.IDENTICAL, webCopy("same").tier)
        } finally {
            settings.update { before }
        }
        assertEquals(DriftTier.BEHAVIOUR, webCopy("same").tier)
    }

    // ------------------------------------------------------------------ invalidation

    fun testOneVfsEditRehashesExactlyOneFile() {
        DriftFixture.await { service.driftAll() }
        val file = vf("${DriftFixture.roleDir("same")}/tasks/main.yml")
        val original = VfsUtilCore.loadText(file)
        val hashed = service.counters.filesHashed
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/tasks/main.yml", "$original\n# edited\n")
        val edited = webCopy("same")
        assertEquals(DriftTier.BEHAVIOUR, edited.tier)
        assertEquals(listOf("tasks/main.yml"), edited.paths.changed)
        assertEquals("only the edited file is rehashed", 1, service.counters.filesHashed - hashed)

        val walks = service.counters.copiesWalked
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/tasks/main.yml", original)
        assertEquals(DriftTier.IDENTICAL, webCopy("same").tier)
        assertEquals(2, service.counters.filesHashed - hashed)
        assertEquals("a name computation walks each copy once", 8, service.counters.copiesWalked - walks)
        assertEquals(0, service.counters.documentsHashed)
    }

    fun testRenamingAFileReusesItsHash() {
        DriftFixture.await { service.driftAll() }
        val hashed = service.counters.filesHashed
        WriteCommandAction.runWriteCommandAction(project) { vf("${DriftFixture.roleDir("tasks")}/files/motd.txt").rename(this, "motd.md") }
        val tasks = webCopy("tasks")
        assertEquals(listOf("files/motd.txt"), tasks.paths.onlyInReference)
        assertEquals(listOf("files/motd.md"), tasks.paths.onlyHere)
        assertEquals("a rename keeps the stamp: nothing is rehashed", 0, service.counters.filesHashed - hashed)
    }

    fun testUnsavedDocumentFlipsTheTierAndUndoRestoresIt() {
        val file = vf("${DriftFixture.roleDir("same")}/tasks/main.yml")
        assertEquals(DriftTier.IDENTICAL, webCopy("same").tier)
        val onDisk = VfsUtilCore.loadText(file)
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        myFixture.type("\n- name: Typed\n  ansible.builtin.meta: noop\n")
        assertTrue(FileDocumentManager.getInstance().isFileModified(file))

        val documents = service.counters.documentsHashed
        val typed = webCopy("same")
        assertEquals("drift follows the unsaved document", DriftTier.BEHAVIOUR, typed.tier)
        assertEquals(listOf("tasks/main.yml"), typed.paths.changed)
        assertEquals(1, service.counters.documentsHashed - documents)
        assertEquals("nothing was written to disk", onDisk, VfsUtilCore.loadText(file))

        undoAll(myFixture.editor, onDisk)
        assertEquals("undo restores the on-disk identity", DriftTier.IDENTICAL, webCopy("same").tier)
    }

    fun testUnsavedDocumentKeepsTheFileLineSeparators() {
        val rel = "templates/web.conf.j2"
        val crlf = "user {{ web_user }};\r\nlisten {{ web_port }};\r\n".toByteArray()
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/$rel", crlf)
        val copy = DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/$rel", crlf)
        assertEquals(DriftTier.IDENTICAL, webCopy("same").tier)

        val document = FileDocumentManager.getInstance().getDocument(copy)!!
        val text = document.text
        WriteCommandAction.runWriteCommandAction(project) { document.setText("$text# temporary\n") }
        assertEquals(DriftTier.BEHAVIOUR, webCopy("same").tier)
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        assertTrue(FileDocumentManager.getInstance().isFileModified(copy))
        assertEquals("the document is hashed with the file's CRLF separators", DriftTier.IDENTICAL, webCopy("same").tier)
        FileDocumentManager.getInstance().reloadFromDisk(document)
    }

    fun testComputationIsLazyAndTheFirstRequestStartsTheWorker() {
        val published = Collections.synchronizedList(ArrayList<String>())
        project.messageBus.connect(testRootDisposable).subscribe(RoleDriftListener.TOPIC, RoleDriftListener { published += it })
        Thread.sleep(200)
        assertEquals("nothing runs before a request", 0, service.counters.copiesWalked)
        assertNull(service.cached("web"))

        service.request(listOf("solo"))
        DriftFixture.waitFor("every name computed") { NAMES.all(service::isCurrent) }
        assertEquals("the requested (visible) name goes first", "solo", published.first())
        assertEquals(NAMES.toSet(), published.toSet())
        assertEquals("one walk per copy", 14L, service.counters.copiesWalked)
        assertEquals(DriftTier.MOLECULE_ONLY, service.cached(RoleCatalog.getInstance(project).copyOf(dir("mol"))!!)?.tier)
    }

    fun testTypingIsPublishedForThatNameOnly() {
        service.documentDebounce = 50.milliseconds
        service.request()
        DriftFixture.waitFor("every name computed") { NAMES.all(service::isCurrent) }
        val published = Collections.synchronizedList(ArrayList<String>())
        project.messageBus.connect(testRootDisposable).subscribe(RoleDriftListener.TOPIC, RoleDriftListener { published += it })
        val hashed = service.counters.filesHashed + service.counters.documentsHashed
        val stamp = service.modificationTracker.modificationCount

        val document = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("same")}/tasks/main.yml"))!!
        val text = document.text
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "# typed\n") }
        DriftFixture.waitFor("the edit is published") { service.cached("web")?.copyOf(dir("same"))?.tier == DriftTier.BEHAVIOUR }
        DriftFixture.waitFor("the worker is idle") { NAMES.all(service::isCurrent) }
        assertEquals(listOf("web"), published.distinct())
        assertEquals("only the typed file is rehashed", 1, service.counters.filesHashed + service.counters.documentsHashed - hashed)
        assertTrue(service.modificationTracker.modificationCount > stamp)

        published.clear()
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        DriftFixture.waitFor("the revert is published") { service.cached("web")?.copyOf(dir("same"))?.tier == DriftTier.IDENTICAL }
        assertEquals(listOf("web"), published.distinct())
        FileDocumentManager.getInstance().reloadFromDisk(document)
    }

    fun testCreateAndDeleteUnderRolesArriveThroughTheStructureTracker() {
        service.request()
        DriftFixture.waitFor("every name computed") { NAMES.all(service::isCurrent) }
        val same = DriftFixture.roleDir("same")

        DriftFixture.write(myFixture, "$same/tasks/extra.yml", "---\n- name: Extra\n  ansible.builtin.meta: noop\n")
        DriftFixture.waitFor("the new file is seen") { service.cached("web")?.copyOf(dir("same"))?.paths?.onlyHere == listOf("tasks/extra.yml") }
        assertEquals(DriftTier.BEHAVIOUR, service.cached("web")?.copyOf(dir("same"))?.tier)

        DriftFixture.delete(myFixture, "$same/tasks/extra.yml")
        DriftFixture.waitFor("the deletion is seen") { service.cached("web")?.copyOf(dir("same"))?.tier == DriftTier.IDENTICAL }

        DriftFixture.delete(myFixture, DriftFixture.roleDir("tasks", "solo"))
        DriftFixture.waitFor("a deleted copy leaves the name") { service.cached("solo")?.copies?.size == 1 }
        val solo = service.cached("solo")!!
        assertEquals("same only", DriftTexts.badge(solo, solo.copies.single()))

        DriftFixture.write(myFixture, "repos/mol/ansible/roles/solo/defaults/main.yml", "---\nsolo_mode: a\n")
        DriftFixture.write(myFixture, "repos/mol/ansible/roles/solo/tasks/main.yml", VfsUtilCore.loadText(vf("${DriftFixture.roleDir("same", "solo")}/tasks/main.yml")))
        DriftFixture.waitFor("a new copy joins the name") { service.cached("solo")?.copies?.size == 2 }
        assertEquals("the new copy is byte-identical to same's", 1, service.cached("solo")!!.variants.size)
    }

    // ------------------------------------------------------------------ secrets

    fun testSensitiveFilesAreFingerprintedButFlagged() {
        val vaultOperations = DecryptGuard()
        project.registerOrReplaceServiceInstance(VaultOperations::class.java, vaultOperations, testRootDisposable)
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/files/ssl/web.key", "synthetic golden key\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/files/ssl/web.key", "synthetic golden key\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("spec")}/files/ssl/web.key", "synthetic repo key!!\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("tasks")}/files/secrets.yml", DriftFixture.syntheticVault("synthetic drift vault"))

        val drift = web()
        val same = drift.copyOf(dir("same"))!!
        assertEquals("an equal key file is identical", DriftTier.IDENTICAL, same.tier)
        val spec = drift.copyOf(dir("spec"))!!
        assertEquals(listOf("files/ssl/web.key", "meta/argument_specs.yml"), spec.paths.changed)
        assertEquals(setOf("files/ssl/web.key"), spec.paths.sensitive)
        assertEquals(DriftTier.BEHAVIOUR, spec.tier)
        val tasks = drift.copyOf(dir("tasks"))!!
        assertEquals(listOf("files/secrets.yml"), tasks.paths.onlyHere)
        assertEquals("a whole-file vault is sensitive whatever its name", setOf("files/secrets.yml", "files/ssl/web.key"), tasks.paths.sensitive)
        assertEquals("three key files hashed (in memory only)", 3, service.counters.sensitiveContentReads)
        assertEquals(1, service.counters.wholeFileVaults)

        val printed = drift.toString()
        assertFalse(printed, printed.contains("synthetic golden key") || printed.contains("ANSIBLE_VAULT") || printed.contains("***"))
        DriftFixture.await { service.compare(drift.copies.first().copy, spec.copy) }
        assertEquals("drift and compare never decrypt", 0, vaultOperations.calls.get())
    }

    fun testSizeOnlyModeNeverReadsSensitiveFiles() {
        service.sensitiveContent = SensitiveContent.SIZE_ONLY
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/files/ssl/web.key", "synthetic key AAAA\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/files/ssl/web.key", "synthetic key BBBB\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("spec")}/files/ssl/web.key", "synthetic key, longer\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("mol")}/templates/deployment/.env", "SYNTHETIC=1\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("mol2")}/files/app.password", "synthetic\n")

        val drift = web()
        assertEquals("sensitive files are never read in size-only mode", 0, service.counters.sensitiveContentReads)
        assertEquals("five sensitive names, each fingerprinted once", 5, service.counters.sizeOnlyFiles)
        assertEquals("same length: identical by size (documented limit of size-only)", DriftTier.IDENTICAL, drift.copyOf(dir("same"))!!.tier)
        assertEquals(listOf("files/ssl/web.key", "meta/argument_specs.yml"), drift.copyOf(dir("spec"))!!.paths.changed)
        assertEquals(setOf("templates/deployment/.env", "files/ssl/web.key"), drift.copyOf(dir("mol"))!!.paths.sensitive)
        assertEquals(setOf("files/app.password", "files/ssl/web.key"), drift.copyOf(dir("mol2"))!!.paths.sensitive)
    }

    // ------------------------------------------------------------------ helpers

    /** Undoes the typing in [editor] (typed characters form several undo groups) until its text is [original]. */
    private fun undoAll(editor: Editor, original: String) {
        val textEditor = TextEditorProvider.getInstance().getTextEditor(editor)
        val undoManager = UndoManager.getInstance(project)
        var steps = 0
        while (editor.document.text != original) {
            assertTrue("undo available after $steps steps", undoManager.isUndoAvailable(textEditor) && steps < 100)
            undoManager.undo(textEditor)
            steps++
        }
        assertTrue(steps > 0)
    }

    /** Fails the test on any vault entry point: drift and compare must never unlock, decrypt or reveal. */
    private class DecryptGuard : VaultOperations {
        val calls = AtomicInteger()

        private fun refuse(): Nothing {
            calls.incrementAndGet()
            throw AssertionError("drift must never touch vault operations")
        }

        override suspend fun unlock(root: AnsibleRoot, identity: String?): VaultUnlockResult = refuse()
        override fun lockAll() = refuse()
        override suspend fun decrypt(location: SourceLocation, purpose: VaultPurpose): VaultDecryptResult = refuse()
        override suspend fun encrypt(root: AnsibleRoot, plaintext: ByteArray, identity: String?, target: VirtualFile?): VaultEncryptResult = refuse()
        override fun reveal(location: SourceLocation, editor: Editor?) = refuse()
    }

    private companion object {
        val NAMES = listOf("app-same", "base", "solo", "web")
    }
}
