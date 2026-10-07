package de.terletzkiy.ansibility.vault.secrets

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.run.become.BecomePasswordDialog
import de.terletzkiy.ansibility.run.become.BecomePasswordRequest
import javax.swing.JRadioButton

/**
 * The password prompts open (the UI DSL of 262 refuses radio buttons outside a buttons group, which made them throw
 * when created) and their remember choices are one group.
 */
class PasswordDialogsTest : BasePlatformTestCase() {
    private fun choices(panel: javax.swing.JComponent): List<JRadioButton> = UIUtil.findComponentsOfType(panel, JRadioButton::class.java)

    fun testTheVaultPasswordDialogOpens() {
        val dialog = VaultPasswordDialog(project, VaultPasswordRequest("library", "default"))
        try {
            val choices = choices(dialog.centerPanel())
            assertEquals("the password safe by default", listOf(true, false, false), choices.map { it.isSelected })
            choices[1].doClick()
            assertEquals("one choice at a time", listOf(false, true, false), choices.map { it.isSelected })
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
        assertNull("cancelled", dialog.answer())
    }

    fun testTheBecomePasswordDialogOpens() {
        val dialog = BecomePasswordDialog(project, BecomePasswordRequest("falcon", "prod", null, false))
        try {
            val choices = choices(dialog.centerPanel())
            assertEquals("this session by default", listOf(false, true, false), choices.map { it.isSelected })
            choices[2].doClick()
            assertEquals("one choice at a time", listOf(false, false, true), choices.map { it.isSelected })
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
