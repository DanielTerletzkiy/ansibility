package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.secrets.PreviousPasswordRecord
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.rotate.RotationService
import com.intellij.ui.dsl.builder.Placeholder
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.semantics.vault.DefaultArm
import de.terletzkiy.ansibility.semantics.vault.GenerationRule
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.RotationScriptRules
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.semantics.vault.SecretShape
import de.terletzkiy.ansibility.vault.regenerate.RegeneratePrompts
import de.terletzkiy.ansibility.vault.regenerate.RegenerateTexts
import de.terletzkiy.ansibility.vault.vcs.TrackedStatuses
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import javax.swing.DefaultCellEditor
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Settings › Languages & Frameworks › Ansibility › Vault (plan amendment R7/R8, F7.9): per root, the vault ids and
 * where each secret comes from (the IDE password store, 1Password, a password file, an environment variable or a
 * prompt), and the environment → id mapping. Ids, kinds and locations go to the shared vault settings; typed
 * passwords go to the IDE password store only, written on Apply off the EDT and zeroed afterwards. Also ANS-V108's
 * allowlist (plan amendment R21, D162), one glob per line, shared with the project, and the commit check's switch
 * (D167), stored per user (`VaultUserState`), so a repository cannot turn it off. Per root also the secret generation
 * rules of Regenerate Secrets… (plan amendment R29, D218), shared with the project, with Import from Script… (X131),
 * which reads a rotation script's `case` arms without running it. Rotate Password… starts Rotate Vault Password… for the
 * selected root and id (plan amendment R29, D204), and the previous passwords a rotation kept are listed with Forget
 * (D212; stored per user, the passwords only in the IDE password store).
 */
class VaultConfigurable(private val project: Project) : BoundSearchableConfigurable(message("settings.vault.name"), ID, ID) {
    internal data class RootEntry(
        val key: String,
        val name: String,
        val environments: List<String>,
        val discovered: List<String>,
        val stored: Set<String>,
        /** The root's real path: the key of its previous passwords (plan amendment R29, D212). */
        val canonical: String = "",
    )

    /** One id as the table shows it; [password] is a typed, not yet stored password. */
    internal class IdRow(
        var label: String,
        var kind: VaultSourceKind,
        var location: String = "",
        var password: CharArray? = null,
        var stored: Boolean = false,
    )

    internal class EnvRow(val environment: String, var identity: String)

    /** One secret generation rule as the table shows it (plan amendment R29, D218). */
    internal class RuleRow(var pattern: String, var kind: GeneratorKind, var length: Int) {
        fun toRule(): GenerationRule = GenerationRule(pattern.trim(), kind, length)

        /** The pattern can be read as a key glob ([GenerationRule.isValidPattern]: `[` closed, no `(`, `)`, `|`). */
        val isReadable: Boolean get() = GenerationRule.isValidPattern(pattern.trim())

        val isValid: Boolean get() = pattern.isNotBlank() && isReadable && length in SecretGenerator.lengths(kind)

        /** A valid rule whose values have fewer than 16 characters: easy to guess (plan amendment R29 review). */
        val isShort: Boolean get() = isValid && toRule().generator.characters < SecretShape.SHORT_BELOW

        companion object {
            fun of(rule: GenerationRule) = RuleRow(rule.pattern, rule.kind, rule.length)
        }
    }

    private class Edited(val ids: List<IdRow>, val envs: List<EnvRow>, val rules: List<RuleRow>)

    private val settings = VaultProjectSettings.getInstance(project)
    private val edits = LinkedHashMap<String, Edited>()
    private val forgotten = LinkedHashMap<String, MutableSet<String>>()
    private var rootEntries: List<RootEntry> = emptyList()
    private var current: RootEntry? = null
    private var uiDisposable: CheckedDisposable? = null
    private var loading = false

    private val rootCombo = ComboBox<RootEntry>().apply {
        renderer = textListCellRenderer("") { it.name }
        addActionListener { if (!loading) select(selectedItem as? RootEntry) }
    }
    private val idModel = ListTableModel<IdRow>(
        readOnly<IdRow>(message("settings.vault.column.id")) { it.label },
        readOnly<IdRow>(message("settings.vault.column.source")) { kindText(it.kind) },
        readOnly<IdRow>(message("settings.vault.column.location")) { row ->
            when (row.kind) {
                VaultSourceKind.PASSWORD_SAFE -> when {
                    row.password != null -> message("settings.vault.password.pending")
                    row.stored -> message("settings.vault.password.stored")
                    else -> message("settings.vault.password.missing")
                }
                VaultSourceKind.PROMPT -> message("settings.vault.prompt.hint")
                else -> row.location
            }
        },
    )
    private val idTable = TableView(idModel)
    private val envModel = ListTableModel<EnvRow>(
        readOnly<EnvRow>(message("settings.vault.column.environment")) { if (it.environment == VaultRootSettings.ANY_ENVIRONMENT) message("settings.vault.env.any") else it.environment },
        object : ColumnInfo<EnvRow, String>(message("settings.vault.column.encrypt.id")) {
            override fun valueOf(item: EnvRow): String = item.identity
            override fun isCellEditable(item: EnvRow): Boolean = true
            override fun setValue(item: EnvRow, value: String) {
                item.identity = value.trim()
                capture()
            }
        },
    )
    private val envTable = TableView(envModel)
    private val ruleModel = ListTableModel<RuleRow>(
        object : ColumnInfo<RuleRow, String>(message("settings.vault.column.pattern")) {
            override fun valueOf(item: RuleRow): String = item.pattern
            override fun isCellEditable(item: RuleRow): Boolean = true
            override fun setValue(item: RuleRow, value: String) {
                item.pattern = value.trim()
                capture()
            }
        },
        object : ColumnInfo<RuleRow, GeneratorKind>(message("settings.vault.column.generator")) {
            override fun valueOf(item: RuleRow): GeneratorKind = item.kind
            override fun isCellEditable(item: RuleRow): Boolean = true
            override fun setValue(item: RuleRow, value: GeneratorKind) {
                item.kind = value
                capture()
            }
            override fun getRenderer(item: RuleRow): TableCellRenderer = KindRenderer
            override fun getEditor(item: RuleRow): TableCellEditor = DefaultCellEditor(ComboBox(GeneratorKind.entries.toTypedArray()).apply {
                renderer = textListCellRenderer { it?.let(RegenerateTexts::kind) }
            })
        },
        object : ColumnInfo<RuleRow, Int>(message("settings.vault.column.length")) {
            override fun valueOf(item: RuleRow): Int = item.length
            override fun isCellEditable(item: RuleRow): Boolean = true
            override fun getColumnClass(): Class<*> = Int::class.javaObjectType
            override fun setValue(item: RuleRow, value: Int) {
                item.length = value
                capture()
            }
        },
    )
    private val ruleTable = TableView(ruleModel)

    /** Below the rules: patterns that cannot be read and rules shorter than 16 characters ([updateRuleWarning]). */
    private val ruleWarning = JBLabel().apply {
        icon = AllIcons.General.Warning
        isVisible = false
        name = RULE_WARNING_COMPONENT
    }
    private val discoveredLabel = JBLabel()
    private val storedInLabel = JBLabel().apply {
        componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }
    private val managerLabels = PasswordManager.entries.associateWith { JBLabel() }
    private lateinit var previousPlaceholder: Placeholder

    override fun createPanel(): DialogPanel {
        uiDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newCheckedDisposable("VaultConfigurable")
        uiDisposable = disposable
        val ids = ToolbarDecorator.createDecorator(idTable)
            .setAddAction { edit(null) }
            .setEditAction { idTable.selectedObject?.let(::edit) }
            .setRemoveAction { remove() }
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .createPanel()
        val envs = ToolbarDecorator.createDecorator(envTable).disableAddAction().disableRemoveAction().disableUpDownActions().createPanel()
        val rules = ToolbarDecorator.createDecorator(ruleTable)
            .setAddAction { addRule() }
            .setRemoveAction { removeRule() }
            .setMoveUpAction { moveRule(-1) }
            .setMoveDownAction { moveRule(1) }
            .createPanel()
        val panel = panel {
            row(message("settings.vault.root")) {
                cell(rootCombo)
                cell(storedInLabel)
            }
            group(message("settings.vault.ids")) {
                row { cell(ids).align(Align.FILL) }.resizableRow()
                row { comment(message("settings.vault.ids.comment")) }
                row { cell(discoveredLabel) }
            }
            group(message("settings.vault.envs")) {
                row { cell(envs).align(Align.FILL) }
                row { comment(message("settings.vault.envs.comment")) }
            }
            // Regenerate Secrets… (plan amendment R29, D218, X131): shared with the project.
            group(message("settings.vault.rules")) {
                row { cell(rules).align(Align.FILL) }
                row { cell(ruleWarning) }
                row {
                    button(message("settings.vault.rules.import")) { importRules() }.applyToComponent { name = IMPORT_RULES_COMPONENT }
                }
                row { comment(message("settings.vault.rules.comment")) }
            }
            // Rotate Vault Password… (plan amendment R29, D204, D212): for the selected id of the selected root, and the
            // previous passwords a rotation kept in the IDE password store, with Forget.
            group(message("settings.vault.rotation")) {
                row {
                    button(message("settings.vault.rotate")) { rotate() }.applyToComponent { name = ROTATE_COMPONENT }
                }
                row { comment(message("settings.vault.rotate.comment")) }
                row { previousPlaceholder = placeholder().align(AlignX.FILL) }
            }
            group(message("settings.vault.managers")) {
                for (manager in PasswordManager.entries) {
                    row { cell(managerLabels.getValue(manager)) }
                    row { comment(message("settings.vault.manager.comment.${manager.name.lowercase()}")) }
                }
            }
            // ANS-V108 (plan amendment R21, D162): the shared allowlist; it can only silence the check.
            group(message("settings.vault.keys")) {
                row {
                    textArea()
                        .label(message("settings.vault.keys.allowlist"), LabelPosition.TOP)
                        .rows(ALLOWLIST_ROWS)
                        .align(AlignX.FILL)
                        .bindText(
                            { settings.plaintextKeyAllowlist.joinToString("\n") },
                            { PlaintextKeyExclusions.setAllowlist(project, it.lines()) },
                        )
                        .comment(message("settings.vault.keys.allowlist.comment"))
                        .applyToComponent { name = ALLOWLIST_COMPONENT }
                }
            }
            // The commit check (plan amendment R21, D167): per user and for every project, never in project files.
            group(message("settings.vault.commits")) {
                row {
                    checkBox(message("settings.vault.commits.check"))
                        .bindSelected({ VaultUserState.getInstance().checkCommits }, { VaultUserState.getInstance().checkCommits = it })
                        .comment(message("settings.vault.commits.check.comment"))
                        .applyToComponent { name = COMMIT_CHECK_COMPONENT }
                }
            }
        }
        detectManagers(disposable)
        loadRoots(disposable)
        return panel
    }

    /** Finds the CLIs off the EDT (the login shell's `PATH` may still be loading). */
    private fun detectManagers(disposable: CheckedDisposable) {
        managerLabels.values.forEach { it.text = message("settings.vault.manager.searching") }
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = PasswordManager.entries.associateWith { manager ->
                if (manager == PasswordManager.BITWARDEN) PasswordManagers.bitwardenBiometrics()?.let { it to true } ?: PasswordManagers.executable(manager)?.let { it to false }
                else PasswordManagers.executable(manager)?.let { it to false }
            }
            ApplicationManager.getApplication().invokeLater({
                for ((manager, label) in managerLabels) {
                    val path = found[manager]
                    label.text = when {
                        path == null -> message("settings.vault.manager.missing", manager.displayName, manager.command)
                        path.second -> message("settings.vault.manager.found.biometric", manager.displayName, path.first.toString())
                        else -> message("settings.vault.manager.found", manager.displayName, path.first.toString())
                    }
                }
            }, ModalityState.any()) { disposable.isDisposed }
        }
    }

    private fun loadRoots(disposable: CheckedDisposable) {
        loading = true
        ReadAction.nonBlocking<List<RootEntry>> { entries() }
            .inSmartMode(project)
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.any()) { entries ->
                rootEntries = entries
                rootCombo.removeAllItems()
                entries.forEach(rootCombo::addItem)
                loading = false
                select(entries.firstOrNull())
                loadStored(entries, disposable)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Which password-store entries exist; read in the background, since the Keychain may block. */
    private fun loadStored(entries: List<RootEntry>, disposable: CheckedDisposable) {
        val secrets = VaultSecretsService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val withStored = entries.map { entry ->
                val root = rootOf(entry.key) ?: return@map entry
                val labels = settings.rootSettings(entry.key).identities.filter { it.kind == VaultSourceKind.PASSWORD_SAFE }.map { it.label }
                entry.copy(stored = labels.filterTo(HashSet()) { secrets.hasStoredPassword(root, it) })
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposable.isDisposed) return@invokeLater
                rootEntries = withStored
                val key = current?.key
                current = withStored.firstOrNull { it.key == key }
                for ((key, edited) in edits) {
                    val stored = withStored.firstOrNull { it.key == key }?.stored.orEmpty()
                    edited.ids.filter { it.kind == VaultSourceKind.PASSWORD_SAFE }.forEach { it.stored = it.stored || it.label in stored }
                }
                select(current)
            }, ModalityState.any())
        }
    }

    private fun entries(): List<RootEntry> {
        val registry = VaultIdentityRegistry.getInstance(project)
        val layouts = ProjectLayoutService.getInstance(project)
        return AnsibleWorkspace.getInstance(project).roots()
            .filter { !it.detached && it.kind != RootKind.ROLE_LIBRARY && it.kind != RootKind.NESTED_PLAYBOOK }
            .map { root ->
                val discovery = registry.discovery(root)
                val discovered = discovery.identities.filter { it.source.origin != VaultSourceOrigin.SETTINGS }.map { identity ->
                    listOfNotNull(identity.label, kindText(identity.source.kind), identity.source.location).joinToString(" \u00B7 ")
                }
                RootEntry(
                    RootKeys.keyOf(project, root.dir), root.displayName, layouts.layout(root).inventories.map { it.id }, discovered, emptySet(),
                    discovery.canonicalRootPath,
                )
            }
    }

    private fun select(entry: RootEntry?) {
        current = entry
        if (rootCombo.selectedItem != entry) rootCombo.selectedItem = entry
        loading = true
        try {
            val edited = entry?.let(::editedOf)
            idModel.items = edited?.ids.orEmpty().toMutableList()
            envModel.items = edited?.envs.orEmpty().toMutableList()
            ruleModel.items = edited?.rules.orEmpty().toMutableList()
            storedInLabel.text = entry?.let { settings.repositoryFileOf(it.key) }?.let { message("settings.vault.storedIn", it) }.orEmpty()
            discoveredLabel.text = if (entry == null || entry.discovered.isEmpty()) message("settings.vault.discovered.none")
            else message("settings.vault.discovered", entry.discovered.joinToString("<br>"))
            showPrevious(entry)
        } finally {
            loading = false
        }
        updateRuleWarning()
    }

    /**
     * The warning below the rules table (plan amendment R29 review): rules whose pattern cannot be read (they match
     * nothing, and Apply refuses them) and rules that make values shorter than 16 characters. Hidden when there is none.
     */
    private fun updateRuleWarning() {
        val rows = ruleModel.items
        val lines = listOfNotNull(
            rows.filter { it.pattern.isNotBlank() && !it.isReadable }.takeIf { it.isNotEmpty() }
                ?.let { bad -> message("settings.vault.rules.unreadable", bad.joinToString(", ") { StringUtil.escapeXmlEntities(it.pattern.trim()) }) },
            rows.filter { it.isShort }.takeIf { it.isNotEmpty() }?.let { short ->
                message("settings.vault.rules.short", short.joinToString(", ") { "${StringUtil.escapeXmlEntities(it.pattern.trim())} (${RegenerateTexts.generator(it.toRule().generator)})" })
            },
        )
        ruleWarning.text = if (lines.isEmpty()) "" else "<html>" + lines.joinToString("<br>") + "</html>"
        ruleWarning.isVisible = lines.isNotEmpty()
    }

    /** For tests: what the warning below the rules table says, or null when it is hidden. */
    @TestOnly
    internal fun ruleWarningText(): String? = ruleWarning.text.takeIf { ruleWarning.isVisible }

    private fun editedOf(entry: RootEntry): Edited = edits.getOrPut(entry.key) { stored(entry) }

    private fun stored(entry: RootEntry): Edited {
        val root = settings.rootSettings(entry.key)
        val ids = root.identities.map { IdRow(it.label, it.kind, it.location.orEmpty(), stored = it.label in entry.stored) }
        val environments = (entry.environments + root.environmentIdentities.keys.filter { it != VaultRootSettings.ANY_ENVIRONMENT }).distinct() +
            VaultRootSettings.ANY_ENVIRONMENT
        return Edited(ids, environments.map { EnvRow(it, root.environmentIdentities[it].orEmpty()) }, root.generationRules.map(RuleRow::of))
    }

    private fun capture() {
        if (loading) return
        val entry = current ?: return
        edits[entry.key] = Edited(idModel.items.toList(), envModel.items.toList(), ruleModel.items.toList())
        updateRuleWarning()
    }

    private fun edit(row: IdRow?) {
        val entry = current ?: return
        val taken = idModel.items.filter { it !== row }.map { it.label }.toSet()
        val dialog = VaultIdDialog(project, row, taken, rootOf(entry.key)?.dir?.toNioPathOrNull())
        if (!dialog.showAndGet()) return
        val result = dialog.result()
        if (row == null) {
            idModel.addRow(result)
        } else {
            if (row.kind == VaultSourceKind.PASSWORD_SAFE && (result.kind != VaultSourceKind.PASSWORD_SAFE || result.label != row.label) && row.stored) {
                forgotten.getOrPut(entry.key) { LinkedHashSet() } += row.label
            }
            row.password?.fill('\u0000')
            row.label = result.label
            row.kind = result.kind
            row.location = result.location
            row.password = result.password
            row.stored = row.stored && result.kind == VaultSourceKind.PASSWORD_SAFE
            idModel.fireTableDataChanged()
        }
        capture()
    }

    private fun remove() {
        val entry = current ?: return
        val row = idTable.selectedObject ?: return
        if (row.kind == VaultSourceKind.PASSWORD_SAFE && row.stored) forgotten.getOrPut(entry.key) { LinkedHashSet() } += row.label
        row.password?.fill('\u0000')
        idModel.removeRow(idModel.indexOf(row))
        capture()
    }

    private fun move(delta: Int) {
        val row = idTable.selectedObject ?: return
        val index = idModel.indexOf(row)
        val target = index + delta
        if (target !in 0 until idModel.rowCount) return
        idModel.exchangeRows(index, target)
        idTable.selection = listOf(row)
        capture()
    }

    private fun addRule() {
        if (current == null) return
        ruleModel.addRow(RuleRow(GenerationRule.ANY, SecretGenerator.DEFAULT.kind, SecretGenerator.DEFAULT.length))
        capture()
    }

    private fun removeRule() {
        val row = ruleTable.selectedObject ?: return
        ruleModel.removeRow(ruleModel.indexOf(row))
        capture()
    }

    private fun moveRule(delta: Int) {
        val row = ruleTable.selectedObject ?: return
        val index = ruleModel.indexOf(row)
        val target = index + delta
        if (target !in 0 until ruleModel.rowCount) return
        ruleModel.exchangeRows(index, target)
        ruleTable.selection = listOf(row)
        capture()
    }

    /**
     * Import from Script… (X131): a rotation script chosen with [RegeneratePrompts.chooseScript] (starting at a
     * `bin/<name>rotate<name>.sh` of the root's repository) is read as text, never run; the rules of its `case` arms
     * are appended, and the arms it did not understand are named. The script's default arm is never imported, and the
     * result says why ([defaultArmText]). EDT.
     */
    internal fun importRules() {
        val entry = current ?: return
        val prompts = RegeneratePrompts.getInstance()
        val script = prompts.chooseScript(project, rootOf(entry.key)?.let(::rotationScriptOf)) ?: return
        val text = try {
            VfsUtilCore.loadText(script)
        } catch (_: IOException) {
            return prompts.importResult(project, 0, emptyList(), null)
        }
        val parsed = RotationScriptRules.parse(text)
        for (rule in parsed.rules) ruleModel.addRow(RuleRow.of(rule))
        capture()
        prompts.importResult(project, parsed.rules.size, parsed.skipped, parsed.defaultArm?.let(::defaultArmText))
    }

    /**
     * Why the script's default arm was left out (plan amendment R29 review, finding 5): as a rule `*` it would match
     * every key first and switch off "same as now" (D218); and, when it makes something else than D218 rule 3's
     * 32 alphanumeric, what it makes.
     */
    @Nls
    private fun defaultArmText(arm: DefaultArm): String {
        val text = message("settings.vault.rules.default")
        val generator = arm.generator?.takeIf { it != SecretGenerator.DEFAULT } ?: return text
        return text + " " + message("settings.vault.rules.default.differs", RegenerateTexts.generator(generator))
    }

    /** A `bin/<name>rotate<name>.sh` in [root]'s repository (or a folder above the root), or null. */
    private fun rotationScriptOf(root: AnsibleRoot): VirtualFile? {
        val repository = TrackedStatuses.getInstance(project).repositoryOf(root.dir)
        val folders = listOfNotNull(repository) + generateSequence(root.dir) { it.parent }.take(ROTATION_SCRIPT_DEPTH)
        return folders.firstNotNullOfOrNull { folder ->
            folder.findChild("bin")?.children?.filter { !it.isDirectory && it.name.endsWith(".sh") && "rotate" in it.name }?.minByOrNull { it.name }
        }
    }

    /**
     * Rotate Password… (plan amendment R29, D204): Rotate Vault Password… with the selected root and the selected id
     * (the id row of the table, else the root's first discovered id) preselected. EDT.
     */
    internal fun rotate() {
        val entry = current
        val label = idTable.selectedObject?.label ?: entry?.discovered?.firstOrNull()?.substringBefore(" \u00B7 ")
        RotationService.getInstance(project).rotate(entry?.key, label)
    }

    /** The previous passwords of [entry] (D212): "default: previous password kept (date)" with Forget. */
    private fun showPrevious(entry: RootEntry?) {
        if (!::previousPlaceholder.isInitialized) return
        val records = entry?.canonical?.takeIf { it.isNotEmpty() }?.let { root -> VaultUserState.getInstance().previousPasswords().filter { it.root == root } }.orEmpty()
        previousPlaceholder.component = panel {
            if (records.isEmpty()) row { comment(message("settings.vault.previous.none")) }
            for (record in records) {
                row {
                    label(message("settings.vault.previous", record.label, RotationService.date(record.date)))
                    button(message("settings.vault.previous.forget")) { forgetPrevious(record) }.applyToComponent { name = FORGET_PREVIOUS_COMPONENT + record.label }
                }
            }
            row { comment(message("settings.vault.previous.comment")) }
        }
    }

    /** Forget (D212): removes the "before rotation" entry from the IDE password store (off the EDT) and its record. */
    internal fun forgetPrevious(record: PreviousPasswordRecord) {
        val secrets = VaultSecretsService.getInstance(project)
        val disposable = uiDisposable
        ApplicationManager.getApplication().executeOnPooledThread {
            secrets.forgetPreviousPassword(record.root, record.label)
            ApplicationManager.getApplication().invokeLater({ if (disposable?.isDisposed == false) showPrevious(current) }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean = super.isModified() || forgotten.values.any { it.isNotEmpty() } ||
        edits.any { (key, edited) -> toSettings(key, edited) != settings.rootSettings(key) || edited.ids.any { it.password != null } }

    override fun apply() {
        super.apply()
        capture()
        for ((key, edited) in edits) validate(key, edited)
        val passwords = ArrayList<Triple<String, String, CharArray?>>()
        for ((key, labels) in forgotten) labels.forEach { passwords += Triple(key, it, null) }
        for ((key, edited) in edits) {
            settings.update(key) { toSettings(key, edited) }
            for (row in edited.ids) {
                val password = row.password ?: continue
                passwords += Triple(key, row.label, password)
                row.password = null
                row.stored = true
            }
        }
        forgotten.clear()
        if (passwords.isNotEmpty()) storePasswords(passwords)
        idModel.fireTableDataChanged()
    }

    /** Writes and removes password-store entries off the EDT, zeroing every typed password afterwards. */
    private fun storePasswords(passwords: List<Triple<String, String, CharArray?>>) {
        val secrets = VaultSecretsService.getInstance(project)
        val roots = passwords.associate { (key, _, _) -> key to rootOf(key) }
        ApplicationManager.getApplication().executeOnPooledThread {
            for ((key, label, password) in passwords) {
                try {
                    roots[key]?.let { secrets.storePassword(it, label, password) }
                } catch (_: Exception) {
                    // Logged by the service; the row shows "not stored" on the next open.
                } finally {
                    password?.fill('\u0000')
                }
            }
        }
    }

    private fun toSettings(key: String, edited: Edited): VaultRootSettings = settings.rootSettings(key).copy(
        identities = edited.ids.map { row ->
            ExplicitIdentity(row.label, row.kind, row.location.trim().takeIf { it.isNotEmpty() && row.kind != VaultSourceKind.PROMPT })
        },
        environmentIdentities = edited.envs.filter { it.identity.isNotBlank() }.associateTo(LinkedHashMap()) { it.environment to it.identity },
        generationRules = edited.rules.filter { it.isValid }.map { it.toRule() },
    )

    private fun validate(key: String, edited: Edited) {
        val name = rootEntries.firstOrNull { it.key == key }?.name ?: key
        val labels = edited.ids.map { it.label }
        labels.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            throw ConfigurationException(message("settings.vault.invalid.duplicate", name, it.key))
        }
        val known = labels.toSet() + rootEntries.firstOrNull { it.key == key }?.discovered.orEmpty().map { it.substringBefore(" \u00B7 ") }
        edited.envs.firstOrNull { it.identity.isNotBlank() && it.identity !in known }?.let {
            throw ConfigurationException(message("settings.vault.invalid.env.id", name, it.environment, it.identity))
        }
        edited.rules.firstOrNull { it.pattern.isNotBlank() && !it.isReadable }?.let {
            throw ConfigurationException(message("settings.vault.invalid.rule.pattern", name, it.pattern.trim()))
        }
        edited.rules.firstOrNull { !it.isValid }?.let {
            throw ConfigurationException(message("settings.vault.invalid.rule", name, it.pattern.ifBlank { "-" }))
        }
    }

    override fun reset() {
        super.reset()
        edits.values.flatMap { it.ids }.forEach { it.password?.fill('\u0000') }
        edits.clear()
        forgotten.clear()
        select(current)
    }

    override fun disposeUIResources() {
        reset()
        super.disposeUIResources()
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
    }

    private fun rootOf(key: String): AnsibleRoot? = AnsibleWorkspace.getInstance(project).roots().firstOrNull { RootKeys.keyOf(project, it.dir) == key }

    companion object {
        const val ID = "de.terletzkiy.ansibility.settings.vault"

        /** The component name of ANS-V108's allowlist text area (tests find it by name). */
        const val ALLOWLIST_COMPONENT = "ansibility.vault.plaintextKeyAllowlist"

        /** The component name of the commit check's checkbox (tests find it by name). */
        const val COMMIT_CHECK_COMPONENT = "ansibility.vault.checkCommits"

        /** The component name of Rotate Password… (plan amendment R29, D204; tests find it by name). */
        const val ROTATE_COMPONENT = "ansibility.vault.rotatePassword"

        /** The component name prefix of a previous password's Forget button, followed by the id (D212; tests find it by name). */
        const val FORGET_PREVIOUS_COMPONENT = "ansibility.vault.forgetPrevious."

        /** The component name of Import from Script… (X131; tests find it by name). */
        const val IMPORT_RULES_COMPONENT = "ansibility.vault.importRules"

        /** The component name of the warning below the rules table (tests find it by name). */
        const val RULE_WARNING_COMPONENT = "ansibility.vault.ruleWarning"

        /** How many folders up from a root Import from Script… looks for `bin/`. */
        private const val ROTATION_SCRIPT_DEPTH = 4

        /** Renders a generator kind by its name. */
        private object KindRenderer : DefaultTableCellRenderer() {
            private fun readResolve(): Any = KindRenderer

            override fun setValue(value: Any?) {
                text = (value as? GeneratorKind)?.let(RegenerateTexts::kind).orEmpty()
            }
        }

        private const val ALLOWLIST_ROWS = 3

        /** The kinds the page offers, in the order of the source chooser. */
        val KINDS = listOf(
            VaultSourceKind.PASSWORD_SAFE,
            VaultSourceKind.ONE_PASSWORD,
            VaultSourceKind.BITWARDEN,
            VaultSourceKind.KEEPASSXC,
            VaultSourceKind.PROTON_PASS,
            VaultSourceKind.PASSWORD_FILE,
            VaultSourceKind.ENVIRONMENT,
            VaultSourceKind.PROMPT,
        )

        fun kindText(kind: VaultSourceKind): String = message("settings.vault.kind.${kind.name.lowercase()}")

        private fun <T> readOnly(name: String, get: (T) -> String) = object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = get(item)
        }
    }
}
