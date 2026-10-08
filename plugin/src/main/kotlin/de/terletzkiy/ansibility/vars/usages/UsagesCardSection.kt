package de.terletzkiy.ansibility.vars.usages

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.resolve.VarUsageQuery

/**
 * The card's **Used in** row (F1.10, D-FU7; `cardSection` id `ansibilityUsages`, SECTION, `order="last"`) on every
 * variable card: `4 templates · 7 tasks · 2 conditions — Show usages`, or `no uses in falcon — Show usages` (the
 * definitions are still listed there). The link runs Find Usages ([UsagesCardLinks]).
 *
 * The counts come from one `ansible.var.use` lookup of the name in the card's root (no PSI, host-independent): template
 * files, and the uses in tasks, in conditions (bare expressions) and in the values of other variables; reads by name
 * (FU2: `hostvars[h].x`, `vars['x']`) count as the reads they are, where they are written. Jinja locals
 * and loop variables (`item` and `ansible_loop` too, wherever they are written) get no row: their uses are scoped to
 * one file or loop, and a root-wide count would add up unrelated loops.
 *
 * A card shown outside Molecule while "Show Molecule in navigation and search" is off counts no use in a Molecule file
 * (plan amendment R20, D153) and says how many reads it left out, grey: `2 tasks · 3 uses in Molecule files (hidden)`,
 * or `only in Molecule files (3 uses, hidden)` when no other use remains (D155: a manual edit then knows the tests read
 * the variable too; rename still edits them).
 */
class UsagesCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.SECTION

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        if (variable.local || variable.name in VarUsageSearch.LOOP_ONLY_NAMES) return null
        val view = MoleculeView.of(context.project, context.file)
        val counts = UsageCounts.of(context.project, variable.root, variable.name, view)
        val link = HtmlChunk.link(UsagesCardLinks.show(context.file, context.offset, variable.name), AnsibilityUsagesBundle.message("card.show.usages"))
        val counted = if (counts.onlyHidden) {
            listOf(HtmlChunk.text(counts.onlyHiddenText()).wrapWith(DocumentationMarkup.GRAYED_ELEMENT))
        } else {
            listOfNotNull(HtmlChunk.text(counts.text(variable.root)), counts.hiddenText()?.let { HtmlChunk.text(PART_SEPARATOR + it).wrapWith(DocumentationMarkup.GRAYED_ELEMENT) })
        }
        val content = HtmlChunk.fragment(*(counted + listOf(HtmlChunk.text(SEPARATOR), link)).toTypedArray())
        return CardSection.row(AnsibilityUsagesBundle.message("card.used.in"), content)
    }

    /**
     * The uses of one name in one root, by family, and the uses in Molecule files the request's view left out
     * ([hiddenInMolecule], always 0 for [MoleculeView.INCLUDE]).
     */
    internal class UsageCounts(val templates: Int, val tasks: Int, val conditions: Int, val values: Int, val hiddenInMolecule: Int = 0) {
        /** `4 templates · 7 tasks · 2 conditions`, or `no uses in falcon`. */
        fun text(root: AnsibleRoot): String {
            val parts = listOfNotNull(
                templates.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.templates", it) },
                tasks.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.tasks", it) },
                conditions.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.conditions", it) },
                values.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.values", it) },
            )
            return if (parts.isEmpty()) AnsibilityUsagesBundle.message("card.count.none", root.displayName) else parts.joinToString(PART_SEPARATOR)
        }

        /** Whether every use is in a Molecule file the view hid. */
        val onlyHidden: Boolean get() = hiddenInMolecule > 0 && templates + tasks + conditions + values == 0

        /** `3 uses in Molecule files (hidden)` (every read once), or null when the view hid none. */
        fun hiddenText(): String? = hiddenInMolecule.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.molecule.hidden", it) }

        /** `only in Molecule files (3 uses, hidden)`, for [onlyHidden]. */
        fun onlyHiddenText(): String = AnsibilityUsagesBundle.message("card.count.molecule.only", hiddenInMolecule)

        companion object {
            /** File kinds whose templated YAML values are other variables' values rather than task arguments. */
            private val VALUE_KINDS = setOf(
                FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS,
                FileKind.INVENTORY, FileKind.MOLECULE_CONFIG,
            )

            /**
             * The counts of [name] in [root] as [view] sees them, from one index lookup (Molecule uses are counted
             * apart rather than looked up twice). Call in a read action in smart mode.
             */
            fun of(project: Project, root: AnsibleRoot, name: String, view: MoleculeView = MoleculeView.INCLUDE): UsageCounts {
                val workspace = AnsibleWorkspace.getInstance(project)
                val templateFiles = HashSet<VirtualFile>()
                var tasks = 0
                var conditions = 0
                var values = 0
                var hidden = 0
                val molecule = HashMap<VirtualFile, Boolean>()
                VarUsageQuery.getInstance(project).process(root, name, null) { use ->
                    ProgressManager.checkCanceled()
                    if (use.called) return@process true
                    val file = use.location.file
                    if (!view.includesMolecule && molecule.getOrPut(file) { MoleculeVisibility.isMoleculeFile(project, file) }) {
                        hidden++
                        return@process true
                    }
                    when (use.container) {
                        JinjaContainer.TEMPLATE_FILE -> templateFiles += file
                        JinjaContainer.YAML_EXPRESSION -> conditions++
                        JinjaContainer.YAML_TEMPLATE -> if (workspace.contextOf(file)?.kind in VALUE_KINDS) values++ else tasks++
                    }
                    true
                }
                return UsageCounts(templateFiles.size, tasks, conditions, values, hidden)
            }
        }
    }

    private companion object {
        const val SEPARATOR = " — "
        const val PART_SEPARATOR = " · "
    }
}
