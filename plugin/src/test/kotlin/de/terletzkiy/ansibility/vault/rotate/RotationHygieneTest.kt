package de.terletzkiy.ansibility.vault.rotate

import com.intellij.history.LocalHistory
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.vault.VaultVectors
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan amendment R29's security rules for Rotate Vault Password… (as F7.13): neither password (old or new) nor any
 * plaintext (old values, the new values of "Also regenerate secrets…") is in a vault file or its document, Local
 * History, the word index of the vault files, the log, a notification, the texts of both dialogs, or a `toString()`.
 * The only file that holds the new password is the password file it was written to.
 */
class RotationHygieneTest : RotationTestCase() {
    private val dbValue = "ANSIBILITY_SENTINEL_81"
    private val apiValue = "ANSIBILITY_SENTINEL_82"
    private val keyValue = "ANSIBILITY_SENTINEL_83"
    private val keyPath get() = "$root/files/ssl/demo.key"

    private fun filesWithWord(word: String): List<String> {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val found = LinkedHashSet<String>()
        runReadActionBlocking {
            val helper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            helper.processAllFilesWithWord(word, scope, { found += it.name; true }, true)
            helper.processAllFilesWithWordInLiterals(word, scope) { found += it.name; true }
            helper.processAllFilesWithWordInText(word, scope, { found += it.name; true }, true)
        }
        return found.toList()
    }

    /** Every revision Local History keeps of [file], as text. */
    private fun history(file: VirtualFile): List<String> {
        val history = LocalHistory.getInstance()
        val stamps = ArrayList<Long>()
        history.getByteContent(file) { stamps += it; false }
        return stamps.distinct().mapNotNull { stamp -> history.getByteContent(file) { it == stamp }?.let { String(it, Charsets.ISO_8859_1) } }
    }

    fun testNoPasswordAndNoValueLeaksFromARotation() {
        write(
            vaultPath,
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt(dbValue, VaultVectors.PW1)) +
                VaultVectors.inline("vault_api_token", VaultVectors.encrypt(apiValue, VaultVectors.PW1, "new")),
        )
        write(keyPath, VaultVectors.encrypt("$keyValue\n", VaultVectors.PW1).formatBytes())
        ready()
        val shown = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification.title
                    shown += notification.content
                    notification.actions.mapNotNullTo(shown) { it.templateText }
                }
            },
        )
        rotationPrompts.onRotation = { request ->
            val dialog = RotationDialog(project, request, SecureRandom())
            try {
                shown += dialog.texts()
                shown += request.toString()
                for (identity in request.identities) {
                    shown += identity.toString()
                    for (group in identity.groups) shown += listOf(group.toString(), group.source.toString()) + group.roots.map { it.toString() }
                }
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
            NewPassword(newPassword.toCharArray(), generated = true).also { shown += it.toString() }
        }
        rotationPrompts.onReview = { review ->
            val dialog = RotationReviewDialog(project, review)
            try {
                shown += dialog.texts()
                shown += review.toString()
                shown += review.roots.map { it.toString() }
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
            review.alsoRegenerate = true
            true
        }
        regeneratePrompts.onValues = { true }

        val log = capture {
            rotate()
            waitFor("the summary was not shown") { shown.any { it.contains("rotated:") } }
        }
        shown += feedback()
        shown += service.lastSummary!!.text
        shown += service.lastSummary!!.toString()

        val newValues = listOf(valueOf(vaultPath, "vault_db_password", newPassword)!!, valueOf(vaultPath, "vault_api_token", newPassword)!!)
        assertFalse(newValues.contains(dbValue) || newValues.contains(apiValue))
        assertEquals("the vault file keeps its value", "$keyValue\n", decryptWith(text(keyPath), newPassword))
        val secrets = listOf(dbValue, apiValue, keyValue, VaultVectors.PW1, newPassword) + newValues
        val documents = FileDocumentManager.getInstance()
        for (secret in secrets) {
            for (path in listOf(vaultPath, keyPath)) assertFalse("$secret in $path", text(path).contains(secret))
            for (path in listOf(vaultPath, keyPath)) documents.getCachedDocument(vf(path))?.let { assertFalse("$secret in a document", it.text.contains(secret)) }
            for (line in log) assertFalse("$secret in the log: $line", line.contains(secret))
            for (text in shown) assertFalse("$secret in '$text'", text.contains(secret))
            for (path in listOf(vaultPath, keyPath)) for (revision in history(vf(path))) assertFalse("$secret in Local History of $path", revision.contains(secret))
        }
        assertTrue("the vault code's own lines were captured: $log", log.any { it.startsWith("vault ") })
        assertTrue("Local History records the vault file", history(vf(vaultPath)).isNotEmpty())
        for (word in listOf(dbValue, apiValue, keyValue)) assertEmpty("$word in the word index", filesWithWord(word))
        val holders = filesWithWord(newPassword)
        assertTrue("only the password file (a project file the IDE indexes) holds the new password: $holders", holders.all { it == ".vault-pass" })
    }

    /** Runs [block] with every logger writing into the returned list as well (the plugin's debug lines included). */
    private fun capture(block: () -> Unit): List<String> {
        val lines = CopyOnWriteArrayList<String>()
        val previous = Logger.getFactory()
        val sink = Sink(lines)
        Logger.setFactory(CapturingFactory(previous, sink))
        try {
            block()
        } finally {
            sink.closed = true
            Logger.setFactory(previous)
        }
        return lines
    }

    private class Sink(val lines: MutableList<String>) {
        @Volatile
        var closed = false

        fun record(message: String?, error: Throwable?, details: Array<out String> = emptyArray()) {
            if (closed) return
            message?.let(lines::add)
            details.forEach(lines::add)
            var cause = error
            while (cause != null) {
                lines += cause.toString()
                cause = cause.cause
            }
        }
    }

    /** Platform debug output is passed on but not searched (DocumentImpl's trace echoes every document change). */
    private class CapturingFactory(private val delegate: Logger.Factory, private val sink: Sink) : Logger.Factory {
        override fun getLoggerInstance(category: String): Logger =
            CapturingLogger(delegate.getLoggerInstance(category), sink, ours = category.removePrefix("#").startsWith("de.terletzkiy.ansibility"))
    }

    private class CapturingLogger(private val delegate: Logger, private val sink: Sink, private val ours: Boolean) : Logger() {
        override fun isDebugEnabled(): Boolean = ours && !sink.closed || delegate.isDebugEnabled

        override fun debug(message: String?, t: Throwable?) {
            if (ours) sink.record(message, t)
            if (delegate.isDebugEnabled) delegate.debug(message, t)
        }

        override fun info(message: String?, t: Throwable?) {
            sink.record(message, t)
            delegate.info(message, t)
        }

        override fun warn(message: String?, t: Throwable?) {
            sink.record(message, t)
            delegate.warn(message, t)
        }

        override fun error(message: String?, t: Throwable?, vararg details: String) {
            sink.record(message, t, details)
            delegate.error(message, t, *details)
        }
    }
}
