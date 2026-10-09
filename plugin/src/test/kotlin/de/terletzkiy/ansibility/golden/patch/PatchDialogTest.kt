package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The dialog of "Copy as Patch for Golden…" (plan amendment R25, X126), headless: the file list with each file's note,
 * "Include key and vault files" only when such files differ (off at first; ticking it is the confirmation), the
 * summary, both outputs off while the patch would hold no file, the choice per button, and the default file name.
 */
class PatchDialogTest : BasePlatformTestCase() {
    private fun model(vararg rows: PatchRow) = PatchModel(
        "web", "falcon", "golden", GoldenPatchBase("roles/web", "/src/golden"), rows.toList(), PatchModel.fileName("web", "falcon", "golden"),
    )

    private inline fun <T> withDialog(model: PatchModel, block: (PatchDialog) -> T): T {
        val dialog = PatchDialog(project, model)
        return try {
            block(dialog)
        } finally {
            if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    private val changed = PatchRow("tasks/main.yml", PatchChange.CHANGED, binary = false, sensitive = false)
    private val added = PatchRow("templates/extra.j2", PatchChange.ADDED, binary = false, sensitive = false)
    private val deleted = PatchRow("handlers/old.yml", PatchChange.DELETED, binary = false, sensitive = false)
    private val binary = PatchRow("files/logo.png", PatchChange.CHANGED, binary = true, sensitive = false)
    private val key = PatchRow("files/ssl/web.key", PatchChange.CHANGED, binary = false, sensitive = true)

    fun testTheFilesTheirNotesAndTheSummary() {
        withDialog(model(changed, added, deleted, binary, key)) { dialog ->
            assertEquals(5, dialog.fileList.model.size)
            assertEquals(
                listOf("changed", "new file", "deleted", "binary: noted, not in the patch", "key or vault file: left out"),
                listOf(changed, added, deleted, binary, key).map(dialog::noteForTests),
            )
            assertEquals("3 files in the patch · 1 binary file noted, not in the patch · 1 key or vault file left out", dialog.summary.text)
            assertFalse("off at first", dialog.includeSensitive)
            assertEquals("Include key and vault files (1)", dialog.sensitiveBox!!.text)
            assertFalse(dialog.sensitiveBox!!.isSelected)
        }
    }

    fun testTickingIncludeKeyAndVaultFilesPutsThemIn() {
        withDialog(model(changed, key)) { dialog ->
            dialog.sensitiveBox!!.doClick()
            assertTrue(dialog.includeSensitive)
            assertEquals("key or vault file, changed", dialog.noteForTests(key))
            assertEquals("2 files in the patch", dialog.summary.text)
            dialog.copyAction.actionPerformed(null)
            assertEquals(PatchChoice(PatchOutput.CLIPBOARD, includeSensitive = true), dialog.choice())
        }
    }

    fun testNoOptionWithoutKeyOrVaultFiles() {
        withDialog(model(changed)) { dialog -> assertNull(dialog.sensitiveBox) }
    }

    fun testBothOutputsAreOffWhileThePatchWouldHoldNoFile() {
        withDialog(model(key, binary)) { dialog ->
            assertFalse(dialog.copyAction.isEnabled)
            assertFalse(dialog.saveAction.isEnabled)
            assertEquals("No file in the patch · 1 binary file noted, not in the patch · 1 key or vault file left out", dialog.summary.text)
            dialog.setIncludeSensitive(true)
            assertTrue(dialog.copyAction.isEnabled)
            assertTrue(dialog.saveAction.isEnabled)
        }
    }

    fun testEachButtonChoosesItsOutput() {
        withDialog(model(changed)) { dialog ->
            assertNull("no choice yet", dialog.choice())
            dialog.saveAction.actionPerformed(null)
            assertEquals(PatchChoice(PatchOutput.FILE, includeSensitive = false), dialog.choice())
        }
    }

    fun testTheDefaultFileName() {
        assertEquals("web-falcon-to-golden.patch", PatchModel.fileName("web", "falcon", "golden"))
        assertEquals("web-pelican-danger_zone-database-to-golden.patch", PatchModel.fileName("web", "pelican › danger_zone/database", "golden"))
        assertEquals("web-wt-1-heron-to-golden.patch", PatchModel.fileName("web", "[wt-1] heron", "golden"))
    }
}
