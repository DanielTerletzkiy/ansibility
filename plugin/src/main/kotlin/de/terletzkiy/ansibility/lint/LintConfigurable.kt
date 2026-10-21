package de.terletzkiy.ansibility.lint

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * Settings › Languages & Frameworks › Ansibility › Lint (plan amendment R28): per root whether and where ansible-lint
 * runs, its config, arguments, offline mode and how findings show; plus the personal commit check. The roots and what
 * each would run load in a background read action.
 */
class LintConfigurable(private val project: Project) : BoundSearchableConfigurable(message("settings.lint.name"), ID, ID) {
    internal data class RootEntry(val key: String, val name: String, val services: List<String>, val detected: String)

    private val settings = LintSettings.getInstance(project)
    private val edits = LinkedHashMap<String, LintRootSettings>()
    private var entries: List<RootEntry> = emptyList()
    private var current: RootEntry? = null
    private var loading = false
    private var uiDisposable: Disposable? = null

    private val rootCombo = ComboBox<RootEntry>().apply {
        renderer = textListCellRenderer("") { it.name }
        addActionListener { if (!loading) select(selectedItem as? RootEntry) }
    }
    private val enabled = JBCheckBox(message("settings.lint.enabled"))
    private val auto = JBRadioButton(message("settings.lint.executor.auto"))
    private val local = JBRadioButton(message("settings.lint.executor.local"))
    private val compose = JBRadioButton(message("settings.lint.executor.compose"))
    private val service = ComboBox<String>()
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
    }
    private val config = JBTextField()
    private val args = JBTextField()
    private val offline = JBCheckBox(message("settings.lint.offline"))
    private val severity = ComboBox(LintSeverity.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { message("settings.lint.severity.${it.name}") }
    }
    private val detected = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
    private val commits = JBCheckBox(message("settings.lint.commits"))

    init {
        listOf(enabled, auto, local, compose, offline).forEach { it.addActionListener { capture() } }
        listOf(service, severity).forEach { it.addActionListener { capture() } }
        listOf(config, args, executable.textField).forEach {
            it.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) = capture()
            })
        }
    }

    override fun createPanel(): DialogPanel {
        uiDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable("LintConfigurable").also { uiDisposable = it }
        val panel = panel {
            row(message("settings.lint.root")) { cell(rootCombo) }
            row { cell(enabled) }
            row { cell(detected) }
            buttonsGroup(message("settings.lint.executor")) {
                row { cell(auto) }
                row { cell(local) }
                row { cell(compose) }
            }
            row(message("settings.lint.service")) { cell(service) }
            row(message("settings.lint.executable")) { cell(executable).align(Align.FILL).comment(message("settings.lint.executable.comment")) }
            row(message("settings.lint.config")) { cell(config).align(Align.FILL).comment(message("settings.lint.config.comment")) }
            row(message("settings.lint.args")) { cell(args).align(Align.FILL) }
            row { cell(offline) }
            row(message("settings.lint.severity")) { cell(severity) }
            row { comment(message("settings.lint.shared.comment")) }
            separator()
            row { cell(commits).comment(message("settings.lint.commits.comment")) }
        }
        commits.isSelected = settings.checkCommits
        load(disposable)
        return panel
    }

    private fun load(disposable: Disposable) {
        loading = true
        ReadAction.nonBlocking<List<RootEntry>> {
            AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }.map { root ->
                val key = RootKeys.keyOf(project, root.dir)
                val rootSettings = settings.rootSettings(key)
                val services = LintRunner.composeTargets(project, root).map { it.service }.distinct()
                val shown = LintRunner.composeTargets(project, root).firstOrNull()?.let { message("settings.lint.detected.compose", it.presentable()) }
                    ?: LintRunner.localExecutable(project, rootSettings)?.let { message("settings.lint.detected.local", it) }
                    ?: message("settings.lint.detected.none")
                RootEntry(key, root.displayName, services, message("settings.lint.detected", shown))
            }
        }
            .inSmartMode(project)
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.any()) { loaded ->
                entries = loaded
                rootCombo.removeAllItems()
                loaded.forEach(rootCombo::addItem)
                loading = false
                select(loaded.firstOrNull())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun select(entry: RootEntry?) {
        current = entry
        if (rootCombo.selectedItem != entry) rootCombo.selectedItem = entry
        val s = entry?.let { edits[it.key] ?: settings.rootSettings(it.key) } ?: LintRootSettings.DEFAULT
        loading = true
        try {
            enabled.isSelected = s.enabled
            auto.isSelected = s.executor == LintExecutor.AUTO
            local.isSelected = s.executor == LintExecutor.LOCAL
            compose.isSelected = s.executor == LintExecutor.COMPOSE
            service.removeAllItems()
            service.addItem(message("settings.lint.service.auto"))
            entry?.services?.forEach(service::addItem)
            service.selectedItem = s.service ?: message("settings.lint.service.auto")
            executable.text = s.executable.orEmpty()
            config.text = s.config.orEmpty()
            args.text = s.extraArgs
            offline.isSelected = s.offline
            severity.selectedItem = s.severity
            detected.text = entry?.detected.orEmpty()
        } finally {
            loading = false
        }
    }

    private fun capture() {
        if (loading) return
        val entry = current ?: return
        edits[entry.key] = LintRootSettings(
            enabled = enabled.isSelected,
            config = config.text.trim().ifEmpty { null },
            extraArgs = args.text.trim(),
            offline = offline.isSelected,
            severity = severity.selectedItem as? LintSeverity ?: LintSeverity.YELLOW,
            executor = when {
                local.isSelected -> LintExecutor.LOCAL
                compose.isSelected -> LintExecutor.COMPOSE
                else -> LintExecutor.AUTO
            },
            executable = executable.text.trim().ifEmpty { null },
            service = (service.selectedItem as? String)?.takeIf { it != message("settings.lint.service.auto") },
        )
    }

    override fun isModified(): Boolean =
        commits.isSelected != settings.checkCommits || edits.any { (key, edited) -> edited != settings.rootSettings(key) }

    override fun apply() {
        super.apply()
        settings.checkCommits = commits.isSelected
        if (edits.isEmpty()) return
        edits.forEach { (key, edited) -> settings.update(key, edited) }
        edits.clear()
        LintService.getInstance(project).reset()
    }

    override fun reset() {
        super.reset()
        edits.clear()
        commits.isSelected = settings.checkCommits
        select(current)
    }

    override fun disposeUIResources() {
        super.disposeUIResources()
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
    }

    companion object {
        const val ID = "de.terletzkiy.ansibility.settings.lint"
    }
}
