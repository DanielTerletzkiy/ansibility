package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.system.OS
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap

/**
 * F7.1 Copy's clipboard hygiene: the 30-s clear, the paste history, merged entries, a clipboard you changed, and the
 * desktop's "this is a secret" markers for clipboard managers.
 */
class VaultClipboardTest : VaultUiTestCase() {
    private val manager get() = CopyPasteManager.getInstance()
    private val clipboard get() = VaultClipboard.getInstance()

    private fun current(): String? = manager.getContents<String>(DataFlavor.stringFlavor)

    private fun inHistory(text: String): Boolean = manager.allContents.any { it.getTransferData(DataFlavor.stringFlavor) == text }

    fun testTheValueIsClearedAndRemovedFromTheHistoryAfterThirtySeconds() {
        manager.setContents(StringSelection("previous"))
        clipboard.copy("ANSIBILITY-SENTINEL-51")
        assertEquals("ANSIBILITY-SENTINEL-51", current())
        assertEquals(1, clipboard.pendingCount)
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS - 1)
        assertEquals("ANSIBILITY-SENTINEL-51", current())
        scheduler.advance(1)
        assertEquals("previous", current())
        assertFalse(inHistory("ANSIBILITY-SENTINEL-51"))
        assertEquals(0, clipboard.pendingCount)
    }

    fun testAnEqualOlderHistoryEntryIsRemovedToo() {
        manager.setContents(StringSelection("ANSIBILITY-SENTINEL-52"))
        manager.setContents(StringSelection("between"))
        clipboard.copy("ANSIBILITY-SENTINEL-52")
        assertEquals("ANSIBILITY-SENTINEL-52", current())
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
        assertFalse(inHistory("ANSIBILITY-SENTINEL-52"))
        assertFalse(current() == "ANSIBILITY-SENTINEL-52")
    }

    fun testSomethingYouCopiedLaterStaysOnTheClipboard() {
        clipboard.copy("ANSIBILITY-SENTINEL-53")
        manager.setContents(StringSelection("mine"))
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
        assertEquals("mine", current())
        assertFalse("still removed from the history", inHistory("ANSIBILITY-SENTINEL-53"))
    }

    fun testTheTransferablePrintsNoValue() {
        clipboard.copy("ANSIBILITY-SENTINEL-54")
        assertEquals("***", manager.contents.toString())
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
    }

    fun testTheCopiedItemCarriesThisDesktopsSecretMarkers() {
        clipboard.copy("ANSIBILITY-SENTINEL-55")
        val contents = manager.contents!!
        val hints = VaultClipboardHints.current
        assertEquals(VaultClipboardHints.forDesktop(VaultClipboardHints.localDesktop()).map { it.native }, hints.map { it.native })
        val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
        for (hint in hints) {
            assertTrue(hint.native, contents.isDataFlavorSupported(hint.flavor))
            assertEquals("mapped to exactly its native name", listOf(hint.native), flavorMap.getNativesForFlavor(hint.flavor))
            assertEmpty("never mapped back to a flavor when reading the clipboard", flavorMap.getFlavorsForNative(hint.native).filter { it == hint.flavor })
            val marker = contents.getTransferData(hint.flavor) as ByteArray
            assertFalse("a marker, never the value", String(marker, Charsets.ISO_8859_1).contains("SENTINEL"))
        }
        assertEquals("ANSIBILITY-SENTINEL-55", contents.getTransferData(DataFlavor.stringFlavor))
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
        assertEquals("the text is forgotten when cleared", "", contents.getTransferData(DataFlavor.stringFlavor))
    }

    fun testSecretMarkersPerDesktop() {
        fun natives(desktop: VaultClipboardHints.Desktop) = VaultClipboardHints.forDesktop(desktop).map { it.native }
        assertEquals(listOf("org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType"), natives(VaultClipboardHints.Desktop.MAC))
        assertEquals(
            listOf("ExcludeClipboardContentFromMonitorProcessing", "CanIncludeInClipboardHistory", "CanUploadToCloudClipboard"),
            natives(VaultClipboardHints.Desktop.WINDOWS),
        )
        assertEquals(listOf("x-kde-passwordManagerHint"), natives(VaultClipboardHints.Desktop.UNIX))
        assertEmpty(natives(VaultClipboardHints.Desktop.OTHER))

        val windows = VaultClipboardHints.forDesktop(VaultClipboardHints.Desktop.WINDOWS)
        for (hint in windows.drop(1)) assertTrue("${hint.native} is the DWORD 0", hint.content().contentEquals(byteArrayOf(0, 0, 0, 0)))
        assertEquals("secret", String(VaultClipboardHints.forDesktop(VaultClipboardHints.Desktop.UNIX).single().content(), Charsets.US_ASCII))
        val all = VaultClipboardHints.Desktop.entries.flatMap(VaultClipboardHints::forDesktop)
        assertEquals("every marker has a flavor of its own", all.size, all.map { it.flavor }.distinct().size)
        assertEquals(VaultClipboardHints.Desktop.MAC, VaultClipboardHints.desktopOf(OS.macOS))
        assertEquals(VaultClipboardHints.Desktop.UNIX, VaultClipboardHints.desktopOf(OS.FreeBSD))
    }
}
