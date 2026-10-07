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
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message

/**
 * Settings › Languages & Frameworks › Ansibility › Vault (plan amendment R7/R8, F7.9): per root, the vault ids and
 * where each secret comes from (the IDE password store, 1Password, a password file, an environment variable or a
 * prompt), and the environment → id mapping. Ids, kinds and locations go to the shared vault settings; typed
 * passwords go to the IDE password store only, written on Apply off the EDT and zeroed afterwards.
 */
class VaultConfigurable(private val project: Project) : BoundSearchableConfigurable(message("settings.vault.name"), ID, ID) {
    internal data class RootEntry(
        val key: String,
        val name: String,
        val environments: List<String>,
        val discovered: List<String>,
        val stored: Set<String>,
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

    private class Edited(val ids: List<IdRow>, val envs: List<EnvRow>)

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
    private val discoveredLabel = JBLabel()
    private val managerLabels = PasswordManager.entries.associateWith { JBLabel() }

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
        val panel = panel {
            row(message("settings.vault.root")) { cell(rootCombo) }
            group(message("settings.vault.ids")) {
                row { cell(ids).align(Align.FILL) }.resizableRow()
                row { comment(message("settings.vault.ids.comment")) }
                row { cell(discoveredLabel) }
            }
            group(message("settings.vault.envs")) {
                row { cell(envs).align(Align.FILL) }
                row { comment(message("settings.vault.envs.comment")) }
            }
            group(message("settings.vault.managers")) {
                for (manager in PasswordManager.entries) {
                    row { cell(managerLabels.getValue(manager)) }
                    row { comment(message("settings.vault.manager.comment.${manager.name.lowercase()}")) }
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
                RootEntry(RootKeys.keyOf(project, root.dir), root.displayName, layouts.layout(root).inventories.map { it.id }, discovered, emptySet())
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
            discoveredLabel.text = if (entry == null || entry.discovered.isEmpty()) message("settings.vault.discovered.none")
            else message("settings.vault.discovered", entry.discovered.joinToString("<br>"))
        } finally {
            loading = false
        }
    }

    private fun editedOf(entry: RootEntry): Edited = edits.getOrPut(entry.key) { stored(entry) }

    private fun stored(entry: RootEntry): Edited {
        val root = settings.rootSettings(entry.key)
        val ids = root.identities.map { IdRow(it.label, it.kind, it.location.orEmpty(), stored = it.label in entry.stored) }
        val environments = (entry.environments + root.environmentIdentities.keys.filter { it != VaultRootSettings.ANY_ENVIRONMENT }).distinct() +
            VaultRootSettings.ANY_ENVIRONMENT
        return Edited(ids, environments.map { EnvRow(it, root.environmentIdentities[it].orEmpty()) })
    }

    private fun capture() {
        if (loading) return
        val entry = current ?: return
        edits[entry.key] = Edited(idModel.items.toList(), envModel.items.toList())
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
