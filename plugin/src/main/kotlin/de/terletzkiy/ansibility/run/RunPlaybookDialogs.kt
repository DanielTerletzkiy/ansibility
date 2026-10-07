package de.terletzkiy.ansibility.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.JComponent

/** "Run Playbook…": the run form for one playbook, with "save as run configuration". */
class RunPlaybookDialog(project: Project, private val context: PlaybookRunContext, initial: PlaybookRunSpec, saved: Boolean) :
    DialogWrapper(project, true) {
    private val form = PlaybookRunForm(project, context)
    private val saveBox = JBCheckBox(message("run.dialog.save"), saved)

    init {
        title = message("run.dialog.title", context.playbook.name)
        setOKButtonText(message("run.dialog.run"))
        form.reset(initial)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { comment(message("run.dialog.root", context.root.displayName, context.rootPath.relativize(context.playbookPath).toString())) }
        row { cell(form.component).align(AlignX.FILL) }
        row { cell(saveBox) }
    }

    override fun getPreferredFocusedComponent(): JComponent = form.preferredFocus

    override fun doValidate(): ValidationInfo? = when {
        form.lacksEnvironment -> ValidationInfo(message("run.dialog.environment.required"), form.environmentComponent)
        form.lacksTags -> ValidationInfo(message("run.dialog.tags.required"), form.tagsComponent)
        else -> null
    }

    override fun getDimensionServiceKey(): String = "Ansibility.RunPlaybookDialog"

    val spec: PlaybookRunSpec get() = form.spec()
    val save: Boolean get() = saveBox.isSelected

    /** The tags to add to the playbook before the run (the user left "Add the missing tags" on). */
    val additions: List<TagAddition> get() = form.additions

    private companion object {
        fun message(key: String, vararg params: Any): String = AnsibilityRunBundle.message(key, *params)
    }
}

/** Asks which environment to run against when a configuration names none and the root has several. */
internal class EnvironmentChoiceDialog(project: Project, private val context: PlaybookRunContext) : DialogWrapper(project, true) {
    private val combo = ComboBox(context.environments.map { it.id }.toTypedArray()).apply {
        renderer = textListCellRenderer(AnsibilityRunBundle.message("run.form.environment.choose")) { id -> context.environment(id)?.label ?: id }
        item = null
    }

    init {
        title = AnsibilityRunBundle.message("run.choose.environment.title")
        setOKButtonText(AnsibilityRunBundle.message("run.dialog.run"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(AnsibilityRunBundle.message("run.choose.environment.prompt", context.playbook.name)) }
        row(AnsibilityRunBundle.message("run.form.environment")) { cell(combo).align(AlignX.FILL) }
    }

    override fun getPreferredFocusedComponent(): JComponent = combo

    override fun doValidate(): ValidationInfo? =
        if (combo.item == null) ValidationInfo(AnsibilityRunBundle.message("run.dialog.environment.required"), combo) else null

    companion object {
        fun choose(project: Project, context: PlaybookRunContext): String? {
            val dialog = EnvironmentChoiceDialog(project, context)
            return if (dialog.showAndGet()) dialog.combo.item else null
        }
    }
}

/** The editor of an "Ansible Playbook" run configuration. */
class PlaybookSettingsEditor(project: Project) : SettingsEditor<AnsiblePlaybookConfiguration>() {
    private val form = PlaybookRunForm(project, null)

    override fun resetEditorFrom(configuration: AnsiblePlaybookConfiguration) = form.reset(configuration.spec)

    override fun applyEditorTo(configuration: AnsiblePlaybookConfiguration) {
        configuration.spec = form.spec()
    }

    override fun createEditor(): JComponent = form.component
}
