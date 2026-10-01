package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.popup.AbstractPopup
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.vault.VaultRevealPresenter
import de.terletzkiy.ansibility.vault.VaultVectors
import java.awt.datatransfer.DataFlavor

/**
 * F7.1 Reveal and Copy: the registered presenter, the masked popup and its info line, the eye, Copy with the 30-s
 * clear, and every close condition (timeout, Esc / outside click, editor switch, lock, project close, a new reveal),
 * each of which zeroes the plaintext.
 */
class VaultRevealPopupTest : VaultUiTestCase() {
    private lateinit var root: String

    override fun setUp() {
        super.setUp()
        root = defaultRoot()
        write("$root/group_vars/all/vault.yml", "a: 1\n" + VaultVectors.inline("vault_db_password", VaultVectors.encrypt("hello world", VaultVectors.PW1)) +
            VaultVectors.inline("vault_other", VaultVectors.encrypt("second\nline\n", VaultVectors.PW1)))
        write("$root/group_vars/all/vars.yml", "x: 1\n")
        root(root)
        myFixture.configureFromExistingVirtualFile(vf("$root/group_vars/all/vault.yml"))
    }

    private fun reveal(key: String = "vault_db_password"): RevealSession {
        VaultOperations.getInstance(project).reveal(location("$root/group_vars/all/vault.yml", key), myFixture.editor)
        waitFor("no reveal popup") { VaultRevealService.getInstance(project).session?.facts?.keyName == key }
        return VaultRevealService.getInstance(project).session!!
    }

    fun testThePopupPresenterIsRegistered() {
        assertTrue(VaultRevealPresenter.EP_NAME.extensionList.any { it is VaultPopupRevealPresenter })
    }

    fun testRevealShowsAMaskedPopupWithTheFactsAndTheEyeShowsTheValue() {
        val session = reveal()
        assertEquals("id default · 1.1 · 11 chars · no trailing newline", session.facts.line)
        assertEquals("the first reveal asked for consent", 1, prompter.consentRequests.size)
        val popup = popups.single() as AbstractPopup
        assertTrue("Esc closes it", popup.isCancelKeyEnabled)
        assertFalse("the masked field never holds the value", session.maskedText.contains("hello"))
        assertFalse(session.shows("hello world"))

        session.toggle()
        assertTrue(session.shows("hello world"))
        session.toggle()
        assertFalse("hidden again", session.shows("hello world"))
        assertEquals(11, session.plaintextSize)
    }

    fun testMultiLineValuesReportTheirTrailingNewline() {
        val session = reveal("vault_other")
        assertEquals("id default · 1.1 · 12 chars · ends with a newline", session.facts.line)
        session.toggle()
        assertTrue(session.shows("second\nline\n"))
    }

    fun testTheTimeoutClosesThePopupAndZeroesThePlaintext() {
        val session = reveal()
        scheduler.advance(VaultUiTimings.REVEAL_MILLIS - 1)
        assertFalse(session.isClosed)
        scheduler.advance(1)
        assertEquals(RevealCloseReason.TIMEOUT, session.closeReason)
        assertEquals("zeroed", 0, session.plaintextSize)
        assertTrue(popups.single().isDisposed)
        assertNull(VaultRevealService.getInstance(project).session)
    }

    fun testEscOrAnOutsideClickClosesIt() {
        val session = reveal()
        // Esc and an outside click both cancel the popup (enabled above); its close listener ends the session.
        popups.single().cancel()
        assertEquals(RevealCloseReason.POPUP_CLOSED, session.closeReason)
        assertEquals(0, session.plaintextSize)
    }

    fun testSwitchingEditorsClosesIt() {
        val session = reveal()
        val manager = FileEditorManager.getInstance(project)
        val provider = TextEditorProvider.getInstance()
        val selected = FileEditorWithProvider(provider.getTextEditor(myFixture.editor), provider)
        project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER).selectionChanged(FileEditorManagerEvent(manager, null, selected))
        assertEquals(RevealCloseReason.EDITOR_SWITCH, session.closeReason)
        assertEquals(0, session.plaintextSize)
    }

    fun testLockingClosesIt() {
        val session = reveal()
        secrets.lockAll()
        waitFor("not closed on lock") { session.isClosed }
        assertEquals(RevealCloseReason.LOCKED, session.closeReason)
        assertEquals(0, session.plaintextSize)
    }

    fun testAVerificationChangeWhileUnlockedKeepsItOpen() {
        val session = reveal()
        secrets.notifyChanged()
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse(session.isClosed)
    }

    fun testProjectCloseClosesIt() {
        val session = reveal()
        VaultRevealService.getInstance(project).dispose()
        assertEquals(RevealCloseReason.PROJECT_CLOSED, session.closeReason)
        assertEquals(0, session.plaintextSize)
    }

    fun testANewRevealReplacesTheOpenOne() {
        val first = reveal()
        val second = reveal("vault_other")
        assertEquals(RevealCloseReason.REPLACED, first.closeReason)
        assertEquals(0, first.plaintextSize)
        assertFalse(second.isClosed)
    }

    fun testCopyPutsTheValueOnTheClipboardAndClearsItAfterThirtySeconds() {
        val manager = CopyPasteManager.getInstance()
        manager.setContents(java.awt.datatransfer.StringSelection("before"))
        val session = reveal()
        session.copy()
        assertEquals("hello world", manager.getContents<String>(DataFlavor.stringFlavor))
        assertTrue(manager.allContents.any { it.getTransferData(DataFlavor.stringFlavor) == "hello world" })
        assertEquals("the transferable never prints the value", "***", manager.contents.toString())
        session.close(RevealCloseReason.TIMEOUT)
        assertEquals("closing the popup does not clear the clipboard early", "hello world", manager.getContents<String>(DataFlavor.stringFlavor))

        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
        assertFalse("cleared", manager.getContents<String>(DataFlavor.stringFlavor) == "hello world")
        assertFalse("removed from the paste history", manager.allContents.any { it.getTransferData(DataFlavor.stringFlavor) == "hello world" })
        assertEquals("the previous entry is back", "before", manager.getContents<String>(DataFlavor.stringFlavor))
        assertEquals(0, VaultClipboard.getInstance().pendingCount)
    }

    fun testAWrongPasswordIsReportedWithoutAPopup() {
        val other = projectRoot("other")
        write("$other/.vault-pass", "not-the-password\n")
        write("$other/vault.yml", VaultVectors.inline("vault_x", VaultVectors.encrypt("nope", VaultVectors.PW1)))
        root(other)
        VaultOperations.getInstance(project).reveal(location("$other/vault.yml", "vault_x"), myFixture.editor)
        waitFor("no failure report") { feedback().isNotEmpty() || VaultRevealService.getInstance(project).session != null }
        assertNull(VaultRevealService.getInstance(project).session)
        assertEmpty(popups)
    }

    fun testRevealOfSomethingElseDoesNothing() {
        val attempts = crypto.decryptAttempts
        VaultOperations.getInstance(project).reveal(SourceLocation(vf("$root/group_vars/all/vault.yml"), 0), myFixture.editor)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(VaultRevealService.getInstance(project).session)
        assertEquals(attempts, crypto.decryptAttempts)
    }
}
