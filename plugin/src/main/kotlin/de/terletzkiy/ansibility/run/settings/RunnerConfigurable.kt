package de.terletzkiy.ansibility.run.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.TableView
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.DockerTargets
import de.terletzkiy.ansibility.run.EnvFiles
import de.terletzkiy.ansibility.run.PlaybookRunContext
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.ui.settings.SecretSourceFields
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import org.jetbrains.annotations.TestOnly
import java.awt.event.MouseEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message as vaultMessage

/**
 * Settings › Languages & Frameworks › Ansibility › Runner: per root, what every run of its playbooks uses (plan
 * amendment R12), so that no `.env.local` is needed: the remote user and jump host, the become password source (for
 * all environments right on the page, per environment in a table: the IDE password store, 1Password, Bitwarden,
 * KeePassXC, Proton Pass, a password file, an environment variable, or ask), the checks before a run, and the
 * environment and Compose variables. Stored per user ([RunnerSettings]); typed become passwords go to the IDE
 * password store on Apply.
 */
class RunnerConfigurable(private val project: Project, private val preselectedRootKey: String?) : SearchableConfigurable {
    /** The platform creates configurables through a `(Project)` constructor; a Kotlin default value does not make one. */
    constructor(project: Project) : this(project, null)

    internal data class RootEntry(
        val key: String,
        val name: String,
        val path: Path?,
        val environments: List<String>,
        /** The variables the root's Compose services interpolate in their volumes, and those a run sets itself. */
        val composeVariables: List<String>,
        val ownVariables: Set<String>,
    ) {
        val envLocal: Path? get() = path?.resolve(PlaybookRunContext.DEFAULT_ENV_FILE)?.takeIf { Files.isRegularFile(it) }
        val becomeRoot: BecomeRoot? get() = path?.let { BecomeRoot(it, name) }
    }

    /**
     * One scope's become password source: [kind] null is "ask" for all environments and "same as all environments"
     * for one; [stored] when the IDE password store holds a password of the scope (set here or remembered at a prompt).
     */
    internal class BecomeRow(
        val environment: String,
        var kind: VaultSourceKind?,
        var location: String = "",
        var password: CharArray? = null,
        var stored: Boolean = false,
    ) {
        val isAll: Boolean get() = environment == RunnerRootSettings.ALL_ENVIRONMENTS
    }

    /** One root's form; [badVariable] is the first variable line that does not parse (dropped from [settings]). */
    private class Edited(val settings: RunnerRootSettings, val become: List<BecomeRow>, val badVariable: String? = null) {
        val all: BecomeRow get() = become.first { it.isAll }
    }

    private val settings = RunnerSettings.getInstance(project)
    private val edits = LinkedHashMap<String, Edited>()
    private val forgotten = LinkedHashMap<String, MutableSet<String>>()
    private val storedScopes = HashMap<String, Set<String>>()
    private var entries: List<RootEntry> = emptyList()
    private var current: RootEntry? = null
    private var loading = false
    private var uiDisposable: CheckedDisposable? = null
    private var component: JComponent? = null

    private val rootCombo = ComboBox<RootEntry>().apply {
        renderer = textListCellRenderer("") { it.name }
        addActionListener { if (!loading) select(selectedItem as? RootEntry) }
    }
    private val remoteUser = JBTextField(20)
    private val jumpEnabled = JBCheckBox(message("settings.runner.jump.enabled")).apply { addActionListener { updateEnabled() } }
    private val jumpUser = JBTextField(16)
    private val jumpHost = JBTextField(28)
    private val jumpPort = JBTextField(6)
    private val forwardAgent = JBCheckBox(message("settings.runner.jump.agent"))
    private val jumpExtraArgs = JBTextField(28)
    private val skipHostKeys = JBCheckBox(message("settings.runner.no.host.keys"))
    private val checkFreshness = JBCheckBox(message("settings.runner.freshness")).apply { addActionListener { updateEnabled() } }
    private val freshnessBranch = JBTextField(20).apply { emptyText.text = message("settings.runner.freshness.default") }
    private val runMetadata = JBCheckBox(message("settings.runner.metadata"))
    private val runView = JBCheckBox(message("settings.runner.view"))
    private val moleculeDestroyMinutes = JBIntSpinner(RunnerRootSettings.DEFAULT_MOLECULE_DESTROY_MINUTES, 0, RunnerRootSettings.MAX_MOLECULE_DESTROY_MINUTES)
    private val environmentVariables = JBTextArea(3, 40)
    private val composeVariables = JBTextArea(3, 40)
    private val composeHint = JBLabel()
    private val importButton = JButton(message("settings.runner.import")).apply { addActionListener { importEnvLocal() } }
    private val importResult = JBLabel()

    /** The become password source for all environments, edited right on the page. */
    private val allSource = SecretSourceFields(project, BECOME_KINDS, message("settings.runner.become.prompt.hint")) { relayout() }
    private val forgetLink = ActionLink(message("settings.runner.become.forget")) { forgetAll() }
    private val envModel = ListTableModel<BecomeRow>(
        column<BecomeRow>(message("settings.runner.become.column.environment")) { it.environment },
        column<BecomeRow>(message("settings.runner.become.column.source")) { row ->
            row.kind?.let(VaultConfigurable::kindText) ?: message("settings.runner.become.inherit")
        },
        column<BecomeRow>(message("settings.runner.become.column.details")) { row -> details(row) },
    )
    private val envTable = TableView(envModel).apply { visibleRowCount = 4 }
    private var envRows: List<Row> = emptyList()
    private val managerLabels = PasswordManager.entries.associateWith { JBLabel() }

    override fun getId(): String = ID

    override fun getDisplayName(): String = message("settings.runner.name")

    override fun createComponent(): JComponent {
        uiDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newCheckedDisposable("RunnerConfigurable")
        uiDisposable = disposable
        val envDecorated = ToolbarDecorator.createDecorator(envTable)
            .disableAddAction()
            .disableUpDownActions()
            .setEditAction { envTable.selectedObject?.let(::editEnvironment) }
            .setRemoveAction { envTable.selectedObject?.let(::resetEnvironment) }
            .setRemoveActionName(message("settings.runner.become.reset"))
            .setPreferredSize(JBUI.size(480, 110))
            .createPanel()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                envTable.selectedObject?.let(::editEnvironment) ?: return false
                return true
            }
        }.installOn(envTable)
        val panel = panel {
            row(message("settings.runner.root")) {
                cell(rootCombo)
                cell(importButton)
            }
            row { cell(importResult) }
            group(message("settings.runner.connection")) {
                row(message("settings.runner.user")) { cell(remoteUser).comment(message("settings.runner.user.comment")) }
                row { cell(jumpEnabled) }
                indent {
                    row(message("settings.runner.jump.host")) {
                        cell(jumpHost)
                        label(message("settings.runner.jump.port"))
                        cell(jumpPort)
                    }
                    row(message("settings.runner.jump.user")) { cell(jumpUser).comment(message("settings.runner.jump.user.comment")) }
                    row(message("settings.runner.jump.args")) { cell(jumpExtraArgs).comment(message("settings.runner.jump.args.comment")) }
                    row { cell(forwardAgent) }
                }
                row { cell(skipHostKeys) }
                row { comment(message("settings.runner.connection.comment")) }
            }
            group(message("settings.runner.become")) {
                row { comment(message("settings.runner.become.comment")) }
                row { label(message("settings.runner.become.all.title")).bold() }
                indent {
                    allSource.install(this)
                    row { cell(forgetLink) }
                }
                envRows = listOf(
                    row { label(message("settings.runner.become.environments.title")).bold() },
                    row { cell(envDecorated).align(AlignX.FILL) },
                    row { comment(message("settings.runner.become.environments.comment")) },
                )
            }
            group(vaultMessage("settings.vault.managers")) {
                for (manager in PasswordManager.entries) {
                    row { cell(managerLabels.getValue(manager)) }
                    row { comment(vaultMessage("settings.vault.manager.comment.${manager.name.lowercase()}")) }
                }
            }
            group(message("settings.runner.before")) {
                row {
                    cell(checkFreshness)
                    cell(freshnessBranch)
                }
                row { cell(runMetadata).comment(message("settings.runner.metadata.comment")) }
                row { cell(runView).comment(message("settings.runner.view.comment")) }
            }
            group(message("settings.runner.molecule")) {
                row(message("settings.runner.molecule.destroy")) {
                    cell(moleculeDestroyMinutes)
                    label(message("settings.runner.molecule.minutes"))
                }.rowComment(message("settings.runner.molecule.destroy.comment"))
            }
            group(message("settings.runner.variables")) {
                row(message("settings.runner.variables.process")) {
                    scrollCell(environmentVariables).align(AlignX.FILL).comment(message("settings.runner.variables.process.comment"))
                }
                row(message("settings.runner.variables.compose")) { scrollCell(composeVariables).align(AlignX.FILL) }
                row { cell(composeHint) }
            }
        }
        component = panel
        detectManagers(disposable)
        loadRoots(disposable)
        return panel
    }

    private fun relayout() {
        component?.revalidate()
        component?.repaint()
    }

    /** Finds the password manager CLIs off the EDT (the login shell's `PATH` may still be loading). */
    private fun detectManagers(disposable: CheckedDisposable) {
        managerLabels.values.forEach { it.text = vaultMessage("settings.vault.manager.searching") }
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = PasswordManager.entries.associateWith { manager ->
                PasswordManagers.executable(manager) to (manager == PasswordManager.BITWARDEN && PasswordManagers.bitwardenBiometrics() != null)
            }
            ApplicationManager.getApplication().invokeLater({
                for ((manager, path) in found) {
                    managerLabels.getValue(manager).text = when {
                        path.first == null -> vaultMessage("settings.vault.manager.missing", manager.displayName, manager.command)
                        path.second -> vaultMessage("settings.vault.manager.found.biometric", manager.displayName, path.first.toString())
                        else -> vaultMessage("settings.vault.manager.found", manager.displayName, path.first.toString())
                    }
                }
            }, ModalityState.any()) { disposable.isDisposed }
        }
    }

    private fun loadRoots(disposable: CheckedDisposable) {
        loading = true
        ReadAction.nonBlocking<List<RootEntry>> { rootEntries() }
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.any()) { found ->
                entries = found
                rootCombo.removeAllItems()
                found.forEach(rootCombo::addItem)
                loading = false
                select(found.firstOrNull { it.key == (current?.key ?: preselectedRootKey) } ?: found.firstOrNull())
                loadStored(found, disposable)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun rootEntries(): List<RootEntry> {
        val layouts = ProjectLayoutService.getInstance(project)
        val home = System.getProperty("user.home")?.let { runCatching { Path.of(it) }.getOrNull() }
        return AnsibleWorkspace.getInstance(project).roots()
            .filter { !it.detached && it.kind != RootKind.ROLE_LIBRARY && it.kind != RootKind.NESTED_PLAYBOOK }
            .map { root ->
                val targets = DockerTargets.find(project, root.dir, home)
                RootEntry(
                    key = RootKeys.keyOf(project, root.dir),
                    name = root.displayName,
                    path = root.dir.toNioPathOrNull(),
                    environments = layouts.layout(root).inventories.map { it.id },
                    composeVariables = targets.flatMap { target -> target.variables.map { it.name } }.distinct(),
                    ownVariables = targets.flatMap { listOfNotNull(it.vaultFileVariable, it.sshSocketVariable) }.toSet(),
                )
            }
    }

    /** Which become passwords the IDE password store holds; read in the background, since the Keychain may block. */
    private fun loadStored(found: List<RootEntry>, disposable: CheckedDisposable) {
        val passwords = BecomePasswords.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val stored = found.associate { entry ->
                val root = entry.becomeRoot
                entry.key to scopesOf(entry).filterTo(HashSet()) { scope -> root != null && passwords.hasStored(root, scope) }
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposable.isDisposed) return@invokeLater
                storedScopes.putAll(stored)
                for ((key, scopes) in stored) {
                    val rows = edits[key]?.become ?: continue
                    rows.forEach { it.stored = it.environment in scopes && it.environment !in forgotten[key].orEmpty() }
                }
                // Only the stored state: the user may already be changing the source shown.
                current?.let { entry -> edits[entry.key]?.all }?.let { all ->
                    allSource.stored = all.stored || all.password != null
                    forgetLink.isVisible = all.stored
                }
                envModel.fireTableDataChanged()
            }, ModalityState.any())
        }
    }

    private fun scopesOf(entry: RootEntry): List<String> = listOf(RunnerRootSettings.ALL_ENVIRONMENTS) + entry.environments

    private fun select(entry: RootEntry?) {
        flushPassword()
        capture()
        current = entry
        if (rootCombo.selectedItem != entry) {
            loading = true
            rootCombo.selectedItem = entry
            loading = false
        }
        val edited = entry?.let(::editedOf)
        allSource.base = entry?.path
        show(edited?.settings ?: RunnerRootSettings.DEFAULT, edited?.become ?: listOf(BecomeRow(RunnerRootSettings.ALL_ENVIRONMENTS, null)))
        importButton.isEnabled = entry?.envLocal != null
        importButton.toolTipText = entry?.envLocal?.toString()
        importResult.text = ""
        composeHint.text = when {
            entry == null || entry.composeVariables.isEmpty() -> message("settings.runner.variables.compose.none")
            else -> message("settings.runner.variables.compose.used", entry.composeVariables.joinToString())
        }
    }

    private fun editedOf(entry: RootEntry): Edited = edits.getOrPut(entry.key) {
        val stored = settings.rootSettings(entry.key)
        val scopes = storedScopes[entry.key].orEmpty()
        val rows = scopesOf(entry).plus(stored.become.keys).distinct().map { scope ->
            val source = stored.become[scope]
            val kind = source?.kind?.takeUnless { scope == RunnerRootSettings.ALL_ENVIRONMENTS && it == VaultSourceKind.PROMPT }
            BecomeRow(scope, kind, source?.location.orEmpty(), stored = scope in scopes)
        }
        Edited(stored, rows)
    }

    private fun show(settings: RunnerRootSettings, rows: List<BecomeRow>) {
        loading = true
        try {
            remoteUser.text = settings.remoteUser
            jumpEnabled.isSelected = settings.jumpHost.enabled
            jumpUser.text = settings.jumpHost.user
            jumpHost.text = settings.jumpHost.host
            jumpPort.text = settings.jumpHost.port
            forwardAgent.isSelected = settings.jumpHost.forwardAgent
            jumpExtraArgs.text = settings.jumpHost.extraArgs
            skipHostKeys.isSelected = settings.skipHostKeyChecking
            checkFreshness.isSelected = settings.checkFreshness
            freshnessBranch.text = settings.freshnessBranch
            runMetadata.isSelected = settings.runMetadata
            runView.isSelected = settings.runView
            moleculeDestroyMinutes.number = settings.moleculeDestroyMinutes
            environmentVariables.text = lines(settings.environmentVariables)
            composeVariables.text = lines(settings.composeVariables)
            rows.firstOrNull { it.isAll }?.let(::showAll)
            val environments = rows.filter { !it.isAll }
            envModel.items = environments.toMutableList()
            envRows.forEach { it.visible(environments.isNotEmpty()) }
            updateEnabled()
        } finally {
            loading = false
        }
    }

    /** The inline fields from the row for all environments. */
    private fun showAll(row: BecomeRow) {
        val wasLoading = loading
        loading = true
        try {
            allSource.reset(row.kind ?: VaultSourceKind.PROMPT, row.location, row.stored || row.password != null)
            forgetLink.isVisible = row.stored
        } finally {
            loading = wasLoading
        }
    }

    private fun updateEnabled() {
        val jump = jumpEnabled.isSelected
        listOf(jumpUser, jumpHost, jumpPort, jumpExtraArgs, forwardAgent).forEach { it.isEnabled = jump }
        freshnessBranch.isEnabled = checkFreshness.isSelected
    }

    /** The form as settings; variables that do not parse are kept as typed until [apply] reports them. */
    private fun formSettings(rows: List<BecomeRow>): RunnerRootSettings = RunnerRootSettings(
        remoteUser = remoteUser.text.trim(),
        jumpHost = JumpHost(jumpEnabled.isSelected, jumpUser.text.trim(), jumpHost.text.trim(), jumpPort.text.trim(), forwardAgent.isSelected, jumpExtraArgs.text.trim()),
        skipHostKeyChecking = skipHostKeys.isSelected,
        become = rows.mapNotNull { row ->
            row.kind?.let { kind -> row.environment to BecomeSource(kind, row.location.takeIf { it.isNotEmpty() && kind != VaultSourceKind.PASSWORD_SAFE && kind != VaultSourceKind.PROMPT }) }
        }.toMap(LinkedHashMap()),
        composeVariables = parseVariables(composeVariables.text).first,
        environmentVariables = parseVariables(environmentVariables.text).first,
        checkFreshness = checkFreshness.isSelected,
        freshnessBranch = freshnessBranch.text.trim(),
        runMetadata = runMetadata.isSelected,
        runView = runView.isSelected,
        moleculeDestroyMinutes = moleculeDestroyMinutes.number,
    )

    /** Reads the form into the edits of the shown root (the inline become fields into its row for all environments). */
    private fun capture() {
        if (loading) return
        val entry = current ?: return
        val rows = edits[entry.key]?.become ?: return
        rows.firstOrNull { it.isAll }?.let { all ->
            all.kind = allSource.selected().takeUnless { it == VaultSourceKind.PROMPT }
            all.location = allSource.locationValue()
        }
        val bad = parseVariables(environmentVariables.text).second ?: parseVariables(composeVariables.text).second
        edits[entry.key] = Edited(formSettings(rows), rows, bad)
    }

    /** Moves a password typed into the inline field into the row for all environments of the shown root. */
    private fun flushPassword() {
        val all = current?.let { edits[it.key] }?.all ?: return
        val typed = allSource.takePassword() ?: return
        all.password?.fill('\u0000')
        all.password = typed
        allSource.stored = true
    }

    /** Forgets the password the IDE password store holds for all environments (stored here or remembered at a prompt). */
    private fun forgetAll() {
        val entry = current ?: return
        val all = edits[entry.key]?.all ?: return
        forgotten.getOrPut(entry.key) { LinkedHashSet() } += all.environment
        all.kind = allSource.selected().takeUnless { it == VaultSourceKind.PROMPT }
        all.location = allSource.locationValue()
        all.stored = false
        all.password?.fill('\u0000')
        all.password = null
        showAll(all)
    }

    private fun editEnvironment(row: BecomeRow) {
        val entry = current ?: return
        val dialog = BecomeSourceDialog(project, row, entry.path)
        if (!dialog.showAndGet()) return
        val (kind, location, password) = dialog.result()
        row.password?.takeIf { it !== password }?.fill('\u0000')
        row.kind = kind
        row.location = location
        row.password = password
        envModel.fireTableDataChanged()
        capture()
    }

    /** Back to the source for all environments, forgetting a stored or remembered password. */
    private fun resetEnvironment(row: BecomeRow) {
        val entry = current ?: return
        if (row.stored) forgotten.getOrPut(entry.key) { LinkedHashSet() } += row.environment
        row.password?.fill('\u0000')
        row.password = null
        row.kind = null
        row.location = ""
        row.stored = false
        envModel.fireTableDataChanged()
        capture()
    }

    private fun details(row: BecomeRow): String = when (row.kind) {
        VaultSourceKind.PASSWORD_SAFE -> when {
            row.password != null -> message("settings.runner.become.pending")
            row.stored -> message("settings.runner.become.stored")
            else -> message("settings.runner.become.missing")
        }
        null, VaultSourceKind.PROMPT -> if (row.stored) message("settings.runner.become.remembered") else ""
        else -> row.location
    }

    /** Fills the form from the root's `.env.local` (the connection and the Compose variables); Apply stores it. */
    private fun importEnvLocal() {
        val entry = current ?: return
        val file = entry.envLocal ?: return
        val values = try {
            EnvFiles.parse(Files.readString(file), EnvironmentUtil.getEnvironmentMap(), System.getProperty("user.home"))
        } catch (e: IOException) {
            importResult.text = message("settings.runner.import.failed", e.message.orEmpty())
            return
        }
        capture()
        val edited = edits[entry.key] ?: return
        val imported = EnvLocalImport.apply(edited.settings, values, entry.composeVariables, entry.ownVariables)
        show(imported, edited.become)
        capture()
        importResult.text = if (EnvLocalImport.hasConnection(values) || imported.composeVariables.isNotEmpty()) {
            message("settings.runner.import.done", file.fileName.toString())
        } else {
            message("settings.runner.import.nothing", file.fileName.toString())
        }
    }

    override fun isModified(): Boolean {
        capture()
        return forgotten.values.any { it.isNotEmpty() } || allSource.hasTypedPassword() ||
            edits.any { (key, edited) -> edited.settings != settings.rootSettings(key) || edited.become.any { it.password != null } || edited.badVariable != null }
    }

    override fun apply() {
        val shown = current
        if (shown != null) {
            allSource.validate(pendingPassword = edits[shown.key]?.all?.let { it.password != null || it.stored } == true)?.let {
                throw ConfigurationException(message("settings.runner.invalid", shown.name, it.message))
            }
        }
        flushPassword()
        capture()
        for ((key, edited) in edits) {
            val name = entries.firstOrNull { it.key == key }?.name ?: key
            invalid(edited)?.let { throw ConfigurationException(message("settings.runner.invalid", name, it)) }
        }
        val writes = ArrayList<Triple<BecomeRoot, String, CharArray?>>()
        for ((key, edited) in edits) {
            // A source that is no password store keeps no stored password around.
            for (row in edited.become) {
                if (row.stored && row.kind != null && row.kind != VaultSourceKind.PROMPT && row.kind != VaultSourceKind.PASSWORD_SAFE) {
                    forgotten.getOrPut(key) { LinkedHashSet() } += row.environment
                    row.stored = false
                }
            }
        }
        for ((key, scopes) in forgotten) {
            val root = entries.firstOrNull { it.key == key }?.becomeRoot ?: continue
            scopes.forEach { writes += Triple(root, it, null) }
        }
        for ((key, edited) in edits) {
            settings.update(key) { edited.settings }
            val root = entries.firstOrNull { it.key == key }?.becomeRoot
            for (row in edited.become) {
                val password = row.password ?: continue
                row.password = null
                if (root == null) {
                    password.fill('\u0000')
                    continue
                }
                writes.removeAll { it.first.path == root.path && it.second == row.environment && it.third == null }
                writes += Triple(root, row.environment, password)
                row.stored = true
            }
        }
        forgotten.clear()
        if (writes.isNotEmpty()) storePasswords(writes)
        envModel.fireTableDataChanged()
        current?.let { entry -> edits[entry.key]?.all?.let(::showAll) }
    }

    /** Writes and removes password-store entries off the EDT, zeroing every typed password afterwards. */
    private fun storePasswords(writes: List<Triple<BecomeRoot, String, CharArray?>>) {
        val passwords = BecomePasswords.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            for ((root, scope, password) in writes) {
                try {
                    passwords.store(root, scope, password)
                } catch (_: Exception) {
                    // The row shows "not stored" on the next open.
                } finally {
                    password?.fill('\u0000')
                }
            }
        }
    }

    /** Why [edited] cannot be stored, or null. */
    private fun invalid(edited: Edited): String? {
        edited.badVariable?.let { return message("settings.runner.invalid.variable", it) }
        val port = edited.settings.jumpHost.port
        if (port.isNotEmpty() && (port.toIntOrNull() ?: 0) !in 1..65535) return message("settings.runner.invalid.port", port)
        if (edited.settings.jumpHost.enabled && edited.settings.jumpHost.host.isBlank()) return message("settings.runner.invalid.jump")
        for (row in edited.become) {
            val scope = if (row.isAll) message("settings.runner.become.all") else row.environment
            val kind = row.kind ?: continue
            val manager = PasswordManager.of(kind)
            when {
                kind == VaultSourceKind.PASSWORD_SAFE && !row.stored && row.password == null -> return message("settings.runner.invalid.password", scope)
                manager != null && !PasswordManagers.isReference(manager, row.location) ->
                    return message("settings.runner.invalid.source", scope, vaultMessage("settings.vault.invalid.reference.${manager.name.lowercase()}"))
                kind == VaultSourceKind.PASSWORD_FILE && row.location.isBlank() -> return message("settings.runner.invalid.source", scope, vaultMessage("settings.vault.invalid.file"))
                kind == VaultSourceKind.ENVIRONMENT && !VARIABLE.matches(row.location) ->
                    return message("settings.runner.invalid.source", scope, vaultMessage("settings.vault.invalid.variable"))
            }
        }
        return null
    }

    override fun reset() {
        allSource.takePassword()?.fill('\u0000')
        edits.values.flatMap { it.become }.forEach { it.password?.fill('\u0000') }
        edits.clear()
        forgotten.clear()
        current?.let {
            current = null
            select(it)
        }
    }

    override fun disposeUIResources() {
        reset()
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
        component = null
    }

    /** The parsed `KEY=value` lines of [text] and the first line that does not parse. */
    private fun parseVariables(text: String): Pair<Map<String, String>, String?> {
        val result = LinkedHashMap<String, String>()
        var bad: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            val key = if (eq > 0) line.substring(0, eq).trim() else ""
            if (!VARIABLE.matches(key)) {
                if (bad == null) bad = line
                continue
            }
            result[key] = line.substring(eq + 1).trim()
        }
        return result to bad
    }

    private fun lines(values: Map<String, String>): String = values.entries.joinToString("\n") { "${it.key}=${it.value}" }

    @TestOnly
    internal fun shownRootKey(): String? = current?.key

    @TestOnly
    internal fun importForTests() = importEnvLocal()

    @TestOnly
    internal fun becomeFieldsForTests(): SecretSourceFields = allSource

    companion object {
        const val ID = "de.terletzkiy.ansibility.settings.runner"
        private val VARIABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** The become password sources, "ask" first: the kinds of a vault id's source. */
        internal val BECOME_KINDS = listOf(
            VaultSourceKind.PROMPT,
            VaultSourceKind.PASSWORD_SAFE,
            VaultSourceKind.ONE_PASSWORD,
            VaultSourceKind.BITWARDEN,
            VaultSourceKind.KEEPASSXC,
            VaultSourceKind.PROTON_PASS,
            VaultSourceKind.PASSWORD_FILE,
            VaultSourceKind.ENVIRONMENT,
        )

        private fun <T> column(name: String, get: (T) -> String) = object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = get(item)
        }
    }
}

/** The become password source of one environment: the same fields as for all environments, in a dialog. */
internal class BecomeSourceDialog(project: Project, private val row: RunnerConfigurable.BecomeRow, base: Path?) : DialogWrapper(project) {
    private var ready = false
    private val source = SecretSourceFields(project, RunnerConfigurable.BECOME_KINDS, message("settings.runner.become.prompt.hint")) { if (ready) pack() }

    init {
        title = message("settings.runner.become.dialog", row.environment)
        source.base = base
        source.reset(row.kind ?: VaultSourceKind.PROMPT, row.location, row.stored || row.password != null)
        init()
        ready = true
    }

    override fun createCenterPanel(): JComponent = panel { source.install(this) }

    override fun getPreferredFocusedComponent(): JComponent = source.kind

    override fun doValidate(): ValidationInfo? = source.validate(pendingPassword = row.password != null)

    /** The source, its location, and a newly typed password (taken from the field). */
    fun result(): Triple<VaultSourceKind, String, CharArray?> {
        val kind = source.selected()
        val typed = source.takePassword()
        return Triple(kind, source.locationValue(), typed ?: row.password?.takeIf { kind == VaultSourceKind.PASSWORD_SAFE }?.copyOf())
    }
}
