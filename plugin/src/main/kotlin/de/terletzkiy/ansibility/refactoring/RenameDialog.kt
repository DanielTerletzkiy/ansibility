package de.terletzkiy.ansibility.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/** The new name, with an optional extra choice ([option]); [validate] gives the error for a name, or null. */
internal class RenameDialog(
    project: Project,
    @Nls title: String,
    @Nls private val label: String,
    initial: String,
    @Nls option: String?,
    private val validate: (String) -> String?,
) : DialogWrapper(project) {
    private val nameField = JBTextField(initial, 30)
    private val checkBox = option?.let { JBCheckBox(it, true) }

    val newName: String get() = nameField.text.trim()
    val optionSelected: Boolean get() = checkBox?.isSelected == true

    init {
        this.title = title
        init()
        nameField.selectAll()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(label) { cell(nameField).align(AlignX.FILL).focused() }
        checkBox?.let { box -> row { cell(box) } }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = validate(newName)?.let { ValidationInfo(it, nameField) }
}
