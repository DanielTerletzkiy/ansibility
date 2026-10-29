package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.ui.DialogWrapper
import de.terletzkiy.ansibility.vault.VaultVectors
import java.security.SecureRandom

/**
 * Plan amendment R29, D205: the rotation dialog (headless): the id with its counts, one row per password source with
 * where the new password goes, a generated password of 64 letters and digits, typed passwords checked (mismatch and
 * empty refused, spaces at either end refused, shorter than 16 warned), at least one root; never a password in a text.
 */
class RotationDialogTest : RotationTestCase() {
    /** What the dialogs said: the validation messages in order, and the password the generating one answered with. */
    private class Seen(val checks: List<String?>, val generated: String)

    fun testTheDialogGeneratesSixtyFourAlphanumericAndChecksTypedPasswords() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        var seen: Result<Seen>? = null
        rotationPrompts.onRotation = { request ->
            seen = runCatching { drive(request) }
            null
        }

        rotate()

        val result = seen!!.getOrThrow()
        assertEquals(
            listOf(
                null,
                "The two passwords are not the same.",
                "Type the new password.",
                "The password cannot start or end with a space: a password file would lose it.",
                "Shorter than 16 characters: easy to guess.",
                null,
                "Choose at least one root.",
            ),
            result.checks,
        )
        val password = result.generated
        assertEquals(64, password.length)
        assertTrue(password, password.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' })
        assertFalse("two dialogs, two passwords", password == NewPassword.generate(SecureRandom()).use { String(it.chars()) })
    }

    /** Drives the dialog on [request] through its checks, then answers a second one with its generated password. EDT. */
    private fun drive(request: RotationRequest): Seen {
        val checks = ArrayList<String?>()
        val dialog = RotationDialog(project, request, SecureRandom())
        try {
            val texts = dialog.texts()
            assertTrue(texts.toString(), texts.contains("default: 1 value in 1 file, no vault files"))
            assertTrue(texts.toString(), texts.any { it == "Write the new password to ${request.identity.groups.single().source.display}" })
            assertTrue(texts.toString(), texts.any { it.startsWith("Password file: ") })
            checks += dialog.validation()?.message
            dialog.type("Abc0Def0Ghi0Jkl0", "Abc0Def0Ghi0Jkl1")
            checks += dialog.validation()?.message
            dialog.type("", "")
            checks += dialog.validation()?.message
            dialog.type(" Abc0Def0Ghi0Jkl0 ", " Abc0Def0Ghi0Jkl0 ")
            checks += dialog.validation()?.message
            dialog.type("Abc0Def0", "Abc0Def0")
            val short = dialog.validation()
            checks += short?.message
            assertTrue("a short password is a warning only", short!!.warning && short.okEnabled)
            dialog.type("Abc0Def0Ghi0Jkl0", "Abc0Def0Ghi0Jkl0")
            checks += dialog.validation()?.message
            for (group in request.identity.groups) group.checked = false
            checks += dialog.validation()?.message
            for (group in request.identity.groups) group.checked = true
            assertFalse("no typed password in a text", dialog.texts().any { it.contains("Abc0Def0") })
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
        val generating = RotationDialog(project, request, SecureRandom())
        try {
            val generated = generating.answer().use { answer ->
                assertTrue(answer.generated)
                val chars = answer.chars()
                try {
                    String(chars)
                } finally {
                    chars.fill('\u0000')
                }
            }
            assertFalse("no generated password in a text", generating.texts().any { it.contains(generated) })
            return Seen(checks, generated)
        } finally {
            generating.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
