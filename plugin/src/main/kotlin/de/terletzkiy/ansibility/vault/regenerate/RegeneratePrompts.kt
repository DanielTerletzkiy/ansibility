package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The questions of Regenerate Secrets… (plan amendment R29): the selection dialog (D217), the small generator chooser
 * of the intentions (X97), and the script chooser and result of X131's import. An application service; tests replace
 * it, like `VaultActionPrompts`. Nothing shown here ever contains a value.
 */
interface RegeneratePrompts {
    /** The selection dialog: edits [request] in place; true on Regenerate, false when cancelled. EDT. */
    fun chooseValues(project: Project, request: RegenerateRequest): Boolean

    /** The generator and length for one value (preset by [request]); null when cancelled. EDT. */
    fun chooseGenerator(project: Project, request: GeneratorRequest): SecretGenerator?

    /** The rotation script to import rules from, starting at [preselect]; null when cancelled (X131). EDT. */
    fun chooseScript(project: Project, preselect: VirtualFile?): VirtualFile?

    /**
     * Says how many rules were imported, which arms or patterns were skipped (their patterns), and, as [defaultArm],
     * why the script's default arm was left out (null when the script has none) (X131). EDT.
     */
    fun importResult(project: Project, imported: Int, skipped: List<String>, @Nls defaultArm: String?)

    companion object {
        fun getInstance(): RegeneratePrompts = ApplicationManager.getApplication().service()
    }
}

/** [RegeneratePrompts] with IDE dialogs. */
class RegenerateDialogPrompts : RegeneratePrompts {
    override fun chooseValues(project: Project, request: RegenerateRequest): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        return RegenerateDialog(project, request).showAndGet()
    }

    override fun chooseGenerator(project: Project, request: GeneratorRequest): SecretGenerator? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = GeneratorDialog(project, request)
        return if (dialog.showAndGet()) dialog.generator() else null
    }

    override fun chooseScript(project: Project, preselect: VirtualFile?): VirtualFile? {
        ThreadingAssertions.assertEventDispatchThread()
        val descriptor = FileChooserDescriptorFactory.singleFile().withTitle(message("settings.vault.rules.import.title"))
        return FileChooser.chooseFile(descriptor, project, preselect)
    }

    override fun importResult(project: Project, imported: Int, skipped: List<String>, @Nls defaultArm: String?) {
        ThreadingAssertions.assertEventDispatchThread()
        val text = listOfNotNull(
            message("settings.vault.rules.imported", imported),
            skipped.takeIf { it.isNotEmpty() }?.let { message("settings.vault.rules.skipped", it.joinToString(", ")) },
            defaultArm,
        ).joinToString("\n")
        Messages.showInfoMessage(project, text, message("settings.vault.rules.import.title"))
    }
}

/**
 * A generator kind and a length, edited together. [preset] is the generator the fields were last set to ([show]):
 * [generator] keeps its details (hex case, padding, symbols) while the kind stays, and [changedKind] / [changedLength]
 * say what you changed since.
 */
internal class GeneratorFields(preset: SecretGenerator) {
    var preset: SecretGenerator = preset
        private set

    val kind = ComboBox(GeneratorKind.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let(RegenerateTexts::kind) }
        selectedItem = preset.kind
    }
    val length = JBIntSpinner(preset.length, 1, SecretGenerator.MAX_CHARACTERS)

    /** Shows [generator] and makes it the preset. */
    fun show(generator: SecretGenerator) {
        preset = generator
        kind.selectedItem = generator.kind
        length.number = generator.length
    }

    /** The kind, when you changed it since [show]; else null. */
    val changedKind: GeneratorKind? get() = (kind.selectedItem as? GeneratorKind)?.takeIf { it != preset.kind }

    /** The length, when you changed it since [show]; else null. */
    val changedLength: Int? get() = length.number.takeIf { it != preset.length }

    /** The generator the fields describe, or null when the length does not fit the kind. */
    fun generator(): SecretGenerator? {
        val kind = kind.selectedItem as? GeneratorKind ?: return null
        val length = length.number
        if (length !in SecretGenerator.lengths(kind)) return null
        return if (kind == preset.kind) preset.copy(length = length) else SecretGenerator(kind, length)
    }
}

/**
 * The selection dialog of Regenerate Secrets… (D217): a checkbox tree repository › root › file › key, all checked
 * except values that are no password (their reason shown) and rows that cannot be regenerated (disabled: vault files,
 * values that did not decrypt). Each key row shows the key path, the id and how the new value is made, with the
 * "short" and equal-value marks; never a value. Below the tree, the generator and length for the selected rows and
 * Split for an equal-value group (D219). Checking a row checks its group.
 */
internal class RegenerateDialog(project: Project, private val request: RegenerateRequest) : DialogWrapper(project) {
    private val root = CheckedTreeNode(message("regenerate.dialog.all"))
    private val leaves = LinkedHashMap<RegenerateRow, CheckedTreeNode>()
    private var syncing = false
    private val count = JBLabel()
    internal val fields = GeneratorFields(SecretGenerator.DEFAULT)
    private val apply = JButton(message("regenerate.dialog.apply"))
    private val split = JButton(message("regenerate.dialog.split"))

    /** The tree; checking a key row checks its equal group as well. */
    val tree: CheckboxTree = object : CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(true, true, true, true)) {
        override fun onNodeStateChanged(node: CheckedTreeNode?) {
            val row = node?.userObject as? RegenerateRow ?: return
            if (syncing) return
            request.setChecked(row, node.isChecked)
            sync()
        }
    }

    init {
        title = message("regenerate.dialog.title")
        setOKButtonText(message("regenerate.dialog.ok"))
        build()
        apply.addActionListener { applyGenerator() }
        split.addActionListener { splitGroup() }
        tree.addTreeSelectionListener { selectionChanged() }
        init()
        sync()
        selectionChanged()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { comment(message("regenerate.dialog.comment")) }
        row { cell(ScrollPaneFactory.createScrollPane(tree)).align(Align.FILL) }.resizableRow()
        row { cell(count) }
        row(message("regenerate.dialog.generator")) {
            cell(fields.kind)
            label(message("regenerate.dialog.length"))
            cell(fields.length)
            cell(apply)
            cell(split)
        }
    }.apply { preferredSize = JBUI.size(760, 520) }

    override fun getDimensionServiceKey(): String = "Ansibility.Vault.RegenerateSecrets"

    override fun getPreferredFocusedComponent(): JComponent = tree

    /** repository › root › file › key, in the order of the rows. */
    private fun build() {
        val repositories = LinkedHashMap<String, CheckedTreeNode>()
        val roots = LinkedHashMap<Pair<String, String>, CheckedTreeNode>()
        val files = LinkedHashMap<String, CheckedTreeNode>()
        for (row in request.rows) {
            val repository = repositories.getOrPut(row.repository) { CheckedTreeNode(row.repository).also(root::add) }
            val rootNode = roots.getOrPut(row.repository to row.root.dir.path) { CheckedTreeNode(row.root).also(repository::add) }
            val fileNode = files.getOrPut(row.file.path) { CheckedTreeNode(row.file).also(rootNode::add) }
            val leaf = CheckedTreeNode(row).apply { isEnabled = row.enabled }
            fileNode.add(leaf)
            leaves[row] = leaf
        }
        (tree.model as DefaultTreeModel).reload()
        TreeUtil.expandAll(tree)
    }

    /** The model's check marks into the tree (a group checks together; disabled rows stay unchecked). */
    private fun sync() {
        syncing = true
        try {
            for ((row, leaf) in leaves) leaf.isChecked = row.checked
            for (node in TreeUtil.treeNodeTraverser(root).postOrderDfsTraversal()) {
                val checked = node as? CheckedTreeNode ?: continue
                if (checked.userObject !is RegenerateRow && checked.childCount > 0) {
                    checked.isChecked = (0 until checked.childCount).any { (checked.getChildAt(it) as CheckedTreeNode).isChecked }
                }
            }
        } finally {
            syncing = false
        }
        count.text = message("regenerate.dialog.count", request.selected.size, request.rows.count { it.enabled })
        tree.repaint()
    }

    /** The key rows the tree selection stands for: selected rows and the rows below selected files, roots and repositories. */
    private fun selectedRows(): List<RegenerateRow> = tree.selectionPaths.orEmpty().flatMap { path ->
        val node = path.lastPathComponent as? CheckedTreeNode ?: return@flatMap emptyList()
        TreeUtil.treeNodeTraverser(node).toList().mapNotNull { (it as? CheckedTreeNode)?.userObject as? RegenerateRow }
    }.distinct().filter { it.enabled }

    /** The fields show the first selected row's generator, all its details kept (plan amendment R29 review, finding 10). */
    private fun selectionChanged() {
        val rows = selectedRows()
        apply.isEnabled = rows.isNotEmpty()
        split.isEnabled = rows.any { it.group != null }
        rows.firstOrNull()?.let { fields.show(it.generator) }
    }

    /** Apply to Selected: only the kind or length you changed reaches the selected rows ([RegenerateRequest.changeGenerators]). */
    internal fun applyGenerator() {
        val rows = selectedRows()
        if (!request.changeGenerators(rows, fields.changedKind, fields.changedLength)) {
            return setErrorText(message("regenerate.dialog.length.invalid"), fields.length)
        }
        setErrorText(null)
        rows.firstOrNull()?.let { fields.show(it.generator) }
        sync()
    }

    /** For tests: selects the tree rows of [rows], as a click would. */
    @TestOnly
    fun select(vararg rows: RegenerateRow) {
        tree.selectionPaths = rows.map { TreePath(leaves.getValue(it).path) }.toTypedArray()
    }

    private fun splitGroup() {
        selectedRows().mapNotNull { it.group }.distinct().forEach(request::split)
        sync()
        selectionChanged()
    }

    /** For tests: what the tree shows for [row]. */
    @TestOnly
    fun textOf(row: RegenerateRow): String = rowText(row)

    /** For tests: clicks [row]'s checkbox. */
    @TestOnly
    fun click(row: RegenerateRow) {
        val leaf = leaves.getValue(row)
        tree.setNodeState(leaf, !leaf.isChecked)
    }

    /** For tests: whether the tree shows [row] checked. */
    @TestOnly
    fun isShownChecked(row: RegenerateRow): Boolean = leaves.getValue(row).isChecked

    /** "vault_db_password · default · rule `*_password`: 32 alphanumeric · short · equal values (group 1)". */
    private fun rowText(row: RegenerateRow): String = buildList {
        add(row.keyPath)
        add(row.identity ?: message("regenerate.row.identity.asked").takeIf { row.kind == RowKind.PLAINTEXT && row.unavailable == null })
        when {
            row.unavailable != null -> add(row.unavailable)
            else -> {
                if (row.kind == RowKind.PLAINTEXT) add(message("regenerate.row.plaintext"))
                add(RegenerateTexts.source(row))
                row.notPassword?.let { add(message("regenerate.row.not.password", RegenerateTexts.reason(it))) }
                if (row.short) add(message("regenerate.mark.short"))
                row.group?.let { group -> add(message("regenerate.mark.group", group, request.members(group).size)) }
            }
        }
    }.filterNotNull().joinToString(" · ")

    private inner class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? CheckedTreeNode ?: return
            when (val item = node.userObject) {
                is RegenerateRow -> {
                    val attributes = if (item.enabled) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
                    textRenderer.append(rowText(item), attributes)
                }
                is VirtualFile -> textRenderer.append(item.name)
                is de.terletzkiy.ansibility.api.AnsibleRoot -> textRenderer.append(item.displayName)
                else -> textRenderer.append(item?.toString().orEmpty())
            }
        }
    }
}

/** The small chooser of "Regenerate secret" and "Generate and encrypt" (X97): generator and length for one value. */
internal class GeneratorDialog(project: Project, private val request: GeneratorRequest) : DialogWrapper(project) {
    val fields = GeneratorFields(request.preset)

    init {
        title = message("regenerate.generator.title")
        setOKButtonText(message("regenerate.dialog.ok"))
        init()
    }

    /** The chosen generator (the preset's details kept for its kind). */
    fun generator(): SecretGenerator = fields.generator() ?: request.preset

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("regenerate.generator.message", request.keyName)) }
        row { comment(message("regenerate.generator.preset", request.presetText)) }
        if (request.short) row { comment(message("regenerate.generator.short")) }
        request.notPassword?.let { reason -> row { comment(message("regenerate.generator.not.password", reason)) } }
        row(message("regenerate.dialog.generator")) {
            cell(fields.kind)
            label(message("regenerate.dialog.length"))
            cell(fields.length)
        }
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (fields.generator() == null) com.intellij.openapi.ui.ValidationInfo(message("regenerate.dialog.length.invalid"), fields.length) else null

    override fun getPreferredFocusedComponent(): JComponent = fields.kind
}
