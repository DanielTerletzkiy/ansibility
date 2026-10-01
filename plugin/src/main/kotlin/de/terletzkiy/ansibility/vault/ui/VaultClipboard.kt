package de.terletzkiy.ansibility.vault.ui

import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.vault.VaultLog
import org.jetbrains.annotations.TestOnly
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Copy for revealed vault values (F7.1, F7.13 rule 5).
 *
 * - The value goes to the clipboard only through [CopyPasteManager], which also reaches the client in split mode;
 *   never through the AWT clipboard.
 * - After [VaultUiTimings.CLIPBOARD_MILLIS] every entry of the IDE's paste history holding the value is removed
 *   (`CopyPasteManagerEx.removeContent`, which also puts the previous entry back on the system clipboard), and the
 *   system clipboard is emptied if it still holds the value. A value the history merged into an older equal entry is
 *   found by content, not by identity.
 * - The service never keeps a second copy of the plaintext: it recognises the value by an HMAC under a random key
 *   per copy, and the transferable it hands out forgets the text when cleared and prints `***`.
 * - The item carries the desktop's "this is a secret" markers for clipboard managers ([VaultClipboardHints]:
 *   concealed and transient on macOS, excluded from monitors, history and cloud sync on Windows, KDE's password
 *   hint elsewhere). Managers that ignore them may still keep a copy; the texts say so.
 *
 * Application level, so a pending clear survives closing the project that copied the value; disposing the service
 * (IDE exit) clears at once.
 */
@Service(Service.Level.APP)
class VaultClipboard : Disposable {
    /** One copied value until it is cleared. */
    private inner class Copied(private val key: ByteArray, private val mac: ByteArray, var transferable: SecretTransferable?) : Disposable {
        fun matches(candidate: Transferable?): Boolean {
            val text = candidate?.let(::stringOf) ?: return false
            return MessageDigest.isEqual(mac, hmac(key, text))
        }

        override fun dispose() = clear(this)

        fun forget() {
            transferable?.wipe()
            transferable = null
            key.fill(0)
        }

        override fun toString(): String = "Copied(***)"
    }

    private val pending: MutableSet<Copied> = ConcurrentHashMap.newKeySet()
    private val random = SecureRandom()

    /** Puts [text] on the clipboard and schedules its removal. Call on the EDT. */
    fun copy(text: String) {
        ThreadingAssertions.assertEventDispatchThread()
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val transferable = SecretTransferable(text, VaultClipboardHints.current)
        val copied = Copied(key, hmac(key, text), transferable)
        Disposer.register(this, copied)
        pending += copied
        CopyPasteManager.getInstance().setContents(transferable)
        VaultUiClock.getInstance().scheduler.schedule(VaultUiTimings.CLIPBOARD_MILLIS, copied) { Disposer.dispose(copied) }
    }

    /** The number of copies not cleared yet. */
    @get:TestOnly
    val pendingCount: Int get() = pending.size

    private fun clear(copied: Copied) {
        if (!pending.remove(copied)) return
        try {
            val manager = CopyPasteManager.getInstance()
            val history = manager as? CopyPasteManagerEx
            if (history != null) {
                for (entry in manager.allContents) if (copied.matches(entry)) history.removeContent(entry)
            }
            if (copied.matches(manager.contents)) {
                val empty = StringSelection("")
                manager.setContents(empty)
                history?.removeContent(empty)
            }
        } catch (e: RuntimeException) {
            // The IDE is shutting down or the clipboard is unavailable: nothing more can be cleared.
            VaultLog.failure(VaultLog.Operation.REVEAL, e)
        } finally {
            copied.forget()
        }
    }

    override fun dispose() = Unit

    /**
     * A string transferable that forgets its text on [wipe] and never prints it. Next to the text it offers the
     * clipboard-manager markers of [hints], whose content is fixed and never the value.
     */
    private class SecretTransferable(text: String, private val hints: List<VaultClipboardHints.Hint>) : Transferable {
        @Volatile
        private var text: String? = text

        private val flavors: Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor) + hints.map { it.flavor }

        fun wipe() {
            text = null
        }

        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.clone()

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor != null && flavors.any { it.equals(flavor) }

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (DataFlavor.stringFlavor.equals(flavor)) return text.orEmpty()
            return hints.firstOrNull { it.flavor.equals(flavor) }?.content() ?: throw UnsupportedFlavorException(flavor)
        }

        override fun toString(): String = "***"
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val HMAC = "HmacSHA256"

        fun getInstance(): VaultClipboard = service()

        private fun hmac(key: ByteArray, text: String): ByteArray =
            Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(text.toByteArray(Charsets.UTF_8))

        private fun stringOf(transferable: Transferable): String? = try {
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) transferable.getTransferData(DataFlavor.stringFlavor) as? String else null
        } catch (_: UnsupportedFlavorException) {
            null
        } catch (_: IOException) {
            null
        }
    }
}
