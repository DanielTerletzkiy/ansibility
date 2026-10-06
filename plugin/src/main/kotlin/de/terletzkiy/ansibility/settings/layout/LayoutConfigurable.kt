package de.terletzkiy.ansibility.settings.layout

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
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
import de.terletzkiy.ansibility.context.layout.AnsibilityLayoutBundle.message
import de.terletzkiy.ansibility.context.layout.ShowLayoutAction
import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds
import de.terletzkiy.ansibility.settings.RootKeys
import javax.swing.JButton

/**
 * Settings › Languages & Frameworks › Ansibility › Layout (plan amendment R10, F10.6): per root, Auto or custom
 * inventories, the one-env-per-file switch, where the overrides are stored, and the personal follow switch.
 * The roots and what was detected for them load in a background read action, so opening the page never scans on the EDT.
 */
class LayoutConfigurable(private val project: Project) : BoundSearchableConfigurable(message("settings.layout.name"), ID, ID) {
    /** One root as the page shows it: its key, name and what the resolver detects without overrides. */
    internal data class RootEntry(val key: String, val name: String, val detected: List<LayoutInventory>, val summary: String)

    /** The edited, not yet applied settings of one root. */
    private data class Edited(val override: LayoutOverride, val storage: LayoutStorage, val follow: Boolean)

    internal class Row(var name: String = "", var sources: String = "", var isDefault: Boolean = false)

    private val settings = LayoutSettings.getInstance(project)
    private val edits = LinkedHashMap<String, Edited>()
    private var rootEntries: List<RootEntry> = emptyList()
    private var current: RootEntry? = null
    private var uiDisposable: Disposable? = null
    private var loading = false

    private val rootCombo = ComboBox<RootEntry>().apply {
        renderer = SimpleListCellRenderer.create("") { it.name }
        addActionListener { if (!loading) select(selectedItem as? RootEntry) }
    }
    private val projectStorage = JBRadioButton(message("settings.layout.storage.project"))
    private val personalStorage = JBRadioButton(message("settings.layout.storage.personal"))
    private val auto = JBRadioButton()
    private val custom = JBRadioButton(message("settings.layout.inventories.custom"))
    private val onePerFile = JBCheckBox(message("settings.layout.onePerFile"))
    private val follow = JBCheckBox(message("settings.layout.follow"))
    private val model = ListTableModel<Row>(
        column(message("settings.layout.column.name"), { it.name }) { row, value -> row.name = value as String },
        column(message("settings.layout.column.sources"), { it.sources }) { row, value -> row.sources = value as String },
        object : ColumnInfo<Row, Boolean>(message("settings.layout.column.default")) {
            override fun valueOf(item: Row): Boolean = item.isDefault
            override fun isCellEditable(item: Row): Boolean = true
            override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
            override fun setValue(item: Row, value: Boolean) {
                if (value) model().items.forEach { it.isDefault = false }
                item.isDefault = value
                captureLater()
            }
        },
    )
    private val table = TableView(model)

    /** The pending background load of the roots; tests wait for it. */
    internal var rootsLoading: org.jetbrains.concurrency.CancellablePromise<List<RootEntry>>? = null
        private set

    init {
        listOf(projectStorage, personalStorage, auto, custom, onePerFile, follow).forEach { it.addActionListener { capture() } }
        custom.addActionListener { table.isEnabled = custom.isSelected }
        auto.addActionListener { table.isEnabled = custom.isSelected }
        model.addTableModelListener { capture() }
    }

    private fun model(): ListTableModel<Row> = model

    override fun createPanel(): DialogPanel {
        uiDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable("LayoutConfigurable")
        uiDisposable = disposable
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(Row()); capture() }
            .setRemoveAction { table.selectedObject?.let { model.removeRow(model.indexOf(it)) }; capture() }
            .addExtraAction(object : DumbAwareAction(message("settings.layout.copy.detected"), null, com.intellij.icons.AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) = copyDetected()
            })
            .createPanel()
        val show = JButton(message("banner.show.layout")).apply {
            addActionListener { rootOf(current)?.let { ShowLayoutAction.show(project, it) } }
        }
        val panel = panel {
            row(message("settings.layout.root")) {
                cell(rootCombo)
                cell(show)
            }
            buttonsGroup(message("settings.layout.storage")) {
                row { cell(projectStorage) }
                row { cell(personalStorage) }
            }
            group(message("settings.layout.inventories")) {
                buttonsGroup {
                    row { cell(auto) }
                    row { cell(custom) }
                }
                row { cell(decorated).align(Align.FILL) }.resizableRow()
                row { comment(message("settings.layout.inventories.comment")) }
                row { cell(onePerFile).comment(message("settings.layout.onePerFile.comment")) }
            }
            group(message("settings.layout.personal")) {
                row { cell(follow).comment(message("settings.layout.follow.comment")) }
            }
        }
        loadRoots(disposable)
        return panel
    }

    private fun loadRoots(disposable: Disposable) {
        loading = true
        rootsLoading = ReadAction.nonBlocking<List<RootEntry>> { entries() }
            .inSmartMode(project)
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.any()) { entries ->
                rootEntries = entries
                rootCombo.removeAllItems()
                entries.forEach(rootCombo::addItem)
                loading = false
                select(entries.firstOrNull())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun entries(): List<RootEntry> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val layouts = ProjectLayoutService.getInstance(project)
        return workspace.roots().filter { !it.detached && it.kind != RootKind.ROLE_LIBRARY }.map { root ->
            val key = RootKeys.keyOf(project, root.dir)
            val base = baseOf(root)
            val layout = layouts.layout(root)
            val detected = layout.inventories.map { def ->
                LayoutInventory(def.id, def.sources.map { s -> s.file?.let { VfsUtilCore.getRelativePath(it, base, '/') } ?: s.path }, def.isDefault)
            }
            val shown = if (detected.isEmpty()) message("settings.layout.detected.none")
            else detected.joinToString(" · ") { "${it.name} ← ${it.sources.joinToString(", ")}" }
            RootEntry(key, root.displayName, detected, shown)
        }
    }

    private fun select(entry: RootEntry?) {
        current = entry
        if (rootCombo.selectedItem != entry) rootCombo.selectedItem = entry
        val edited = entry?.let(::editedOf) ?: Edited(LayoutOverride(), LayoutStorage.PROJECT, false)
        loading = true
        try {
            auto.text = message("settings.layout.inventories.auto", entry?.summary ?: "")
            projectStorage.isSelected = edited.storage == LayoutStorage.PROJECT
            personalStorage.isSelected = edited.storage == LayoutStorage.ONLY_ME
            val inventories = edited.override.inventories
            custom.isSelected = inventories != null
            auto.isSelected = inventories == null
            table.isEnabled = inventories != null
            model.items = inventories.orEmpty().map { Row(it.name, it.sources.joinToString(", "), it.isDefault) }.toMutableList()
            onePerFile.isSelected = edited.override.onePerFile == true
            follow.isSelected = edited.follow
        } finally {
            loading = false
        }
    }

    private fun editedOf(entry: RootEntry): Edited = edits[entry.key] ?: stored(entry.key)

    private fun stored(key: String): Edited = settings.of(key).let { Edited(it.override, it.storage, it.follow) }

    private fun captureLater() = javax.swing.SwingUtilities.invokeLater(::capture)

    private fun capture() {
        if (loading) return
        val entry = current ?: return
        val inventories = if (custom.isSelected) {
            model.items.map { row -> LayoutInventory(row.name.trim(), splitSources(row.sources), row.isDefault) }
        } else {
            null
        }
        edits[entry.key] = Edited(
            LayoutOverride(inventories, onePerFile.isSelected.takeIf { it }),
            if (personalStorage.isSelected) LayoutStorage.ONLY_ME else LayoutStorage.PROJECT,
            follow.isSelected,
        )
    }

    private fun copyDetected() {
        val entry = current ?: return
        custom.isSelected = true
        table.isEnabled = true
        model.items = entry.detected.map { Row(it.name, it.sources.joinToString(", "), it.isDefault) }.toMutableList()
        capture()
    }

    override fun isModified(): Boolean =
        super.isModified() || edits.any { (key, edited) -> normalized(edited) != normalized(stored(key)) }

    private fun normalized(edited: Edited): Edited =
        if (edited.override.isEmpty && !edited.follow) Edited(LayoutOverride(), LayoutStorage.PROJECT, false) else edited

    override fun apply() {
        super.apply()
        capture()
        for ((key, edited) in edits) validate(key, edited)
        val applied = edits.toMap()
        edits.clear()
        for ((key, edited) in applied) {
            val entry = rootEntries.firstOrNull { it.key == key }
            settings.update(key, edited.override, edited.storage, edited.follow)
            val root = rootOf(entry) ?: continue
            for ((old, new) in renames(entry!!.detected, edited.override.inventories.orEmpty())) {
                LayoutRenames.rename(project, root, old, new)
            }
        }
        if (applied.isNotEmpty()) loadRoots(uiDisposable ?: return)
    }

    override fun reset() {
        super.reset()
        edits.clear()
        select(current)
    }

    override fun disposeUIResources() {
        super.disposeUIResources()
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
    }

    private fun validate(key: String, edited: Edited) {
        val inventories = edited.override.inventories ?: return
        val name = rootEntries.firstOrNull { it.key == key }?.name ?: key
        EnvironmentIds.nameProblems(inventories.map { it.name }).entries.firstOrNull()?.let { (i, problem) ->
            throw ConfigurationException(message("settings.layout.invalid.${problem.name.lowercase()}", name, inventories[i].name))
        }
        if (edited.storage == LayoutStorage.PROJECT) {
            val outside = inventories.flatMap { it.sources }.firstOrNull(::leavesProject)
            if (outside != null) throw ConfigurationException(message("settings.layout.invalid.outside", name, outside))
        }
    }

    private fun leavesProject(source: String): Boolean =
        source.startsWith("/") || source.startsWith("~") || source.startsWith("$") || source.split('/').contains("..")

    private fun rootOf(entry: RootEntry?): AnsibleRoot? = entry?.let { e ->
        AnsibleWorkspace.getInstance(project).roots().firstOrNull { RootKeys.keyOf(project, it.dir) == e.key }
    }

    companion object {
        const val ID = "de.terletzkiy.ansibility.settings.layout"

        /** The environments of [before] that [after] keeps with the same sources under another name: old → new. */
        internal fun renames(before: List<LayoutInventory>, after: List<LayoutInventory>): List<Pair<String, String>> =
            after.mapNotNull { new ->
                val old = before.firstOrNull { it.sources.map(::normalSource) == new.sources.map(::normalSource) } ?: return@mapNotNull null
                (old.name to new.name).takeIf { old.name != new.name && after.none { it.name == old.name } }
            }

        private fun normalSource(source: String): String = source.trim().trimEnd('/')

        internal fun splitSources(text: String): List<String> = text.split(',').map(String::trim).filter(String::isNotEmpty)

        private fun baseOf(root: AnsibleRoot) = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir

        private fun column(name: String, get: (Row) -> String, set: (Row, Any?) -> Unit) = object : ColumnInfo<Row, String>(name) {
            override fun valueOf(item: Row): String = get(item)
            override fun isCellEditable(item: Row): Boolean = true
            override fun setValue(item: Row, value: String) = set(item, value)
        }
    }
}
