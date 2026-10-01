package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.ui.RootSettingsEditor

/** The root setting "Unguarded optional variables without a default are always errors" in the settings UI (F8.12). */
class UndefinedSettingTest : BasePlatformTestCase() {
    fun testCheckboxLoadsAndAppliesTheRootSetting() {
        var changes = 0
        val editor = RootSettingsEditor { changes++ }
        val text = AnsibilityUndefinedBundle.message("settings.unguarded.always.error")
        val box = UIUtil.findComponentsOfType(editor.component, JBCheckBox::class.java).single { it.text == text }
        assertSame(editor.unguardedOptionalAlwaysError, box)

        editor.load(RootSettings.DEFAULT.copy(unguardedOptionalAlwaysError = true))
        assertTrue(box.isSelected)
        assertEquals("loading is no user change", 0, changes)
        assertTrue(editor.applyTo(RootSettings.DEFAULT).unguardedOptionalAlwaysError)

        box.doClick()
        assertEquals(1, changes)
        assertFalse(editor.applyTo(RootSettings.DEFAULT.copy(unguardedOptionalAlwaysError = true)).unguardedOptionalAlwaysError)

        editor.load(RootSettings.DEFAULT)
        assertFalse("off by default", box.isSelected)
    }
}
