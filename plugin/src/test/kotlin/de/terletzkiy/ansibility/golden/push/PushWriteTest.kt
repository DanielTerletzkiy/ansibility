package de.terletzkiy.ansibility.golden.push

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.DriftTier
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.run.molecule.MoleculeBatchLauncher
import de.terletzkiy.ansibility.run.molecule.MoleculeTarget
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * What a push does on real files (plan amendment R24, D189–D191): mirror (byte-identical copies, deletes on and off),
 * a role created in a root without it, sensitive files only when opted in (never decrypted), the binary confirmation,
 * every precondition failing for all roots at once (stale, unsaved, read-only), a changed source, the notification
 * and its Undo, Run Molecule Tests and Commit… buttons, and the whole way from the action.
 */
class PushWriteTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingPushUi

    override fun setUp() {
        super.setUp()
        ui = RecordingPushUi()
        PushTestSupport.install(ui, testRootDisposable)
    }

    override fun tearDown() {
        try {
            PushService.getInstance(project).hookForTests = null
            PushService.getInstance(project).specCheckBudget = PushService.SPEC_CHECK_BUDGET
            FileEditorManager.getInstance(project).openFiles.forEach(FileEditorManager.getInstance(project)::closeFile)
            MoleculeBatchLauncher.executeForTests = null
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun golden(role: String = "web"): RoleCopy = roleCopy("golden", role)

    private fun push(vararg names: String, options: PushOptions = PushOptions(), source: RoleCopy = golden()): PushOutcome =
        PushTestSupport.push(project, source, names.toList(), options)

    private fun plan(source: String, target: String, includeSensitive: Boolean = true): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), PlanOptions(includeSensitive = includeSensitive)) }

    /** Every file of [team]'s copy of [role] on disk, as path → Base64 of its bytes. */
    private fun snapshot(team: String, role: String = "web"): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team, role))
        if (!Files.isDirectory(dir)) return emptyMap()
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(dir).toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
        }
    }

    /** Clicks the notification's Undo; the platform must not ask (D190: the push asks once itself, through PushUi). */
    private fun clickUndo(outcome: PushOutcome) {
        val platformAsked = AtomicInteger()
        TestDialogManager.setTestDialog { platformAsked.incrementAndGet(); Messages.OK }
        try {
            PushTestSupport.click(project, outcome.notification!!, "Undo")
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("the platform never asks per repo", 0, platformAsked.get())
    }

    /** A root `repos/wren/ansible` with a roles directory but no `web`. */
    private fun addWren() {
        Files.createDirectories(base.resolve("repos/wren/ansible/roles/other/tasks"))
        Files.writeString(base.resolve("repos/wren/ansible/ansible.cfg"), "[defaults]\n")
        Files.writeString(base.resolve("repos/wren/ansible/roles/other/tasks/main.yml"), "---\n")
        refresh()
    }

    // ------------------------------------------------------------------ mirror

    fun testMirrorMakesTheTickedCopiesByteIdenticalAndLeavesTheOthers() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val untouched = listOf("same", "spec", "specmol", "mol2").associateWith { snapshot(it) }

        val outcome = push("missing", "mol", "tasks")
        assertEquals(outcome.toString(), PushOutcome.Status.PUSHED, outcome.status)
        for (team in listOf("missing", "mol", "tasks")) {
            assertTrue("$team is byte-identical to golden", plan("golden", team).isIdentical)
            assertEquals(snapshot("golden"), snapshot(team))
        }
        assertFalse("an extra file is deleted by default", Files.exists(path("missing", "files/notes/extra.txt")))
        for ((team, files) in untouched) assertEquals("$team was not ticked", files, snapshot(team))

        val drift = DriftFixture.drift(RoleDriftService.getInstance(project), "web")
        val mol = drift.copyOf(roleDir("mol"))!!
        assertEquals(DriftTier.IDENTICAL, mol.tier)
        assertEquals("the badge flips", "= golden", DriftTexts.badge(drift, mol))
    }

    fun testWithoutDeletesExtraFilesStay() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val outcome = push("missing", options = PushOptions(deleteExtra = false))
        assertEquals(PushOutcome.Status.PUSHED, outcome.status)
        assertTrue(Files.exists(path("missing", "files/notes/extra.txt")))
        assertEquals(listOf("files/notes/extra.txt"), plan("golden", "missing").entries.map { it.relPath })
    }

    fun testARootWithoutTheRoleGetsItCreated() {
        addWren()
        val row = PushTestSupport.rows(project, golden()).single { it.name == "wren" }
        assertTrue(row.createsRole)
        val outcome = push("wren")
        assertEquals(outcome.toString(), PushOutcome.Status.PUSHED, outcome.status)
        assertEquals(snapshot("golden"), snapshot("wren"))
        refresh()
        val created = RoleCatalog.getInstance(project).copyOf(roleDir("wren"))
        assertNotNull("the new copy is in the catalog", created)
        assertTrue(plan("golden", "wren").isIdentical)
        assertTrue(ui.notifications.single().content, ui.notifications.single().content.startsWith("Pushed web from golden to wren: 16 added"))

        clickUndo(outcome)
        assertEquals("Undo removes every created file", emptyMap<String, String>(), snapshot("wren"))
        assertFalse("and the role directory", Files.exists(base.resolve(DriftFixture.roleDir("wren"))))
    }

    // ------------------------------------------------------------------ never touched (D191)

    fun testPathsThatAreNeverTouchedSurviveAMirrorAndTheExecutableBitFollowsTheSource() {
        writeOnDisk("golden", "files/run.sh", "#!/bin/sh\necho golden\n".toByteArray())
        Files.setPosixFilePermissions(path("golden", "files/run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"))
        writeOnDisk("tasks", "files/run.sh", "#!/bin/sh\necho tasks\n".toByteArray())
        writeOnDisk("tasks", "files/__pycache__/helper.cpython-312.pyc", byteArrayOf(1, 2, 3))
        writeOnDisk("tasks", "files/.DS_Store", byteArrayOf(0, 0, 0, 1))
        writeOnDisk("tasks", "library/helper.pyc", byteArrayOf(4, 5))
        writeOnDisk("tasks", ".git", "gitdir: ../../.git/modules/x\n".toByteArray())
        val outside = Files.createDirectories(base.resolve("outside"))
        Files.writeString(outside.resolve("linked.yml"), "---\n")
        Files.createSymbolicLink(path("tasks", "files/linked"), outside)
        refresh()

        val outcome = push("tasks")
        assertEquals(outcome.toString(), PushOutcome.Status.PUSHED, outcome.status)
        for (kept in listOf("files/__pycache__/helper.cpython-312.pyc", "files/.DS_Store", "library/helper.pyc", ".git", "files/linked/linked.yml")) {
            assertTrue("$kept is never touched, deletes or not", Files.exists(path("tasks", kept)))
        }
        assertEquals("#!/bin/sh\necho golden\n", String(bytes("tasks", "files/run.sh")))
        assertEquals("the source's executable bit", PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(path("tasks", "files/run.sh")))
    }

    // ------------------------------------------------------------------ sensitive files (D191)

    fun testKeyAndVaultFilesAreLeftAsTheyAreUnlessOptedInAndNeverDecrypted() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material A\n".toByteArray())
        writeOnDisk("mol", "files/ssl/web.key", "synthetic key material B\n".toByteArray())
        writeOnDisk("golden", "vars/secrets.yml", DriftFixture.syntheticVault("golden").toByteArray())
        writeOnDisk("mol", "vars/secrets.yml", DriftFixture.syntheticVault("mol").toByteArray())
        writeOnDisk("mol", "files/ssl/extra.pem", "synthetic extra\n".toByteArray())
        val key = bytes("mol", "files/ssl/web.key")
        val vault = bytes("mol", "vars/secrets.yml")

        val first = push("mol")
        assertEquals(PushOutcome.Status.PUSHED, first.status)
        assertEquals(String(key), String(bytes("mol", "files/ssl/web.key")))
        assertEquals(String(vault), String(bytes("mol", "vars/secrets.yml")))
        assertTrue("an extra key file is never deleted unless opted in", Files.exists(path("mol", "files/ssl/extra.pem")))
        assertTrue(first.notification!!.content, first.notification.content.contains("Key and vault files were left as they are."))
        assertEquals(setOf("files/ssl/extra.pem", "files/ssl/web.key", "vars/secrets.yml"), plan("golden", "mol").entries.map { it.relPath }.toSet())

        val second = push("mol", options = PushOptions(includeSensitive = true))
        assertEquals(PushOutcome.Status.PUSHED, second.status)
        assertTrue("opted in, the copy matches golden, ciphertext as it is", plan("golden", "mol").isIdentical)
        assertFalse(Files.exists(path("mol", "files/ssl/extra.pem")))
        assertEquals("nothing is decrypted", 0, guard.calls.get())
    }

    // ------------------------------------------------------------------ binary files

    fun testBinaryFilesAreConfirmedFirst() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        writeOnDisk("golden", "files/logo.png", png)
        writeOnDisk("mol", "files/logo.png", png + byteArrayOf(1))
        writeOnDisk("tasks", "files/logo.png", png)
        val mol = snapshot("mol")
        ui.confirm = { false }

        val declined = push("mol", "tasks")
        assertEquals(PushOutcome.Status.CANCELLED, declined.status)
        assertEquals(listOf(listOf("mol: files/logo.png")), ui.binaryLists)
        assertEquals("nothing written", mol, snapshot("mol"))

        ui.confirm = { true }
        val pushed = push("mol", "tasks")
        assertEquals(PushOutcome.Status.PUSHED, pushed.status)
        assertTrue(plan("golden", "mol").isIdentical)
        val content = pushed.notification!!.content
        assertTrue(content, content.contains("1 file cannot be undone (binary content); git can restore it."))
    }

    // ------------------------------------------------------------------ preconditions: all or nothing

    fun testAFileChangedInOneRootWritesNothingAnywhere() {
        val mol = snapshot("mol")
        PushService.getInstance(project).hookForTests = object : PushService.Hook {
            override fun beforeWrite() = writeOnDisk("tasks", "tasks/main.yml", "---\n# edited elsewhere\n".toByteArray())
        }
        val edited = "---\n# edited elsewhere\n"

        val outcome = push("mol", "tasks")
        assertEquals(outcome.toString(), PushOutcome.Status.NOT_WRITTEN, outcome.status)
        assertEquals(WriteResult.Status.STALE, outcome.results.getValue(roleDir("tasks")).status)
        assertEquals(WriteResult.Status.NOT_RUN, outcome.results.getValue(roleDir("mol")).status)
        assertEquals(mol, snapshot("mol"))
        assertEquals(edited, String(bytes("tasks", "tasks/main.yml")))
        val notice = ui.notices.single()
        assertTrue(notice, notice.startsWith("Nothing was pushed of web:\ntasks: A file changed since the comparison (tasks/main.yml)"))
        assertTrue("no notification", ui.notifications.isEmpty())
    }

    fun testAReadOnlyFileInOneRootWritesNothingAnywhere() {
        val tasks = snapshot("tasks")
        Files.setPosixFilePermissions(path("mol", "molecule/default/verify.yml"), PosixFilePermissions.fromString("r--r--r--"))
        refresh()
        try {
            val outcome = push("mol", "tasks")
            assertEquals(PushOutcome.Status.NOT_WRITTEN, outcome.status)
            assertEquals(WriteResult.Status.READ_ONLY, outcome.results.getValue(roleDir("mol")).status)
            assertEquals(tasks, snapshot("tasks"))
            assertTrue(ui.notices.single(), ui.notices.single().contains("mol: A file is read-only (molecule/default/verify.yml)"))
        } finally {
            Files.setPosixFilePermissions(path("mol", "molecule/default/verify.yml"), PosixFilePermissions.fromString("rw-r--r--"))
            refresh()
        }
    }

    fun testUnsavedChangesInATargetCountAsStale() {
        val document = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# typed\n") }
        try {
            val mol = snapshot("mol")
            val outcome = push("mol", "tasks")
            assertEquals(PushOutcome.Status.NOT_WRITTEN, outcome.status)
            assertEquals(mol, snapshot("mol"))
            assertTrue("the typed text stays", document.text.startsWith("# typed\n"))
            assertTrue(ui.notices.single(), ui.notices.single().contains("unsaved changes (tasks: tasks/main.yml)"))
        } finally {
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
    }

    fun testUnsavedChangesInTheSourceArePushedAsSeen() {
        val document = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("golden")}/handlers/main.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# typed in golden\n") }
        try {
            assertEquals(PushOutcome.Status.PUSHED, push("mol").status)
            assertTrue(String(bytes("mol", "handlers/main.yml")).startsWith("# typed in golden\n"))
        } finally {
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
    }

    fun testASourceChangedWhilePushingAsksToRetry() {
        val mol = snapshot("mol")
        PushService.getInstance(project).hookForTests = object : PushService.Hook {
            override fun afterPlan(row: PushRow) {
                ApplicationManager.getApplication().invokeAndWait { writeOnDisk("golden", "molecule/default/verify.yml", "---\n# golden edited\n".toByteArray()) }
            }
        }
        val outcome = push("mol")
        assertEquals(outcome.toString(), PushOutcome.Status.SOURCE_CHANGED, outcome.status)
        assertEquals(mol, snapshot("mol"))
        assertTrue(ui.notices.single(), ui.notices.single().contains("web in golden changed while it was pushed (molecule/default/verify.yml). Nothing was written; push again."))
    }

    // ------------------------------------------------------------------ afterwards (D190)

    fun testTheNotificationSummarisesThePushAndOffersItsButtons() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val outcome = push("missing", "mol", "same", "tasks")
        val notification = outcome.notification!!
        assertSame(notification, ui.notifications.single())
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals("Pushed web from golden to missing, mol, tasks: 5 changed, 9 added, 1 deleted<br>Already the same: same", notification.content)
        assertEquals(listOf("Undo", "Run Molecule Tests", "Commit…"), PushTestSupport.actions(notification))
        assertNotNull("the VCS module is there: the platform commit UI", ActionManager.getInstance().getAction(PushService.COMMIT_ACTION_ID))
        assertEquals(listOf(roleDir("missing").path, roleDir("mol").path, roleDir("tasks").path), outcome.moleculeTargets.map { it.spec.roleDir })

        val ran = CopyOnWriteArrayList<List<MoleculeTarget>>()
        MoleculeBatchLauncher.executeForTests = { ran += it }
        PushTestSupport.click(project, notification, "Run Molecule Tests")
        assertEquals(listOf(listOf("missing", "mol", "tasks")), ran.map { targets -> targets.map { it.label } })
    }

    fun testNoMoleculeButtonWithoutScenariosOrWithMoleculeRunsOff() {
        val settings = AnsibilityProjectSettings.getInstance(project)
        settings.update { it.copy(molecule = it.molecule.copy(runTests = false)) }
        val off = push("mol")
        assertEquals(listOf("Undo", "Commit…"), PushTestSupport.actions(off.notification!!))

        settings.update { it.copy(molecule = it.molecule.copy(runTests = true)) }
        val noScenarios = push("mol2", source = golden("base"))
        assertEquals("base has no Molecule scenarios", listOf("Undo", "Commit…"), PushTestSupport.actions(noScenarios.notification!!))
    }

    fun testNothingToPushWhenEveryTickedCopyIsTheSame() {
        val outcome = push("same")
        assertEquals(PushOutcome.Status.NOTHING_TO_DO, outcome.status)
        assertEquals("Nothing to push: web in same is already the same as in golden", outcome.notification!!.content)
        assertTrue(outcome.notification.actions.isEmpty())
    }

    fun testUndoAsksOnceAndRevertsEveryPushedRoot() {
        val before = listOf("missing", "mol", "tasks").associateWith { snapshot(it) }
        val outcome = push("missing", "mol", "tasks")
        val commands = outcome.undo!!.commands
        assertEquals(3, commands.size)
        val number = Regex("""Push web to missing \(#(\d+)\)""").matchEntire(commands[0])?.groupValues?.get(1)
        assertNotNull("S2: the push's own command names: ${commands[0]}", number)
        assertEquals(listOf("Push web to missing (#$number)", "Push web to mol (#$number)", "Push web to tasks (#$number)"), commands)
        assertTrue(outcome.undo.canUndo())

        clickUndo(outcome)
        assertEquals("one question for the whole push", listOf("Undo the push of web to missing, mol, tasks?"), ui.undoQuestions)
        for ((team, files) in before) assertEquals("$team is restored", files, snapshot(team))
        assertTrue(outcome.notification!!.isExpired)
        assertFalse(outcome.undo.canUndo())
    }

    fun testDecliningTheOneQuestionUndoesNothing() {
        val outcome = push("mol", "tasks")
        val pushed = listOf("mol", "tasks").associateWith { snapshot(it) }
        ui.answerUndo = { false }
        clickUndo(outcome)
        assertEquals(listOf("Undo the push of web to mol, tasks?"), ui.undoQuestions)
        for ((team, files) in pushed) assertEquals("$team stays pushed", files, snapshot(team))
        assertFalse("the notification stays for another try", outcome.notification!!.isExpired)
        assertTrue(outcome.undo!!.canUndo())
    }

    fun testEditUndoFromAnEditorRevertsTheMostRecentRepoOnlyWithoutAQuestion() {
        val mol = snapshot("mol")
        val tasks = snapshot("tasks")
        val outcome = push("mol", "tasks")
        val pushedMol = snapshot("mol")
        assertFalse(mol == pushedMol)
        val file = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        val editor = FileEditorManager.getInstance(project).openFile(file, true).filterIsInstance<TextEditor>().single()

        val platformAsked = AtomicInteger()
        TestDialogManager.setTestDialog { platformAsked.incrementAndGet(); Messages.OK }
        try {
            val undo = UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(editor))
            assertTrue(undo.getUndoActionNameAndDescription(editor).second.contains("Push web to tasks (#"))
            undo.undo(editor)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("no question", 0, platformAsked.get())
        assertEquals("the most recent repo is reverted", tasks, snapshot("tasks"))
        assertEquals("the repo before it stays pushed", pushedMol, snapshot("mol"))
        assertEquals("U5: the push's own Undo now undoes the rest", listOf("mol"), outcome.undo!!.remainingNames)
        assertTrue(outcome.undo.canUndo())
        assertTrue("the next global Undo is the repo before", UndoManager.getInstance(project).getUndoActionNameAndDescription(null).second.contains("Push web to mol (#"))
    }

    fun testUndoIsUnavailableOnceAnotherCommandIsOnTop() {
        val outcome = push("mol")
        val file = vf("${DriftFixture.roleDir("same")}/tasks/main.yml")
        WriteCommandAction.writeCommandAction(project).withName("Other change").withGlobalUndo().run<RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)!!.insertString(0, "# other\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(outcome.undo!!.canUndo())
        val pushed = snapshot("mol")

        clickUndo(outcome)
        assertEquals("nothing was undone", pushed, snapshot("mol"))
        assertTrue(outcome.notification!!.isExpired)
        val offer = ui.notifications.last()
        assertEquals(NotificationType.WARNING, offer.type)
        assertTrue(offer.content, offer.content.contains("can no longer be undone here"))
        assertTrue(offer.content, offer.content.contains("Before pushing web from golden to mol"))
        assertEquals(listOf("Show Local History"), PushTestSupport.actions(offer))
    }

    fun testALaterPushOfTheSameCopyTakesOverUndo() {
        val first = push("mol")
        writeOnDisk("mol", "tasks/main.yml", "---\n# edited after the first push\n".toByteArray())
        val second = push("mol")
        assertFalse("S2: every push names its commands", first.undo!!.commands == second.undo!!.commands)
        assertFalse("the first push is no longer the last one", first.undo.canUndo())
        assertTrue(second.undo.canUndo())
        assertEquals(PushUndo.Result.NOT_ON_TOP, first.undo.undo())
    }

    // ------------------------------------------------------------------ R23 checks after a push (X124)

    fun testAPushThatChangesDefaultsNamesTheR23FindingsOfThePushedCopies() {
        // specmol's defaults/main.yml sets web_port 8081 while its argument_specs document 8080 (ANS-S003). web_extra is
        // a role default its option does not document (ANS-S005, INFO): not highlighted, so not named.
        val spec = String(bytes("specmol", "meta/argument_specs.yml"), Charsets.UTF_8)
        writeOnDisk("specmol", "meta/argument_specs.yml", (spec.trimEnd() + "\n      web_extra:\n        type: int\n        description: Extra.\n").toByteArray())
        val defaults = String(bytes("specmol", "defaults/main.yml"), Charsets.UTF_8)
        writeOnDisk("specmol", "defaults/main.yml", (defaults.trimEnd() + "\nweb_extra: 1\n").toByteArray())
        val outcome = push("mol", "same", source = roleCopy("specmol"))
        assertEquals(outcome.toString(), PushOutcome.Status.PUSHED, outcome.status)
        val content = outcome.notification!!.content
        assertTrue(content, content.startsWith("Pushed web from specmol to mol, same: "))
        assertTrue(
            content,
            content.endsWith("<br>argument_specs checks: mol web: 1 spec default differs from the role default (ANS-S003); same web: 1 spec default differs from the role default (ANS-S003)"),
        )
        assertSame("shown once, with the line", outcome.notification, ui.notifications.single())
    }

    fun testNoR23LineWhenThePushedCopiesAreCleanOrNoSpecOrDefaultChanged() {
        val spec = push("spec")
        assertEquals("argument_specs changed, the copy is clean afterwards", "Pushed web from golden to spec: 1 changed", spec.notification!!.content)
        val mol = push("mol")
        assertEquals("only molecule/ changed: no checks", "Pushed web from golden to mol: 1 changed", mol.notification!!.content)
    }

    fun testFindingsThatTakeLongerThanTheBudgetComeInANotificationOfTheirOwn() {
        PushService.getInstance(project).specCheckBudget = Duration.ZERO
        val outcome = push("mol", source = roleCopy("specmol"))
        assertFalse(outcome.notification!!.content, outcome.notification.content.contains("argument_specs checks"))
        GoldenTestSupport.waitFor("the late R23 line") { ui.notifications.size == 2 }
        assertEquals("argument_specs checks: mol web: 1 spec default differs from the role default (ANS-S003)", ui.notifications.last().content)
    }

    // ------------------------------------------------------------------ the whole way

    fun testFromTheActionThroughTheDialogToTheFiles() {
        ui.pick = { rows -> PushChoice(rows.filter { it.name == "mol" }, PushOptions()) }
        val action = ActionManager.getInstance().getAction("Ansibility.Golden.PushToRepos")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(GoldenDataKeys.ROLE_COPY, roleDir("golden")).build()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
        GoldenTestSupport.waitFor("the push notification") { ui.notifications.isNotEmpty() }
        assertTrue(plan("golden", "mol").isIdentical)
        assertEquals("Pushed web from golden to mol: 1 changed", ui.notifications.single().content)
    }

    fun testACancelledDialogWritesNothing() {
        val mol = snapshot("mol")
        assertNull(PushService.getInstance(project).push(golden()))
        assertEquals(mol, snapshot("mol"))
        assertTrue(ui.models.single().rows.isCancelled || ui.models.single().rows.isCompleted)
    }
}
