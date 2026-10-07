package de.terletzkiy.ansibility.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.settings.RunnerConfigurable
import javax.swing.JComponent

/**
 * The fields of a playbook run, shared by the run dialog (with a [context]: target, environment, executor, completion
 * and the root's runner settings come from the playbook's root) and the run configuration editor (without one: plain
 * fields).
 */
class PlaybookRunForm(private val project: Project, private val context: PlaybookRunContext?) {
    /** One choice of the executor combo: AUTO, NATIVE or DOCKER with a discovered [target]. */
    class ExecutorItem(val executor: PlaybookExecutor, val target: DockerTarget?, val text: String)

    private val playbookField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle(message("run.form.playbook.choose")))
    }
    private val targetCombo = ComboBox((context?.targets ?: emptyList()).toTypedArray()).apply {
        renderer = textListCellRenderer("") { it.text }
    }
    private val additionsBox = JBCheckBox(message("run.form.tags.add")).apply { isSelected = true }
    private val additionsLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val tagsNote = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val environmentIds: List<String> = context?.environments?.map { it.id }.orEmpty()
    private val environmentCombo = ComboBox(environmentIds.toTypedArray()).apply {
        renderer = textListCellRenderer(message("run.form.environment.choose")) { id -> context?.environment(id)?.label ?: id }
    }
    private val environmentText = JBTextField()
    private val limitProvider = PatternCompletion(emptyList(), ",:&!")
    private val limitField = TextFieldWithAutoCompletion(project, limitProvider, false, "")
    private val tagsField = TextFieldWithAutoCompletion(project, PatternCompletion(context?.tags.orEmpty(), ","), false, "")
    private val skipTagsField = TextFieldWithAutoCompletion(project, PatternCompletion(context?.tags.orEmpty(), ","), false, "")
    private val extraVarsArea = JBTextArea(3, 40)
    private val checkBox = JBCheckBox(message("run.form.check"))
    private val diffBox = JBCheckBox(message("run.form.diff"))
    private val verbosityCombo = ComboBox((0..PlaybookRunSpec.MAX_VERBOSITY).toList().toTypedArray()).apply {
        renderer = textListCellRenderer("") { level -> if (level == 0) message("run.form.verbosity.none") else "-" + "v".repeat(level) }
    }
    private val becomeBox = JBCheckBox(message("run.form.become")).apply {
        addActionListener { if (!resetting) becomeChoice = isSelected }
    }

    /** The user's own become choice; null while it follows [PlaybookRunContext.becomeByDefault]. */
    private var becomeChoice: Boolean? = null

    /** The configuration editor's become choice (it has no context to decide "automatic" with). */
    private val becomeModeCombo = ComboBox(BecomeMode.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { message(it.key) }
    }

    private enum class BecomeMode(val value: Boolean?, val key: String) {
        AUTO(null, "run.form.become.auto"),
        ON(true, "run.form.become.on"),
        OFF(false, "run.form.become.off"),
        ;

        companion object {
            fun of(value: Boolean?): BecomeMode = entries.first { it.value == value }
        }
    }
    private val becomeSource = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val connectionLabel = JBLabel()
    private val freshnessBox = JBCheckBox(message("run.form.freshness"))
    private val executorCombo = ComboBox(executorItems().toTypedArray()).apply {
        renderer = textListCellRenderer("") { it.text }
    }
    private val composeFileField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle(message("run.form.compose.choose")))
    }
    private val composeServiceField = JBTextField()
    private val envFileField = JBTextField().apply { emptyText.text = PlaybookRunContext.DEFAULT_ENV_FILE }
    private val additionalArgsField = JBTextField()

    private var playbookPath: String = context?.playbookPath?.toString().orEmpty()
    private var target: PlaybookTarget = PlaybookTarget.PLAYBOOK

    init {
        environmentCombo.addActionListener {
            updateLimitVariants()
            updateRunner()
            updateBecome()
        }
        targetCombo.addActionListener { if (!resetting) selectTarget() }
        additionsBox.addActionListener { if (!resetting) selectTarget() }
        updateRunner()
    }

    private var resetting = false

    /** The tags the user agreed to add to the playbook before the run. */
    val additions: List<TagAddition>
        get() = if (context != null && additionsBox.isSelected) selection()?.additions.orEmpty() else emptyList()

    /** Whether a play or role is chosen but no tag selects it. */
    val lacksTags: Boolean get() = context != null && (targetCombo.item?.target?.kind ?: TargetKind.PLAYBOOK) != TargetKind.PLAYBOOK && tagsField.text.isBlank()

    val tagsComponent: JComponent get() = tagsField

    private fun selection(): TagSelection? = targetCombo.item?.selection

    val component: JComponent by lazy { panel { build() } }

    val preferredFocus: JComponent get() = if (context != null && context.environments.size > 1 && environmentCombo.item == null) environmentCombo else limitField

    /** Whether the root has several environments and none is chosen. */
    val lacksEnvironment: Boolean get() = context != null && context.environments.size > 1 && environmentCombo.item == null

    val environmentComponent: JComponent get() = environmentCombo

    private fun Panel.build() {
        if (context == null) {
            row(message("run.form.playbook")) { cell(playbookField).align(AlignX.FILL) }
            row(message("run.form.environment")) { cell(environmentText).align(AlignX.FILL).comment(message("run.form.environment.comment")) }
        } else {
            if (context.targets.size > 1) row(message("run.form.target")) { cell(targetCombo).align(AlignX.FILL) }
            if (environmentIds.isNotEmpty()) row(message("run.form.environment")) { cell(environmentCombo).align(AlignX.FILL) }
        }
        row(message("run.form.limit")) { cell(limitField).align(AlignX.FILL).comment(message("run.form.limit.comment")) }
        row(message("run.form.tags")) { cell(tagsField).align(AlignX.FILL) }
        if (context != null) {
            row("") { cell(additionsBox) }
            row("") { cell(additionsLabel) }
            row("") { cell(tagsNote) }
        }
        row(message("run.form.skip.tags")) { cell(skipTagsField).align(AlignX.FILL) }
        row(message("run.form.extra.vars")) {
            scrollCell(extraVarsArea).align(AlignX.FILL).comment(message("run.form.extra.vars.comment"))
        }
        row {
            cell(checkBox)
            cell(diffBox)
        }
        if (context != null) {
            row {
                cell(becomeBox)
                cell(becomeSource)
            }
        } else {
            row(message("run.form.become")) { cell(becomeModeCombo).comment(message("run.form.become.auto.comment")) }
        }
        row { cell(freshnessBox) }
        row(message("run.form.verbosity")) { cell(verbosityCombo) }
        if (context != null) {
            row(message("run.form.connection")) {
                cell(connectionLabel)
                link(message("run.form.runner.settings")) { editRunnerSettings() }
            }
        } else {
            row { comment(message("run.form.runner.comment")) }
        }
        row(message("run.form.executor")) { cell(executorCombo).align(AlignX.FILL) }
        if (context == null) {
            row(message("run.form.compose.file")) { cell(composeFileField).align(AlignX.FILL) }
            row(message("run.form.compose.service")) { cell(composeServiceField).align(AlignX.FILL).comment(message("run.form.compose.service.comment")) }
        }
        row(message("run.form.env.file")) { cell(envFileField).align(AlignX.FILL).comment(message("run.form.env.file.comment")) }
        row(message("run.form.args")) { cell(additionalArgsField).align(AlignX.FILL) }
    }

    fun reset(spec: PlaybookRunSpec) {
        playbookPath = spec.playbook
        target = spec.target
        playbookField.text = spec.playbook
        resetting = true
        try {
            targetCombo.item = context?.target(spec.target) ?: context?.targets?.firstOrNull()
            additionsBox.isSelected = true
        } finally {
            resetting = false
        }
        environmentText.text = spec.environment.orEmpty()
        environmentCombo.item = spec.environment?.takeIf { it in environmentIds }
        updateLimitVariants()
        limitField.text = spec.limit
        tagsField.text = spec.tags
        skipTagsField.text = spec.skipTags
        extraVarsArea.text = spec.extraVars
        checkBox.isSelected = spec.check
        diffBox.isSelected = spec.diff
        verbosityCombo.item = spec.verbosity.coerceIn(0, PlaybookRunSpec.MAX_VERBOSITY)
        becomeChoice = spec.become
        becomeModeCombo.item = BecomeMode.of(spec.become)
        resetting = true
        try {
            becomeBox.isSelected = spec.become ?: becomeByDefault()
        } finally {
            resetting = false
        }
        freshnessBox.isSelected = !spec.skipFreshnessCheck
        executorCombo.item = executorItemOf(spec)
        composeFileField.text = spec.composeFile
        composeServiceField.text = spec.composeService
        envFileField.text = spec.envFile
        additionalArgsField.text = spec.additionalArgs
        updateRunner()
        updateTagNotes()
    }

    fun spec(): PlaybookRunSpec {
        val executor = executorCombo.item ?: executorItems().first()
        val docker = executor.target
        return PlaybookRunSpec(
            playbook = if (context == null) playbookField.text.trim() else playbookPath,
            target = if (context == null) target else targetCombo.item?.target ?: PlaybookTarget.PLAYBOOK,
            environment = (if (context == null) environmentText.text.trim() else environmentCombo.item)?.takeIf { it.isNotEmpty() },
            limit = limitField.text.trim(),
            tags = tagsField.text.trim(),
            skipTags = skipTagsField.text.trim(),
            extraVars = extraVarsArea.text,
            check = checkBox.isSelected,
            diff = diffBox.isSelected,
            verbosity = verbosityCombo.item ?: 0,
            become = if (context == null) becomeModeCombo.item?.value else becomeChoice,
            skipFreshnessCheck = !freshnessBox.isSelected,
            executor = executor.executor,
            composeFile = docker?.composeFile?.toString() ?: if (context == null) composeFileField.text.trim() else "",
            composeService = docker?.service ?: if (context == null) composeServiceField.text.trim() else "",
            envFile = envFileField.text.trim(),
            additionalArgs = additionalArgsField.text.trim(),
        )
    }

    /** A new play or role: its tags go into the Tags field (with the ones to add, while that is on). */
    private fun selectTarget() {
        val choice = targetCombo.item ?: return
        val selection = choice.selection
        tagsField.text = when {
            selection == null -> if (target.kind == TargetKind.PLAYBOOK) tagsField.text else ""
            additionsBox.isSelected -> selection.tagsWithAdditions.joinToString(",")
            else -> selection.tags.joinToString(",")
        }
        target = choice.target
        updateTagNotes()
        updateBecome()
    }

    /** The default of the root and target while the user has not chosen; null outside the dialog. */
    private fun becomeByDefault(): Boolean {
        val context = context ?: return false
        val environment = environmentCombo.item ?: context.environments.singleOrNull()?.id
        return context.becomeByDefault(project, environment, targetCombo.item?.target ?: target)
    }

    /** Follows the default (environment, target, runner settings) until the user ticks or unticks the box. */
    private fun updateBecome() {
        if (context == null || becomeChoice != null) return
        resetting = true
        try {
            becomeBox.isSelected = becomeByDefault()
        } finally {
            resetting = false
        }
    }

    /** What the chosen play or role's tags leave out and select elsewhere, and the tags that would be added. */
    private fun updateTagNotes() {
        val selection = selection()
        val additions = selection?.additions.orEmpty()
        additionsBox.isVisible = additions.isNotEmpty()
        additionsLabel.isVisible = additions.isNotEmpty()
        additionsLabel.text = html(additions.map { it.text })
        val notes = selection?.notSelected.orEmpty().map { message("run.tags.note.not.selected", it) } +
            selection?.alsoSelected.orEmpty().map { message("run.tags.note.also", it) }
        tagsNote.isVisible = notes.isNotEmpty()
        tagsNote.text = html(notes)
    }

    private fun html(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }

    /** Shows the become password source and the connection of the root's runner settings as they are now. */
    private fun updateRunner() {
        val context = context ?: return
        val runner = context.runner(project)
        becomeSource.text = BecomePasswords.getInstance(project).describe(runner, environmentCombo.item ?: context.environments.singleOrNull()?.id)
        connectionLabel.text = PlaybookPreparation.describeConnection(runner) ?: message("run.connection.none")
        freshnessBox.isVisible = runner.checkFreshness
    }

    private fun editRunnerSettings() {
        val context = context ?: return
        ShowSettingsUtil.getInstance().editConfigurable(project, RunnerConfigurable(project, context.rootKey))
        updateRunner()
        updateBecome()
    }

    private fun updateLimitVariants() {
        val environment = context?.environment(environmentCombo.item) ?: return
        limitField.setVariants(environment.groups + environment.hosts)
    }

    private fun executorItems(): List<ExecutorItem> {
        val context = context ?: return listOf(
            ExecutorItem(PlaybookExecutor.AUTO, null, message("run.form.executor.auto.plain")),
            ExecutorItem(PlaybookExecutor.NATIVE, null, message("run.form.executor.native.plain")),
            ExecutorItem(PlaybookExecutor.DOCKER, null, message("run.form.executor.docker.plain")),
        )
        val auto = PlaybookRunSpec(playbook = "", executor = PlaybookExecutor.AUTO)
        val autoText = when (context.executor(auto)) {
            PlaybookExecutor.DOCKER -> context.dockerTarget(auto)?.let { message("run.form.executor.auto.docker", it.presentable()) }
            else -> context.nativeExecutable?.let { message("run.form.executor.auto.native", it.toString()) }
        } ?: message("run.form.executor.auto.none")
        val native = context.nativeExecutable?.let { message("run.form.executor.native", it.toString()) } ?: message("run.form.executor.native.missing")
        return listOf(ExecutorItem(PlaybookExecutor.AUTO, null, autoText), ExecutorItem(PlaybookExecutor.NATIVE, null, native)) +
            context.dockerTargets.map { ExecutorItem(PlaybookExecutor.DOCKER, it, message("run.form.executor.docker", it.presentable())) }
    }

    private fun executorItemOf(spec: PlaybookRunSpec): ExecutorItem? {
        val items = (0 until executorCombo.itemCount).map { executorCombo.getItemAt(it) }
        if (spec.executor != PlaybookExecutor.DOCKER) return items.firstOrNull { it.executor == spec.executor }
        val target = context?.dockerTarget(spec)
        return items.firstOrNull { it.executor == PlaybookExecutor.DOCKER && it.target == target } ?: items.firstOrNull()
    }

    /** Completes the item after the last of [separators] (`web,db:!old` completes `old`). */
    private class PatternCompletion(variants: Collection<String>, private val separators: String) :
        TextFieldWithAutoCompletion.StringsCompletionProvider(variants, null) {
        override fun getPrefix(text: String, offset: Int): String {
            val start = text.lastIndexOfAny(separators.toCharArray(), (offset - 1).coerceAtLeast(0)) + 1
            return text.substring(start.coerceAtMost(offset), offset).trimStart()
        }
    }

    private companion object {
        fun message(key: String, vararg params: Any): String = AnsibilityRunBundle.message(key, *params)
    }
}
