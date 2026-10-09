package de.terletzkiy.ansibility.settings.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selected
import com.intellij.ui.layout.selectedValueIs
import com.intellij.ui.layout.selectedValueMatches
import com.intellij.util.concurrency.AppExecutorUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle
import de.terletzkiy.ansibility.golden.remote.ConnectionResult
import de.terletzkiy.ansibility.golden.remote.GoldenGitUrls
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorConsents
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorListener
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorTexts
import de.terletzkiy.ansibility.golden.remote.GoldenMirrors
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilitySettingsBundle.message
import de.terletzkiy.ansibility.settings.AppSettings
import de.terletzkiy.ansibility.settings.DocsWebBase
import de.terletzkiy.ansibility.settings.DriftSettings
import de.terletzkiy.ansibility.settings.ExecutableSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.JinjaSettings
import de.terletzkiy.ansibility.settings.ModuleNavigationTarget
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.settings.RemoteGolden
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.nonBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.CancellablePromise
import javax.swing.JButton
import javax.swing.event.DocumentEvent

/**
 * Settings › Languages & Frameworks › Ansibility (plan "Coexistence & settings"), in Kotlin UI DSL v2.
 *
 * The page edits working copies of [AppSettings] and [ProjectSettings]: DSL bindings and the two tables write into
 * them, [apply] stores them through [AnsibilityAppSettings.update] and [AnsibilityProjectSettings.update], and
 * [reset] reloads them. The roots table lists the roots [AnsibleWorkspace] detects, loaded in a background read
 * action so opening the page never scans the project on the EDT.
 *
 * The "Role drift" group (plan amendment R24, D177) chooses the golden root from the same rows: None, the first role
 * library (automatic) or any non-detached root by name; a stored root that no longer exists shows as
 * "<key> (not found)". The combo fills when the rows arrive, so it is not a DSL binding: [isModified], [apply] and
 * [reset] handle it by hand.
 *
 * Plan amendment R25 adds two external golden roots to the combo: "Git repository" (D193: URL, branch or tag, roles
 * path, refresh interval, X127 history depth, Test Connection, Fetch Now) and "Folder outside the project" (X125). Their
 * fields show only for that choice and are handled by hand too; an invalid URL, ref, roles path or folder stops Apply
 * with the reason. A URL chosen here counts as this machine's consent to fetch it (D203).
 */
class AnsibilityConfigurable(private val project: Project) :
    BoundSearchableConfigurable(message("settings.display.name"), ID, ID) {

    private val appService = AnsibilityAppSettings.getInstance()
    private val projectService = AnsibilityProjectSettings.getInstance(project)

    private var app: AppSettings = appService.settings
    private var edited: ProjectSettings = projectService.settings
    private var share: Boolean = projectService.isSharedWithTeam

    private val rules = OuterLanguageRulesTable()
    private val roots = RootsTable(settingsOf = { edited.root(it) }, update = ::updateRoot)
    private val rootEditor = RootSettingsEditor(::rootEditorChanged)
    private var uiDisposable: Disposable? = null

    /** The golden root combo (D177); public for tests. */
    internal val goldenCombo = ComboBox<GoldenRoot>()

    /** The detected roots by key with their names, null until the background load finished. */
    private var goldenNames: Map<String, String>? = null
    private var fillingGolden = false

    /** R25 (D193): the git repository's fields; public for tests. */
    internal val gitUrlField = JBTextField()
    internal val gitRefField = JBTextField()
    internal val gitRolesPathField = JBTextField()
    internal val refreshCombo = ComboBox<Int>()
    internal val historyDepthSpinner = JBIntSpinner(RemoteGolden.DEFAULT_HISTORY_DEPTH, RemoteGolden.HISTORY_DEPTH_RANGE.first, RemoteGolden.HISTORY_DEPTH_RANGE.last)
    internal val testConnectionButton = JButton(message("drift.git.test"))
    internal val fetchNowButton = JButton(message("drift.git.fetch"))

    /** The inline result of Test Connection. */
    internal val connectionResult = JBLabel()

    /**
     * R25 step 2: "Refresh in the background through my SSH agent (it may ask for approval)", this machine's opt-in
     * ([GoldenMirrorConsents.sshAgentRefresh], application level, never in the project's files); public for tests.
     */
    internal val sshAgentBox = JBCheckBox(AnsibilityGoldenBundle.message("settings.sshAgent"))

    /** The mirror's (or folder's) current state, as [GoldenMirrorTexts.status] puts it. */
    internal val mirrorStatus = JBLabel()

    /** X125: the folder outside the project. */
    internal val folderField = TextFieldWithBrowseButton()

    /** The running Test Connection; tests wait for it. */
    internal var connectionTest: Job? = null
        private set

    /** The pending background load of the roots table; tests wait for it. */
    internal var rootsLoading: CancellablePromise<List<RootRow>>? = null
        private set

    init {
        roots.table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) loadRootEditor()
        }
        goldenCombo.renderer = textListCellRenderer { golden: GoldenRoot? -> golden?.let(::goldenLabel).orEmpty() }
        goldenCombo.addActionListener {
            if (!fillingGolden) edited = edited.copy(drift = edited.drift.copy(golden = goldenCombo.item ?: GoldenRoot.None))
            updateFetchNow()
        }
        fillGoldenCombo()
        refreshCombo.renderer = textListCellRenderer { minutes: Int? -> minutes?.let(::refreshLabel).orEmpty() }
        folderField.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle(message("drift.folder.choose")))
        val fieldsChanged = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateFetchNow()
        }
        listOf(gitUrlField, gitRefField, gitRolesPathField).forEach { it.document.addDocumentListener(fieldsChanged) }
        refreshCombo.addActionListener { updateFetchNow() }
        historyDepthSpinner.addChangeListener { updateFetchNow() }
        testConnectionButton.addActionListener { testConnection() }
        fetchNowButton.addActionListener { GoldenMirrors.getInstance(project).fetchNow(interactive = true) }
        loadExternalFields(projectService.settings.drift)
    }

    override fun createPanel(): DialogPanel {
        uiDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable("AnsibilityConfigurable")
        uiDisposable = disposable
        val panel = panel {
            applicationSection()
            projectSection()
        }
        loadRoots(disposable)
        project.messageBus.connect(disposable).subscribe(
            GoldenMirrorListener.TOPIC,
            GoldenMirrorListener { state -> ApplicationManager.getApplication().invokeLater({ showMirrorStatus(state) }, ModalityState.any()) },
        )
        showMirrorStatus(GoldenMirrors.getInstance(project).state())
        return panel
    }

    private fun Panel.applicationSection() {
        group(message("app.group.executables")) {
            row { comment(message("app.executables.comment")) }
            executableRow("ansible-doc", { app.executables.ansibleDoc }) { app.executables.copy(ansibleDoc = it) }
            executableRow("ansible-inventory", { app.executables.ansibleInventory }) { app.executables.copy(ansibleInventory = it) }
            executableRow("ansible", { app.executables.ansible }) { app.executables.copy(ansible = it) }
            row {
                checkBox(message("app.executables.local.target"))
                    .bindSelected(
                        { app.executables.localTargetGuess },
                        { app = app.copy(executables = app.executables.copy(localTargetGuess = it)) },
                    )
                    .comment(message("app.executables.local.target.comment"))
            }
        }
        group(message("app.group.docs")) {
            lateinit var webBase: ComboBox<DocsWebBase>
            row(message("docs.web.base")) {
                webBase = comboBox(DocsWebBase.entries, SettingsLabels.listRenderer(SettingsLabels::webBase))
                    .bindItem({ app.docs.webBase }, { app = app.copy(docs = app.docs.copy(webBase = it ?: DocsWebBase.TARGET_VERSIONED)) })
                    .component
            }
            row(message("docs.web.base.custom.url")) {
                textField()
                    .bindText({ app.docs.customWebBaseUrl }, { app = app.copy(docs = app.docs.copy(customWebBaseUrl = it.trim())) })
                    .align(AlignX.FILL)
                    .enabledIf(webBase.selectedValueIs(DocsWebBase.CUSTOM))
                    .comment(message("docs.web.base.custom.url.comment"))
            }
            row(message("docs.module.navigation")) {
                comboBox(ModuleNavigationTarget.entries, SettingsLabels.listRenderer(SettingsLabels::moduleNavigation))
                    .bindItem(
                        { app.docs.moduleNavigation },
                        { app = app.copy(docs = app.docs.copy(moduleNavigation = it ?: ModuleNavigationTarget.WEB_DOCS)) },
                    )
            }
            row {
                checkBox(message("docs.background.refresh"))
                    .bindSelected({ app.docs.backgroundRefresh }, { app = app.copy(docs = app.docs.copy(backgroundRefresh = it)) })
                    .comment(message("docs.background.refresh.comment"))
            }
        }
        group(message("app.group.jinja")) {
            lateinit var claim: JBCheckBox
            row {
                claim = checkBox(message("jinja.claim.j2"))
                    .bindSelected({ app.jinja.claimJ2InsideRoots }, { jinja { copy(claimJ2InsideRoots = it) } })
                    .comment(message("jinja.claim.j2.comment"))
                    .component
            }
            indent {
                row {
                    checkBox(message("jinja.keep.yaml"))
                        .bindSelected({ app.jinja.keepYamlForJ2 }, { jinja { copy(keepYamlForJ2 = it) } })
                        .enabledIf(claim.selected)
                        .comment(message("jinja.keep.yaml.comment"))
                }
                row {
                    checkBox(message("jinja.defer.pycharm"))
                        .bindSelected({ app.jinja.deferToPyCharmJinja }, { jinja { copy(deferToPyCharmJinja = it) } })
                        .enabledIf(claim.selected)
                        .comment(message("jinja.defer.pycharm.comment"))
                }
            }
            row {
                checkBox(message("jinja.templates.dir"))
                    .bindSelected(
                        { app.jinja.treatJinjaTemplatesUnderTemplatesDir },
                        { jinja { copy(treatJinjaTemplatesUnderTemplatesDir = it) } },
                    )
                    .comment(message("jinja.templates.dir.comment"))
            }
            row {
                checkBox(message("jinja.auto.close"))
                    .bindSelected({ app.jinja.autoCloseDelimiters }, { jinja { copy(autoCloseDelimiters = it) } })
            }
            row {
                checkBox(message("jinja.auto.end.tags"))
                    .bindSelected({ app.jinja.autoInsertEndTags }, { jinja { copy(autoInsertEndTags = it) } })
            }
            row { label(message("jinja.rules.label")) }
            row { cell(rules.component).align(Align.FILL) }.resizableRow()
            row {
                comment(message("jinja.rules.comment"))
            }
            row {
                link(message("jinja.rules.reset")) { rules.reset(JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES) }
            }
        }
        group(message("app.group.coexistence")) {
            row {
                checkBox(message("coexist.notifications"))
                    .bindSelected(
                        { app.coexistence.conflictNotifications },
                        { app = app.copy(coexistence = app.coexistence.copy(conflictNotifications = it)) },
                    )
            }
            row {
                checkBox(message("coexist.hide.completions"))
                    .bindSelected(
                        { app.coexistence.hideOtherAnsibleCompletions },
                        { app = app.copy(coexistence = app.coexistence.copy(hideOtherAnsibleCompletions = it)) },
                    )
                    .comment(message("coexist.hide.completions.comment"))
            }
        }
    }

    private fun Panel.executableRow(
        name: String,
        get: () -> String?,
        set: (String?) -> ExecutableSettings,
    ) {
        row("$name:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle(message("app.executables.choose", name)), project)
                .bindText({ get().orEmpty() }, { app = app.copy(executables = set(it.nonBlank())) })
                .align(AlignX.FILL)
        }
    }

    private fun Panel.projectSection() {
        group(message("project.group")) {
            row {
                checkBox(message("project.share"))
                    .bindSelected({ share }, { share = it })
                    .comment(message("project.share.comment"))
            }
        }
        group(message("project.group.roots")) {
            row { comment(message("project.roots.comment")) }
            row { scrollCell(roots.table).align(Align.FILL) }.resizableRow()
            row { label(message("project.roots.selected")) }
            row { cell(rootEditor.component).align(AlignX.FILL) }
            row { comment(message("preset.help.documented")) }
            row { comment(message("preset.help.runtime")) }
            row { comment(message("preset.help.strict")) }
        }
        group(message("project.group.paths")) {
            row {
                checkBox(message("paths.detached"))
                    .bindSelected({ edited.paths.detachedRule }, { edited = edited.copy(paths = edited.paths.copy(detachedRule = it)) })
                    .comment(message("paths.detached.comment"))
            }
            row(message("paths.ignored")) {
                textArea()
                    .rows(IGNORED_PATHS_ROWS)
                    .columns(IGNORED_PATHS_COLUMNS)
                    .bindText(
                        { edited.paths.extraIgnoredPaths.joinToString("\n") },
                        { text -> edited = edited.copy(paths = edited.paths.copy(extraIgnoredPaths = parseLines(text))) },
                    )
                    .comment(message("paths.ignored.comment"))
            }
            row {
                checkBox(message("paths.schemastore"))
                    .bindSelected(
                        { edited.paths.schemaStoreExclusion },
                        { edited = edited.copy(paths = edited.paths.copy(schemaStoreExclusion = it)) },
                    )
                    .comment(message("paths.schemastore.comment"))
            }
        }
        moleculeGroup()
    }

    /** The Molecule switches (plan amendment R20, D150): they replace the old "Molecule support" of the Paths group. */
    private fun Panel.moleculeGroup() {
        group(message("project.group.molecule")) {
            row {
                checkBox(message("molecule.tests"))
                    .bindSelected({ edited.molecule.runTests }, { edited = edited.copy(molecule = edited.molecule.copy(runTests = it)) })
                    .comment(message("molecule.tests.comment"))
            }
            row {
                checkBox(message("molecule.navigation"))
                    .bindSelected(
                        { edited.molecule.showInNavigation },
                        { edited = edited.copy(molecule = edited.molecule.copy(showInNavigation = it)) },
                    )
                    .comment(message("molecule.navigation.comment"))
            }
            row { comment(message("molecule.ignore.comment")) }
        }
        driftGroup()
    }

    /**
     * Role drift (plan amendment R24, D177): the golden root and "Ignore molecule/ in drift" (R9's D41); for the
     * external golden roots of plan amendment R25 the repository's fields (D193, X127) or the folder (X125).
     */
    private fun Panel.driftGroup() {
        group(message("project.group.drift")) {
            row(message("drift.golden")) {
                cell(goldenCombo).comment(message("drift.golden.comment"))
            }
            val git = goldenCombo.selectedValueIs(GoldenRoot.Git)
            val folder = goldenCombo.selectedValueIs(GoldenRoot.Folder)
            val external = goldenCombo.selectedValueMatches { it == GoldenRoot.Git || it == GoldenRoot.Folder }
            indent {
                row(message("drift.git.url")) {
                    cell(gitUrlField)
                        .align(AlignX.FILL)
                        .validationOnInput { field -> if (goldenCombo.item == GoldenRoot.Git) urlError(field.text)?.let(::error) else null }
                        .comment(message("drift.git.url.comment"))
                }.visibleIf(git)
                row(message("drift.git.ref")) {
                    cell(gitRefField)
                        .columns(REF_COLUMNS)
                        .validationOnInput { field -> GoldenGitUrls.refProblem(field.text)?.let { error(GoldenMirrorTexts.refProblem()) } }
                        .comment(message("drift.git.ref.comment"))
                }.visibleIf(git)
                row(message("drift.git.roles")) {
                    cell(gitRolesPathField)
                        .columns(REF_COLUMNS)
                        .validationOnInput { field -> GoldenGitUrls.rolesPathProblem(field.text)?.let { error(GoldenMirrorTexts.rolesPathProblem()) } }
                        .comment(message("drift.git.roles.comment"))
                }.visibleIf(git)
                row(message("drift.git.refresh")) {
                    cell(refreshCombo)
                }.visibleIf(git)
                row(message("drift.git.depth")) {
                    cell(historyDepthSpinner).comment(message("drift.git.depth.comment"))
                }.visibleIf(git)
                row {
                    cell(sshAgentBox).comment(AnsibilityGoldenBundle.message("settings.sshAgent.comment"))
                }.visibleIf(git)
                row {
                    cell(testConnectionButton)
                    cell(fetchNowButton)
                }.visibleIf(git)
                row { cell(connectionResult) }.visibleIf(git)
                row { comment(message("drift.git.comment")) }.visibleIf(git)
                row(message("drift.folder")) {
                    cell(folderField).align(AlignX.FILL).comment(message("drift.folder.comment"))
                }.visibleIf(folder)
                row { cell(mirrorStatus) }.visibleIf(external)
            }
            row {
                checkBox(message("drift.ignore.molecule"))
                    .bindSelected({ edited.drift.ignoreMolecule }, { edited = edited.copy(drift = edited.drift.copy(ignoreMolecule = it)) })
                    .comment(message("drift.ignore.molecule.comment"))
            }
        }
    }

    /** The URL's problem as the settings page shows it, or null. */
    private fun urlError(url: String): String? = GoldenGitUrls.problem(url)?.let(GoldenMirrorTexts::urlProblem)

    /** "Every 30 minutes", "On demand only". */
    internal fun refreshLabel(minutes: Int): String =
        if (minutes == 0) message("drift.git.refresh.never") else message("drift.git.refresh.minutes", minutes)

    /** The repository settings as the fields show them. */
    internal fun remoteFromFields(): RemoteGolden = RemoteGolden(
        url = gitUrlField.text,
        ref = gitRefField.text,
        rolesPath = gitRolesPathField.text,
        refreshMinutes = refreshCombo.item ?: RemoteGolden.DEFAULT_REFRESH_MINUTES,
        historyDepth = historyDepthSpinner.number,
    ).normalized()

    /** The folder as an absolute path with `~` expanded (X125); a text that is no absolute path stays as typed (Apply refuses it). */
    private fun folderFromField(): String {
        val text = folderField.text.trim()
        return DriftSettings.expandFolder(text)?.toString() ?: text
    }

    /** Shows [drift]'s repository and folder in the fields. */
    private fun loadExternalFields(drift: DriftSettings) {
        val remote = drift.remote.normalized()
        gitUrlField.text = remote.url
        gitRefField.text = remote.ref
        gitRolesPathField.text = remote.rolesPath
        refreshCombo.removeAllItems()
        val choices = RemoteGolden.REFRESH_CHOICES + listOfNotNull(remote.refreshMinutes.takeIf { it !in RemoteGolden.REFRESH_CHOICES })
        choices.forEach(refreshCombo::addItem)
        refreshCombo.selectedItem = remote.refreshMinutes
        historyDepthSpinner.number = remote.effectiveHistoryDepth
        folderField.text = drift.folder
        sshAgentBox.isSelected = GoldenMirrorConsents.getInstance().sshAgentRefresh
        connectionResult.text = ""
        updateFetchNow()
    }

    /** Fetch Now works on the applied setting: enabled only while the page shows the stored repository. */
    private fun updateFetchNow() {
        val stored = projectService.settings.drift
        fetchNowButton.isEnabled = goldenCombo.item == GoldenRoot.Git && stored.golden == GoldenRoot.Git &&
            remoteFromFields() == stored.remote.normalized() && GoldenGitUrls.problem(stored.remote.url) == null
    }

    /** Test Connection (D195): `ls-remote` of the URL and ref in the fields, explicit (the IDE may ask for credentials). */
    private fun testConnection() {
        val url = gitUrlField.text
        val ref = gitRefField.text
        urlError(url)?.let {
            showConnection(ConnectionResult(false, it))
            return
        }
        connectionResult.text = message("drift.git.testing")
        connectionResult.icon = null
        connectionTest?.cancel()
        val modality = ModalityState.stateForComponent(testConnectionButton)
        connectionTest = project.service<DriftSettingsScope>().scope.launch {
            val result = GoldenMirrors.getInstance(project).testConnection(url, ref)
            withContext(Dispatchers.EDT + modality.asContextElement()) { showConnection(result) }
        }
    }

    private fun showConnection(result: ConnectionResult) {
        connectionResult.text = result.text
        connectionResult.icon = if (result.success) AllIcons.General.InspectionsOK else AllIcons.General.BalloonError
    }

    private fun showMirrorStatus(state: GoldenMirrorState?) {
        mirrorStatus.text = GoldenMirrorTexts.status(state)
        updateFetchNow()
    }

    /** "None", "First role library (automatic)", a root's name, or "<key> (not found)" once the roots are known. */
    internal fun goldenLabel(golden: GoldenRoot): String = when (golden) {
        GoldenRoot.None -> message("drift.golden.none")
        GoldenRoot.FirstRoleLibrary -> message("drift.golden.first.library")
        is GoldenRoot.Root -> {
            val names = goldenNames
            when {
                names == null -> golden.key
                else -> names[golden.key] ?: message("drift.golden.missing", golden.key)
            }
        }
        GoldenRoot.Git -> message("drift.golden.git")
        GoldenRoot.Folder -> message("drift.golden.folder")
    }

    /** The combo's items (the known roots, plus a stored root that is not found) with the working copy's choice selected. */
    private fun fillGoldenCombo() {
        val current = edited.drift.golden
        val roots = goldenNames.orEmpty().keys.map { GoldenRoot.Root(it) }
        val items = buildList {
            add(GoldenRoot.None)
            add(GoldenRoot.FirstRoleLibrary)
            addAll(roots)
            if (current is GoldenRoot.Root && current !in roots) add(current)
            // R25: the external golden roots (D193, X125).
            add(GoldenRoot.Git)
            add(GoldenRoot.Folder)
        }
        fillingGolden = true
        try {
            goldenCombo.removeAllItems()
            items.forEach(goldenCombo::addItem)
            goldenCombo.selectedItem = current
        } finally {
            fillingGolden = false
        }
    }

    /** No help page exists for this page, so no help button is shown. */
    override fun getHelpTopic(): String? = null

    override fun isModified(): Boolean {
        rules.stopEditing()
        return super.isModified() ||
            rules.rules() != appService.settings.jinja.outerLanguageRules ||
            edited.normalized().roots != projectService.settings.roots ||
            edited.drift.golden != projectService.settings.drift.golden ||
            remoteFromFields() != projectService.settings.drift.remote.normalized() ||
            folderFromField() != projectService.settings.drift.folder.trim() ||
            sshAgentBox.isSelected != GoldenMirrorConsents.getInstance().sshAgentRefresh
    }

    override fun apply() {
        rules.stopEditing()
        if (roots.table.isEditing) roots.table.cellEditor?.stopCellEditing()
        val golden = goldenCombo.item ?: GoldenRoot.None
        val remote = remoteFromFields()
        val folder = folderFromField()
        checkExternal(golden, remote, folder)
        val before = projectService.settings.drift
        super.apply()
        edited = edited.copy(drift = edited.drift.copy(golden = golden, remote = remote, folder = folder))
        // D203: a repository chosen on this page of this machine needs no further question.
        if (golden == GoldenRoot.Git && (before.golden != GoldenRoot.Git || before.remote.normalized().url != remote.url)) {
            GoldenMirrorConsents.getInstance().set(remote.url, true)
        }
        // R25 step 2: per user and machine (application level), never in the project's settings.
        val consents = GoldenMirrorConsents.getInstance()
        val sshAgentChanged = sshAgentBox.isSelected != consents.sshAgentRefresh
        consents.sshAgentRefresh = sshAgentBox.isSelected
        val newApp = app.copy(jinja = app.jinja.copy(outerLanguageRules = rules.rules()))
        appService.update { newApp }
        projectService.setSharedWithTeam(share)
        projectService.update { edited }
        app = appService.settings
        edited = projectService.settings
        roots.refresh()
        fillGoldenCombo()
        loadExternalFields(edited.drift)
        if (sshAgentChanged) GoldenMirrors.getInstance(project).sshAgentRefreshChanged()
    }

    /** Stops Apply with the reason when the chosen external golden root's fields are invalid (D193, X125). */
    private fun checkExternal(golden: GoldenRoot, remote: RemoteGolden, folder: String) {
        when (golden) {
            GoldenRoot.Git -> {
                urlError(remote.url)?.let { throw ConfigurationException(it) }
                if (GoldenGitUrls.refProblem(remote.ref) != null) throw ConfigurationException(GoldenMirrorTexts.refProblem())
                if (GoldenGitUrls.rolesPathProblem(remote.rolesPath) != null) throw ConfigurationException(GoldenMirrorTexts.rolesPathProblem())
            }
            GoldenRoot.Folder -> if (DriftSettings.expandFolder(folder) == null) throw ConfigurationException(GoldenMirrorTexts.folderInvalid())
            else -> Unit
        }
    }

    override fun reset() {
        app = appService.settings
        edited = projectService.settings
        share = projectService.isSharedWithTeam
        super.reset()
        rules.reset(app.jinja.outerLanguageRules)
        roots.refresh()
        loadRootEditor()
        fillGoldenCombo()
        loadExternalFields(edited.drift)
        showMirrorStatus(GoldenMirrors.getInstance(project).state())
    }

    override fun disposeUIResources() {
        connectionTest?.cancel()
        connectionTest = null
        rootsLoading?.cancel()
        rootsLoading = null
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
        super.disposeUIResources()
    }

    private fun jinja(transform: JinjaSettings.() -> JinjaSettings) {
        app = app.copy(jinja = app.jinja.transform())
    }

    private fun updateRoot(key: String, transform: (RootSettings) -> RootSettings) {
        edited = edited.withRoot(key, transform(edited.root(key)))
    }

    private fun rootEditorChanged() {
        val row = roots.selectedRow ?: return
        updateRoot(row.key) { rootEditor.applyTo(it) }
    }

    private fun loadRootEditor() {
        rootEditor.load(roots.selectedRow?.let { edited.root(it.key) })
    }

    private fun loadRoots(disposable: Disposable) {
        rootsLoading = ReadAction.nonBlocking<List<RootRow>> { detectRows() }
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.current()) { rows ->
                roots.setRows(rows)
                loadRootEditor()
                // The golden root may be any non-detached root, listed by name (D177).
                goldenNames = rows.filter { !it.root.detached }.associate { it.key to it.root.displayName }
                fillGoldenCombo()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The detected roots with their auto-detected targets (ignoring overrides, so "Auto" shows what Auto means). */
    private fun detectRows(): List<RootRow> {
        if (project.isDisposed) return emptyList()
        val detected = AnsibleWorkspace.getInstance(project).roots()
        val versions = TargetVersionDetector.resolve(detected, { null }, TargetVersionDetector::findPins)
        return detected.map { root -> RootRow(RootKeys.keyOf(project, root.dir), root, versions[root.dir] ?: TargetVersion.UNKNOWN) }
    }

    companion object {
        const val ID: String = "de.terletzkiy.ansibility.settings"
        private const val IGNORED_PATHS_ROWS = 3
        private const val IGNORED_PATHS_COLUMNS = 40
        private const val REF_COLUMNS = 20

        private fun parseLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** The coroutine scope of the settings page's Test Connection (a project light service: cancelled with the project). */
@Service(Service.Level.PROJECT)
internal class DriftSettingsScope(val scope: CoroutineScope)
