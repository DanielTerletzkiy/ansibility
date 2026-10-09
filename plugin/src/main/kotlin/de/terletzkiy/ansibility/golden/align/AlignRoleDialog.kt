package de.terletzkiy.ansibility.golden.align

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.compare.CompareChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/**
 * The opening step of Align Role… (plan amendment R24, D184): the target and the source among the role's copies
 * (in-scope first, never filtered, D44), "Include key and vault files" (off), and the counts of the pair, computed in
 * the background whenever the choice changes, with the vault-id warning.
 */
internal class AlignRoleDialog(private val project: Project, private val setup: AlignSetup) : DialogWrapper(project) {
    private val targetBox = ComboBox(setup.choices.toTypedArray()).apply {
        renderer = choiceRenderer()
        selectedItem = setup.defaultTarget
    }
    private val sourceBox = ComboBox(setup.choices.toTypedArray()).apply {
        renderer = choiceRenderer()
        selectedItem = setup.defaultSource
    }
    private val includeBox = JBCheckBox(message("align.setup.include"), false)
    private val counts = JBLabel(message("align.setup.computing")).apply { foreground = UIUtil.getContextHelpForeground() }
    private val warning = JBLabel("", AllIcons.General.Warning, JBLabel.LEADING).apply { isVisible = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    init {
        title = message("align.setup.title", setup.name)
        setOKButtonText(message("align.setup.ok"))
        Disposer.register(disposable) { scope.cancel() }
        targetBox.addActionListener { refresh() }
        sourceBox.addActionListener { refresh() }
        includeBox.addActionListener { refresh() }
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("align.setup.target")) { cell(targetBox) }
        row(message("align.setup.source")) { cell(sourceBox) }
        row { cell(includeBox).comment(message("align.setup.include.comment")) }
        row { cell(counts) }
        row { cell(warning) }
    }

    /** The request, or null when cancelled. */
    fun choose(): AlignRequest? {
        if (!showAndGet()) return null
        val target = selected(targetBox) ?: return null
        val source = selected(sourceBox) ?: return null
        if (target.copy.dir == source.copy.dir || setup.linkedTo(target.copy) != null) return null
        return AlignRequest(target.copy, source.copy, includeBox.isSelected)
    }

    private fun selected(box: ComboBox<CompareChoice>): CompareChoice? = box.selectedItem as? CompareChoice

    private fun refresh() {
        job?.cancel()
        val target = selected(targetBox)
        val source = selected(sourceBox)
        if (target == null || source == null || target.copy.dir == source.copy.dir) {
            counts.text = message("align.setup.same")
            warning.isVisible = false
            isOKActionEnabled = false
            return
        }
        val linkedTo = setup.linkedTo(target.copy)
        if (linkedTo != null) {
            // D191: never written through a symbolic link.
            counts.text = message("align.setup.linked", target.copy.root.displayName, linkedTo)
            warning.isVisible = false
            isOKActionEnabled = false
            return
        }
        isOKActionEnabled = true
        counts.text = message("align.setup.computing")
        val include = includeBox.isSelected
        job = scope.launch {
            val preview = setup.preview(target.copy, source.copy, include)
            // Labels only: no model access, so any modality (the dialog is modal).
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                counts.text = if (preview.isEmpty) message("align.setup.identical") + "  " + preview.text else preview.text
                warning.text = preview.vaultWarning.orEmpty()
                warning.isVisible = preview.vaultWarning != null
            }
        }
    }

    private fun choiceRenderer() = textListCellRenderer { choice: CompareChoice? ->
        when {
            choice == null -> ""
            choice.inScope -> choice.text
            else -> message("align.setup.outsideScope", choice.text)
        }
    }
}
