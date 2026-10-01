package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * "Choose roots…" (F9.1): one checkbox per non-detached root in display order, nested playbook roots indented below
 * their parent with a "nested in" comment. OK needs at least one root; the result is a `ScopeChoice.Roots` key set.
 */
class ChooseRootsDialog(project: Project, private val options: List<RootOption>, initial: Set<String>) : DialogWrapper(project, true) {
    private val selected: MutableSet<String> = options.mapTo(LinkedHashSet()) { it.key }.apply { retainAll(initial) }

    /** The checkbox of each option, by key (tests toggle them). */
    internal val checkBoxes: MutableMap<String, JBCheckBox> = LinkedHashMap()

    init {
        title = AnsibilityScopeBundle.message("dialog.chooseRoots.title")
        init()
        updateOk()
    }

    /** The chosen root keys, in display order. */
    fun selection(): Set<String> = options.mapNotNullTo(LinkedHashSet()) { option -> option.key.takeIf { it in selected } }

    override fun createCenterPanel(): JComponent = panel {
        row { comment(AnsibilityScopeBundle.message("dialog.chooseRoots.comment")) }
        for (option in options) {
            if (option.nestedIn == null) optionRow(option) else indent { optionRow(option) }
        }
    }

    private fun Panel.optionRow(option: RootOption) {
        row {
            val cell = checkBox(option.label)
                .applyToComponent { isSelected = option.key in selected }
                .onChanged { box ->
                    if (box.isSelected) selected += option.key else selected -= option.key
                    updateOk()
                }
            option.nestedIn?.let { parent -> cell.comment(AnsibilityScopeBundle.message("dialog.chooseRoots.nested", parent)) }
            checkBoxes[option.key] = cell.component
        }
    }

    private fun updateOk() {
        isOKActionEnabled = selected.isNotEmpty()
    }
}
